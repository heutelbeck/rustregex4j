package com.heutelbeck.rustregex4j;

import static java.util.Arrays.stream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeSystemProperties;

import lombok.val;

/**
 * Links the regex engine statically into a GraalVM native image.
 * <p>
 * A fully static native image ({@code --static --libc=musl}) cannot load shared libraries at
 * run time. With this feature the engine becomes part of the executable instead: the build
 * links the static engine library bundled in the jar, and the image needs no library file and
 * extracts nothing at run time. Enable it in the build of a static image:
 * <pre>
 * --features=com.heutelbeck.rustregex4j.StaticEngineFeature
 * </pre>
 * The jar carries a static engine library for {@code linux-x86_64} built against musl, the
 * platform on which GraalVM builds fully static images. The feature uses internal API of the
 * GraalVM image builder; the required {@code --add-exports} options are set by the jar. It
 * supports GraalVM for JDK 25.
 */
public final class StaticEngineFeature implements Feature {

    private static final String ERROR_EXTRACTION_FAILED = "Extracting the static engine library %s failed.";
    private static final String ERROR_INTERNAL_API      = "The GraalVM image builder does not offer the internal API %s that linking the engine statically needs.";
    private static final String ERROR_NO_STATIC_LIBRARY = "No static engine library of rustregex4j exists for the platform %s.";

    private static final String NATIVE_PREFIX       = "com_heutelbeck_rustregex4j";
    private static final String STATIC_LIBRARY_NAME = "librustregex4j.a";
    private static final String LIBRARY_SUPPORT     = "com.oracle.svm.core.jdk.PlatformNativeLibrarySupport";
    private static final String NATIVE_LIBRARIES    = "com.oracle.svm.hosted.c.NativeLibraries";

    /** The method that registers a built-in native prefix; its name changed between GraalVM releases. */
    private static final String[] ADD_PREFIX_METHODS = { "addBuiltinNativePrefix", "addBuiltinPkgNativePrefix" };

    /** Creates the feature; the image builder instantiates it when it is enabled. */
    public StaticEngineFeature() {
        // The feature has no state; the builder calls the hooks below.
    }

    /**
     * Describes the feature in the output of the image builder.
     *
     * @return the description
     */
    @Override
    public String getDescription() {
        return "Links the rustregex4j regex engine statically into the image.";
    }

    /**
     * Declares the engine functions as built into the image and tells the engine loader at
     * run time that there is nothing to load.
     *
     * @param access the access to the image build
     */
    @Override
    public void duringSetup(DuringSetupAccess access) {
        val librarySupport = invoke(LIBRARY_SUPPORT, null, "singleton");
        invoke(LIBRARY_SUPPORT, librarySupport, firstPresent(LIBRARY_SUPPORT, ADD_PREFIX_METHODS), NATIVE_PREFIX);
        RuntimeSystemProperties.register(EngineLoader.BUILT_IN_PROPERTY, Boolean.TRUE.toString());
    }

    /**
     * Extracts the static engine library of the target platform and adds it to the libraries
     * the image is linked with.
     *
     * @param access the access to the image build
     */
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        val nativeLibraries = invoke(NATIVE_LIBRARIES, null, "singleton");
        @SuppressWarnings("unchecked")
        val libraryPaths = (Collection<String>) invoke(NATIVE_LIBRARIES, nativeLibraries, "getLibraryPaths");
        libraryPaths.add(extractStaticLibrary());
        invoke(NATIVE_LIBRARIES, nativeLibraries, "addStaticJniLibrary", EngineLoader.LIBRARY_NAME, new String[0]);
    }

    /**
     * Copies the static library of the platform the builder runs on, which is the platform of
     * the image, into a temporary directory.
     */
    private static String extractStaticLibrary() {
        val platform = EngineLoader.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""))
                + EngineLoader.MUSL_SUFFIX;
        val resource = EngineLoader.RESOURCE_DIRECTORY + platform + '/' + STATIC_LIBRARY_NAME;
        try (val library = StaticEngineFeature.class.getResourceAsStream(resource)) {
            if (library == null) {
                throw new IllegalStateException(ERROR_NO_STATIC_LIBRARY.formatted(platform));
            }
            val directory = Files.createTempDirectory(EngineLoader.LIBRARY_NAME + "-static-");
            directory.toFile().deleteOnExit();
            val target = directory.resolve(STATIC_LIBRARY_NAME);
            Files.copy(library, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return directory.toString();
        } catch (IOException error) {
            throw new UncheckedIOException(ERROR_EXTRACTION_FAILED.formatted(resource), error);
        }
    }

    /** Returns the first of alternative method names that the class declares. */
    private static String firstPresent(String className, String... methodNames) {
        val type = classNamed(className);
        for (val methodName : methodNames) {
            if (stream(type.getMethods()).anyMatch(method -> method.getName().equals(methodName))) {
                return methodName;
            }
        }
        throw new IllegalStateException(ERROR_INTERNAL_API.formatted(className + '.' + String.join(" or ", methodNames)));
    }

    /**
     * Calls a public method of an internal class of the image builder, found by name and
     * argument count.
     */
    private static Object invoke(String className, Object target, String methodName, Object... arguments) {
        val method = stream(classNamed(className).getMethods())
                .filter(candidate -> candidate.getName().equals(methodName) && candidate.getParameterCount() == arguments.length)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(ERROR_INTERNAL_API.formatted(className + '.' + methodName)));
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException(ERROR_INTERNAL_API.formatted(className + '.' + methodName), error);
        }
    }

    private static Class<?> classNamed(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(ERROR_INTERNAL_API.formatted(className), missing);
        }
    }
}
