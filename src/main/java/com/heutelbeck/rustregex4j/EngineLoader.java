package com.heutelbeck.rustregex4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Loads the regex engine library of the current platform.
 * <p>
 * The jar carries one engine library per supported platform as a resource under
 * {@code com/heutelbeck/rustregex4j/engine/<os>-<arch>/}, on Linux with musl as C library under
 * {@code com/heutelbeck/rustregex4j/engine/linux-<arch>-musl/}. The library of the current platform
 * is copied to a temporary file and loaded from there. The system property
 * {@value #LIBRARY_PROPERTY} names a library file to load instead, for platforms without a
 * bundled library or for environments that forbid executing files from the temporary
 * directory. In a native image whose build linked the engine statically (see
 * {@link StaticEngineFeature}), the built-in engine is used and nothing is extracted.
 */
@UtilityClass
class EngineLoader {

    /** System property naming an engine library file to load instead of the bundled one. */
    static final String LIBRARY_PROPERTY = "rustregex4j.library";

    /**
     * System property that a native image build sets when it links the engine statically;
     * the engine functions are then part of the executable and nothing is loaded.
     */
    static final String BUILT_IN_PROPERTY = "rustregex4j.engine.builtin";

    static final String LIBRARY_NAME       = "rustregex4j";
    static final String MUSL_SUFFIX        = "-musl";
    static final String RESOURCE_DIRECTORY = "/com/heutelbeck/rustregex4j/engine/";

    private static final String ERROR_COPY_FAILED = "Copying the engine library %s to a temporary file failed.";
    private static final String ERROR_NO_LIBRARY  = "No engine library of rustregex4j exists for the platform %s. Set the system property %s to the path of a library built for this platform.";

    private static final String LINUX            = "linux";
    private static final String MUSL_LOADER      = "/ld-musl-";
    private static final Path   PROCESS_MAPPINGS = Path.of("/proc/self/maps");

    /**
     * Loads the library. Called once by the static initializer of {@link RegexEngine}.
     *
     * @throws UnsatisfiedLinkError if there is no library for this platform or it cannot be loaded
     */
    static void load() {
        val configured = System.getProperty(LIBRARY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            System.load(Path.of(configured).toAbsolutePath().toString());
            return;
        }
        if (Boolean.getBoolean(BUILT_IN_PROPERTY)) {
            return;
        }
        val platform = platform();
        val fileName = System.mapLibraryName(LIBRARY_NAME);
        val resource = RESOURCE_DIRECTORY + platform + '/' + fileName;
        try (val library = EngineLoader.class.getResourceAsStream(resource)) {
            if (library == null) {
                throw new UnsatisfiedLinkError(ERROR_NO_LIBRARY.formatted(platform, LIBRARY_PROPERTY));
            }
            val suffix    = fileName.substring(fileName.lastIndexOf('.'));
            val extracted = Files.createTempFile(LIBRARY_NAME + '-', suffix);
            extracted.toFile().deleteOnExit();
            Files.copy(library, extracted, StandardCopyOption.REPLACE_EXISTING);
            System.load(extracted.toAbsolutePath().toString());
        } catch (IOException error) {
            throw new UncheckedIOException(ERROR_COPY_FAILED.formatted(resource), error);
        }
    }

    /**
     * Returns the platform name used for the resource directory, in the naming of the
     * os-maven-plugin that names it at build time.
     *
     * @return {@code <os>-<arch>}, for example {@code linux-x86_64} or {@code osx-aarch_64}, with
     * the suffix {@code -musl} on Linux with musl as C library
     */
    static String platform() {
        val platform = platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        if (platform.startsWith(LINUX + '-') && runsOnMusl()) {
            return platform + MUSL_SUFFIX;
        }
        return platform;
    }

    /**
     * Reports whether this process runs with musl as C library. The JVM of a musl system is
     * started by the dynamic loader of musl, which is mapped into the process. Installed musl
     * files alone do not count, as glibc systems may carry them too.
     *
     * @return {@code true} if the dynamic loader of musl is mapped into this process; {@code
     * false} if not, or if the mappings cannot be read
     */
    private static boolean runsOnMusl() {
        try (val mappings = Files.lines(PROCESS_MAPPINGS)) {
            return mapsMuslLoader(mappings);
        } catch (IOException | UncheckedIOException | SecurityException unreadable) {
            return false;
        }
    }

    /**
     * Reports whether the memory mappings of a process, in the format of
     * {@code /proc/<pid>/maps}, contain the dynamic loader of musl.
     *
     * @param mappings the lines of the mappings
     * @return {@code true} if a mapped file is the dynamic loader of musl
     */
    static boolean mapsMuslLoader(Stream<String> mappings) {
        return mappings.anyMatch(mapping -> mapping.contains(MUSL_LOADER));
    }

    /**
     * Returns the platform name for an operating system and an architecture as the JVM
     * reports them in {@code os.name} and {@code os.arch}.
     *
     * @param operatingSystem the value of {@code os.name}
     * @param architecture the value of {@code os.arch}
     * @return {@code <os>-<arch>}
     */
    static String platform(String operatingSystem, String architecture) {
        return operatingSystem(operatingSystem) + '-' + architecture(architecture);
    }

    private static String operatingSystem(String name) {
        val normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(LINUX)) {
            return LINUX;
        }
        if (normalized.startsWith("mac") || normalized.startsWith("darwin")) {
            return "osx";
        }
        if (normalized.startsWith("windows")) {
            return "windows";
        }
        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private static String architecture(String name) {
        val normalized = name.toLowerCase(Locale.ROOT);
        return switch (normalized) {
        case "amd64", "x86_64", "x64" -> "x86_64";
        case "aarch64", "arm64"       -> "aarch_64";
        default                       -> normalized.replaceAll("[^a-z0-9_]", "");
        };
    }
}
