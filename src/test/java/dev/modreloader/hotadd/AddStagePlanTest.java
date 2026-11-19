package dev.modreloader.hotadd;

import dev.modreloader.hotadd.HotAddEngine.FailureKind;
import dev.modreloader.hotadd.HotAddEngine.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the hot-add stage order. The constraints themselves come from FML's
 * startup sequence (mixins/AT before construct, registry content after it,
 * client events in between); this test is the place where a reordering is
 * deliberate instead of an accident.
 */
class AddStagePlanTest {
    @Test
    void canonicalOrderIsPinned() {
        assertEquals(List.of(
                Stage.VALIDATE, Stage.MODULE, Stage.MIXIN, Stage.COREMOD, Stage.MODLIST,
                Stage.CONSTRUCT, Stage.REGISTRY, Stage.EVENT, Stage.PACK, Stage.CLIENT, Stage.DATAPACK),
                AddStagePlan.ORDER);
    }

    @Test
    void optionalStagesMayBeSkipped() {
        AddStagePlan plan = new AddStagePlan();
        plan.enter(Stage.MODULE);
        plan.enter(Stage.MIXIN);
        // a plain mod has no coremod, registry content or datapack
        plan.enter(Stage.CONSTRUCT);
        plan.enter(Stage.EVENT);
        plan.enter(Stage.PACK);
        assertEquals(Stage.PACK, plan.current());
    }

    @Test
    void repeatingTheCurrentStageIsAllowed() {
        AddStagePlan plan = new AddStagePlan();
        plan.enter(Stage.CONSTRUCT);
        plan.enter(Stage.CONSTRUCT);
        assertEquals(Stage.CONSTRUCT, plan.current());
    }

    @Test
    void goingBackwardsIsRefused() {
        AddStagePlan plan = new AddStagePlan();
        plan.enter(Stage.CONSTRUCT);
        assertThrows(IllegalStateException.class, () -> plan.enter(Stage.MIXIN));
    }

    @Test
    void stageFailureKindsAreStable() {
        assertEquals(FailureKind.MIXIN_CONFIG, AddStagePlan.defaultFailure(Stage.MIXIN));
        assertEquals(FailureKind.RESOURCE_PACK, AddStagePlan.defaultFailure(Stage.PACK));
        assertEquals(FailureKind.DATAPACK, AddStagePlan.defaultFailure(Stage.DATAPACK));
        assertEquals(FailureKind.UNKNOWN, AddStagePlan.defaultFailure(Stage.NONE));
    }
}