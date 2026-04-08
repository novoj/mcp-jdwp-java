package one.edee.mcp.jdwp.evaluation.exceptions;

import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;

import java.io.Serial;

/**
 * Single checked exception propagated out of the expression-evaluation pipeline (the
 * {@link InMemoryJavaCompiler}, {@link RemoteCodeExecutor},
 * and {@link JdiExpressionEvaluator}). Caught at the top level by
 * `JDWPTools.jdwp_evaluate_expression` and `jdwp_assert_expression`, where the message is
 * formatted into the human-readable string returned to the MCP client.
 */
public class JdiEvaluationException extends Exception {

    @Serial
    private static final long serialVersionUID = -8640643362312809268L;

    public JdiEvaluationException(String message) {
        super(message);
    }

    public JdiEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
