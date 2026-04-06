package io.mcp.jdwp.evaluation;

import com.sun.jdi.VirtualMachine;
import io.mcp.jdwp.TestReflectionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JdkDiscoveryServiceTest {

	private JdkDiscoveryService service;

	@BeforeEach
	void setUp() {
		// JdkDiscoveryService requires a VirtualMachine but extractMajorVersion and isValidJdkHome
		// do not use it, so a mock is sufficient for these tests.
		VirtualMachine mockVm = mock(VirtualMachine.class);
		service = new JdkDiscoveryService(mockVm);
	}

	@Test
	void shouldExtractMajorVersionFromJava8Format() throws Exception {
		int result = TestReflectionUtils.invokePrivate(
			service, "extractMajorVersion",
			new Class[]{String.class}, "1.8.0_292"
		);
		assertThat(result).isEqualTo(8);
	}

	@Test
	void shouldExtractMajorVersionFromJava11Format() throws Exception {
		int result = TestReflectionUtils.invokePrivate(
			service, "extractMajorVersion",
			new Class[]{String.class}, "11.0.21"
		);
		assertThat(result).isEqualTo(11);
	}

	@Test
	void shouldExtractMajorVersionFromJava17Format() throws Exception {
		int result = TestReflectionUtils.invokePrivate(
			service, "extractMajorVersion",
			new Class[]{String.class}, "17.0.9"
		);
		assertThat(result).isEqualTo(17);
	}

	@Test
	void shouldExtractMajorVersionFromJava21Format() throws Exception {
		int result = TestReflectionUtils.invokePrivate(
			service, "extractMajorVersion",
			new Class[]{String.class}, "21.0.1"
		);
		assertThat(result).isEqualTo(21);
	}

	@Test
	void shouldReturnZeroForUnparseableVersion() throws Exception {
		int result = TestReflectionUtils.invokePrivate(
			service, "extractMajorVersion",
			new Class[]{String.class}, "abc"
		);
		assertThat(result).isZero();
	}

	@Test
	void shouldRejectNonexistentPath() throws Exception {
		boolean result = TestReflectionUtils.invokePrivate(
			service, "isValidJdkHome",
			new Class[]{String.class}, "/nonexistent/path"
		);
		assertThat(result).isFalse();
	}

	@Test
	void shouldValidateRealJdkHome() throws Exception {
		// The running JVM's java.home should be a valid JDK (or JRE) home.
		// On a full JDK (Java 9+), this should have jmods or lib/jrt-fs.jar.
		String javaHome = System.getProperty("java.home");
		boolean result = TestReflectionUtils.invokePrivate(
			service, "isValidJdkHome",
			new Class[]{String.class}, javaHome
		);
		assertThat(result).isTrue();
	}
}
