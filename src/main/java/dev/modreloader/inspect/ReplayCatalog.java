package dev.modreloader.inspect;

import java.util.List;
import java.util.Set;

/**
 * The startup-only mod-bus events this runtime replays, in one list.
 *
 * <p>Every one-shot replay posts its event through
 * {@link dev.modreloader.hotadd.OneShotReplay#fire}, which is handed one of these
 * names and checks it against the object actually posted. The registration audit
 * grades a jar against {@link #covered()}, so "what we replay" and "what we claim
 * to replay" are the same list - the layer-definition replay used to be missing
 * here and the audit reported it as a behaviour gap for a day.
 *
 * <p>Plain strings on purpose: the audit runs on both sides and a dedicated
 * server does not have the client event classes. The implementing class is noted
 * next to each group instead.
 */
public final class ReplayCatalog {
    // FML lifecycle, FmlEvents
    public static final String FML_CONSTRUCT = "net.neoforged.fml.event.lifecycle.FMLConstructModEvent";
    public static final String FML_COMMON_SETUP = "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent";
    public static final String FML_CLIENT_SETUP = "net.neoforged.fml.event.lifecycle.FMLClientSetupEvent";
    public static final String FML_LOAD_COMPLETE = "net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent";

    // client/ClientPlatformHooks
    public static final String REGISTER_KEY_MAPPINGS =
            "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent";
    public static final String REGISTER_MENU_SCREENS =
            "net.neoforged.neoforge.client.event.RegisterMenuScreensEvent";

    // client/ClientRegistrationReplay
    public static final String REGISTER_LAYER_DEFINITIONS =
            "net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterLayerDefinitions";
    public static final String REGISTER_RENDERERS =
            "net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterRenderers";
    public static final String REGISTER_COLOR_HANDLERS_BLOCK =
            "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent$Block";
    public static final String REGISTER_COLOR_HANDLERS_ITEM =
            "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent$Item";
    public static final String REGISTER_CLIENT_EXTENSIONS =
            "net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent";
    public static final String REGISTER_CLIENT_RELOAD_LISTENERS =
            "net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent";
    public static final String REGISTER_CLIENT_TOOLTIP_FACTORIES =
            "net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent";
    public static final String REGISTER_PARTICLE_PROVIDERS =
            "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent";

    // client/GuiLayerReplay, ItemDecoratorReplay, RecipeBookCategoryReplay, ShaderReplay
    public static final String REGISTER_GUI_LAYERS =
            "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent";
    public static final String REGISTER_ITEM_DECORATIONS =
            "net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent";
    public static final String REGISTER_RECIPE_BOOK_CATEGORIES =
            "net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent";
    public static final String REGISTER_SHADERS =
            "net.neoforged.neoforge.client.event.RegisterShadersEvent";

    // hotadd/NetworkAdaptation, RegistryStage, CapabilityReplay
    public static final String REGISTER_PAYLOAD_HANDLERS =
            "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent";
    public static final String REGISTER_EVENT = "net.neoforged.neoforge.registries.RegisterEvent";
    public static final String REGISTER_NEW_REGISTRY =
            "net.neoforged.neoforge.registries.NewRegistryEvent";
    public static final String REGISTER_CAPABILITIES =
            "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent";

    private static final List<String> ENTRIES = List.of(
            FML_CONSTRUCT,
            FML_COMMON_SETUP,
            FML_CLIENT_SETUP,
            FML_LOAD_COMPLETE,
            REGISTER_KEY_MAPPINGS,
            REGISTER_MENU_SCREENS,
            REGISTER_LAYER_DEFINITIONS,
            REGISTER_RENDERERS,
            REGISTER_COLOR_HANDLERS_BLOCK,
            REGISTER_COLOR_HANDLERS_ITEM,
            REGISTER_CLIENT_EXTENSIONS,
            REGISTER_CLIENT_RELOAD_LISTENERS,
            REGISTER_CLIENT_TOOLTIP_FACTORIES,
            REGISTER_PARTICLE_PROVIDERS,
            REGISTER_GUI_LAYERS,
            REGISTER_ITEM_DECORATIONS,
            REGISTER_RECIPE_BOOK_CATEGORIES,
            REGISTER_SHADERS,
            REGISTER_PAYLOAD_HANDLERS,
            REGISTER_EVENT,
            REGISTER_NEW_REGISTRY,
            REGISTER_CAPABILITIES);

    private static final Set<String> COVERED = Set.copyOf(ENTRIES);

    private ReplayCatalog() {
    }

    /** Events the audit should grade as replayed by this runtime. */
    public static Set<String> covered() {
        return COVERED;
    }

    static List<String> entries() {
        return ENTRIES;
    }
}