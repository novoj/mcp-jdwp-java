package one.edee.jdwp.sandbox.recursion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entry point for the "Echo Chamber" test flight. The assertion is real (the production code
 * is correct), so this test passes when run standalone. Its role in the sandbox is to give
 * the Maven Surefire JVM something to pause in so an MCP client can attach, set a breakpoint
 * inside {@code compute(int)}, and drive a recursive re-entry via expression evaluation.
 *
 * <p>Run with:
 * <pre>
 *   mvn -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest -DskipTests=false \
 *       -Dmaven.surefire.debug
 * </pre>
 */
class RecursiveCalculatorTest {

	@Test
	void shouldComputeFibonacciOfFive() {
		RecursiveCalculator calculator = new RecursiveCalculator();

		assertThat(calculator.compute(5)).isEqualTo(5);
	}
}
