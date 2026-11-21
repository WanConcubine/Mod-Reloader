package dev.modreloader.hotadd;

import dev.modreloader.inspect.WrapperJars;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Resolves a distribution wrapper to the mod jar it carries.
 *
 * <p>The inspection half (is this a wrapper, which modIds does it bundle) lives
 * in {@link WrapperJars} so the watcher and this package can share it without
 * inverting the package layering. Extraction reuses {@link JarInJar}, which is
 * content-addressed and idempotent.
 */
public final class WrapperJar {
    private WrapperJar() {
    }

    /** The mod jar a wrapper carries. */
    public record Unwrapped(Path effective, String nestedPath) {
    }

    /**
     * @return {@code null} when {@code jar} is not a wrapper
     */
    public static Unwrapped resolve(Path jar) throws IOException {
        WrapperJars.Nested wrapper = WrapperJars.nestedMod(jar);
        if (wrapper == null) {
            return null;
        }
        for (JarInJar.Nested nested : JarInJar.extract(jar).nested()) {
            if (nested.declaresMod()) {
                return new Unwrapped(nested.file(), nested.declaredPath());
            }
        }
        return null;
    }
}