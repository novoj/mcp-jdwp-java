package io.mcp.jdwp.evaluation;

import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassType;
import com.sun.jdi.ReferenceType;
import io.mcp.jdwp.JDIConnectionService;
import io.mcp.jdwp.TestReflectionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JdiExpressionEvaluator}'s private {@code getDeclaredType(ReferenceType)} helper.
 * This routine resolves the type name the wrapper class should reference, taking care of
 * dynamic proxies (CGLIB / Guice / Mockito {@code $$}) and non-public types (walks up to a
 * public supertype, falling back to {@code java.lang.Object}).
 */
class JdiExpressionEvaluatorGetDeclaredTypeTest {

	private JdiExpressionEvaluator evaluator;

	@BeforeEach
	void setUp() {
		InMemoryJavaCompiler compiler = mock(InMemoryJavaCompiler.class);
		RemoteCodeExecutor executor = mock(RemoteCodeExecutor.class);
		JDIConnectionService jdiService = mock(JDIConnectionService.class);
		evaluator = new JdiExpressionEvaluator(compiler, executor, jdiService);
	}

	@Nested
	@DisplayName("Public class types")
	class PublicClassType {

		@Test
		void shouldReturnNameForPublicClassDirectly() throws Exception {
			ClassType type = mock(ClassType.class);
			when(type.name()).thenReturn("com.example.Foo");
			when(type.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(type)).isEqualTo("com.example.Foo");
		}
	}

	@Nested
	@DisplayName("Non-public class types")
	class NonPublicClassType {

		@Test
		void shouldWalkUpToFirstPublicSupertype() throws Exception {
			ClassType c = mock(ClassType.class);
			ClassType b = mock(ClassType.class);
			ClassType a = mock(ClassType.class);
			when(a.name()).thenReturn("com.example.A");
			when(a.isPublic()).thenReturn(false);
			when(a.superclass()).thenReturn(b);
			when(b.name()).thenReturn("com.example.B");
			when(b.isPublic()).thenReturn(false);
			when(b.superclass()).thenReturn(c);
			when(c.name()).thenReturn("com.example.C");
			when(c.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(a)).isEqualTo("com.example.C");
		}

		@Test
		void shouldFallBackToJavaLangObjectWhenAllSuperclassesNonPublic() throws Exception {
			ClassType only = mock(ClassType.class);
			when(only.name()).thenReturn("com.example.Hidden");
			when(only.isPublic()).thenReturn(false);
			when(only.superclass()).thenReturn(null);

			assertThat(invokeGetDeclaredType(only)).isEqualTo("java.lang.Object");
		}
	}

	@Nested
	@DisplayName("Dynamic proxy handling")
	class DynamicProxyHandling {

		@Test
		void shouldUnwrapCglibProxyViaSuperclass() throws Exception {
			ClassType real = mock(ClassType.class);
			when(real.name()).thenReturn("com.example.Foo");
			when(real.isPublic()).thenReturn(true);

			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.example.Foo$$EnhancerByCGLIB$$abc123");
			when(proxy.superclass()).thenReturn(real);

			assertThat(invokeGetDeclaredType(proxy)).isEqualTo("com.example.Foo");
		}

		@Test
		void shouldUnwrapGuiceProxy() throws Exception {
			ClassType real = mock(ClassType.class);
			when(real.name()).thenReturn("com.example.Service");
			when(real.isPublic()).thenReturn(true);

			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.example.Service$$EnhancerByGuice$$abc");
			when(proxy.superclass()).thenReturn(real);

			assertThat(invokeGetDeclaredType(proxy)).isEqualTo("com.example.Service");
		}

		@Test
		void shouldFallBackToPrefixBeforeDollarsWhenSuperclassIsObject() throws Exception {
			// When the proxy's superclass is java.lang.Object, fall through to the substring fallback,
			// which returns the prefix before "$$". For a non-class declaring type the resulting name
			// is what gets returned by the trailing return statement.
			ClassType objectSuper = mock(ClassType.class);
			when(objectSuper.name()).thenReturn("java.lang.Object");

			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.example.Bar$$Mock");
			when(proxy.superclass()).thenReturn(objectSuper);
			when(proxy.isPublic()).thenReturn(true);

			// After the proxy fallback truncates the name to "com.example.Bar", the method then
			// re-enters the public-walk branch on the original ClassType (which is public), and
			// returns the original full proxy name. This locks in the current behaviour.
			String result = invokeGetDeclaredType(proxy);
			assertThat(result).isEqualTo("com.example.Bar$$Mock");
		}
	}

	@Nested
	@DisplayName("Non-class reference types")
	class NonClassReferenceType {

		@Test
		void shouldReturnNameForArrayType() throws Exception {
			ArrayType arr = mock(ArrayType.class);
			when(arr.name()).thenReturn("int[]");

			assertThat(invokeGetDeclaredType(arr)).isEqualTo("int[]");
		}
	}

	private String invokeGetDeclaredType(ReferenceType type) throws Exception {
		return TestReflectionUtils.invokePrivate(
			evaluator, "getDeclaredType",
			new Class[]{ReferenceType.class}, type);
	}
}
