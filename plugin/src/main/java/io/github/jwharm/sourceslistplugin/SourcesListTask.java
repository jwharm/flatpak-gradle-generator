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

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A task that creates a sources list file with all Gradle dependencies,
 * so they can be downloaded for an offline build.
 */
public abstract class SourcesListTask extends DefaultTask {

    private static final String DEFAULT_DOWNLOAD_DIRECTORY = "maven-local";
    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";

    /**
     * Specifies where to write the resulting json file.
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Specifies the value for the {@code "dest"} attribute in the json file.
     * <p>
     * Defaults to {@code "localRepository"}.
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getDownloadDirectory();

    // Set of generated dependency specs, to prevent generating duplicate entries
    private HashSet<String> ids;

    private final ArtifactResolver resolver = ArtifactResolver.getInstance(getDest());
    private final ParentPOM parentPOM = ParentPOM.getInstance(resolver);
    private final ModuleMetadata moduleMetadata = ModuleMetadata.getInstance();

    @TaskAction
    public void apply() throws NoSuchAlgorithmException, IOException {
        var project = getProject();
        ids = new HashSet<>();

        // StringJoiner is used to build the json file
        var joiner = new StringJoiner(",\n", "[\n", "\n]\n");

        // Buildscript classpath dependencies
        var classpath = project.getBuildscript().getConfigurations().getByName("classpath");
        var buildScriptRepositories = listPluginRepositories(project);
        if (classpath.isCanBeResolved()) {
            generateSourcesList(buildScriptRepositories, classpath, joiner);
        }

        // List the declared repositories; include the buildscript classpath repositories too
        var repositories = listRepositories(project);
        repositories.addAll(buildScriptRepositories);

        // Loop through configurations and generate json blocks for the resolved dependencies
        for (var configuration : project.getConfigurations()) {
            if (configuration.isCanBeResolved()) {
                generateSourcesList(repositories, configuration, joiner);
            }
        }

        // Write the StringJoiner result to the json file
        var fileName = getOutputFile().getAsFile().get();
        try (BufferedWriter output = new BufferedWriter(new FileWriter(fileName))) {
            output.write(joiner.toString());
        }
    }

    /**
     * Get all Maven repositories that were declared in the build file
     * @param project the project for which to list the repositories
     * @return the repositories that are declared in the project
     */
    private List<String> listRepositories(Project project) {
        return new ArrayList<>(project.getRepositories().stream()
                .filter(repo -> repo instanceof MavenArtifactRepository)
                .map(repo -> ((MavenArtifactRepository) repo).getUrl().toString())
                .map(repo -> repo.endsWith("/") ? repo : repo + "/")
                .toList());
    }

    /**
     * Get all Maven repositories that were declared in the settings file (pluginManagement block),
     * and the Gradle Plugin Portal
     * @param project the project for which to list the plugin repositories
     * @return the plugin repositories that are declared in the settings file (pluginManagement block),
     *         and the Gradle Plugin Portal.
     */
    private List<String> listPluginRepositories(Project project) {
        var settings = ((GradleInternal) project.getGradle()).getSettings();
        var repositories = new ArrayList<>(settings.getPluginManagement().getRepositories().stream()
                .filter(repo -> repo instanceof MavenArtifactRepository)
                .map(repo -> ((MavenArtifactRepository) repo).getUrl().toString())
                .map(repo -> repo.endsWith("/") ? repo : repo + "/")
                .toList());

        // The Gradle plugin repository is always available
        repositories.add(GRADLE_PLUGIN_PORTAL);

        return repositories;
    }

    /**
     * Generate json blocks for all dependencies in the provided configuration, and add them to the StringJoiner
     * @param repositories the list of declared repositories
     * @param configuration a configuration that may hold dependencies
     * @param joiner the StringJoiner to append the JSON to
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
    private void generateSourcesList(List<String> repositories, Configuration configuration, StringJoiner joiner)
            throws IOException, NoSuchAlgorithmException {
        for (var dependency : listDependencies(configuration)) {
            // Don't process the same dependency multiple times
            String id = dependency.getSelected().getId().getDisplayName();
            if (ids.contains(id))
                continue;
            ids.add(id);

            String variant = dependency.getResolvedVariant().getDisplayName();

            System.out.printf("%s: %s\n", configuration.getName(), id);

            // Build simple helper object with the Maven coordinates of the artifact
            var dep = DependencyDetails.of(id);

            // Loop through the repositories, skip duplicates
            for (var repository : repositories.stream().distinct().toList()) {
                // Download .module artifact
                var result = resolver.tryResolve(dep, repository, dep.filename("module"), joiner);

                // Add .jar artifact from information in the .module file
                if (result.isPresent()) {
                    try {
                        String jarFilename = null;
                        try {
                            jarFilename = moduleMetadata.process(new String(result.get()), variant);
                        } catch (ModuleMetadata.RedirectedException redirected) {
                            // Download .module artifact from alternate URL
                            result = resolver.tryResolve(dep, repository, redirected.url(), joiner);
                            if (result.isPresent())
                                jarFilename = moduleMetadata.process(new String(result.get()), variant);
                        }
                        if (jarFilename != null)
                            resolver.tryResolveCached(configuration, dependency.getSelected().getModuleVersion(),
                                    dep, repository, jarFilename, joiner);

                    } catch (NoSuchElementException noJar) {
                        // No jar file declared for this variant in the .module file
                        // Get .jar artifact from local Gradle cache
                        resolver.tryResolveCached(configuration, dependency.getSelected().getModuleVersion(),
                                dep, repository, dep.filename("jar"), joiner);
                    }
                }

                // Get .jar artifact from local Gradle cache
                else {
                    resolver.tryResolveCached(configuration, dependency.getSelected().getModuleVersion(),
                            dep, repository, dep.filename("jar"), joiner);
                }

                // Download .pom artifact
                result = resolver.tryResolve(dep, repository, dep.filename("pom"), joiner);

                // Add parent POMs
                if (result.isPresent())
                    parentPOM.addParentPOMs(result.get(), repository, joiner);

                // Add marker artifact
                // Only for plugin jar files downloaded from the Gradle Plugin Portal
                if (repository.equals(GRADLE_PLUGIN_PORTAL) && dep.group().startsWith("gradle.plugin."))
                    addPluginMarker(dep, joiner);

                // Success! No need to resolve this dependency against other repositories
                break;
            }
        }
    }

    /**
     * Get the downloadDirectory property, fill in the default if necessary, and append a slash if necessary.
      * @return the download directory, ending with a slash
     */
    private String getDest() {
        String dest = getDownloadDirectory().getOrElse(DEFAULT_DOWNLOAD_DIRECTORY);
        return dest.endsWith("/") ? dest : (dest + "/");
    }

    /**
     * Find all resolved dependencies (both direct and transitive)
     * @param configuration the configuration to list the dependencies for
     * @return all resolved dependencies (both direct and transitive)
     */
    private List<ResolvedDependencyResult> listDependencies(Configuration configuration) {
        return configuration
                .getIncoming()
                .getResolutionResult()
                .getAllDependencies()
                .stream()
                .filter(dependency -> dependency instanceof ResolvedDependencyResult)
                .map(result -> ((ResolvedDependencyResult) result))
                .toList();
    }

    /**
     * For Gradle plugins, we add the plugin marker artifact to the JSON file.
     * @param dep details about the dependency
     * @param joiner the StringJoiner to which the marker artifact JSON will be added
     */
    private void addPluginMarker(DependencyDetails dep, StringJoiner joiner) throws NoSuchAlgorithmException {
        // This is the marker artifact we are looking for
        var marker = new DependencyDetails(
                dep.group().substring("gradle.plugin.".length()),
                dep.group().substring("gradle.plugin.".length()) + ".gradle.plugin",
                dep.version(),
                dep.snapshotDetail(),
                dep.isSnapshot()
        );
        resolver.tryResolve(marker, GRADLE_PLUGIN_PORTAL, dep.filename("pom"), joiner);
    }
}
