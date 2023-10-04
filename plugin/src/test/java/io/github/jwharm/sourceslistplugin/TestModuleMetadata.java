/* flatpak-gradle-generator - a Gradle plugin to generate a list of dependencies
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestModuleMetadata {

    @Test
    public void testModuleMetadata() {
        var mod = ModuleMetadata.getInstance();
        var result = mod.process(MODULE_FILE, "runtimeElements");
        assertEquals("cairo-1.16.2.jar", result);
    }

    private static final String MODULE_FILE = """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "io.github.jwharm.cairobindings",
                "module": "cairo",
                "version": "1.16.2",
                "attributes": {
                  "org.gradle.status": "release"
                }
              },
              "createdBy": {
                "gradle": {
                  "version": "8.3"
                }
              },
              "variants": [
                {
                  "name": "apiElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.jvm.version": 20,
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-api"
                  },
                  "files": [
                    {
                      "name": "cairo-1.16.2.jar (INVALID)",
                      "url": "cairo-1.16.2.jar",
                      "size": 179193,
                      "sha512": "b01b894f1c218188ab302a2c348164fa886bf702a2ede3c0229997e5e2552f8c6859eb7bdc258a26360b867592740bf2a58c1ff8370727873aefdc8d6030e461",
                      "sha256": "5f31d80cd97140107849a1217872f1b811a377e3efffccd08704027bd492d3aa",
                      "sha1": "d20360f7a756ddfc358f54c1a52f3bf94d19d396",
                      "md5": "cd20934746c086f17ded471e60821708"
                    }
                  ]
                },
                {
                  "name": "runtimeElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.jvm.version": 20,
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-runtime"
                  },
                  "files": [
                    {
                      "name": "cairo-1.16.2.jar",
                      "url": "cairo-1.16.2.jar",
                      "size": 179193,
                      "sha512": "b01b894f1c218188ab302a2c348164fa886bf702a2ede3c0229997e5e2552f8c6859eb7bdc258a26360b867592740bf2a58c1ff8370727873aefdc8d6030e461",
                      "sha256": "5f31d80cd97140107849a1217872f1b811a377e3efffccd08704027bd492d3aa",
                      "sha1": "d20360f7a756ddfc358f54c1a52f3bf94d19d396",
                      "md5": "cd20934746c086f17ded471e60821708"
                    }
                  ]
                },
                {
                  "name": "sourcesElements",
                  "attributes": {
                    "org.gradle.category": "documentation",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.docstype": "sources",
                    "org.gradle.usage": "java-runtime"
                  },
                  "files": [
                    {
                      "name": "cairo-1.16.2-sources.jar",
                      "url": "cairo-1.16.2-sources.jar",
                      "size": 161855,
                      "sha512": "91cfd30ad50ae2e71751f2e086460fad5df7d83ab443252bf5b4fb0bccd69acac9688461dc4357df7c2b44efcc5b1417a7a975cab305c93255b7962572533498",
                      "sha256": "24ec996d113b25c777ef7aa546a4cfbdb05abf32152e2ada91a3a3486d340b01",
                      "sha1": "4d3c0160736463ec6e255b81f1443cfa70a774dc",
                      "md5": "b092574c0ee1ece76a511f112d1928dc"
                    }
                  ]
                },
                {
                  "name": "javadocElements",
                  "attributes": {
                    "org.gradle.category": "documentation",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.docstype": "javadoc",
                    "org.gradle.usage": "java-runtime"
                  },
                  "files": [
                    {
                      "name": "cairo-1.16.2-javadoc.jar",
                      "url": "cairo-1.16.2-javadoc.jar",
                      "size": 553344,
                      "sha512": "d3d48d0c9b5a97ef5eef45fb89d8c2ecf59143eaadaaf3b8f05f597cdf786ed1ceec4e5ea442c172042a20993791d0ec93a0b6a67ebdd617feb9df7531ed4c3f",
                      "sha256": "e668e20477c39f86ef5c82034644724553a7007c272b7ccf939691d19b81d1ba",
                      "sha1": "2c0b8392dff3030a84362d42e1e8a6ed46a0fde0",
                      "md5": "53048ac7abcf4ff9b89fd2dd62a10f57"
                    }
                  ]
                }
              ]
            }
            """;
}
