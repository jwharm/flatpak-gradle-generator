/* flatpak-gradle-generator - a Gradle plugin to generate a list of dependencies
 * Copyright (C) 2023-2026 Jan-Willem Harmannij
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

package io.github.jwharm.flatpakgradlegenerator;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the plugin with temporary created Gradle builds with varying
 * dependencies. For every build a JSON is generated, and then compared to the
 * expected JSON file.
 */
class TestDependencyOutput {

    @TempDir
    File projectDir;

    /**
     * Test a dependency with transient dependencies.
     */
    @Test
    void testJUnitJupiterDependencies() throws IOException {
        testDependencyOutput("junit-jupiter");
    }

    /**
     * Test a Gradle plugin dependency.
     */
    @Test
    void testGradleLeanDependencies() throws IOException {
        testDependencyOutput("gradle-lean");
    }

    /**
     * Test a dependency with parent POMs.
     */
    @Test
    void testCommonsIODependencies() throws IOException {
        testDependencyOutput("commons-io");
    }

    /**
     * Test "includeConfigurations" option.
     */
    @Test
    void testCommonsIODependenciesWithIncludeOption() throws IOException {
        testDependencyOutput("commons-io-with-include-option");
    }

    /**
     * Test "excludeConfigurations" option.
     */
    @Test
    void testCommonsIODependenciesWithExcludeOption() throws IOException {
        testDependencyOutput("commons-io-with-exclude-option");
    }

    /**
     * Test a build with BOM dependencies.
     */
    @Test
    void testCommonsCodecDependencies() throws IOException {
        testDependencyOutput("commons-codec");
    }

    /**
     * Test a build with a commonly used dependency.
     */
    @Test
    void testDokka() throws IOException {
        testDependencyOutput("dokka");
    }

    /**
     * Test a build with a commonly used dependency.
     */
    @Test
    void testKotlinGradlePluginApi() throws IOException {
        testDependencyOutput("kotlin-gradle-plugin-api");
    }

    /**
     * Test a build with a dependency that is declared multiple times with
     * different classifiers.
     */
    @Test
    void testLeptonica() throws IOException {
        testDependencyOutput("leptonica");
    }

    /**
     * Test a build with a commonly used dependency.
     */
    @Test
    void testLog4j() throws IOException {
        testDependencyOutput("log4j");
    }

    /**
     * Test a build where a module refers to a filename that is not in the
     * local Gradle cache.
     */
    @Test
    void testFilekitCore() throws IOException {
        testDependencyOutput("filekit-core");
    }

    /**
     * Test "onlyArches" option.
     */
    @Test @EnabledOnOs(OS.LINUX)
    void testJavafx() throws IOException {
        testDependencyOutput("javafx");
    }

    /**
     * Test a build with a dependency POM that uses properties that are set in
     * a parent POM.
     */
    @Test
    void testAnthropic() throws IOException {
        testDependencyOutput("anthropic");
    }

    /**
     * Test a build where a plugin depends on a Kotlin library with a version
     * that Gradle overrides with its included version.
     * <p>
     * When this test fails, your Gradle version is probably different from the
     * one that was used to generated the expected JSON output.
     */
    @Test
    void testGradleBuildconfigPlugin() throws IOException {
        testDependencyOutput("gradle-buildconfig-plugin", "settings.gradle.kts", "build.gradle.kts");
    }

    /**
     * Run the flatpakGradleGenerator task on a temporary Gradle build, and
     * compare the results with the expected output. Expects "settings.gradle"
     * and "build.gradle" files.
     *
     * @param variant the resource name
     */
    private void testDependencyOutput(String variant) throws IOException {
        testDependencyOutput(variant, "settings.gradle", "build.gradle");
    }

    /**
     * Run the flatpakGradleGenerator task on a temporary Gradle build, and
     * compare the results with the expected output.
     *
     * @param variant      the resource name
     * @param settingsfile either "settings.gradle" or "settings.gradle.kts"
     * @param buildfile    either "build.gradle" or "build.gradle.kts"
     */
    private void testDependencyOutput(String variant, String settingsfile, String buildfile) throws IOException {
        // Temporary build directory
        File tempDir = createTempDir(variant);

        // Full path to output file
        String outputFile = new File(tempDir, "output.json")
                .getAbsolutePath()
                // Escape Windows-style path separators
                .replace("\\", "\\\\");

        // Write settings.gradle and build.gradle
        writeString(
                new File(tempDir, settingsfile),
                readString(variant, settingsfile)
        );
        writeString(
                new File(tempDir, buildfile),
                readString(variant, buildfile).formatted(outputFile)
        );

        // Log the dependency tree & build environment
        GradleRunner.create()
                .forwardOutput()
                .withPluginClasspath()
                .withArguments("dependencies", "buildEnvironment")
                .withProjectDir(tempDir)
                .build();

        // Run the build
        GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withArguments("flatpakGradleGenerator", "--info")
            .withProjectDir(tempDir)
            .build();

        // Read the expected and actual result
        String expected = readString(variant, "expected-output.json");
        String actual = Files.readString(Path.of(outputFile));

        // Sanitize line breaks
        expected = expected.replace("\r\n", "\n");
        actual = actual.replace("\r\n", "\n");

        assertEquals(expected, actual);
    }

    // Create subdirectory in a JUnit temp dir
    private File createTempDir(String variant) throws IOException {
        File dir = new File(projectDir, variant);
        if (dir.mkdir())
            return dir;
        else
            throw new IOException("Cannot create directory " + dir);
    }

    // Write text to file
    private void writeString(File file, String string) throws IOException {
        try (Writer writer = new FileWriter(file)) {
            writer.write(string);
        }
    }

    // Read text from resource
    private String readString(String variant, String name) throws IOException {
        var resource = this.getClass().getClassLoader().getResource(variant);
        if (resource != null) {
            File f = new File(resource.getFile(), name);
            try (var in = new FileInputStream(f)) {
                return new String(in.readAllBytes());
            }
        }
        throw new FileNotFoundException("Cannot find file " + name);
    }
}
