package dev.modreloader.reflect;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * Access to private/final JDK and FML fields.
 *
 * <p>Java's module system blocks {@code setAccessible} on {@code java.base}
 * internals, so we use {@link Unsafe} for field writes and a trusted
 * {@link MethodHandles.Lookup} for private constructors/methods.
 */
public final class UnsafeAccess {
    private static final Unsafe UNSAFE;
    private static final MethodHandles.Lookup TRUSTED;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);

            Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(UNSAFE, implLookup);
            long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, implLookup);
            TRUSTED = (MethodHandles.Lookup) unsafeClass
                    .getMethod("getObject", Object.class, long.class)
                    .invoke(UNSAFE, base, offset);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private UnsafeAccess() {
    }

    public static MethodHandles.Lookup trusted() {
        return TRUSTED;
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, Class<?> declaring, String name) {
        try {
            Field field = declaring.getDeclaredField(name);
            long offset = UNSAFE.objectFieldOffset(field);
            return (T) UNSAFE.getObject(target, offset);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + declaring.getName() + "#" + name, e);
        }
    }

    public static void set(Object target, Class<?> declaring, String name, Object value) {
        try {
            Field field = declaring.getDeclaredField(name);
            long offset = UNSAFE.objectFieldOffset(field);
            UNSAFE.putObject(target, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot write " + declaring.getName() + "#" + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStatic(Class<?> declaring, String name) {
        try {
            Field field = declaring.getDeclaredField(name);
            Object base = UNSAFE.staticFieldBase(field);
            long offset = UNSAFE.staticFieldOffset(field);
            return (T) UNSAFE.getObject(base, offset);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read static " + declaring.getName() + "#" + name, e);
        }
    }

    public static boolean getBoolean(Object target, Class<?> declaring, String name) {
        try {
            Field field = declaring.getDeclaredField(name);
            long offset = UNSAFE.objectFieldOffset(field);
            return UNSAFE.getBoolean(target, offset);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read boolean " + declaring.getName() + "#" + name, e);
        }
    }

    public static void setBoolean(Object target, Class<?> declaring, String name, boolean value) {
        try {
            Field field = declaring.getDeclaredField(name);
            long offset = UNSAFE.objectFieldOffset(field);
            UNSAFE.putBoolean(target, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot write boolean " + declaring.getName() + "#" + name, e);
        }
    }

    public static boolean getStaticBoolean(Class<?> declaring, String name) {
        try {
            Field field = declaring.getDeclaredField(name);
            Object base = UNSAFE.staticFieldBase(field);
            long offset = UNSAFE.staticFieldOffset(field);
            return UNSAFE.getBoolean(base, offset);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read static boolean " + declaring.getName() + "#" + name, e);
        }
    }

    public static void setStaticBoolean(Class<?> declaring, String name, boolean value) {
        try {
            Field field = declaring.getDeclaredField(name);
            Object base = UNSAFE.staticFieldBase(field);
            long offset = UNSAFE.staticFieldOffset(field);
            UNSAFE.putBoolean(base, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot write static boolean " + declaring.getName() + "#" + name, e);
        }
    }

    public static void setStatic(Class<?> declaring, String name, Object value) {
        try {
            Field field = declaring.getDeclaredField(name);
            Object base = UNSAFE.staticFieldBase(field);
            long offset = UNSAFE.staticFieldOffset(field);
            UNSAFE.putObject(base, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot write static " + declaring.getName() + "#" + name, e);
        }
    }
}
