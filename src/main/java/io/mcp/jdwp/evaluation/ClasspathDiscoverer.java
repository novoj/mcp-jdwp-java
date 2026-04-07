package io.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Discovers the full classpath of a target JVM by exploring its classloader hierarchy.
 * This is necessary for Tomcat/container applications where most JARs are loaded dynamically
 * via custom classloaders and are not visible in the initial java.class.path system property.
 *
 * This is NOT a Spring bean - it is instantiated manually with a VirtualMachine instance.
 */
@Slf4j
public class ClasspathDiscoverer {

	private final VirtualMachine vm;

	public ClasspathDiscoverer(VirtualMachine vm) {
		this.vm = vm;
	}

	/**
	 * Discovers the full classpath by exploring the classloader hierarchy starting from
	 * the thread's context classloader.
	 *
	 * CRITICAL: This method first discovers a matching local JDK installation. If no JDK is found,
	 * it throws JdkNotFoundException. Expression evaluation cannot proceed without a local JDK.
	 *
	 * @param suspendedThread A thread already suspended at a breakpoint
	 * @return DiscoveryResult containing local JDK path and application classpath
	 * @throws JdkDiscoveryService.JdkNotFoundException if no matching JDK is found locally
	 */
	public DiscoveryResult discoverFullClasspath(ThreadReference suspendedThread)
			throws JdkDiscoveryService.JdkNotFoundException {
		long startTime = System.currentTimeMillis();

		try {
			// STEP 1 (CRITICAL): Discover matching local JDK FIRST
			// This MUST succeed before we continue with application classpath discovery
			JdkDiscoveryService jdkDiscovery = new JdkDiscoveryService(vm);
			String localJdkPath = jdkDiscovery.discoverMatchingJdk(suspendedThread);

			log.info("[Discoverer] Local JDK found: {}", localJdkPath);

			// STEP 2: Discover application classpath from classloaders
			Set<String> classpathEntries = new LinkedHashSet<>();

			// 2a. Get the initial java.class.path (might be incomplete but good to include)
			addInitialClasspath(suspendedThread, classpathEntries);

			// 2b. Get Thread.getContextClassLoader()
			ClassLoaderReference contextClassLoader = getContextClassLoader(suspendedThread);
			if (contextClassLoader == null) {
				log.warn("[Discoverer] Context classloader is null, falling back to initial classpath only");
				return new DiscoveryResult(localJdkPath, classpathEntries, jdkDiscovery.getTargetMajorVersion());
			}

			// 2c. Prepare ClassType references for known classloader types
			ClassType urlClassLoaderClass = getClassTypeSafe("java.net.URLClassLoader");
			ClassType webappClassLoaderBaseClass = getClassTypeSafe("org.apache.catalina.loader.WebappClassLoaderBase");

			// 2d. Traverse classloader hierarchy
			Set<ClassLoaderReference> visitedClassLoaders = new HashSet<>();
			ClassLoaderReference currentClassLoader = contextClassLoader;

			while (currentClassLoader != null && visitedClassLoaders.add(currentClassLoader)) {
				ReferenceType clType = currentClassLoader.referenceType();
				log.debug("[Discoverer] Inspecting ClassLoader: {}", clType.name());

				// Try to extract URLs from this classloader
				if (urlClassLoaderClass != null && isAssignableTo(clType, urlClassLoaderClass)) {
					extractUrlsFromClassLoader(currentClassLoader, suspendedThread, classpathEntries);
				} else if (webappClassLoaderBaseClass != null && isAssignableTo(clType, webappClassLoaderBaseClass)) {
					extractUrlsFromClassLoader(currentClassLoader, suspendedThread, classpathEntries);
				} else {
					log.debug("[Discoverer] ClassLoader {} is not a recognized URL-based classloader", clType.name());
				}

				// Move to parent classloader
				currentClassLoader = getParentClassLoader(currentClassLoader, suspendedThread);
			}

			long elapsed = System.currentTimeMillis() - startTime;
			log.info("[Discoverer] Application classpath discovered in {}ms ({} entries)", elapsed, classpathEntries.size());

			return new DiscoveryResult(localJdkPath, classpathEntries, jdkDiscovery.getTargetMajorVersion());

		} catch (JdkDiscoveryService.JdkNotFoundException e) {
			// Propagate JDK not found exception - this is a critical error
			throw e;
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - startTime;
			log.error("[Discoverer] Error discovering full classpath after {}ms", elapsed, e);
			throw new RuntimeException("Classpath discovery failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Result of classpath discovery containing both JDK path and application classpath.
	 */
	public static class DiscoveryResult {
		private final String localJdkPath;
		private final Set<String> applicationClasspath;
		private final int targetMajorVersion;

		public DiscoveryResult(String localJdkPath, Set<String> applicationClasspath, int targetMajorVersion) {
			this.localJdkPath = localJdkPath;
			this.applicationClasspath = applicationClasspath;
			this.targetMajorVersion = targetMajorVersion;
		}

		public String getLocalJdkPath() {
			return localJdkPath;
		}

		public Set<String> getApplicationClasspath() {
			return applicationClasspath;
		}

		public int getTargetMajorVersion() {
			return targetMajorVersion;
		}
	}

	private void addInitialClasspath(ThreadReference suspendedThread, Set<String> classpathEntries) {
		try {
			ClassType systemClass = (ClassType) vm.classesByName("java.lang.System").get(0);
			Method getPropertyMethod = systemClass.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
			StringReference pathArg = vm.mirrorOf("java.class.path");
			Value result = systemClass.invokeMethod(
				suspendedThread,
				getPropertyMethod,
				Collections.singletonList(pathArg),
				ClassType.INVOKE_SINGLE_THREADED
			);

			if (result instanceof StringReference stringRef) {
				String initialClasspath = stringRef.value();
				if (initialClasspath != null && !initialClasspath.isEmpty()) {
					// Detect path separator from target OS (use semicolon for Windows, colon for Unix)
					String separator = initialClasspath.contains(";") ? ";" : ":";
					String[] entries = initialClasspath.split(separator);
					for (String entry : entries) {
						if (!entry.trim().isEmpty()) {
							classpathEntries.add(entry.trim());
						}
					}
					log.debug("[Discoverer] Added {} entries from initial java.class.path", entries.length);
				}
			}
		} catch (Exception e) {
			log.warn("[Discoverer] Could not retrieve initial java.class.path: {}", e.getMessage());
		}
	}

	private ClassLoaderReference getContextClassLoader(ThreadReference suspendedThread) {
		try {
			Method getContextClassLoaderMethod = suspendedThread.referenceType()
				.methodsByName("getContextClassLoader", "()Ljava/lang/ClassLoader;").get(0);

			Value result = suspendedThread.invokeMethod(
				suspendedThread,
				getContextClassLoaderMethod,
				Collections.emptyList(),
				ClassType.INVOKE_SINGLE_THREADED
			);

			return (ClassLoaderReference) result;
		} catch (Exception e) {
			log.error("[Discoverer] Failed to get context classloader", e);
			return null;
		}
	}

	private ClassType getClassTypeSafe(String className) {
		try {
			List<ReferenceType> classes = vm.classesByName(className);
			if (!classes.isEmpty()) {
				return (ClassType) classes.get(0);
			}
		} catch (Exception e) {
			log.debug("[Discoverer] ClassType {} not found: {}", className, e.getMessage());
		}
		return null;
	}

	private boolean isAssignableTo(ReferenceType type, ClassType target) {
		try {
			if (type instanceof ClassType classType) {
				return classType.equals(target) || classType.allInterfaces().contains(target) || isSuperclassOf(classType, target);
			}
		} catch (Exception e) {
			log.debug("[Discoverer] Error checking type assignability: {}", e.getMessage());
		}
		return false;
	}

	private boolean isSuperclassOf(ClassType classType, ClassType target) {
		try {
			ClassType current = classType;
			while (current != null) {
				if (current.equals(target)) {
					return true;
				}
				current = current.superclass();
			}
		} catch (Exception e) {
			// Ignore
		}
		return false;
	}

	/**
	 * Reflectively invokes {@code getURLs()} on a URLClassLoader (or compatible subclass) in the target JVM
	 * via JDI method invocation, then extracts file paths from the returned URL array.
	 */
	private void extractUrlsFromClassLoader(ClassLoaderReference classLoaderRef, ThreadReference suspendedThread,
											Set<String> classpathEntries) {
		try {
			ReferenceType clType = classLoaderRef.referenceType();
			List<Method> getUrlsMethods = clType.methodsByName("getURLs", "()[Ljava/net/URL;");

			if (getUrlsMethods.isEmpty()) {
				log.debug("[Discoverer] ClassLoader {} does not have getURLs() method", clType.name());
				return;
			}

			Method getUrlsMethod = getUrlsMethods.get(0);
			Value result = classLoaderRef.invokeMethod(
				suspendedThread,
				getUrlsMethod,
				Collections.emptyList(),
				ClassType.INVOKE_SINGLE_THREADED
			);

			if (!(result instanceof ArrayReference urlsArray)) {
				return;
			}
			int urlCount = 0;

			for (Value urlValue : urlsArray.getValues()) {
				if (urlValue instanceof ObjectReference urlRef) {
					String path = extractPathFromUrl(urlRef, suspendedThread);
					if (path != null && !path.isEmpty()) {
						classpathEntries.add(path);
						urlCount++;
					}
				}
			}

			log.debug("[Discoverer] Extracted {} URLs from {}", urlCount, clType.name());

		} catch (Exception e) {
			log.warn("[Discoverer] Error extracting URLs from classloader: {}", e.getMessage());
		}
	}

	private String extractPathFromUrl(ObjectReference urlRef, ThreadReference suspendedThread) {
		try {
			ClassType urlClass = (ClassType) urlRef.referenceType();
			Method getPathMethod = urlClass.methodsByName("getPath", "()Ljava/lang/String;").get(0);

			Value result = urlRef.invokeMethod(
				suspendedThread,
				getPathMethod,
				Collections.emptyList(),
				ClassType.INVOKE_SINGLE_THREADED
			);

			if (result instanceof StringReference stringRef) {
				String path = stringRef.value();
				// URL paths may be URL-encoded (e.g., spaces as %20)
				// Decode if needed
				return decodeUrlPath(path);
			}
		} catch (Exception e) {
			log.debug("[Discoverer] Error extracting path from URL: {}", e.getMessage());
		}
		return null;
	}

	private String decodeUrlPath(String path) {
		try {
			// Simple URL decoding (replace %20 with space, etc.)
			return URLDecoder.decode(path, StandardCharsets.UTF_8);
		} catch (Exception e) {
			// If decoding fails, return the original path
			return path;
		}
	}

	private ClassLoaderReference getParentClassLoader(
			ClassLoaderReference classLoaderRef, ThreadReference suspendedThread) {
		try {
			ReferenceType clType = classLoaderRef.referenceType();
			List<Method> getParentMethods = clType.methodsByName("getParent", "()Ljava/lang/ClassLoader;");

			if (getParentMethods.isEmpty()) {
				return null;
			}

			Method getParentMethod = getParentMethods.get(0);
			Value result = classLoaderRef.invokeMethod(
				suspendedThread,
				getParentMethod,
				Collections.emptyList(),
				ClassType.INVOKE_SINGLE_THREADED
			);

			return (ClassLoaderReference) result;
		} catch (Exception e) {
			log.debug("[Discoverer] Could not get parent classloader: {}", e.getMessage());
			return null;
		}
	}
}
