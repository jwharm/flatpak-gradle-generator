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

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.Optional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

@CacheableTask
public abstract class FlatpakGradleGeneratorTask extends DefaultTask {

    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";
    private static final int    MAX_THREADS = 120;
    private static final Logger LOGGER = Logging.getLogger(FlatpakGradleGeneratorTask.class);

    /**
     * Specifies where to write the resulting JSON file.
     *
     * @return the output file
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Specifies the value for the {@code "dest"} attribute in the JSON file.
     * <p>
     * Defaults to {@code "offline-repository"}.
     *
     * @return the download directory
     */
    @Input
    @Optional
    public abstract Property<String> getDownloadDirectory();

    /**
     * The configurations for which dependencies should be collected.
     * <p>
     * Defaults to all project's configurations.
     *
     * @return the included configurations
     */
    @Input
    @Optional
    public abstract SetProperty<String> getIncludeConfigurations();

    /**
     * The configurations to exclude from dependency collection.
     * <p>
     * Overrides {@link #getIncludeConfigurations()}.
     * <p>
     * Defaults to an empty list.
     *
     * @return the excluded configurations
     */
    @Input
    @Optional
    public abstract SetProperty<String> getExcludeConfigurations();

    /**
     * The supported architectures.
     *
     * @return the "only-arches" value that will be added to the generated JSON entries.
     */
    @Input
    @Optional
    public abstract Property<String> getOnlyArches();

    /**
     * The Maven repository URLs of the project.
     */
    @Input
    public abstract SetProperty<String> getRepositories();

    /**
     * The buildscript Maven repositories.
     */
    @Input
    public abstract SetProperty<String> getPluginRepositories();

    @Internal
    public abstract MapProperty<String, ResolutionResult> getResolutionResults();

    @Internal
    public abstract MapProperty<String, List<ResolvedArtifactResult>> getResolvedArtifacts();

    @Internal
    public abstract MapProperty<String, ResolutionResult> getBuildscriptResolutionResults();

    @Internal
    public abstract MapProperty<String, List<ResolvedArtifactResult>> getBuildscriptResolvedArtifacts();

    /**
     * Per-execution state. We avoid using private fields, because they would
     * be serialized into the configuration cache.
     */
    private record Context(
            ArtifactResolver resolver,
            ModuleMetadata moduleMetadata,
            ExecutorService executor) {
    }

    /**
     * Run the flatpakGradleGenerator task.
     *
     * @throws ExecutionException   error during execution
     * @throws InterruptedException interrupted during execution
     * @throws IOException          error writing JSON list to output file
     */
    @TaskAction
    public void apply() throws IOException, ExecutionException, InterruptedException {
        ModuleMetadata moduleMetadata = new ModuleMetadata();
        String dest = Utils.addSlash(getDownloadDirectory().get());
        ArtifactResolver resolver = new ArtifactResolver(dest, getOnlyArches().get());

        List<String> pluginRepositories = filterRepositories(getPluginRepositories().get());
        List<String> repositories = filterRepositories(Utils.concat(getRepositories().get(), pluginRepositories));

        ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
        try {
            Context ctx = new Context(resolver, moduleMetadata, executor);

            LOGGER.info("Resolving buildscript dependencies using repositories: {}", pluginRepositories);
            generateSourcesList(ctx,
                    pluginRepositories,
                    getBuildscriptResolutionResults().get(),
                    getBuildscriptResolvedArtifacts().get(),
                    getBuildscriptResolutionResults().get().keySet());

            LOGGER.info("Resolving project dependencies using repositories: {}", repositories);
            generateSourcesList(ctx,
                    repositories,
                    getResolutionResults().get(),
                    getResolvedArtifacts().get(),
                    getResolutionResults().get().keySet());
        } finally {
            executor.shutdownNow();
        }

        File outputFile = getOutputFile().get().getAsFile();
        LOGGER.info("Writing result to {}", outputFile);
        Files.createDirectories(outputFile.toPath().toAbsolutePath().getParent());
        Files.writeString(outputFile.toPath(), resolver.getJsonOutput());
    }

    // Generate sources for a set of configurations
    private void generateSourcesList(Context ctx, List<String> repositories, Map<String, ResolutionResult> graphs,
                                     Map<String, List<ResolvedArtifactResult>> artifacts, Set<String> configurations)
            throws ExecutionException, InterruptedException {

        List<String> names = Utils.sorted(configurations);
        LOGGER.info("Following configurations will be processed: {}", names);

        for (String configuration : names) {
            ResolutionResult graph = graphs.get(configuration);
            if (graph != null)
                generateSourcesList(ctx, repositories, configuration, graph,
                        artifacts.getOrDefault(configuration, emptyList()));
        }
    }

    // Generate sources for a specific configuration
    private void generateSourcesList(Context ctx, List<String> repositories, String configuration,
                                     ResolutionResult graph, List<ResolvedArtifactResult> artifacts)
            throws ExecutionException, InterruptedException {

        Map<String, File> cachedArtifacts = indexArtifacts(artifacts);

        List<Callable<Void>> jobs = graph.getAllDependencies().stream()
                .filter(ResolvedDependencyResult.class::isInstance)
                .map(ResolvedDependencyResult.class::cast)
                .map(dependency -> (Callable<Void>) () -> {
                    try {
                        processDependency(ctx, repositories, cachedArtifacts, configuration, dependency);
                        return null;
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException("Error while processing dependency", e);
                    }
                })
                .toList();

        LOGGER.info("Starting processing of configuration {} with {} dependencies",
                configuration, jobs.size());

        for (Future<Void> job : ctx.executor().invokeAll(jobs)) {
            job.get();
        }

        LOGGER.info("Configuration {} processed", configuration);
    }

    private void processDependency(Context ctx, List<String> repositories, Map<String, File> cachedArtifacts,
                                   String configurationName, ResolvedDependencyResult dependency)
            throws InterruptedException, IOException, NoSuchAlgorithmException {

        String id = dependency.getSelected().getId().getDisplayName();
        String variant = dependency.getResolvedVariant().getDisplayName();

        // Skip dependencies on local Gradle projects
        if (id.startsWith("project "))
            return;

        var dep = DependencyDetails.of(id);
        var version = requireNonNull(dependency.getSelected().getModuleVersion());
        processDependencyDetails(ctx, repositories, cachedArtifacts,
                gav(version.getModule().getGroup(), version.getModule().getName(), version.getVersion()),
                dep, variant);
        LOGGER.info("Dependency {} in {} processed", id, configurationName);

        // For Gradle-bundled libraries (like Kotlin), emit both the requested
        // version and Gradle's embedded version (via the version constraint)
        if (dependency.getRequested() instanceof ModuleComponentSelector selector) {
            String required = selector.getVersionConstraint().getRequiredVersion();
            if (!required.isEmpty() && !required.equals(dep.version())) {
                var requiredDep = DependencyDetails.of(
                        selector.getGroup() + ":" + selector.getModule() + ":" + required);
                processDependencyDetails(ctx, repositories, cachedArtifacts,
                        gav(selector.getGroup(), selector.getModule(), required), requiredDep, variant);
                LOGGER.info("Dependency version constraint {} in {} processed", required, configurationName);
            }
        }
    }

    private void processDependencyDetails(Context ctx, List<String> repositories, Map<String, File> cachedArtifacts,
                                          String versionKey, DependencyDetails dep, String variant)
            throws InterruptedException, IOException, NoSuchAlgorithmException {

        for (String repository : repositories) {
            // Check for a Gradle module artifact
            var module = ctx.resolver().tryResolve(dep, repository, dep.filename("module"));

            // Add file artifacts from information in the Gradle module
            if (module.isPresent()) {
                try {
                    List<ModuleMetadata.FileDTO> files = null;
                    try {
                        files = ctx.moduleMetadata().process(new String(module.get()), variant);
                    } catch (ModuleMetadata.RedirectedException redirected) {
                        // Download .module artifact from alternate URL
                        module = ctx.resolver().tryResolve(dep, repository, redirected.url());
                        if (module.isPresent())
                            files = ctx.moduleMetadata().process(new String(module.get()), variant);
                    }

                    if (files != null) {
                        for (var file : files) {
                            ctx.resolver().tryResolveCached(
                                    cachedArtifacts, versionKey, dep, repository, file.url, true, file.name);
                        }
                    }
                } catch (NoSuchElementException noJar) {
                    // No files declared for this variant: fall back to the local
                    // Gradle cache
                    ctx.resolver().tryResolveCached(
                            cachedArtifacts, versionKey, dep, repository, dep.filename("jar"), false, null);
                }
            } else {
                ctx.resolver().tryResolveCached(
                        cachedArtifacts, versionKey, dep, repository, dep.filename("jar"), false, null);
            }

            // Download POM artifact
            var pom = ctx.resolver().tryResolve(dep, repository, dep.filename("pom"));

            // Add parent POMs
            pom.ifPresent(bytes ->
                PomHandler.getInstance(ctx.resolver()).addParentPOMs(bytes, repository));

            // Add marker artifact
            // Only for plugin jar files downloaded from the Gradle Plugin Portal
            if (repository.equals(GRADLE_PLUGIN_PORTAL) && dep.group().startsWith("gradle.plugin."))
                addPluginMarker(ctx, dep);

            // Success! No need to resolve this dependency against other repositories
            if (module.isPresent() || pom.isPresent())
                break;
        }
    }

    /**
     * For Gradle plugins, we add the plugin marker artifact to the JSON file.
     *
     * @param ctx the context
     * @param dep details about the dependency
     */
    private void addPluginMarker(Context ctx, DependencyDetails dep)
            throws IOException, NoSuchAlgorithmException, InterruptedException {

        String group = dep.group().substring("gradle.plugin.".length());
        var marker = new DependencyDetails(
                group, group + ".gradle.plugin", dep.version(), dep.snapshotDetail(), dep.isSnapshot());
        ctx.resolver().tryResolve(marker, GRADLE_PLUGIN_PORTAL, dep.filename("pom"));
    }

    /**
     * Index the locally cached artifacts by {@code group:module:version} so the
     * resolver can look them up without holding {@code ResolvedArtifact}
     * instances (which are not configuration-cache compatible).
     */
    private Map<String, File> indexArtifacts(Collection<ResolvedArtifactResult> artifacts) {
        Map<String, File> files = new HashMap<>();
        for (ResolvedArtifactResult artifact : artifacts) {
            if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier module) {
                files.putIfAbsent(
                        gav(module.getGroup(), module.getModule(), module.getVersion()),
                        artifact.getFile());
            }
        }
        return files;
    }

    // Skip duplicate & local repositories
    private List<String> filterRepositories(Collection<String> repositories) {
        return repositories.stream()
                .distinct()
                .filter(repository -> !repository.startsWith("file:/"))
                .toList();
    }

    private static String gav(String group, String module, String version) {
        return group + ":" + module + ":" + version;
    }
}
