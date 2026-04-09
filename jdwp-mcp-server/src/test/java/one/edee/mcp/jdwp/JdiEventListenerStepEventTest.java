package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JdiEventListener}'s handling of {@link StepEvent}s: the one-shot StepRequest
 * deletion, event recording, latch firing, and suppression under the evaluation guard.
 */
class JdiEventListenerStepEventTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private EvaluationGuard evaluationGuard;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluationGuard = new EvaluationGuard();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, evaluationGuard);
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("Normal step: STEP recorded, latch fired, StepRequest deleted")
	void shouldRecordStepAndFireLatchAndDeleteRequest() throws Exception {
		ThreadReference thread = mockThread("worker", 100L);
		StepRequest stepRequest = mock(StepRequest.class);
		VirtualMachine requestVm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(stepRequest.virtualMachine()).thenReturn(requestVm);
		when(requestVm.eventRequestManager()).thenReturn(erm);

		StepEvent event = mockStepEvent(thread, stepRequest, "com.example.App", 42);
		EventSet eventSet = mockEventSet(event);

		CountDownLatch latch = tracker.armNextEventLatch();
		runListenerWith(eventSet);

		// StepRequest should be deleted (JDI convention for one-shot steps)
		verify(erm).deleteEventRequest(stepRequest);
		// STEP event recorded
		assertLatestEventType("STEP");
		// Latch should be fired
		assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
		// EventSet should NOT be resumed (step stays suspended)
		verify(eventSet, never()).resume();
	}

	@Test
	@DisplayName("Suppressed step: guard armed, STEP_SUPPRESSED recorded, eventSet resumed")
	void shouldSuppressStepWhenThreadIsInsideEvaluation() throws Exception {
		ThreadReference evalThread = mockThread("eval-thread", 200L);
		StepRequest stepRequest = mock(StepRequest.class);
		VirtualMachine requestVm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(stepRequest.virtualMachine()).thenReturn(requestVm);
		when(requestVm.eventRequestManager()).thenReturn(erm);

		StepEvent event = mockStepEvent(evalThread, stepRequest, "com.example.Internal", 99);
		EventSet eventSet = mockEventSet(event);

		evaluationGuard.enter(evalThread.uniqueID());
		runListenerWith(eventSet);

		// StepRequest is always deleted, even when suppressed
		verify(erm).deleteEventRequest(stepRequest);
		// STEP_SUPPRESSED recorded
		assertLatestEventType("STEP_SUPPRESSED");
		// EventSet should be auto-resumed since no event demanded suspension
		verify(eventSet).resume();
	}

	// ── Helpers ──

	private void runListenerWith(EventSet eventSet) throws InterruptedException {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);

		BlockingQueue<Object> pending = new ArrayBlockingQueue<>(4);
		try {
			pending.put(eventSet);
			pending.put(new VMDisconnectedException());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}

		CountDownLatch drained = new CountDownLatch(2);
		when(queue.remove()).thenAnswer(invocation -> {
			Object next = pending.take();
			drained.countDown();
			if (next instanceof EventSet es) {
				return es;
			}
			throw (VMDisconnectedException) next;
		});

		listener.start(vm);
		assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(30);
	}

	private static EventSet mockEventSet(Event... events) {
		EventSet set = mock(EventSet.class);
		when(set.iterator()).thenAnswer(inv -> iteratorOver(events));
		return set;
	}

	private static Iterator<Event> iteratorOver(Event[] events) {
		return List.of(events).iterator();
	}

	private static StepEvent mockStepEvent(ThreadReference thread, StepRequest request,
			String className, int line) {
		StepEvent event = mock(StepEvent.class);
		when(event.request()).thenReturn(request);
		when(event.thread()).thenReturn(thread);
		Location location = mock(Location.class);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(location.declaringType()).thenReturn(declaringType);
		when(location.lineNumber()).thenReturn(line);
		when(event.location()).thenReturn(location);
		return event;
	}

	private static ThreadReference mockThread(String name, long uniqueId) {
		ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn(name);
		when(thread.uniqueID()).thenReturn(uniqueId);
		return thread;
	}

	private void assertLatestEventType(String expectedType) {
		List<EventHistory.DebugEvent> recent = eventHistory.getRecent(5);
		assertThat(recent).isNotEmpty();
		assertThat(recent.get(recent.size() - 1).type()).isEqualTo(expectedType);
	}
}
