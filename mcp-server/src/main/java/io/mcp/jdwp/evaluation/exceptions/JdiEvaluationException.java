package io.mcp.jdwp.evaluation.exceptions;

/**
 * Single checked exception propagated out of the expression-evaluation pipeline (the
 * {@link io.mcp.jdwp.evaluation.InMemoryJavaCompiler}, {@link io.mcp.jdwp.evaluation.RemoteCodeExecutor},
 * and {@link io.mcp.jdwp.evaluation.JdiExpressionEvaluator}). Caught at the top level by
 * `JDWPTools.jdwp_evaluate_expression` and `jdwp_assert_expression`, where the message is
 * formatted into the human-readable string returned to the MCP client.
 */
public class JdiEvaluationException extends Exception {

	public JdiEvaluationException(String message) {
		super(message);
	}

	public JdiEvaluationException(String message, Throwable cause) {
		super(message, cause);
	}
}
