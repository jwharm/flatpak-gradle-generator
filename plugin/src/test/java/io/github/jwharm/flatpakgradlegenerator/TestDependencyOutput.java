/* flatpak-gradle-generator - a Gradle plugin to generate a list of dependencies
 * Copyright (C) 2023-2024 Jan-Willem Harmannij
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
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the plugin with a temporary created Gradle build that
 * depends on junit-jupiter. It should generate a sources list
 * that contains all dependencies.
 */
class TestDependencyOutput {

    @TempDir
    File projectDir;

    /**
     * Test a dependency with transient dependencies
     */
    @Test
    void testJUnitJupiterDependencies() throws IOException {
        testDependencyOutput("junit-jupiter");
    }

    /**
     * Test a Gradle plugin dependency
     */
    @Test
    void testGradleLeanDependencies() throws IOException {
        testDependencyOutput("gradle-lean");
    }

    /**
     * Test a dependency with parent POMs
     */
    @Test
    void testCommonsIODependencies() throws IOException {
        testDependencyOutput("commons-io");
    }

    @Test
    void testCommonsCodecDependencies() throws IOException {
        testDependencyOutput("commons-codec");
    }

    @Test
    void testDokka() throws IOException {
        testDependencyOutput("dokka");
    }

    @Test
    void testKotlinGradlePluginApi() throws IOException {
        testDependencyOutput("kotlin-gradle-plugin-api");
    }

    @Test
    void testLeptonica() throws IOException {
        testDependencyOutput("leptonica");
    }

    @Test
    void testLog4j() throws IOException {
        testDependencyOutput("log4j");
    }

    @Test
    void testFilekitCore() throws IOException {
        testDependencyOutput("filekit-core");
    }

    /**
     * Run the flatpakGradleGenerator task on a temporary Gradle build, and
     * compare the results with the expected output.
     *
     * @param variant the resource name
     */
    private void testDependencyOutput(String variant) throws IOException {
        // Temporary build directory
        File tempDir = createTempDir(variant);

        // Full path to output file
        String outputFile = new File(tempDir, "output.json")
                .getAbsolutePath()
                // Escape Windows-style path separators
                .replace("\\", "\\\\");

        // Write settings.gradle and build.gradle
        writeString(
                new File(tempDir, "settings.gradle"),
                readString(variant, "settings.gradle")
        );
        writeString(
                new File(tempDir, "build.gradle"),
                readString(variant, "build.gradle").formatted(outputFile)
        );

        // Run the build
        GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withArguments("flatpakGradleGenerator")
            .withProjectDir(tempDir)
            .build();

        // Verify the result
        String expected = readString(variant, "expected-output.json");
        String actual = Files.readString(Path.of(outputFile));
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
