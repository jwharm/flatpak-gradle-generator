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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.tasks.TaskProvider;

import java.util.Comparator;
import java.util.Set;

/**
 * A plugin that creates a sources list file with all Gradle dependencies,
 * so they can be downloaded for an offline build.
 */
public class FlatpakGradleGeneratorPlugin implements Plugin<Project> {

    private static final String DEFAULT_DOWNLOAD_DIRECTORY = "offline-repository";
    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";

    @Override
    public void apply(Project project) {
        TaskProvider<FlatpakGradleGeneratorTask> task = project.getTasks()
                .register("flatpakGradleGenerator", FlatpakGradleGeneratorTask.class, t -> {
                    t.getDownloadDirectory().convention(DEFAULT_DOWNLOAD_DIRECTORY);
                    t.getIncludeConfigurations().convention(Set.of());
                    t.getExcludeConfigurations().convention(Set.of());
                    t.getOnlyArches().convention("");
                });

        project.getRepositories().all(repository -> {
            if (repository instanceof MavenArtifactRepository maven) {
                String url = Utils.addSlash(maven.getUrl().toString());
                task.configure(t -> t.getRepositories().add(url));
            }
        });

        if (project.getGradle() instanceof GradleInternal gradle) try {
            gradle.getSettings().getPluginManagement().getRepositories().all(repo -> {
                if (repo instanceof MavenArtifactRepository mavenRepo) {
                    String url = Utils.addSlash(mavenRepo.getUrl().toString());
                    task.configure(t -> t.getPluginRepositories().add(url));
                }
            });
        } catch (IllegalStateException ignored) {
            // No Gradle settings for this project
        }
        task.configure(t -> t.getPluginRepositories().add(GRADLE_PLUGIN_PORTAL));

        project.afterEvaluate(p -> {
            FlatpakGradleGeneratorTask t = task.get();
            Set<String> include = t.getIncludeConfigurations().getOrElse(Set.of());
            Set<String> exclude = t.getExcludeConfigurations().getOrElse(Set.of());
            wire(p.getConfigurations(), t, false, include, exclude);
            wire(p.getBuildscript().getConfigurations(), t, true, include, exclude);
        });
    }

    private static void wire(ConfigurationContainer configurations, FlatpakGradleGeneratorTask task,
                             boolean buildscript, Set<String> include, Set<String> exclude) {

        var resolutionResults = buildscript ? task.getBuildscriptResolutionResults() : task.getResolutionResults();
        var resolvedArtifacts = buildscript ? task.getBuildscriptResolvedArtifacts() : task.getResolvedArtifacts();

        for (Configuration configuration : configurations) {
            if (!configuration.isCanBeResolved())
                continue;

            String name = configuration.getName();
            if (!include.isEmpty() && !include.contains(name))
                continue;
            if (exclude.contains(name))
                continue;

            resolutionResults.put(name, configuration.getIncoming().getResolutionResult());
            resolvedArtifacts.put(name, configuration.getIncoming()
                    .getArtifacts()
                    .getResolvedArtifacts()
                    .map(results -> results.stream()
                            // sorted for reproducable task inputs
                            .sorted(Comparator.comparing(r -> r.getId().getDisplayName()))
                            .toList()));
        }
    }
}
