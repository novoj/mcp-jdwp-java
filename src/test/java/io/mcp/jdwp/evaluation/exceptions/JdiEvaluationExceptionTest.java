package io.mcp.jdwp.evaluation.exceptions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdiEvaluationExceptionTest {

	@Test
	void shouldStoreMessage() {
		JdiEvaluationException ex = new JdiEvaluationException("eval failed");
		assertThat(ex.getMessage()).isEqualTo("eval failed");
		assertThat(ex.getCause()).isNull();
	}

	@Test
	void shouldStoreMessageAndCause() {
		RuntimeException cause = new RuntimeException("root cause");
		JdiEvaluationException ex = new JdiEvaluationException("eval failed", cause);

		assertThat(ex.getMessage()).isEqualTo("eval failed");
		assertThat(ex.getCause()).isSameAs(cause);
	}
}
