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
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A task that creates a sources list file with all Gradle dependencies,
 * so they can be downloaded for an offline build.
 */
public abstract class SourcesListTask extends DefaultTask {

    private static final String DEFAULT_DOWNLOAD_DIRECTORY = "maven-local";
    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";
    private static final List<String> ARTIFACT_EXTENSIONS = List.of("jar", "pom", "module");

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

    // Get all Maven repositories that were declared in the build file
    private List<String> listRepositories(Project project) {
        return new ArrayList<>(project.getRepositories().stream()
                .filter(repo -> repo instanceof MavenArtifactRepository)
                .map(repo -> ((MavenArtifactRepository) repo).getUrl().toString())
                .map(repo -> repo.endsWith("/") ? repo : repo + "/")
                .toList());
    }

    // Get all Maven repositories that were declared in the settings file (pluginManagement block),
    // and the Gradle plugin repository
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

    // Generate json blocks for all dependencies in the provided configuration,
    // and add them to the StringJoiner
    private void generateSourcesList(List<String> repositories, Configuration configuration, StringJoiner joiner)
            throws NoSuchAlgorithmException, IOException {
        for (var dependency : listDependencies(configuration)) {

            // Don't process the same dependency multiple times
            String id = dependency.getId().getDisplayName();
            if (ids.contains(id))
                continue;
            ids.add(id);

            // Build simple helper object
            var dep = DependencyDetails.of(id);

            for (String ext : ARTIFACT_EXTENSIONS) {  // .jar, .pom and .module
                // Find the locally cached artifact
                var artifact = getArtifact(configuration, dependency.getModuleVersion(), ext);
                if (artifact.isEmpty())
                    continue;

                // the "sha512"
                String sha512 = calculateSHA512(artifact.get());

                // The "dest"
                var dest = getDest() + dep.path();

                // The "dest-filename"
                String destFilename = dep.filename(ext);

                // Generate an url for each repository,
                // and find the first repository that responds with HTTP 200 (OK).
                var json = repositories.stream()
                        .map(repo -> repo + dep.path() + "/" + dep.filename(ext))        // build the url
                        .distinct()                                                      // skip duplicate repositories
                        .filter(this::tryResolve)                                        // filter for valid urls
                        .findFirst()                                                     // keep the first valid url
                        .map(url -> addPluginMarker(url, dep, artifact.get(), joiner))   // add marker artifact
                        .map(url -> generateJsonBlock(url, sha512, dest, destFilename)); // generate the json

                json.ifPresent(joiner::add);
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
     * Simple record type to work with dependencies
     * @param group the Maven groupId
     * @param name the Maven artifact name
     * @param version the Maven artifact version, for snapshots this ends with "-SNAPSHOT"
     * @param snapshotDetail only for snapshot dependencies, format "yyyymmdd.hhmmss-n"
     * @param isSnapshot whether this is a snapshot version
     */
    private record DependencyDetails(
            String group,
            String name,
            String version,
            String snapshotDetail,
            boolean isSnapshot
    ) {

        /**
         * Parse a dependency record from this id
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
         * @return the path to use in the url
         */
        String path() {
            return group.replace(".", "/") + "/" + name + "/" + version;
        }

        /**
         * Generate the filename to use in the url. Format is name-version.jar.
         * For snapshot versions, this is the actual filename, formatted as name-version-yyyymmdd.hhmmss-n.[ext]
         * @param ext the extension to append to the filename
         * @return the filename to use in the url
         */
        String filename(String ext) {
            return "%s-%s.%s".formatted(name, version.replace("SNAPSHOT", snapshotDetail), ext);
        }
    }

    /**
     * Get the {@link File} object of the locally cached artifact for a dependency
     * @param configuration the configuration with this dependency
     * @param id the {@link ModuleVersionIdentifier} of the dependency
     * @return the {@link File} object of the locally cached artifact, or empty when the artifact is not found
     */
    private Optional<File> getArtifact(Configuration configuration, ModuleVersionIdentifier id, String ext) {
        for (var artifact : configuration.getResolvedConfiguration().getResolvedArtifacts()) {
            if (artifact.getModuleVersion().getId().equals(id)) {
                // Found the jar artifact.
                // Find the artifact with the requested extension. It should exist in a sibling directory.
                File parentDir = artifact.getFile().getParentFile().getParentFile();
                for (var dir : Objects.requireNonNull(parentDir.listFiles())) {
                    // Check if there is exactly one file in this directory, with the requested extension.
                    File[] files = dir.listFiles(($, name) -> name.endsWith("." + ext));
                    if (files != null && files.length == 1) {
                        return Optional.of(files[0]);
                    }
                }
            }
        }
        // Artifact not found in the cache.
        return Optional.empty();
    }

    /**
     * Create a json string
     * @param url the url
     * @param sha512 the hash
     * @param dest the target directory
     * @param destFilename the filename
     * @return the generated json string
     */
    private String generateJsonBlock(String url, String sha512, String dest, String destFilename) {
        return """
                          {
                            "type": "file",
                            "url": "%s",
                            "sha512": "%s",
                            "dest": "%s",
                            "dest-filename": "%s"
                        """
                .formatted(url, sha512, dest, destFilename)
                + "  }";
    }

    /**
     * Find all resolved dependencies (both direct and transitive)
     * @param configuration the configuration to list the dependencies for
     * @return all resolved dependencies (both direct and transitive)
     */
    private List<ResolvedComponentResult> listDependencies(Configuration configuration) {
        return configuration
                .getIncoming()
                .getResolutionResult()
                .getAllDependencies()
                .stream()
                .filter(dependency -> dependency instanceof ResolvedDependencyResult)
                .map(result -> ((ResolvedDependencyResult) result))
                .map(ResolvedDependencyResult::getSelected)
                .toList();
    }

    /**
     * For Gradle plugins, we add the plugin marker artifact to the JSON file.
     * @param url a valid artifact url
     * @param dep details about the dependency
     * @param artifact the locally cached file for the dependency
     * @param joiner the StringJoiner to which the marker artifact JSON will be added
     * @return the original url to facilitate method chaining
     */
    private String addPluginMarker(String url, DependencyDetails dep, File artifact, StringJoiner joiner) {
        try {
            // Only run for jar files that are downloaded from the Gradle Plugin Portal
            if (url.startsWith(GRADLE_PLUGIN_PORTAL) && url.endsWith(".jar")) {

                // This is the marker artifact we are looking for
                var marker = new DependencyDetails(
                        dep.group().substring("gradle.plugin.".length()),
                        dep.group().substring("gradle.plugin.".length()) + ".gradle.plugin",
                        dep.version(),
                        dep.snapshotDetail(),
                        dep.isSnapshot()
                );

                // Find the locally cached pom file of the marker artifact
                File pom = null;
                var path = artifact.toPath()
                        .getParent().getParent().getParent().getParent().getParent()
                        .resolve(Path.of(marker.group(), marker.name(), marker.version()))
                        .toFile();
                for (var dir : Objects.requireNonNull(path.listFiles())) {
                    // Check if there is exactly one .pom file in this directory.
                    File[] files = dir.listFiles(($, name) -> name.endsWith(".pom"));
                    if (files != null && files.length == 1) {
                        pom = files[0];
                        break;
                    }
                }
                if (pom == null)
                    throw new FileNotFoundException();

                // Calculate sha512 for the marker pom
                String sha512 = calculateSHA512(pom);

                // Build the url
                String markerUrl = GRADLE_PLUGIN_PORTAL + marker.path() + "/" + pom.getName();

                // Try to resolve the url
                if (tryResolve(markerUrl)) {
                    // Build "dest" path
                    String dest = getDest() + marker.path();

                    // Generate json and add it to the StringJoiner
                    var json = generateJsonBlock(markerUrl, sha512, dest, pom.getName());
                    joiner.add(json);
                }
            }
        } catch (Exception ignored) {
        }
        // Always return the original artifact url
        return url;
    }

    /**
     * Check if the file in this url exists, without downloading it. This will send an HTTP request
     * and check if the response code is HTTP 200 (OK).
     * @param url the url to check
     * @return true if the url is valid (HTTP 200 OK), otherwise false
     */
    private boolean tryResolve(String url) {
        try {
            URL fileUrl = new URI(url).toURL();
            HttpURLConnection httpURLConnection = (HttpURLConnection) fileUrl.openConnection();

            // Set the request to follow redirects (303 for example)
            httpURLConnection.setInstanceFollowRedirects(true);

            // Set the request method to HEAD (only retrieve headers, not the entire file)
            httpURLConnection.setRequestMethod("HEAD");

            // Get the response code
            int responseCode = httpURLConnection.getResponseCode();

            // Check if the response code indicates that the file exists (usually 200 OK)
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false; // File existence couldn't be determined
        }
    }

    /**
     * Generate an SHA-512 hash for a file using MessageDigest.
     * @param file the file to calculate the hash for
     * @return the hash string
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     * @throws IOException error reading the file
     */
    private static String calculateSHA512(File file) throws NoSuchAlgorithmException, IOException {
        // Create a MessageDigest object for SHA-512
        MessageDigest md = MessageDigest.getInstance("SHA-512");

        // Create a FileInputStream to read the file
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[8192]; // Adjust the buffer size as needed

        int bytesRead;
        while ((bytesRead = fis.read(buffer)) != -1) {
            // Update the MessageDigest with the bytes read from the file
            md.update(buffer, 0, bytesRead);
        }

        // Close the FileInputStream
        fis.close();

        // Get the final hash value as a byte array
        byte[] hashBytes = md.digest();

        // Convert the byte array to a hexadecimal string
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }

        // Return the SHA-512 hash as a string
        return sb.toString();
    }
}
