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

/**
 * Simple record type to work with dependencies
 *
 * @param group          the Maven groupId
 * @param name           the Maven artifact name
 * @param version        the Maven artifact version, for snapshots this ends with "-SNAPSHOT"
 * @param snapshotDetail only for snapshot dependencies, format "yyyymmdd.hhmmss-n"
 * @param isSnapshot     whether this is a snapshot version
 */
record DependencyDetails(
        String group,
        String name,
        String version,
        String snapshotDetail,
        boolean isSnapshot
) {

    /**
     * Parse a dependency record from this id
     *
     * @param id a Maven dependency id as returned by Gradle {@code ResolvedComponentResult.getId().getDisplayName()}
     * @return a new dependency record for this id
     */
    static DependencyDetails of(String id) {
        String[] parts = id.split(":");
        return new DependencyDetails(
                parts[0],
                parts[1],
                parts[2],
                parts.length > 3 ? parts[3] : "",
                parts[2].endsWith("-SNAPSHOT")
        );
    }

    /**
     * Generate the path to use in the url (combines group and name, using slashes as separators)
     *
     * @return the path to use in the url
     */
    String path() {
        return group.replace(".", "/") + "/" + name + "/" + version;
    }

    /**
     * Generate the filename to use in the url. Format is name-version.[ext].
     * For snapshot jar files, the filename is formatted as name-version-yyyymmdd.hhmmss-n.[ext]
     *
     * @param ext the extension to append to the filename
     * @return the filename to use in the url
     */
    String filename(String ext) {
        if (ext.equals("jar")) {
            return "%s-%s.%s".formatted(name, version.replace("SNAPSHOT", snapshotDetail), ext);
        } else {
            return "%s-%s.%s".formatted(name, version, ext);
        }
    }
}
