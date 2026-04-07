package io.mcp.jdwp;

import java.lang.reflect.Method;

/**
 * Utility for invoking private methods in tests via reflection.
 */
public final class TestReflectionUtils {

	private TestReflectionUtils() {
		throw new UnsupportedOperationException("Utility class");
	}

	/**
	 * Invokes a private method on the given target object.
	 *
	 * @param target     the object on which to invoke the method
	 * @param methodName the name of the private method
	 * @param paramTypes the parameter types of the method
	 * @param args       the arguments to pass to the method
	 * @return the result of the method invocation
	 */
	@SuppressWarnings("unchecked")
	public static <T> T invokePrivate(Object target, String methodName, Class<?>[] paramTypes, Object... args)
			throws Exception {
		Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
		method.setAccessible(true);
		return (T) method.invoke(target, args);
	}
}
