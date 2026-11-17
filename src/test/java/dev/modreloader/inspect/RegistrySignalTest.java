package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the "registering vs reading" line that separates guifei from fei. The
 * negative cases are the reason this class exists: a tooltip/GUI mod that only
 * reads registries must not be treated as content.
 */
class RegistrySignalTest {
    @Test
    void vanillaRegisterIsRegistration() {
        assertTrue(RegistrySignal.isRegistrationCall("net/minecraft/core/Registry", "register"));
    }

    @Test
    void deferredRegisterCallsAreRegistration() {
        assertTrue(RegistrySignal.isRegistrationCall(
                "net/neoforged/neoforge/registries/DeferredRegister", "register"));
        assertTrue(RegistrySignal.isRegistrationCall(
                "net/neoforged/neoforge/registries/DeferredRegister$Blocks", "register"));
        assertTrue(RegistrySignal.isRegistrationCall(
                "mekanism/common/registration/MekanismDeferredRegister", "register"));
    }

    @Test
    void registerEventIsRegistration() {
        assertTrue(RegistrySignal.isRegistrationCall(
                "net/neoforged/neoforge/event/RegisterEvent", "register"));
    }

    @Test
    void lookupsAreNotRegistration() {
        // These are the shapes that used to mark Jade/guideme-style tool mods as content.
        assertFalse(RegistrySignal.isRegistrationCall(
                "net/minecraft/core/registries/BuiltInRegistries", "get"));
        assertFalse(RegistrySignal.isRegistrationCall("net/minecraft/core/Registries", "get"));
        assertFalse(RegistrySignal.isRegistrationCall(
                "net/minecraft/client/gui/screens/Screen", "register"));
        assertFalse(RegistrySignal.isRegistrationCall(
                "net/neoforged/neoforge/registries/DeferredRegister", "create"));
    }

    @Test
    void deferredRegisterTypeReferenceCounts() {
        assertTrue(RegistrySignal.referencesDeferredRegister(
                "net/neoforged/neoforge/registries/DeferredRegister"));
        assertTrue(RegistrySignal.referencesDeferredRegister(
                "Lnet/neoforged/neoforge/registries/DeferredRegister$Items;"));
        assertFalse(RegistrySignal.referencesDeferredRegister("net/minecraft/core/Registry"));
        assertFalse(RegistrySignal.referencesDeferredRegister("net/minecraft/core/registries/BuiltInRegistries"));
    }
}
