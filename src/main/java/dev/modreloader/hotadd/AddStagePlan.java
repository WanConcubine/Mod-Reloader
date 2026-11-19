package dev.modreloader.hotadd;

import java.util.List;

/**
 * The stage order of one hot-add, in one place.
 *
 * <p>The order is a contract with FML's startup sequence, not a local
 * convention: mixins and access transformers only apply before construction,
 * registry content only after it, and the client finishes its startup-only
 * events between them. Several fixes (status issues 52/53/54) were about a
 * constraint that until now lived only inside {@link HotAddEngine#add}; this
 * class pins the order so a change is deliberate and testable without the game.
 *
 * <p>Optional stages may be skipped (a plain mod has no COREMOD, a mod without
 * registry content has no REGISTRY). Repeating the current stage is allowed;
 * going back is not.
 *
 * <p>Pure: no game classes; unit-tested in {@code AddStagePlanTest}.
 */
public final class AddStagePlan {
    /** The order one add may pass through; skips are allowed, reversals are not. */
    public static final List<HotAddEngine.Stage> ORDER = List.of(
            HotAddEngine.Stage.VALIDATE,
            HotAddEngine.Stage.MODULE,
            HotAddEngine.Stage.MIXIN,
            HotAddEngine.Stage.COREMOD,
            HotAddEngine.Stage.MODLIST,
            HotAddEngine.Stage.CONSTRUCT,
            HotAddEngine.Stage.REGISTRY,
            HotAddEngine.Stage.EVENT,
            HotAddEngine.Stage.PACK,
            HotAddEngine.Stage.DATAPACK);

    private int index;

    public AddStagePlan() {
        this.index = ORDER.indexOf(HotAddEngine.Stage.VALIDATE);
    }

    /** Records a transition; returns {@code next} so callers keep one variable. */
    public HotAddEngine.Stage enter(HotAddEngine.Stage next) {
        int nextIndex = ORDER.indexOf(next);
        if (nextIndex < 0) {
            throw new IllegalArgumentException("unknown stage: " + next);
        }
        if (nextIndex < index) {
            throw new IllegalStateException(
                    "stage went backwards: " + ORDER.get(index) + " -> " + next);
        }
        index = nextIndex;
        return next;
    }

    public HotAddEngine.Stage current() {
        return ORDER.get(index);
    }

    /** The failure kind a stage reports when its own check fails. */
    public static HotAddEngine.FailureKind defaultFailure(HotAddEngine.Stage stage) {
        return switch (stage) {
            case MIXIN -> HotAddEngine.FailureKind.MIXIN_CONFIG;
            case COREMOD -> HotAddEngine.FailureKind.COREMOD;
            case MODULE -> HotAddEngine.FailureKind.MODULE;
            case MODLIST -> HotAddEngine.FailureKind.MODLIST;
            case CONSTRUCT -> HotAddEngine.FailureKind.CONSTRUCT;
            case REGISTRY -> HotAddEngine.FailureKind.REGISTRY;
            case EVENT -> HotAddEngine.FailureKind.EVENT;
            case PACK -> HotAddEngine.FailureKind.RESOURCE_PACK;
            case DATAPACK -> HotAddEngine.FailureKind.DATAPACK;
            case VALIDATE -> HotAddEngine.FailureKind.INVALID_METADATA;
            case NONE -> HotAddEngine.FailureKind.UNKNOWN;
        };
    }
}