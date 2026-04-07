package io.mcp.jdwp.evaluation;

import com.sun.jdi.VirtualMachine;
import io.mcp.jdwp.TestReflectionUtils;
import io.mcp.jdwp.evaluation.JdkDiscoveryService.JdkNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests {@link JdkDiscoveryService#getCommonJdkPaths(int)} (private — invoked via reflection)
 * and the basic shape of {@link JdkNotFoundException}. The path list is platform-branched, so
 * assertions are guarded by {@code System.getProperty("os.name")}.
 */
class JdkDiscoveryServiceCommonPathsTest {

	private JdkDiscoveryService service;

	@BeforeEach
	void setUp() {
		VirtualMachine vm = mock(VirtualMachine.class);
		service = new JdkDiscoveryService(vm);
	}

	@Nested
	@DisplayName("getCommonJdkPaths")
	class CommonPaths {

		@Test
		void shouldReturnNonEmptyPathListForMajorVersion17() throws Exception {
			List<String> paths = TestReflectionUtils.invokePrivate(
				service, "getCommonJdkPaths",
				new Class[]{int.class}, 17);

			assertThat(paths).isNotEmpty();
		}

		@Test
		void shouldIncludeVersionInEveryPath() throws Exception {
			List<String> paths = TestReflectionUtils.invokePrivate(
				service, "getCommonJdkPaths",
				new Class[]{int.class}, 17);

			assertThat(paths).allSatisfy(p -> assertThat(p).contains("17"));
		}

		@Test
		void shouldReturnPlatformAppropriatePaths() throws Exception {
			List<String> paths = TestReflectionUtils.invokePrivate(
				service, "getCommonJdkPaths",
				new Class[]{int.class}, 17);

			boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
			if (isWindows) {
				assertThat(paths).anyMatch(p -> p.contains("C:\\Program Files"));
			} else {
				assertThat(paths).anyMatch(p -> p.contains("/usr/lib/jvm") || p.contains("/opt"));
			}
		}
	}

	@Nested
	@DisplayName("JdkNotFoundException")
	class JdkNotFoundExceptionTests {

		@Test
		void shouldStoreMessage() {
			JdkNotFoundException ex = new JdkNotFoundException("missing JDK");

			assertThat(ex.getMessage()).isEqualTo("missing JDK");
			assertThat(ex.getCause()).isNull();
		}

		@Test
		void shouldStoreMessageAndCause() {
			RuntimeException cause = new RuntimeException("io fail");
			JdkNotFoundException ex = new JdkNotFoundException("wrap", cause);

			assertThat(ex.getMessage()).isEqualTo("wrap");
			assertThat(ex.getCause()).isSameAs(cause);
		}
	}
}
