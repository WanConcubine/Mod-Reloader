package mixinfixture;

/**
 * Target of a static {@code @Invoker} accessor mixin.
 *
 * <p>{@code hidden()} is private, so the only way the harness can reach it is the
 * proxy Mixin generates into the invoker interface at that interface's class
 * definition. Without that rewrite the interface keeps its
 * {@code AssertionError("Mixin application failed!")} stub (issue 65).
 */
public final class AccessTarget {
    private AccessTarget() {
    }

    private static String hidden() {
        return "reached-target";
    }
}