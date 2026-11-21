package mixinfixture.mixin.accessor;

import mixinfixture.AccessTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A static {@code @Invoker} interface mixin, shaped like ponder's
 * {@code RenderTypeAccessor}: the stub throws {@code AssertionError} and Mixin
 * is expected to replace its body when the interface class is defined.
 *
 * <p>The harness loads this interface only <em>after</em> the jar was hot-added,
 * which is the order that used to leave the stub in place.
 */
@Mixin(AccessTarget.class)
public interface AccessTargetInvoker {
    @Invoker("hidden")
    static String mixinfixture$hidden() {
        throw new AssertionError("Mixin application failed!");
    }
}