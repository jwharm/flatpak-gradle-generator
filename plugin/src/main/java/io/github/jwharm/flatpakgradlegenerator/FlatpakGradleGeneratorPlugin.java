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

package io.github.jwharm.flatpakgradlegenerator;

import org.gradle.api.Project;
import org.gradle.api.Plugin;

/**
 * A plugin that creates a sources list file with all Gradle dependencies,
 * so they can be downloaded for an offline build.
 */
public class FlatpakGradleGeneratorPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getTasks().register("flatpakGradleGenerator", FlatpakGradleGeneratorTask.class);
    }
}
