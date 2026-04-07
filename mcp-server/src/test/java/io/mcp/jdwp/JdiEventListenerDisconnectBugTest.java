package io.mcp.jdwp;

import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventQueue;
import io.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies FINDING-7: when the JDI event loop catches a {@link VMDisconnectedException} on
 * external VM disconnect, the listener must release the {@code resume_until_event} latch
 * before exiting so any parked waiter can return promptly instead of hanging until timeout.
 *
 * <p>This test starts the real listener thread with a mocked VM whose
 * {@code eventQueue().remove()} throws {@code VMDisconnectedException}, then asserts the
 * pre-armed latch IS released within a short observation window.
 */
class JdiEventListenerDisconnectBugTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator);
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	/**
	 * FINDING-7: external VM disconnect must release {@code resume_until_event} waiters.
	 * Drives the listener with a queue that immediately throws {@link VMDisconnectedException}
	 * and asserts the pre-armed latch is counted down promptly.
	 */
	@Test
	void shouldNotReleaseWaiterWhenVmDisconnectExceptionIsThrown_FINDING_7() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		when(queue.remove()).thenThrow(new VMDisconnectedException());

		CountDownLatch latch = tracker.armNextEventLatch();
		listener.start(vm);

		// Listener must wake the waiter so the caller can detect the disconnect promptly.
		boolean released = latch.await(2, TimeUnit.SECONDS);
		assertThat(released).isTrue();
	}
}
