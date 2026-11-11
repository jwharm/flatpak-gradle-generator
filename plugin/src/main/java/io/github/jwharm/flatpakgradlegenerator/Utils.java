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

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

class Utils {
    /**
     * Ensure the string ends with a slash.
     */
    static String addSlash(String str) {
        return str.endsWith("/") ? str : (str + "/");
    }

    /**
     * Return a list with all elements of a followed by all elements of b.
     */
    static <T> List<T> concat(Collection<T> a, Collection<T> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    /**
     * Sort a collection.
     */
    static <T> List<T> sorted(Collection<T> collection) {
        return collection.stream().sorted().toList();
    }
}
