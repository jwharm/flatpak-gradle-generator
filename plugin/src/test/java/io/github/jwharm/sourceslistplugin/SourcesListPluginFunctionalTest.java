/* SourcesListPlugin - a Gradle plugin to generate a list of dependencies
 * Copyright (C) 2023 Jan-Willem Harmannij
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

package io.github.jwharm.sourceslistplugin;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.io.FileWriter;
import java.nio.file.Files;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the plugin with a temporary created Gradle build that
 * depends on Jetbrains Annotations. It should generate a
 * sources list that contains the annotations-24.0.1.jar file.
 */
class SourcesListPluginFunctionalTest {
    @TempDir
    File projectDir;

    private File getBuildFile() {
        return new File(projectDir, "build.gradle");
    }

    private File getSettingsFile() {
        return new File(projectDir, "settings.gradle");
    }

    private File getOutputFile() {
        return new File(projectDir, "output.txt");
    }

    @Test void canRunTask() throws IOException {
        writeString(getSettingsFile(), "");
        writeString(getBuildFile(),
        """
                plugins {
                  id 'io.github.jwharm.sourceslistplugin'
                  id 'application'
                }
                
                repositories {
                  mavenCentral()
                  maven { url 'https://jitpack.io' }
                }
                
                dependencies {
                  compileOnly 'org.jetbrains:annotations:24.0.1'
                }
                
                tasks.sourcesList {
                  outputFile = file('%s')
                  downloadDirectory = 'localRepository'
                }
                """.formatted(getOutputFile().getAbsolutePath()));

        // Run the build
        GradleRunner runner = GradleRunner.create();
        runner.forwardOutput();
        runner.withPluginClasspath();
        runner.withArguments("sourcesList");
        runner.withProjectDir(projectDir);
        BuildResult result = runner.build();

        // Verify the result
        String sourcesList = Files.readString(getOutputFile().toPath());

        String expected = """
            [
              {
                "type": "file",
                "url": "https://repo.maven.apache.org/maven2/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar"
                "sha512": "ac5879c0170b80106962881ed2d9a3d5f4b4ef0f6908806ab19c8418fab3b59e2dd7b5f03eb434544119c92c559bcab52a50dcac036b01ff4436c34411f80682"
                "dest": "localRepository"
                "dest-filename": "annotations-24.0.1.jar"
              }
            ]
            """;
        assertEquals(expected, sourcesList);
    }

    private void writeString(File file, String string) throws IOException {
        try (Writer writer = new FileWriter(file)) {
            writer.write(string);
        }
    }
}
