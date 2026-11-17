package mixinfixture.mixin;

import mixinfixture.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A body-only mixin: injects at HEAD and immediately returns 2.
 *
 * <p>The handler method adds no fields and no interfaces to {@link Probe}, but it
 * does add a method, which is why this only works while {@code Probe} is being
 * defined - a loaded target would need JBR's enhanced redefinition.
 */
@Mixin(Probe.class)
public abstract class ProbeMixin {
    @Inject(method = "value", at = @At("HEAD"), cancellable = true)
    private static void mixinfixture$value(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(2);
    }
}
