package dev.modreloader.inspect;

/**
 * What counts as "this jar registers registry content".
 *
 * <p>The tier model calls a jar <em>guifei</em> when it adds entries to a game
 * registry, because that is the change a hot-add cannot roll back. The signal has
 * to distinguish <b>registering</b> from <b>reading</b>: a tooltip mod that looks
 * up {@code BuiltInRegistries.ITEM} is not content, and treating it as content made
 * the runtime open a registry window and show the experimental add-only warning for
 * mods that never register anything (measured: Jade 15.10.6 references the registry
 * types from 12 classes and contains zero registration calls, yet the runtime
 * inspector called it guifei while the analyzer called it fei).
 *
 * <p>This predicate mirrors {@code tools/analyzer/modreloader/analyzer.py}
 * ({@code registration_refs} + the {@code DeferredRegister} marker) so the runtime
 * and the analyzer agree on the registry axis. It imports no game class, so it is
 * unit-testable without a game.
 */
public final class RegistrySignal {
    private RegistrySignal() {
    }

    /**
     * A call that adds entries: {@code Registry.register(...)} (vanilla style),
     * {@code DeferredRegister#register*} (NeoForge style, including the
     * {@code $Blocks}/{@code $Items} subclasses), {@code BuiltInRegistries#register*}
     * and {@code RegisterEvent#register*}.
     */
    public static boolean isRegistrationCall(String owner, String methodName) {
        if (owner == null || methodName == null || !methodName.startsWith("register")) {
            return false;
        }
        return owner.equals("net/minecraft/core/Registry")
                || owner.endsWith("/BuiltInRegistries")
                || owner.endsWith("/RegisterEvent")
                || owner.contains("DeferredRegister");
    }

    /**
     * Any reference to {@code DeferredRegister} (or its nested {@code $Blocks},
     * {@code $Items}, … subclasses) means the mod builds registration calls, even
     * when the call itself lives in another class.
     */
    public static boolean referencesDeferredRegister(String internalNameOrDescriptor) {
        return internalNameOrDescriptor != null && internalNameOrDescriptor.contains("DeferredRegister");
    }

    /** @return true when the jar's scan saw any registration, computed by the caller */
    public static boolean anyRegistration(boolean calls, boolean deferredRegisterRefs) {
        return calls || deferredRegisterRefs;
    }
}
