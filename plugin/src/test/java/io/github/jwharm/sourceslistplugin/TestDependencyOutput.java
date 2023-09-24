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

    private File getBuildFile() {
        return new File(projectDir, "build.gradle");
    }

    private File getSettingsFile() {
        return new File(projectDir, "settings.gradle");
    }

    private String getOutputFile() {
        return new File(projectDir, "output.json")
                .getAbsolutePath()
                .replace("\\", "\\\\");
    }

    @Test void testDependencyOutput() throws IOException {
        // Write temporary settings.gradle
        writeString(getSettingsFile(), "");

        // Write temporary build.gradle
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
                  implementation 'org.junit.jupiter:junit-jupiter:5.9.2'
                }
                
                tasks.sourcesList {
                  outputFile = file('%s')
                  downloadDirectory = 'maven-local'
                }
                """.formatted(getOutputFile()));

        // Run the build
        GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withArguments("sourcesList")
            .withProjectDir(projectDir)
            .build();

        // Verify the result
        String sourcesList = Files.readString(Path.of(getOutputFile()));

        String expected = """
                [
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.9.2/junit-jupiter-5.9.2.jar",
                    "sha512": "518967645266167d50416f234eaf324bbf6d701c19a96fe5699824b9078d765146335b1a57a5fdfce7a50e8f489c8a6edd4068cb9acf4acee130d6e7cfa3fb9d",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter/5.9.2",
                    "dest-filename": "junit-jupiter-5.9.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.9.2/junit-jupiter-5.9.2.pom",
                    "sha512": "93f1271f7d388c709bb024816de4a748d8a48daef95fc7646f20f16262703e1bd0d1243216314a6664c1b3f14782aa1cbe6fcb1277438af482a444685b91f5a0",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter/5.9.2",
                    "dest-filename": "junit-jupiter-5.9.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.9.2/junit-jupiter-5.9.2.module",
                    "sha512": "bde3475b10061efc50139c82e180d43da289f2ddf7041158cb21ed0aefef446251c0bd9baea70f1b062ca03eecc54524cd24dce32ffe150dfbd4318e8e24390d",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter/5.9.2",
                    "dest-filename": "junit-jupiter-5.9.2.module"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-api/5.9.2/junit-jupiter-api-5.9.2.jar",
                    "sha512": "36efb8800c40b359133cfe823723c3d6f34b0d39df91187fb8f7f90339a7d9984a34b4d091c945475afc862f3e5ad5412516c1577656b1aee963fe0f6da0d59e",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-api/5.9.2",
                    "dest-filename": "junit-jupiter-api-5.9.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-api/5.9.2/junit-jupiter-api-5.9.2.pom",
                    "sha512": "b072a1864d0a605ab1b48700c231125e5dff61534bf437a4d88adf570338718f3eca554d436faf68a32a92449a712ce2256dac5b16d1d0e026f7e2ca336f8de6",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-api/5.9.2",
                    "dest-filename": "junit-jupiter-api-5.9.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-api/5.9.2/junit-jupiter-api-5.9.2.module",
                    "sha512": "92818cf2c805b10aabdefb1b27eb7711485449daa1b3ce017ed72b5275dc421357c3eab5e455e3814d030c03dfc96e301db4944f2596928d565ca997e096ff42",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-api/5.9.2",
                    "dest-filename": "junit-jupiter-api-5.9.2.module"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/opentest4j/opentest4j/1.2.0/opentest4j-1.2.0.jar",
                    "sha512": "17f77797a260eb2bd1666a90e25efc79a5413afa9df1c1cb6c4cd1949d61c38b241e3bb20956396b5f54d144720303d72a2ac00bc5bf245a260a3c3099e01c74",
                    "dest": "maven-local/org/opentest4j/opentest4j/1.2.0",
                    "dest-filename": "opentest4j-1.2.0.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/opentest4j/opentest4j/1.2.0/opentest4j-1.2.0.pom",
                    "sha512": "28ce0d0b5cceeac5adfcdd16ee6f5fbefd43eef8057b924993fb7a9f4b7a7085b980f3e808db5fb7750a53d0072b7fe7b118f5c0011e3ef0efe6c9a90a87b868",
                    "dest": "maven-local/org/opentest4j/opentest4j/1.2.0",
                    "dest-filename": "opentest4j-1.2.0.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-commons/1.9.2/junit-platform-commons-1.9.2.jar",
                    "sha512": "dd259a9e2f37588552322c9b4dd37aad4daa2e2ae0c10b79e7e3e128698020b028020d7c7dfa058944b9fafa493f1cf8aaf6d32911292a7d4f01910106bb552b",
                    "dest": "maven-local/org/junit/platform/junit-platform-commons/1.9.2",
                    "dest-filename": "junit-platform-commons-1.9.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-commons/1.9.2/junit-platform-commons-1.9.2.pom",
                    "sha512": "9af10898f4698381afb928c26e97944ffb5fb6eebbd3981ae62f4cd370199da60aae6ac45d112d1b91a76b0b6288b0b27d070b834a6fb6019fa365066fb4455a",
                    "dest": "maven-local/org/junit/platform/junit-platform-commons/1.9.2",
                    "dest-filename": "junit-platform-commons-1.9.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-commons/1.9.2/junit-platform-commons-1.9.2.module",
                    "sha512": "fa1e7ef9ccb17374fb7fd6a0a6bbd9174c5733015fe07a73b3131cbe7193c7dd8d0f4e35c4f593d2f9ffc1e5e4bb3b143241591a074025656c28eec9ef8baa02",
                    "dest": "maven-local/org/junit/platform/junit-platform-commons/1.9.2",
                    "dest-filename": "junit-platform-commons-1.9.2.module"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar",
                    "sha512": "d7ccd0e7019f1a997de39d66dc0ad4efe150428fdd7f4c743c93884f1602a3e90135ad34baea96d5b6d925ad6c0c8487c8e78304f0a089a12383d4a62e2c9a61",
                    "dest": "maven-local/org/apiguardian/apiguardian-api/1.1.2",
                    "dest-filename": "apiguardian-api-1.1.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.pom",
                    "sha512": "bb604d1103315784a2f56a43244434dbbd7edc97d0450dada21313463d230e00f2f23f6e7c2ca45be94e81304057e70c79c9f0fb84247f7244d0277110b5747e",
                    "dest": "maven-local/org/apiguardian/apiguardian-api/1.1.2",
                    "dest-filename": "apiguardian-api-1.1.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.module",
                    "sha512": "b8f1e5623d4d146caf73a869e8ee719746831d67e0259ecff8dec276c0563b92c65ee6bf2dd40b6e80b309e5da805730a39869f8942321626f793f69b57f11ff",
                    "dest": "maven-local/org/apiguardian/apiguardian-api/1.1.2",
                    "dest-filename": "apiguardian-api-1.1.2.module"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-params/5.9.2/junit-jupiter-params-5.9.2.jar",
                    "sha512": "6fd6fb739f9ab7d7d188a96e56c26979ab720f0dd7d9f12bf732bb3b1689ba4f5c327e86b4cfae5e468027ca11019dfcbfff60c0ec924c8bc69389bff03de98c",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-params/5.9.2",
                    "dest-filename": "junit-jupiter-params-5.9.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-params/5.9.2/junit-jupiter-params-5.9.2.pom",
                    "sha512": "c4bcbd185f742393df906d5930908f0864cdcacfe570804e72685506de0cff5bea670c95919201ec292e0da18d7bc5e7054197b6a880b0e093862ed3f7aa60d6",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-params/5.9.2",
                    "dest-filename": "junit-jupiter-params-5.9.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-params/5.9.2/junit-jupiter-params-5.9.2.module",
                    "sha512": "c8f00a7c07272bd9d41a4ccebc53a9858db5c2270cf48f3ad224e5e93b7a7d2d0f75feab12b1ab2aeb2111bc843a697978827060b8a24f72d7565f20b625f02f",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-params/5.9.2",
                    "dest-filename": "junit-jupiter-params-5.9.2.module"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-engine/5.9.2/junit-jupiter-engine-5.9.2.jar",
                    "sha512": "901f2910ed4b05984cfbd900e9a92122d363fd4556418b58cd0807af7e91a89c2653c73ee777e97d4d23e9fd364549b1d0430f5eb70fc259b522df5e0ed81578",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-engine/5.9.2",
                    "dest-filename": "junit-jupiter-engine-5.9.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-engine/5.9.2/junit-jupiter-engine-5.9.2.pom",
                    "sha512": "a00822fec0783153c769caad8b3847c852d9791d8f4e406df25017e1468f95555fc12f8dc02e6e7a2459a80d7adef46b032bddfa0e40ea90006f4a8194cfac94",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-engine/5.9.2",
                    "dest-filename": "junit-jupiter-engine-5.9.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-engine/5.9.2/junit-jupiter-engine-5.9.2.module",
                    "sha512": "3e8807bf880c02c8dce17cf33141e2add7fc9c56fa50c2ccb905d10c2846697c9126ab1ca31c387194297cd017d669d9262f8e97191255d883de100bd5071659",
                    "dest": "maven-local/org/junit/jupiter/junit-jupiter-engine/5.9.2",
                    "dest-filename": "junit-jupiter-engine-5.9.2.module"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-engine/1.9.2/junit-platform-engine-1.9.2.jar",
                    "sha512": "99766a267099708337498c4b0cfe0b86733301cc59bb8b2b52a1151d78126a9226f4013d11d2bef990297c5e32b168e5b41b036f9a7cb9cc5b78ca0aacdc5a36",
                    "dest": "maven-local/org/junit/platform/junit-platform-engine/1.9.2",
                    "dest-filename": "junit-platform-engine-1.9.2.jar"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-engine/1.9.2/junit-platform-engine-1.9.2.pom",
                    "sha512": "9f673ab4654993c6a6dc5daff4b18a49e4bce9894eba65db86c9f8d48ecd13d3c880de1f2f2edb66c59840d1fa91f91c9c730e860e87443fe7ba3fcbb1cef8b5",
                    "dest": "maven-local/org/junit/platform/junit-platform-engine/1.9.2",
                    "dest-filename": "junit-platform-engine-1.9.2.pom"
                  },
                  {
                    "type": "file",
                    "url": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-engine/1.9.2/junit-platform-engine-1.9.2.module",
                    "sha512": "81bf3f5be568ad0459ed02cded8bdef71d0cdebc429cdfe4296540a3c7c43eb22a9a73ebbbbd95c1bad815c36723aeeced3f49832d47a4376991763df5b4c7b1",
                    "dest": "maven-local/org/junit/platform/junit-platform-engine/1.9.2",
                    "dest-filename": "junit-platform-engine-1.9.2.module"
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
