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
import java.net.URI;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A task that creates a sources list file with all Gradle dependencies,
 * so they can be downloaded for an offline build.
 */
public abstract class FlatpakGradleGeneratorTask extends DefaultTask {

    private static final String DEFAULT_DOWNLOAD_DIRECTORY = "offline-repository";
    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";

    /**
     * Specifies where to write the resulting json file.
     *
     * @return the output file
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Specifies the value for the {@code "dest"} attribute in the json file.
     * <p>
     * Defaults to {@code "offline-repository"}.
     *
     * @return the download directory
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getDownloadDirectory();

    // Set of generated dependency specs, to prevent generating duplicate entries
    private HashSet<String> ids;

    private ArtifactResolver resolver;
    private PomHandler POMHandler;
    private ModuleMetadata moduleMetadata;

    /**
     * Run the flatpakGradleGenerator task.
     *
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     * @throws IOException              error writing json list to output file
     */
    @TaskAction
    public void apply() throws NoSuchAlgorithmException, IOException {
        ids = new HashSet<>();
        resolver = ArtifactResolver.getInstance(getDest());
        POMHandler = PomHandler.getInstance(resolver);
        moduleMetadata = ModuleMetadata.getInstance();
        Project project = getProject();

        // Buildscript classpath dependencies
        var buildScriptRepositories = listPluginRepositories(project);
        for (var configuration : project.getBuildscript().getConfigurations()) {
            if (configuration.isCanBeResolved()) {
                generateSourcesList(buildScriptRepositories, configuration);
            }
        }

        // List the declared repositories; include the buildscript classpath
        // repositories too
        var repositories = listRepositories(project);
        repositories.addAll(buildScriptRepositories);

        // Loop through configurations and generate json blocks for the
        // resolved dependencies
        for (var configuration : project.getConfigurations()) {
            if (configuration.isCanBeResolved()) {
                generateSourcesList(repositories, configuration);
            }
        }

        // Merge the json blocks into a json list, and write the result to the
        // output file
        Files.writeString(
                getOutputFile().getAsFile().get().toPath(),
                resolver.getJsonOutput()
        );
    }

    /**
     * Get all Maven repositories that were declared in the build file.
     *
     * @param  project the project for which to list the repositories
     * @return the repositories that are declared in the project
     */
    private List<String> listRepositories(Project project) {
        return new ArrayList<>(project.getRepositories().stream()
                .filter(MavenArtifactRepository.class::isInstance)
                .map(MavenArtifactRepository.class::cast)
                .map(MavenArtifactRepository::getUrl)
                .map(URI::toString)
                .map(repo -> repo.endsWith("/") ? repo : repo + "/")
                .toList());
    }

    /**
     * Get all Maven repositories that were declared in the settings file
     * (pluginManagement block), and the Gradle Plugin Portal.
     *
     * @param  project the project for which to list the plugin repositories
     * @return the urls of the plugin repositories that are declared in the
     *         settings file (pluginManagement block), and the Gradle Plugin
     *         Portal.
     */
    private List<String> listPluginRepositories(Project project) {
        var settings = ((GradleInternal) project.getGradle()).getSettings();
        var repositories = settings.getPluginManagement().getRepositories();
        var urls = new ArrayList<>(repositories.stream()
                .filter(MavenArtifactRepository.class::isInstance)
                .map(MavenArtifactRepository.class::cast)
                .map(MavenArtifactRepository::getUrl)
                .map(URI::toString)
                .map(repo -> repo.endsWith("/") ? repo : repo + "/")
                .toList());

        // The Gradle plugin repository is always included
        urls.add(GRADLE_PLUGIN_PORTAL);

        return urls;
    }

    /**
     * Generate json blocks for all dependencies in the provided configuration.
     *
     * @param repositories  the list of declared repositories
     * @param configuration a configuration that may hold dependencies
     *
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
    private void generateSourcesList(List<String> repositories,
                                     Configuration configuration)
            throws IOException, NoSuchAlgorithmException {

        for (var dependency : listDependencies(configuration)) {

            // Don't process the same dependency multiple times
            String id = dependency.getSelected().getId().getDisplayName();
            if (ids.contains(id))
                continue;
            ids.add(id);

            String variant = dependency.getResolvedVariant().getDisplayName();

            // Skip dependencies on local Gradle projects
            if (id.startsWith("project "))
                continue;

            // Build simple helper object with the Maven coordinates of the artifact
            var dep = DependencyDetails.of(id);

            // Loop through the repositories, skip duplicates
            for (var repository : repositories.stream().distinct().toList()) {

                // Skip local repositories
                if (repository.startsWith("file:/"))
                    continue;

                // Check for a Gradle module artifact
                var module = resolver.tryResolve(dep, repository, dep.filename("module"));

                // Add file artifacts from information in the Gradle module
                if (module.isPresent()) {
                    try {
                        List<ModuleMetadata.FileDTO> files = null;
                        try {
                            files = moduleMetadata.process(new String(module.get()), variant);
                        } catch (ModuleMetadata.RedirectedException redirected) {
                            // Download .module artifact from alternate URL
                            module = resolver.tryResolve(dep, repository, redirected.url());
                            if (module.isPresent())
                                files = moduleMetadata.process(new String(module.get()), variant);
                        }

                        if (files != null) {
                            for (var file : files) {
                                resolver.tryResolveCached(
                                        configuration,
                                        dependency.getSelected().getModuleVersion(),
                                        dep,
                                        repository,
                                        file.url,
                                        true,
                                        file.name
                                );
                            }
                        }
                    } catch (NoSuchElementException noJar) {
                        // No files declared for this variant in the Gradle module
                        // Get jar artifact from local Gradle cache
                        resolver.tryResolveCached(
                                configuration,
                                dependency.getSelected().getModuleVersion(),
                                dep,
                                repository,
                                dep.filename("jar"),
                                false,
                                null
                        );
                    }
                }

                // Get jar artifact from local Gradle cache
                else {
                    resolver.tryResolveCached(
                            configuration,
                            dependency.getSelected().getModuleVersion(),
                            dep,
                            repository,
                            dep.filename("jar"),
                            false,
                            null
                    );
                }

                // Download POM artifact
                var pom = resolver.tryResolve(dep, repository, dep.filename("pom"));

                // Add parent POMs
                pom.ifPresent(bytes ->
                        POMHandler.addParentPOMs(bytes, repository));

                // Add marker artifact
                // Only for plugin jar files downloaded from the Gradle Plugin Portal
                if (repository.equals(GRADLE_PLUGIN_PORTAL)
                        && dep.group().startsWith("gradle.plugin."))
                    addPluginMarker(dep);

                // Success! No need to resolve this dependency against other repositories
                if (module.isPresent() || pom.isPresent())
                    break;
            }
        }
    }

    /**
     * Get the downloadDirectory property, fill in the default if necessary,
     * and append a slash if necessary.
     *
     * @return the download directory, ending with a slash
     */
    private String getDest() {
        String dest = getDownloadDirectory().getOrElse(DEFAULT_DOWNLOAD_DIRECTORY);
        return dest.endsWith("/") ? dest : (dest + "/");
    }

    /**
     * Find all resolved dependencies (both direct and transitive).
     *
     * @param  configuration the configuration to list the dependencies for
     * @return all resolved dependencies (both direct and transitive)
     */
    private List<ResolvedDependencyResult> listDependencies(Configuration configuration) {
        return configuration
                .getIncoming()
                .getResolutionResult()
                .getAllDependencies()
                .stream()
                .filter(ResolvedDependencyResult.class::isInstance)
                .map(ResolvedDependencyResult.class::cast)
                .toList();
    }

    /**
     * For Gradle plugins, we add the plugin marker artifact to the JSON file.
     *
     * @param dep details about the dependency
     */
    private void addPluginMarker(DependencyDetails dep) throws NoSuchAlgorithmException {

        // This is the marker artifact we are looking for
        var marker = new DependencyDetails(
                dep.group().substring("gradle.plugin.".length()),
                dep.group().substring("gradle.plugin.".length()) + ".gradle.plugin",
                dep.version(),
                dep.snapshotDetail(),
                dep.isSnapshot()
        );

        // Download the artifact
        resolver.tryResolve(marker, GRADLE_PLUGIN_PORTAL, dep.filename("pom"));
    }
}
