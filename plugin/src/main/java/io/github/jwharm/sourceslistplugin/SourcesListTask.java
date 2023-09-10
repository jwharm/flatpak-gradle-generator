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
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import rife.bld.dependencies.ArtifactRetriever;
import rife.bld.dependencies.Dependency;
import rife.bld.dependencies.DependencyResolver;
import rife.bld.dependencies.Repository;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A task that creates a sources list file with all Gradle dependencies,
 * so they can be downloaded for an offline build.
 */
public abstract class SourcesListTask extends DefaultTask {

    /**
     * Specifies where to write the resulting json file.
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Specifies the value for the {@code "dest"} attribute in the json file.
     * Defaults to {@code "localRepository"}.
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getDownloadDirectory();

    private Set<String> dependencies;
    private Map<String, String> artifacts;
    private Map<String, String> transferLocations;
    private Set<Repository> repositories;

    @TaskAction
    public void apply() throws NoSuchAlgorithmException, IOException {
        dependencies = new HashSet<>();
        artifacts = new HashMap<>();
        transferLocations = new HashMap<>();
        repositories = new HashSet<>();

        var project = getProject();

        // Find all resolved dependencies (the "groupId:artifact:version" strings).
        // This includes all recursively resolved dependencies
        for (var configuration : project.getConfigurations()) {
            listDependencies(configuration);
            listArtifacts(configuration);
        }

        // Do the same for the buildscript classpath configuration (plugin dependencies)
        try {
            var classpath = project.getBuildscript().getConfigurations().getByName("classpath");
            listDependencies(classpath);
            listArtifacts(classpath);
        } catch (UnknownConfigurationException ignored) {}

        // Get all Maven repositories that were declared in the build file
        repositories.addAll(project.getRepositories().stream()
                .filter(repo -> repo instanceof MavenArtifactRepository)
                .map(repo -> new Repository(((MavenArtifactRepository) repo).getUrl().toString()))
                .toList()
        );

        // Get all Maven repositories that were declared in the settings file (pluginManagement block)
        var settings = ((GradleInternal) project.getGradle()).getSettings();
        repositories.addAll(settings.getPluginManagement().getRepositories().stream()
                .filter(repo -> repo instanceof MavenArtifactRepository)
                .map(repo -> new Repository(((MavenArtifactRepository) repo).getUrl().toString()))
                .toList()
        );

        // Get download locations (URLs) for the dependencies.
        // This seems impossible to achieve with just the Gradle plugin API.
        // As a workaround, we use the `DependencyResolver` class from `bld` to figure out what the URLs are.
        for (String name : dependencies) {
            // The dependency artifact can be in any of the declared repositories.
            // First, we generate download URLs for all repositories.
            var resolver = new DependencyResolver(
                    ArtifactRetriever.instance(),
                    repositories.stream().toList(),
                    Dependency.parse(name)
            );
            List<String> locations = resolver.getTransferLocations();

            // Next, we must determine in which repository the artifact is actually available.
            // We try a HTTP GET request for each URL, until one responds with a 200 OK.
            var location = getFirstResolvableLocation(locations);
            if (location == null) {
                // Cannot download anything for this dependency
                continue;
            }

            // Now put the URL in the HashMap. The file name is the key
            var filename = location.substring(location.lastIndexOf("/") + 1);
            transferLocations.put(filename, location);
        }

        // Generate the json blocks, and join them with a StringJoiner
        var joiner = new StringJoiner(",\n", "[\n", "\n]\n");
        var dest = getDownloadDirectory().getOrElse("localRepository");
        artifacts.forEach((fileName, sha512) -> {
            var url = transferLocations.get(fileName);
            if (url != null) {
                var spec = """
                      {
                        "type": "file",
                        "url": "%s",
                        "sha512": "%s",
                        "dest": "%s",
                        "dest-filename": "%s"
                      }"""
                        .formatted(url, sha512, dest, fileName);
                joiner.add(spec);
            }
        });

        // Write the results to the json file
        var fileName = getOutputFile().getAsFile().get();
        try (BufferedWriter output = new BufferedWriter(new FileWriter(fileName))) {
            output.write(joiner.toString());
        }
    }

    // Find all resolved dependencies (direct and transitive)
    private void listDependencies(Configuration configuration) {
        if (!configuration.isCanBeResolved())
            return;

        configuration
                .getIncoming()
                .getResolutionResult()
                .getAllDependencies()
                .stream()
                .filter(dependency -> dependency instanceof ResolvedDependencyResult)
                .forEach(result -> {
                    var dependency = (ResolvedDependencyResult) result;
                    String name = dependency.getSelected().getId().getDisplayName();
                    dependencies.add(name);
                });
    }

    // Find all resolved artifacts and calculate a SHA-512 hash
    private void listArtifacts(Configuration configuration) throws NoSuchAlgorithmException, IOException {
        if (!configuration.isCanBeResolved())
            return;

        // Gradle has already downloaded the artifacts into the build cache.
        // Calculate the SHA-512 hashes from the cached jar files.
        for (var artifact : configuration
                .getResolvedConfiguration()
                .getResolvedArtifacts()) {
            String name = artifact.getFile().getName();
            String sha512 = calculateSHA512(artifact.getFile());
            artifacts.put(name, sha512);
        }
    }

    // Loop through the possible locations (repository URLs), and return the first one that exists
    private static String getFirstResolvableLocation(List<String> locations) {
        for (var location : locations) {
            if (tryResolve(location)) {
                return location;
            }
        }
        // When none of the urls seem to work, return null
        return null;
    }

    // Check if the file in this URL exists, without downloading it
    private static boolean tryResolve(String url) {
        try {
            URL fileUrl = new URL(url);
            HttpURLConnection.setFollowRedirects(false);
            HttpURLConnection httpURLConnection = (HttpURLConnection) fileUrl.openConnection();

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

    // Generate a SHA-512 hash for a file using MessageDigest
    // Thanks ChatGPT
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
