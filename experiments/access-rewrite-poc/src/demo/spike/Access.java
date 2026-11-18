package demo.spike;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime side of the generated accessors: one cached MethodHandle per member,
 * looked up through a private lookup in the target class.
 *
 * <p>Works on a stock JVM as long as the target class is in a module that opens
 * the package to us — the unnamed module (classpath) does. It never changes the
 * target class.
 */
public final class Access {
    private static final Map<String, MethodHandle> CACHE = new ConcurrentHashMap<>();

    private Access() {
    }

    public static MethodHandle fieldGetter(Class<?> owner, String name, String desc) {
        Class<?> type = fieldType(owner, desc);
        return cached("get " + owner.getName() + "." + name,
                () -> lookup(owner).findGetter(owner, name, type));
    }

    public static MethodHandle fieldSetter(Class<?> owner, String name, String desc) {
        Class<?> type = fieldType(owner, desc);
        return cached("set " + owner.getName() + "." + name,
                () -> lookup(owner).findSetter(owner, name, type));
    }

    public static MethodHandle staticFieldGetter(Class<?> owner, String name, String desc) {
        Class<?> type = fieldType(owner, desc);
        return cached("sget " + owner.getName() + "." + name,
                () -> lookup(owner).findStaticGetter(owner, name, type));
    }

    public static MethodHandle staticFieldSetter(Class<?> owner, String name, String desc) {
        Class<?> type = fieldType(owner, desc);
        return cached("sset " + owner.getName() + "." + name,
                () -> lookup(owner).findStaticSetter(owner, name, type));
    }

    public static MethodHandle virtualMethod(Class<?> owner, String name, String desc) {
        return cached("call " + owner.getName() + "." + name + desc,
                () -> lookup(owner).findVirtual(owner, name, methodType(owner, desc)));
    }

    public static MethodHandle staticMethod(Class<?> owner, String name, String desc) {
        return cached("scall " + owner.getName() + "." + name + desc,
                () -> lookup(owner).findStatic(owner, name, methodType(owner, desc)));
    }

    /** Same (Class, name, desc) shape as the other factories; name is always &lt;init&gt;. */
    public static MethodHandle constructor(Class<?> owner, String name, String desc) {
        return cached("new " + owner.getName() + desc,
                () -> lookup(owner).findConstructor(owner, methodType(owner, desc)));
    }

    private static Class<?> fieldType(Class<?> owner, String desc) {
        return MethodType.fromMethodDescriptorString("()" + desc, owner.getClassLoader()).returnType();
    }

    private static MethodType methodType(Class<?> owner, String desc) {
        return MethodType.fromMethodDescriptorString(desc, owner.getClassLoader());
    }

    private static MethodHandles.Lookup lookup(Class<?> owner) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("privateLookupIn " + owner.getName()
                    + " (is its package open to the unnamed module?)", e);
        }
    }

    private static MethodHandle cached(String key, HandleSupplier supplier) {
        MethodHandle existing = CACHE.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            MethodHandle resolved = supplier.get();
            MethodHandle raced = CACHE.putIfAbsent(key, resolved);
            return raced == null ? resolved : raced;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot resolve " + key, e);
        }
    }

    private interface HandleSupplier {
        MethodHandle get() throws ReflectiveOperationException;
    }
}
