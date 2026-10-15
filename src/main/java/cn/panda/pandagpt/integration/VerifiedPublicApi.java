package cn.panda.pandagpt.integration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact public read contracts; mismatched flags never degrade into permissive defaults. */
final class VerifiedPublicApi {
    static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (!result.isAssignableFrom(method.getReturnType()) || Modifier.isStatic(method.getModifiers()) != isStatic)
            throw new NoSuchMethodException("Incompatible public API signature: " + name);
        return method;
    }

    static Method getter(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class)
            throw new NoSuchMethodException("Incompatible public API getter: " + name);
        return method;
    }

    private VerifiedPublicApi() {}
}
