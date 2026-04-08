package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end compilation tests for {@link InMemoryJavaCompiler} that exercise paths not
 * touched by {@link InMemoryJavaCompilerTest}: packaged classes (so {@code MemoryJavaFileManager}
 * and the temp-dir directory creation are exercised together) and the target version 8 branch.
 */
class InMemoryJavaCompilerPackageIntegrationTest {

	private InMemoryJavaCompiler compiler;

	@BeforeEach
	void setUp() {
		compiler = new InMemoryJavaCompiler();
	}

	@Nested
	@DisplayName("Packaged class compilation")
	class PackagedClass {

		@Test
		void shouldCompilePackagedClassAndReturnBytecodeKeyedBySlashName() throws JdiEvaluationException {
			compiler.configure(System.getProperty("java.home"), "", 17);

			String source = "package com.example; public class Foo { public static int run() { return 7; } }";
			Map<String, byte[]> result = compiler.compile("com.example.Foo", source);

			// MemoryJavaFileManager keys bytecode by the URI form (slashes), not by the FQN.
			assertThat(result).containsKey("com/example/Foo");
			byte[] bytecode = result.get("com/example/Foo");
			assertThat(bytecode.length).isGreaterThan(4);
			assertThat(bytecode[0]).isEqualTo((byte) 0xCA);
			assertThat(bytecode[1]).isEqualTo((byte) 0xFE);
			assertThat(bytecode[2]).isEqualTo((byte) 0xBA);
			assertThat(bytecode[3]).isEqualTo((byte) 0xBE);
		}

		@Test
		void shouldCleanUpTemporaryDirectoryAfterCompile() throws Exception {
			compiler.configure(System.getProperty("java.home"), "", 17);

			compiler.compile("com.example.Foo",
				"package com.example; public class Foo { public static int run() { return 1; } }");

			Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
			try (Stream<Path> entries = Files.list(tmp)) {
				assertThat(entries.filter(p -> p.getFileName().toString().startsWith("mcp-compiler-")))
					.isEmpty();
			}
		}
	}

	@Nested
	@DisplayName("Target version handling")
	class TargetVersion {

		@Test
		void shouldCompileWithTargetVersion8Flag() throws JdiEvaluationException {
			compiler.configure(System.getProperty("java.home"), "", 8);

			String source = "public class V8 { public static int run() { return 1; } }";
			Map<String, byte[]> result = compiler.compile("V8", source);

			assertThat(result).containsKey("V8");
			assertThat(result.get("V8").length).isGreaterThan(4);
		}

		@Test
		void shouldDefaultToVersion8WhenConfiguredWithZero() throws JdiEvaluationException {
			compiler.configure(System.getProperty("java.home"), "", 0);

			String source = "public class V0 { public static int run() { return 1; } }";
			Map<String, byte[]> result = compiler.compile("V0", source);

			assertThat(result).containsKey("V0");
		}
	}
}
