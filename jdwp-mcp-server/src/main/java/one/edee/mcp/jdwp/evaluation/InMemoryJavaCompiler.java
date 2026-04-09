package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compiles Java source for the expression-evaluation pipeline using Eclipse JDT (ECJ) via JSR-199.
 *
 * Compilation output is captured in memory through {@link MemoryJavaFileManager}, but the input source still
 * round-trips through a short-lived temp directory because JDT's standard file-object API requires a real
 * `javax.tools.JavaFileObject` backed by a path. The temp directory is unconditionally deleted in `finally`.
 *
 * Requires {@link #configure(String, String, int)} to be called first with the path to a target-matching JDK
 * (used as `--system <jdkPath>` so JDT can resolve `java.*` system classes) and the target VM's classpath.
 * Calling {@link #compile(String, String)} without configuration throws {@link JdiEvaluationException}.
 *
 * Source/target version is derived from the major version: `1.8` for Java 8, the bare number for Java 9+.
 * The `-g` flag is always passed so the evaluator can resolve local variable names from the captured bytecode.
 */
@Service
public class InMemoryJavaCompiler {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InMemoryJavaCompiler.class);

	/** Filesystem path to the target-matching JDK; used as `--system` so JDT can resolve system classes. */
	@Nullable
	private String jdkPath;
	/** Path-separator-delimited classpath of the target VM, populated lazily by {@link #configure}. */
	@Nullable
	private String classpath;
	/** Target JVM major version (8, 11, 17, ...); defaults to 8 until {@link #configure} is called. */
	private int targetMajorVersion = 8;

	/**
	 * Configures the compiler with a JDK path, classpath, and target version. Synchronized so that a
	 * concurrent {@link #compile} sees a fully populated state. A non-positive `targetMajorVersion`
	 * silently clamps to 8 (the lowest version JDT can target).
	 *
	 * @param jdkPath            file path to the root of a JDK matching the target VM's major version
	 * @param classpath          target VM classpath (colon- or semicolon-separated, depending on target OS)
	 * @param targetMajorVersion target JVM major version (e.g., 8, 11, 17, 21); 0 or negative falls back to 8
	 */
	public synchronized void configure(String jdkPath, String classpath, int targetMajorVersion) {
		long startTime = System.currentTimeMillis();
		this.jdkPath = jdkPath;
		this.classpath = classpath;
		this.targetMajorVersion = targetMajorVersion > 0 ? targetMajorVersion : 8;

		if (classpath == null || classpath.isEmpty()) {
			log.warn("[Compiler] Classpath is empty, application classes may not be resolved.");
		}

		long elapsed = System.currentTimeMillis() - startTime;
		String[] classpathEntries = classpath != null ? classpath.split(File.pathSeparator) : new String[0];
		log.info("[Compiler] Configured with JDK at {} and {} classpath entries in {}ms",
			jdkPath, classpathEntries.length, elapsed);
	}

	/**
	 * Compiles `sourceCode` into bytecode for `className`. The returned map is keyed by fully qualified
	 * class name and includes any inner/anonymous classes JDT emits.
	 *
	 * @param className  fully qualified name of the primary class declared in `sourceCode`
	 * @param sourceCode complete `.java` source text (must declare a package matching `className`)
	 * @return map of fully qualified class name to compiled bytecode
	 * @throws JdiEvaluationException if {@link #configure} was never called, if temp file I/O fails,
	 *                                or if JDT reports any `Diagnostic.Kind.ERROR` diagnostic
	 */
	public Map<String, byte[]> compile(String className, String sourceCode) throws JdiEvaluationException {
		if (this.jdkPath == null) {
			throw new JdiEvaluationException(
				"Compiler is not configured. Please call configure() with a valid JDK path and classpath."
			);
		}

		long startTime = System.currentTimeMillis();

		JavaCompiler compiler = new EclipseCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

		Path tempDir = null;
		try (StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, null, null);
			 MemoryJavaFileManager fileManager = new MemoryJavaFileManager(standardFileManager)) {

			// 1. Write source to a temp file (tempDir captured for cleanup in finally)
			tempDir = Files.createTempDirectory("mcp-compiler-");
			Path sourceFile = writeSourceFileToTemp(tempDir, className, sourceCode);

			// 2. Get a JavaFileObject for the temporary file
			Iterable<? extends JavaFileObject> compilationUnits = standardFileManager.getJavaFileObjects(sourceFile.toFile());

			// 3. Build compiler options and run the compilation task
			List<String> options = buildCompilerOptions();
			JavaCompiler.CompilationTask task = compiler.getTask(
				null, fileManager, diagnostics, options, null, compilationUnits
			);

			boolean success = task.call();

			if (!success) {
				String errors = diagnostics.getDiagnostics().stream()
					.filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
					.map(d -> {
						String location = d.getSource() != null
							? "Line " + d.getLineNumber() + " in " + d.getSource().getName()
							: "Line " + d.getLineNumber();
						return location + ": " + d.getMessage(null);
					})
					.collect(Collectors.joining("\n"));
				long elapsed = System.currentTimeMillis() - startTime;
				log.error("[Compiler] Compilation failed after {}ms:\n{}", elapsed, errors);
				throw new JdiEvaluationException("Compilation failed:\n" + errors);
			}

			Map<String, byte[]> compiledBytecode = fileManager.getCompiledBytecode();
			long elapsed = System.currentTimeMillis() - startTime;
			log.info("[Compiler] Compilation successful in {}ms ({} bytes generated for {} class(es))",
				elapsed, compiledBytecode.values().stream().mapToInt(b -> b.length).sum(), compiledBytecode.size());
			return compiledBytecode;

		} catch (IOException e) {
			throw new JdiEvaluationException("Failed to create or write temporary source file for compilation.", e);
		} finally {
			// 4. Clean up the temporary directory
			if (tempDir != null) {
				try (var paths = Files.walk(tempDir)) {
					paths.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
				} catch (IOException e) {
					log.warn("[Compiler] Failed to delete temporary compilation directory: {}", tempDir, e);
				}
			}
		}
	}

	/**
	 * Materialises the source for {@code className} under {@code tempDir}, creating any required
	 * package subdirectories. Returns the path of the written {@code .java} file.
	 */
	private Path writeSourceFileToTemp(Path tempDir, String className, String sourceCode) throws IOException {
		String[] packageAndClass = getPackageAndClassName(className);
		Path packageDir = tempDir;
		if (packageAndClass[0] != null) {
			packageDir = tempDir.resolve(packageAndClass[0].replace('.', File.separatorChar));
			Files.createDirectories(packageDir);
		}
		Path sourceFile = packageDir.resolve(packageAndClass[1] + ".java");
		Files.write(sourceFile, sourceCode.getBytes(StandardCharsets.UTF_8));
		return sourceFile;
	}

	/**
	 * Builds the JDT compiler option list from the configured target version, JDK path, and classpath.
	 */
	private List<String> buildCompilerOptions() {
		List<String> options = new ArrayList<>();
		String versionStr = targetMajorVersion <= 8 ? "1." + targetMajorVersion : String.valueOf(targetMajorVersion);
		options.addAll(Arrays.asList("-source", versionStr, "-target", versionStr));
		options.add("-g"); // Preserve local variable names
		options.addAll(Arrays.asList("--system", this.jdkPath));
		if (this.classpath != null && !this.classpath.isEmpty()) {
			options.addAll(Arrays.asList("-classpath", this.classpath));
		}
		return options;
	}

	/**
	 * Splits a fully qualified class name into `[packageName, simpleName]`. Returns `[null, simpleName]`
	 * for default-package classes (no `.` in the name).
	 */
	private String[] getPackageAndClassName(String fullClassName) {
		int lastDot = fullClassName.lastIndexOf('.');
		if (lastDot == -1) {
			return new String[]{null, fullClassName};
		}
		return new String[]{fullClassName.substring(0, lastDot), fullClassName.substring(lastDot + 1)};
	}

	/**
	 * Captures compiled bytecode in memory while delegating all read operations (source lookup,
	 * type resolution, classpath scanning) to the underlying standard file manager. Only
	 * {@link #getJavaFileForOutput} is overridden — JDT writes class files through that hook,
	 * and we redirect those writes to per-class {@link ByteArrayOutputStream} instances.
	 */
	private static class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
		private final Map<String, ByteArrayOutputStream> outputStreams = new HashMap<>();

		MemoryJavaFileManager(JavaFileManager fileManager) {
			super(fileManager);
		}

		@Override
		public JavaFileObject getJavaFileForOutput(
			Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
			throws IOException {
			return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), kind) {
				@Override
				public OutputStream openOutputStream() {
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					outputStreams.put(className, bos);
					return bos;
				}
			};
		}

		/**
		 * Returns a snapshot copy of the captured bytecode keyed by fully qualified class name.
		 * Safe to call after the file manager has been closed because the byte arrays are detached
		 * from the underlying streams.
		 */
		Map<String, byte[]> getCompiledBytecode() {
			Map<String, byte[]> bytecodeMap = new HashMap<>();
			for (Map.Entry<String, ByteArrayOutputStream> entry : outputStreams.entrySet()) {
				bytecodeMap.put(entry.getKey(), entry.getValue().toByteArray());
			}
			return bytecodeMap;
		}
	}
}
