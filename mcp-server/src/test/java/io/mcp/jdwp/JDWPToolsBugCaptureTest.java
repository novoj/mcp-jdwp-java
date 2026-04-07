package io.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import io.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import io.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Captures bugs found by the bug-hunter pass that involve {@link JDWPTools} entry points.
 * Each test asserts the CURRENT broken behaviour so the eventual fix can flip the assertion.
 */
@DisplayName("JDWPTools known limitations")
class JDWPToolsBugCaptureTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private WatcherManager watcherManager;
	private JdiExpressionEvaluator evaluator;
	private EventHistory eventHistory;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		watcherManager = mock(WatcherManager.class);
		evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = mock(EventHistory.class);
		tools = new JDWPTools(jdiService, breakpointTracker, watcherManager, evaluator, eventHistory, new EvaluationGuard());
	}

	/**
	 * FINDING-5: passing a length-1 input of just {@code "} must NOT crash with a
	 * {@link StringIndexOutOfBoundsException}. The strip-quotes branch must guard on
	 * {@code value.length() >= 2} so an unbalanced single quote falls through unchanged.
	 * The resulting value is mirrored verbatim and the call succeeds.
	 */
	@Test
	void shouldCrashOnSingleQuoteWhenSettingStringLocal_FINDING_5() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		ThreadReference thread = mock(ThreadReference.class);
		StackFrame frame = mock(StackFrame.class);
		LocalVariable localVar = mock(LocalVariable.class);
		Type type = mock(Type.class);
		com.sun.jdi.StringReference mirrored = mock(com.sun.jdi.StringReference.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(frame.visibleVariableByName("s")).thenReturn(localVar);
		when(localVar.typeName()).thenReturn("java.lang.String");
		when(localVar.type()).thenReturn(type);
		when(type.name()).thenReturn("java.lang.String");
		when(vm.mirrorOf("\"")).thenReturn(mirrored);

		String result = tools.jdwp_set_local(1L, 0, "s", "\"");

		assertThat(result).doesNotStartWith("Error");
		assertThat(result).contains("Variable 's' set to");
	}

	/**
	 * FINDING-10: when {@code jdiService.getVM()} throws (e.g. external VM disconnect),
	 * {@code jdwp_reset} must still clear all server-local state (watchers, object cache,
	 * event history, breakpoint tracker) so a subsequent reconnect starts from a clean slate.
	 */
	@Test
	void shouldSilentlyDropWatcherAndCacheClearsWhenGetVmThrows_FINDING_10() throws Exception {
		when(jdiService.getVM()).thenThrow(new Exception("Not connected"));

		String result = tools.jdwp_reset();

		assertThat(result).doesNotStartWith("Error:");
		// Server-local state must be cleared even when the VM connection is dead.
		verify(watcherManager).clearAll();
		verify(eventHistory).clear();
		verify(jdiService).clearObjectCache();
		verify(breakpointTracker).reset();
	}

	/**
	 * Tier 1B: in {@code jdwp_set_breakpoint}, the recheck-after-CPR-registration path can throw
	 * {@link AbsentInformationException} from {@code locationsOfLine}. The pending breakpoint
	 * registered earlier in the method must be removed in the catch block — otherwise it leaks
	 * into {@code BreakpointTracker} along with its ClassPrepareRequest.
	 */
	@Test
	void shouldRemoveOrphanPendingBreakpointWhenLocationsOfLineThrows() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);
		// First lookup returns nothing → enter the pending branch
		when(jdiService.findOrForceLoadClass("com.example.MyClass")).thenReturn(null);
		// The recheck inside the pending branch finds the class — race window
		when(vm.classesByName("com.example.MyClass")).thenReturn(List.of(refType));
		// locationsOfLine throws AbsentInformationException
		when(refType.locationsOfLine(42)).thenThrow(new AbsentInformationException());
		// The pending registration returns ID 99
		when(breakpointTracker.registerPendingBreakpoint(anyString(), anyInt(), anyInt(), anyString()))
			.thenReturn(99);

		String result = tools.jdwp_set_breakpoint("com.example.MyClass", 42, "all", null);

		// User-facing error message preserved
		assertThat(result).startsWith("Error:");
		assertThat(result).contains("debug info");
		// The orphan pending entry must be cleaned up
		verify(breakpointTracker).removePendingBreakpoint(99);
	}

	/**
	 * Tier 1B: same orphan-leak in {@code jdwp_set_logpoint}. The recheck path can throw
	 * {@link AbsentInformationException}, leaking the pending entry, its logpoint expression
	 * metadata, and the ClassPrepareRequest.
	 */
	@Test
	void shouldRemoveOrphanPendingLogpointWhenLocationsOfLineThrows() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);
		when(jdiService.findOrForceLoadClass("com.example.MyClass")).thenReturn(null);
		when(vm.classesByName("com.example.MyClass")).thenReturn(List.of(refType));
		when(refType.locationsOfLine(42)).thenThrow(new AbsentInformationException());
		when(breakpointTracker.registerPendingBreakpoint(anyString(), anyInt(), anyInt(), anyString()))
			.thenReturn(77);

		String result = tools.jdwp_set_logpoint("com.example.MyClass", 42, "\"x=\" + x", null);

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("debug info");
		verify(breakpointTracker).removePendingBreakpoint(77);
	}

	/**
	 * Tier 3-E: when the user calls {@code jdwp_clear_breakpoint} with an exception class name,
	 * the "no breakpoint found" message should hint at {@code jdwp_clear_exception_breakpoint}
	 * instead of leaving the user wondering why the clear was a no-op. The hint MUST only fire
	 * when the class name actually matches a tracked exception BP — otherwise it's noise on
	 * every typo.
	 */
	@Test
	void shouldHintAtExceptionClearToolWhenClassNameMatchesPendingExceptionBp() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		// No regular line BP class is loaded — go down the "class not loaded" branch
		when(vm.classesByName("java.lang.NullPointerException")).thenReturn(List.of());
		// No pending line BPs for that class either
		when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(java.util.Map.of());
		// But there IS a pending exception BP
		BreakpointTracker.PendingExceptionBreakpoint pending = mock(BreakpointTracker.PendingExceptionBreakpoint.class);
		when(pending.getExceptionClass()).thenReturn("java.lang.NullPointerException");
		when(breakpointTracker.getAllPendingExceptionBreakpoints()).thenReturn(java.util.Map.of(5, pending));
		when(breakpointTracker.getAllExceptionBreakpoints()).thenReturn(java.util.Map.of());

		String result = tools.jdwp_clear_breakpoint("java.lang.NullPointerException", 0);

		assertThat(result).contains("jdwp_clear_exception_breakpoint");
	}

	/**
	 * Tier 3-E: the hint must NOT appear for a regular class name with no matching exception BP.
	 * The original "no breakpoint found" message should be preserved verbatim so this isn't
	 * noise on every typo or normal miss.
	 */
	@Test
	void shouldNotHintAtExceptionClearToolForUnknownClassName() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(vm.classesByName("com.example.Unknown")).thenReturn(List.of());
		when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(java.util.Map.of());
		when(breakpointTracker.getAllPendingExceptionBreakpoints()).thenReturn(java.util.Map.of());
		when(breakpointTracker.getAllExceptionBreakpoints()).thenReturn(java.util.Map.of());

		String result = tools.jdwp_clear_breakpoint("com.example.Unknown", 42);

		assertThat(result).doesNotContain("jdwp_clear_exception_breakpoint");
	}
}
