package io.mcp.jdwp.evaluation;

import io.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import io.mcp.jdwp.JDIConnectionService;
import com.sun.jdi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates the evaluation of a Java expression within a given JDI StackFrame context.
 * It generates, compiles, injects, and executes code in the target VM.
 */
@Slf4j
@Service
public class JdiExpressionEvaluator {

	private static final String EVALUATION_PACKAGE = "mcp.jdi.evaluation";
	private static final String EVALUATION_CLASS_PREFIX = "ExpressionEvaluator_";
	private static final String EVALUATION_METHOD_NAME = "evaluate";
	private static final int MAX_CACHE_SIZE = 100;

	private final InMemoryJavaCompiler compiler;
	private final RemoteCodeExecutor remoteCodeExecutor;
	private final JDIConnectionService jdiConnectionService;

	// Cache: (context signature + expression) -> CachedCompilation (class name + bytecode map)
	private final Map<String, CachedCompilation> compilationCache = new ConcurrentHashMap<>();

	public JdiExpressionEvaluator(InMemoryJavaCompiler compiler,
								   RemoteCodeExecutor remoteCodeExecutor,
								   JDIConnectionService jdiConnectionService) {
		this.compiler = compiler;
		this.remoteCodeExecutor = remoteCodeExecutor;
		this.jdiConnectionService = jdiConnectionService;
	}

	/**
	 * Evaluates a Java expression in the context of a given stack frame.
	 *
	 * @param frame The stack frame providing the context (local variables, 'this').
	 * @param expression The Java expression to evaluate.
	 * @return The resulting Value from the evaluation.
	 * @throws JdiEvaluationException if any part of the process fails.
	 */
	public Value evaluate(StackFrame frame, String expression) throws JdiEvaluationException {
		try {
			// NOTE: Classpath configuration must be done BEFORE calling evaluate() to avoid nested JDI calls
			// The caller (e.g., jdwp_evaluate_watchers) is responsible for calling configureCompilerClasspath()

			// 1. Analyze the frame to build the evaluation context
			EvaluationContext context = buildContext(frame);

			// Auto-rewrite bare references to fields of `this` so users can write
			// `sessions.containsKey(session)` instead of `_this.sessions.containsKey(session)`.
			// Only safe when `this`'s declared type is public AND the specific field is public —
			// otherwise the wrapper class either can't reference the type or can't access the field,
			// and we'd just produce a misleading compile error ("not visible" instead of a real hint).
			ObjectReference thisObject = frame.thisObject();
			if (thisObject != null && thisObject.referenceType() instanceof ClassType thisClass && thisClass.isPublic()) {
				java.util.Set<String> shadowingLocals = context.getVariables().stream()
					.map(v -> v.name)
					.collect(Collectors.toSet());
				java.util.Set<String> publicFieldNames = thisClass.allFields().stream()
					.filter(Field::isPublic)
					.map(Field::name)
					.collect(Collectors.toSet());
				expression = rewriteThisFieldReferences(expression, publicFieldNames, shadowingLocals);
			}

			// 2. Use cache key based on context + expression (excludes UUID for cache hits)
			String cacheKey = context.getSignature() + "###" + expression;

			// Evict entire cache when it exceeds the size limit to prevent unbounded memory growth
			if (compilationCache.size() >= MAX_CACHE_SIZE) {
				log.info("[Evaluator] Compilation cache reached {} entries, clearing", compilationCache.size());
				compilationCache.clear();
			}

			CachedCompilation cached = compilationCache.get(cacheKey);

			String className;
			byte[] bytecode;

			if (cached != null) {
				// Cache hit — reuse previously compiled class name and bytecode
				className = cached.className;
				bytecode = cached.bytecode;
			} else {
				// Cache miss — generate unique class name, compile, and cache
				String uniqueId = UUID.randomUUID().toString().replace("-", "");
				className = EVALUATION_PACKAGE + "." + EVALUATION_CLASS_PREFIX + uniqueId;

				String sourceCode = generateSourceCode(className, context, expression);

				Map<String, byte[]> compiledCode = compiler.compile(className, sourceCode);

				bytecode = compiledCode.get(className);
				if (bytecode == null) {
					// Some compilers key by binary name with slashes, simple name, or with leading slashes —
					// fall back to a suffix match on the simple class name.
					String simpleName = className.substring(className.lastIndexOf('.') + 1);
					for (Map.Entry<String, byte[]> entry : compiledCode.entrySet()) {
						String key = entry.getKey();
						String keyTail = key.substring(key.lastIndexOf('/') + 1).replace(".class", "");
						keyTail = keyTail.substring(keyTail.lastIndexOf('.') + 1);
						if (keyTail.equals(simpleName)) {
							bytecode = entry.getValue();
							log.debug("[Evaluator] Bytecode found via fallback key '{}' for class '{}'", key, className);
							break;
						}
					}
				}
				if (bytecode == null) {
					throw new JdiEvaluationException("Could not find compiled bytecode for class " + className
						+ " (available keys: " + compiledCode.keySet() + ")");
				}

				compilationCache.put(cacheKey, new CachedCompilation(className, bytecode));
			}

			// 3. Find a suitable class loader in the target VM
			ClassLoaderReference classLoader = findClassLoader(frame);

			// 4. Execute the code remotely
			return remoteCodeExecutor.execute(
				frame.virtualMachine(),
				frame.thread(),
				classLoader,
				className,
				bytecode,
				EVALUATION_METHOD_NAME,
				context.getValues()
			);
		} catch (Exception e) {
			// Un-wrap runtime exception from cache computation
			if (e instanceof RuntimeException && e.getCause() instanceof JdiEvaluationException jdiEx) {
				throw jdiEx;
			}
			throw new JdiEvaluationException("Expression evaluation failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Extracts locals and the {@code this} reference from a stack frame. Uses declared type
	 * rather than runtime type for {@code this} to handle proxy classes (Guice, CGLIB, etc.).
	 */
	private EvaluationContext buildContext(StackFrame frame) throws AbsentInformationException {
		List<ContextVariable> variables = new ArrayList<>();
		List<Value> values = new ArrayList<>();

		ObjectReference thisObject = frame.thisObject();
		if (thisObject != null) {
			// Use declared type instead of runtime type to avoid issues with dynamic proxies (Guice, CGLIB, etc.)
			String declaredType = getDeclaredType(thisObject.referenceType());
			variables.add(new ContextVariable("_this", declaredType));
			values.add(thisObject);
		}

		for (LocalVariable var : frame.visibleVariables()) {
			if (var.isArgument() || !var.name().startsWith("this$")) {
				String typeName = resolveLocalVarType(var);
				variables.add(new ContextVariable(var.name(), typeName));
				values.add(frame.getValue(var));
			}
		}
		return new EvaluationContext(variables, values);
	}

	/**
	 * Resolves a local variable's declared type to one the wrapper class can reference.
	 * Falls back to a public supertype (or Object) for non-public types.
	 */
	private String resolveLocalVarType(LocalVariable var) {
		try {
			com.sun.jdi.Type t = var.type();
			if (t instanceof ReferenceType refType) {
				return getDeclaredType(refType);
			}
			return t.name();
		} catch (ClassNotLoadedException e) {
			// Type not loaded yet — fall back to declared name (may still be valid if a public type)
			return var.typeName();
		}
	}

	/**
	 * Generates a wrapper class with a static {@code evaluate()} method that accepts
	 * the context variables as parameters and returns the result of the user expression.
	 */
	private String generateSourceCode(String className, EvaluationContext context, String expression) {
		String packageName = EVALUATION_PACKAGE;
		String simpleClassName = className.substring(packageName.length() + 1);

		String methodParameters = context.getVariables().stream()
			.map(v -> v.type + " " + v.name)
			.collect(Collectors.joining(", "));

		// Replace 'this' with '_this' in the expression to match the parameter name
		String safeExpression = expression.replaceAll("(?<!\\w)this(?!\\w)", "_this");

		return "package " + packageName + ";\n" +
			   "\n" +
			   "// Automatically generated class for JDI expression evaluation\n" +
			   "public class " + simpleClassName + " {\n" +
			   "    public static Object " + EVALUATION_METHOD_NAME + "(" + methodParameters + ") {\n" +
			   "        // User expression:\n" +
			   "        return (Object) (" + safeExpression + ");\n" +
			   "    }\n" +
			   "}\n";
	}

	/**
	 * Get a name suitable to use in the generated wrapper class for the given runtime type.
	 * Handles two cases the wrapper compiler cannot otherwise express:
	 * <ul>
	 *   <li><b>Dynamic proxies</b> (Guice, CGLIB, Mockito, Spring AOP) — unwrap to the real class.</li>
	 *   <li><b>Non-public types</b> (e.g., package-private test classes) — walk up the superclass
	 *       chain until a public type is found, falling back to {@code java.lang.Object}. The wrapper
	 *       class lives in {@code mcp.jdi.evaluation} and can only reference public types.</li>
	 * </ul>
	 */
	private String getDeclaredType(ReferenceType type) {
		String typeName = type.name();

		// Check if it's a dynamic proxy (contains $$ which is common for Guice, CGLIB, Mockito, etc.)
		if (typeName.contains("$$")) {
			// Try to get the superclass (proxies usually extend the real class)
			if (type instanceof ClassType classType) {
				ClassType superclass = classType.superclass();
				if (superclass != null && !superclass.name().equals("java.lang.Object")) {
					return getDeclaredType(superclass);
				}
			}

			// Fallback: try to extract the base class name before $$
			int dollarIndex = typeName.indexOf("$$");
			if (dollarIndex > 0) {
				typeName = typeName.substring(0, dollarIndex);
			}
		}

		// Walk up to find a public supertype the wrapper can reference
		if (type instanceof ClassType classType) {
			ClassType current = classType;
			while (current != null && !current.isPublic()) {
				current = current.superclass();
			}
			if (current != null) {
				return current.name();
			}
			return "java.lang.Object";
		}

		return typeName;
	}

	private ClassLoaderReference findClassLoader(StackFrame frame) throws JdiEvaluationException {
		ObjectReference thisObject = frame.thisObject();
		if (thisObject != null) {
			ClassLoaderReference cl = thisObject.referenceType().classLoader();
			if (cl != null) {
				return cl;
			}
		} else {
			// Static method — use the declaring type's classloader
			ClassLoaderReference cl = frame.location().declaringType().classLoader();
			if (cl != null) {
				return cl;
			}
		}

		// All lookups returned null (bootstrap class context) — invoke ClassLoader.getSystemClassLoader() in the target VM
		try {
			List<ReferenceType> clTypes = frame.virtualMachine().classesByName("java.lang.ClassLoader");
			if (!clTypes.isEmpty()) {
				ClassType clType = (ClassType) clTypes.get(0);
				Method getSystemCL = clType.concreteMethodByName("getSystemClassLoader", "()Ljava/lang/ClassLoader;");
				if (getSystemCL != null) {
					Value result = clType.invokeMethod(
						frame.thread(), getSystemCL, Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED
					);
					if (result instanceof ClassLoaderReference clRef) {
						return clRef;
					}
				}
			}
		} catch (Exception e) {
			log.warn("[Evaluator] Failed to invoke ClassLoader.getSystemClassLoader() in target VM", e);
		}

		throw new JdiEvaluationException(
			"Could not find a non-null ClassLoader. The frame may be in a bootstrap-loaded class " +
			"and ClassLoader.getSystemClassLoader() was not available."
		);
	}

	/**
	 * Rewrites bare references to fields of {@code this} as {@code _this.field} so the wrapper class
	 * can resolve them. Only safe to call when {@code this}'s declared type is public AND each
	 * candidate field is itself public — for non-public types or fields the wrapper class still
	 * couldn't access the field even after the rewrite. The caller decides what's safe to pass.
	 *
	 * <p>Implemented as a hand-rolled lightweight tokenizer rather than a regex so that bare field
	 * names appearing INSIDE string literals, char literals, or text blocks are NOT rewritten.
	 * Without this, an expression like {@code "name=" + name} (with field {@code name}) would
	 * incorrectly become {@code "_this.name=" + _this.name}, corrupting the string content.
	 *
	 * <p>The tokenizer handles:
	 * <ul>
	 *   <li>Regular string literals {@code "..."} with backslash escapes</li>
	 *   <li>Java text blocks {@code """..."""} with multi-line content and escapes</li>
	 *   <li>Character literals {@code '.'} including {@code '\u0041'} escapes</li>
	 *   <li>Qualified references — an identifier preceded by {@code .} (with optional whitespace)
	 *       is treated as a field/method access on something else and is NOT rewritten</li>
	 *   <li>Identifier characters per {@link Character#isJavaIdentifierStart(int)} /
	 *       {@link Character#isJavaIdentifierPart(int)}</li>
	 * </ul>
	 *
	 * <p>Static + package-private so it can be unit-tested without a real {@link StackFrame}.
	 *
	 * @param expression       the user-supplied Java expression
	 * @param thisFieldNames   field names declared on {@code this}'s type (already filtered for publicness)
	 * @param shadowingLocals  local variable names that shadow fields and must NOT be rewritten
	 * @return the expression with bare field references (outside string/char literals) prefixed by {@code _this.}
	 */
	static String rewriteThisFieldReferences(String expression, java.util.Set<String> thisFieldNames,
			java.util.Set<String> shadowingLocals) {
		if (thisFieldNames.isEmpty()) {
			return expression;
		}
		java.util.Set<String> rewritable = thisFieldNames.stream()
			.filter(name -> !shadowingLocals.contains(name))
			.collect(Collectors.toSet());
		if (rewritable.isEmpty()) {
			return expression;
		}

		StringBuilder out = new StringBuilder(expression.length() + 16);
		int i = 0;
		int n = expression.length();
		while (i < n) {
			char c = expression.charAt(i);
			if (c == '"') {
				// String literal or text block — copy verbatim, do not rewrite contents
				int end = skipStringLiteral(expression, i);
				out.append(expression, i, end);
				i = end;
			} else if (c == '\'') {
				// Char literal — copy verbatim
				int end = skipCharLiteral(expression, i);
				out.append(expression, i, end);
				i = end;
			} else if (Character.isJavaIdentifierStart(c)) {
				int end = i + 1;
				while (end < n && Character.isJavaIdentifierPart(expression.charAt(end))) {
					end++;
				}
				String identifier = expression.substring(i, end);
				if (!rewritable.contains(identifier) || isPrecededByDot(expression, i)) {
					out.append(identifier);
				} else {
					out.append("_this.").append(identifier);
				}
				i = end;
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	/**
	 * Returns true if the character at position {@code pos} is preceded by a {@code .}
	 * (skipping whitespace) — indicating a qualified reference like {@code obj.field}
	 * or {@code obj . field} that should NOT be rewritten.
	 */
	private static boolean isPrecededByDot(String s, int pos) {
		for (int j = pos - 1; j >= 0; j--) {
			char p = s.charAt(j);
			if (Character.isWhitespace(p)) {
				continue;
			}
			return p == '.';
		}
		return false;
	}

	/**
	 * Returns the index just past the end of a string literal that starts at position {@code start}.
	 * Handles both regular strings ({@code "..."}) and Java text blocks ({@code """..."""}),
	 * with backslash escape sequences. If the literal is unterminated, returns the end of the string
	 * (best-effort tolerance — we never throw on malformed input).
	 */
	private static int skipStringLiteral(String s, int start) {
		int n = s.length();
		// Text block: """..."""
		if (start + 3 <= n && s.charAt(start + 1) == '"' && s.charAt(start + 2) == '"') {
			int i = start + 3;
			while (i < n) {
				if (s.charAt(i) == '\\') {
					i = Math.min(i + 2, n);
					continue;
				}
				if (i + 3 <= n && s.charAt(i) == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
					return i + 3;
				}
				i++;
			}
			return n;
		}
		// Regular string literal
		int i = start + 1;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '\\') {
				i = Math.min(i + 2, n);
				continue;
			}
			if (c == '"') {
				return i + 1;
			}
			i++;
		}
		return n;
	}

	/**
	 * Returns the index just past the end of a char literal starting at position {@code start}.
	 * Handles backslash escapes (including {@code '\u0041'} unicode escapes). Tolerant of
	 * unterminated literals — returns the end of the string in that case.
	 */
	private static int skipCharLiteral(String s, int start) {
		int n = s.length();
		int i = start + 1;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '\\') {
				i = Math.min(i + 2, n);
				continue;
			}
			if (c == '\'') {
				return i + 1;
			}
			i++;
		}
		return n;
	}

	/**
	 * Captures the variable names, types, and values from a stack frame for expression compilation.
	 */
	private static class EvaluationContext {
		private final List<ContextVariable> variables;
		private final List<Value> values;
		private final String signature;

		EvaluationContext(List<ContextVariable> variables, List<Value> values) {
			this.variables = variables;
			this.values = values;
			this.signature = variables.stream().map(v -> v.type + " " + v.name).collect(Collectors.joining(","));
		}

		public List<ContextVariable> getVariables() { return variables; }
		public List<Value> getValues() { return values; }
		public String getSignature() { return signature; }
	}

	/** Holds a compiled class name and its bytecode for caching across evaluations. */
	private static class CachedCompilation {
		final String className;
		final byte[] bytecode;

		CachedCompilation(String className, byte[] bytecode) {
			this.className = className;
			this.bytecode = bytecode;
		}
	}

	/** A name-type pair representing a single variable from a stack frame. */
	private static class ContextVariable {
		final String name;
		final String type;

		ContextVariable(String name, String type) {
			this.name = name;
			this.type = type;
		}
	}

	/**
	 * Configures the compiler with the target JVM's classpath. Skips if already configured for the current connection.
	 * Automatically reconfigures after a disconnect/reconnect cycle (detected via null JDK path).
	 * Must be called BEFORE any expression evaluation to avoid nested JDI calls.
	 *
	 * @param suspendedThread a thread already suspended at a breakpoint (REQUIRED)
	 */
	public synchronized void configureCompilerClasspath(ThreadReference suspendedThread) {
		// Self-healing: if JDK path is already set, classpath is already configured for this connection
		if (jdiConnectionService.getDiscoveredJdkPath() != null) {
			return;
		}

		// New connection or reconnect — clear stale compilation cache
		compilationCache.clear();

		long startTime = System.currentTimeMillis();

		try {
			String classpath = jdiConnectionService.discoverClasspath(suspendedThread);
			String jdkPath = jdiConnectionService.getDiscoveredJdkPath();

			if (jdkPath == null) {
				log.error("[Evaluator] JDK path not discovered, cannot configure compiler");
				return;
			}

			int version = jdiConnectionService.getTargetMajorVersion();
			if (classpath != null && !classpath.isEmpty()) {
				compiler.configure(jdkPath, classpath, version);

				long elapsed = System.currentTimeMillis() - startTime;
				log.info("[Evaluator] Compiler configured in {}ms", elapsed);
			} else {
				log.error("[Evaluator] Failed to discover classpath, expression evaluation may fail for application classes");
			}

		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - startTime;
			log.error("[Evaluator] Error configuring classpath after {}ms", elapsed, e);
		}
	}
}
