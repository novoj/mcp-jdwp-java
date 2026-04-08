package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers a local JDK installation matching the target JVM's Java version. The Eclipse JDT
 * compiler used by {@link InMemoryJavaCompiler} needs `--system <jdkPath>` to resolve `java.*`
 * system classes when compiling expression-evaluator wrapper classes; without this discovery the
 * compiler can't produce bytecode for the target.
 *
 * One-shot, stateful helper: holds {@link #targetMajorVersion} after a successful
 * {@link #discoverMatchingJdk} so callers can read it without re-running discovery. NOT a Spring
 * bean — instantiated manually by {@link ClasspathDiscoverer} per discovery call.
 *
 * Search strategy (in order):
 * 1. The target JVM's own `java.home` if accessible from the MCP server's filesystem.
 * 2. Common per-OS install paths (Adoptium, Oracle, OpenJDK, Zulu on Windows; `/usr/lib/jvm`,
 *    `/opt` on Linux/Unix).
 * 3. Directory scan of those parent paths for any subdirectory matching a `<name>-<version>`
 *    pattern containing the major version.
 */
@Slf4j
public class JdkDiscoveryService {

	private final VirtualMachine vm;
	/** Target JVM major version, populated as a side effect of {@link #discoverMatchingJdk}; 0 until then. */
	private int targetMajorVersion;

	public JdkDiscoveryService(VirtualMachine vm) {
		this.vm = vm;
	}

	/** Returns 0 until {@link #discoverMatchingJdk} has been called successfully. */
	public int getTargetMajorVersion() {
		return targetMajorVersion;
	}

	/**
	 * Runs the search strategy and returns the absolute path of a JDK home directory matching the
	 * target JVM. Side effect: populates {@link #targetMajorVersion}. The thrown
	 * {@link JdkNotFoundException} carries a user-actionable error message listing the common
	 * installation paths the search probed.
	 *
	 * @param suspendedThread thread suspended at a breakpoint or step (used to invoke
	 *                        `System.getProperty` in the target VM)
	 * @return path to the local JDK home directory
	 * @throws JdkNotFoundException if no matching JDK is found anywhere on the search path
	 */
	public String discoverMatchingJdk(ThreadReference suspendedThread) throws JdkNotFoundException {
		try {
			// 1. Get target JVM version
			String targetVersion = getTargetJavaVersion(suspendedThread);
			String targetHome = getTargetJavaHome(suspendedThread);

			log.info("[JDK Discovery] Target JVM: Java {} at {}", targetVersion, targetHome);

			// 2. Extract major version (e.g., "11" from "11.0.21")
			int targetMajorVersion = extractMajorVersion(targetVersion);
			this.targetMajorVersion = targetMajorVersion;

			log.info("[JDK Discovery] Looking for Java {} JDK on MCP server...", targetMajorVersion);

			// 3. Search for matching JDK
			String localJdkPath = findLocalJdk(targetMajorVersion, targetHome);

			if (localJdkPath != null) {
				log.info("[JDK Discovery] Found matching JDK: {}", localJdkPath);
				return localJdkPath;
			}

			// 4. Not found - throw explicit error
			String errorMessage = String.format(
				"No local JDK installation found for Java %d.\n\n" +
				"The target JVM is running Java %s, but the MCP server cannot find a matching JDK.\n\n" +
				"To fix this:\n" +
				"  1. Install a Java %d JDK on the MCP server\n" +
				"  2. Common locations checked:\n" +
				"     - C:\\Program Files\\Eclipse Adoptium\\jdk-%d.*\n" +
				"     - C:\\Program Files\\Java\\jdk-%d.*\n" +
				"     - C:\\Program Files\\OpenJDK\\jdk-%d.*\n" +
				"     - /usr/lib/jvm/java-%d-openjdk*\n" +
				"     - /usr/lib/jvm/jdk-%d*\n\n" +
				"Expression evaluation requires access to JDK system classes.",
				targetMajorVersion, targetVersion, targetMajorVersion,
				targetMajorVersion, targetMajorVersion, targetMajorVersion,
				targetMajorVersion, targetMajorVersion
			);

			throw new JdkNotFoundException(errorMessage);

		} catch (Exception e) {
			if (e instanceof JdkNotFoundException jnf) {
				throw jnf;
			}
			throw new JdkNotFoundException("Failed to discover JDK: " + e.getMessage(), e);
		}
	}

	/** Invokes `System.getProperty("java.version")` in the target VM. */
	private String getTargetJavaVersion(ThreadReference suspendedThread) throws Exception {
		return getSystemProperty(suspendedThread, "java.version");
	}

	/** Invokes `System.getProperty("java.home")` in the target VM. */
	private String getTargetJavaHome(ThreadReference suspendedThread) throws Exception {
		return getSystemProperty(suspendedThread, "java.home");
	}

	/**
	 * Invokes {@code System.getProperty(name)} in the target VM via JDI and returns the string value.
	 */
	private String getSystemProperty(ThreadReference suspendedThread, String propertyName) throws Exception {
		ClassType systemClass = (ClassType) vm.classesByName("java.lang.System").get(0);
		Method getPropertyMethod = systemClass.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
		StringReference nameArg = vm.mirrorOf(propertyName);

		Value result = systemClass.invokeMethod(
			suspendedThread,
			getPropertyMethod,
			Collections.singletonList(nameArg),
			ClassType.INVOKE_SINGLE_THREADED
		);

		return ((StringReference) result).value();
	}

	/**
	 * Parses both the legacy `1.8.x` scheme (returns 8) and the modern `<major>.x.x` scheme
	 * (returns the first dot-separated integer). Returns 0 on parse failure.
	 */
	private int extractMajorVersion(String version) {
		// Handle both "1.8.0_xxx" (Java 8) and "11.0.21" (Java 9+) formats
		if (version.startsWith("1.8")) {
			return 8;
		}

		// For Java 9+, major version is the first number
		String[] parts = version.split("\\.");
		try {
			return Integer.parseInt(parts[0]);
		} catch (NumberFormatException e) {
			log.warn("[JDK Discovery] Could not parse version: {}", version);
			return 0;
		}
	}

	/**
	 * Three-strategy fallback search documented on the class. Returns the first valid JDK home
	 * found, or `null` if every strategy fails (the caller throws {@link JdkNotFoundException}).
	 */
	private String findLocalJdk(int majorVersion, String targetHome) {
		// Strategy 1: Check if target JVM's java.home is accessible locally
		if (isValidJdkHome(targetHome)) {
			log.debug("[JDK Discovery] Target JVM home is accessible locally: {}", targetHome);
			return targetHome;
		}

		// Strategy 2: Search common JDK installation directories
		List<String> searchPaths = getCommonJdkPaths(majorVersion);

		for (String path : searchPaths) {
			if (isValidJdkHome(path)) {
				log.debug("[JDK Discovery] Found JDK at: {}", path);
				return path;
			}
		}

		// Strategy 3: Search for any JDK with matching major version
		String foundJdk = searchDirectoriesForJdk(majorVersion);
		if (foundJdk != null) {
			return foundJdk;
		}

		return null;
	}

	/**
	 * Returns OS-sensitive list of common JDK install paths. On Windows checks Adoptium, Oracle
	 * Java, OpenJDK, and Zulu directories under `Program Files`; on Linux/Unix checks
	 * `/usr/lib/jvm` and `/opt`.
	 */
	private List<String> getCommonJdkPaths(int majorVersion) {
		List<String> paths = new ArrayList<>();

		// Windows paths
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			paths.add(String.format("C:\\Program Files\\Eclipse Adoptium\\jdk-%d", majorVersion));
			paths.add(String.format("C:\\Program Files\\Java\\jdk-%d", majorVersion));
			paths.add(String.format("C:\\Program Files\\OpenJDK\\jdk-%d", majorVersion));
			paths.add(String.format("C:\\Program Files\\Zulu\\zulu-%d", majorVersion));
		} else {
			// Linux/Unix paths
			paths.add(String.format("/usr/lib/jvm/java-%d-openjdk", majorVersion));
			paths.add(String.format("/usr/lib/jvm/java-%d-openjdk-amd64", majorVersion));
			paths.add(String.format("/usr/lib/jvm/jdk-%d", majorVersion));
			paths.add(String.format("/opt/jdk-%d", majorVersion));
		}

		return paths;
	}

	/**
	 * Filesystem scan of the common JDK parent directories looking for a subdirectory whose name
	 * contains `jdk` or `java` and matches a `-<major>` / `_<major>` version-suffix pattern.
	 * Returns the first valid JDK home found via {@link #isValidJdkHome}.
	 */
	private String searchDirectoriesForJdk(int majorVersion) {
		List<Path> searchDirs = new ArrayList<>();

		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			searchDirs.add(Paths.get("C:\\Program Files\\Eclipse Adoptium"));
			searchDirs.add(Paths.get("C:\\Program Files\\Java"));
			searchDirs.add(Paths.get("C:\\Program Files\\OpenJDK"));
		} else {
			searchDirs.add(Paths.get("/usr/lib/jvm"));
			searchDirs.add(Paths.get("/opt"));
		}

		for (Path searchDir : searchDirs) {
			if (!Files.exists(searchDir)) {
				continue;
			}

			try (Stream<Path> paths = Files.list(searchDir)) {
				Optional<Path> matchingJdk = paths
					.filter(Files::isDirectory)
					.filter(p -> p.getFileName().toString().contains("jdk") ||
								 p.getFileName().toString().contains("java"))
					.filter(p -> {
						String name = p.getFileName().toString();
						return name.contains("-" + majorVersion) ||
							   name.contains("_" + majorVersion) ||
							   name.matches(".*jdk" + majorVersion + ".*");
					})
					.filter(p -> isValidJdkHome(p.toString()))
					.findFirst();

				if (matchingJdk.isPresent()) {
					return matchingJdk.get().toString();
				}
			} catch (Exception e) {
				log.debug("[JDK Discovery] Error searching {}: {}", searchDir, e.getMessage());
			}
		}

		return null;
	}

	/**
	 * Detects whether `path` points to a valid JDK home using three layout markers:
	 * - Java 9+: presence of `jmods/` or `lib/jrt-fs.jar`.
	 * - Java 8: presence of `lib/rt.jar`.
	 * - JDK-with-bundled-JRE layout: presence of `jre/lib/rt.jar`.
	 */
	private boolean isValidJdkHome(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}

		File dir = new File(path);
		if (!dir.exists() || !dir.isDirectory()) {
			return false;
		}

		// Check for JDK markers
		// Java 9+: jmods directory or lib/jrt-fs.jar
		if (new File(dir, "jmods").exists() || new File(dir, "lib/jrt-fs.jar").exists()) {
			return true;
		}

		// Java 8: lib/rt.jar
		if (new File(dir, "lib/rt.jar").exists()) {
			return true;
		}

		// Also check in jre subdirectory (some JDK distributions)
		if (new File(dir, "jre/lib/rt.jar").exists()) {
			return true;
		}

		return false;
	}

	/**
	 * Exception thrown when no matching JDK is found.
	 */
	public static class JdkNotFoundException extends Exception {
		public JdkNotFoundException(String message) {
			super(message);
		}

		public JdkNotFoundException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
