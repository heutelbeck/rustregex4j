package com.heutelbeck.rustregex4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

@DisplayName("Loading the engine library")
class EngineLoaderTests {

    static Stream<Arguments> platforms() {
        return Stream.of(arguments("Linux", "amd64", "linux-x86_64"), arguments("Linux", "aarch64", "linux-aarch_64"),
                arguments("Mac OS X", "aarch64", "osx-aarch_64"), arguments("Mac OS X", "x86_64", "osx-x86_64"),
                arguments("Windows 11", "amd64", "windows-x86_64"), arguments("Windows Server 2025", "aarch64", "windows-aarch_64"));
    }

    @MethodSource("platforms")
    @ParameterizedTest(name = "{0} on {1}")
    @DisplayName("names platforms as the build names the bundled libraries")
    void whenJvmReportsPlatformThenNameMatchesBuildClassifier(String operatingSystem, String architecture, String expected) {
        assertThat(EngineLoader.platform(operatingSystem, architecture)).isEqualTo(expected);
    }

    static Stream<Arguments> processMappings() {
        return Stream.of(arguments("Alpine x86_64", true, """
                55d0c4a3e000-55d0c4a3f000 r--p 00000000 00:3f 1312 /usr/lib/jvm/java-21-openjdk/bin/java
                7f8f2c000000-7f8f2c014000 r--p 00000000 00:3f 41 /lib/ld-musl-x86_64.so.1
                """), arguments("Alpine aarch64", true, """
                ffff9a2b0000-ffff9a355000 r-xp 00000000 00:3f 41 /lib/ld-musl-aarch64.so.1
                """), arguments("glibc with musl installed", false, """
                7f3a1c000000-7f3a1c028000 r--p 00000000 08:01 2612 /usr/lib/x86_64-linux-gnu/libc.so.6
                7f3a1c400000-7f3a1c42a000 r--p 00000000 08:01 2598 /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
                """), arguments("no mappings", false, ""));
    }

    @MethodSource("processMappings")
    @ParameterizedTest(name = "{0}")
    @DisplayName("recognises musl by its dynamic loader in the process, not by installed files")
    void whenProcessMappingsAreReadThenMuslIsRecognisedByItsLoader(String system, boolean expected, String mappings) {
        assertThat(EngineLoader.mapsMuslLoader(mappings.lines())).isEqualTo(expected);
    }

    @Test
    @DisplayName("finds a bundled library for the platform the tests run on")
    void whenRunningOnBuildPlatformThenBundledLibraryExists() {
        assertThat(EngineLoader.class.getResource("/com/heutelbeck/rustregex4j/engine/" + EngineLoader.platform() + '/'
                + System.mapLibraryName("rustregex4j"))).isNotNull();
    }

    @Test
    @DisplayName("loads the library the system property names, and fails if it does not exist")
    void whenConfiguredLibraryDoesNotExistThenLoadingFails() {
        val previous = System.getProperty(EngineLoader.LIBRARY_PROPERTY);
        System.setProperty(EngineLoader.LIBRARY_PROPERTY, "does-not-exist/" + System.mapLibraryName("rustregex4j"));
        try {
            assertThatExceptionOfType(UnsatisfiedLinkError.class).isThrownBy(EngineLoader::load)
                    .withMessageContaining("does-not-exist");
        } finally {
            if (previous == null) {
                System.clearProperty(EngineLoader.LIBRARY_PROPERTY);
            } else {
                System.setProperty(EngineLoader.LIBRARY_PROPERTY, previous);
            }
        }
    }
}
