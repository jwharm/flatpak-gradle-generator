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
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.RegularFileProperty;
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

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getDownloadDirectory();

    @TaskAction
    public void apply() throws NoSuchAlgorithmException, IOException {
        Set<String> dependencies = new HashSet<>();
        Map<String, String> transferLocations = new HashMap<>();
        Map<String, String> artifacts = new HashMap<>();

        // Find all resolved dependencies (display names)
        for (var configuration : getProject().getConfigurations().stream()
                .filter(Configuration::isCanBeResolved)
                .toList()) {
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

            // Calculate SHA512 hashes
            for (var artifact : configuration
                    .getResolvedConfiguration()
                    .getResolvedArtifacts()) {
                String name = artifact.getFile().getName();
                String sha512 = calculateSHA512(artifact.getFile());
                artifacts.put(name, sha512);
            }
        }

        // Get all Maven repositories
        List<Repository> repositories = getProject().getRepositories().stream()
                .filter(repo -> repo instanceof MavenArtifactRepository)
                .map(repo -> new Repository(((MavenArtifactRepository) repo).getUrl().toString()))
                .toList();

        // Get download locations
        for (String name : dependencies) {
            // Get a download location for each repository
            var resolver = new DependencyResolver(ArtifactRetriever.instance(), repositories, Dependency.parse(name));
            List<String> locations = resolver.getTransferLocations();

            // Get the first location that exists
            var location = getFirstResolvableLocation(locations);
            if (location.isEmpty())
                continue;
            String url = location.get();

            var filename = url.substring(url.lastIndexOf("/") + 1);
            transferLocations.put(filename, url);
        }

        // Write the results to the file
        StringJoiner joiner = new StringJoiner(",\n", "[\n", "\n]\n");
        artifacts.forEach((fileName, sha512) -> {
            String spec = "  {\n" +
                    "    \"type\": \"file\",\n" +
                    "    \"url\": \"" + transferLocations.get(fileName) + "\"\n" +
                    "    \"sha512\": \"" + sha512 + "\"\n" +
                    "    \"dest\": \"" + getDownloadDirectory().getOrElse("localRepository") + "\"\n" +
                    "    \"dest-filename\": \"" + fileName + "\"\n" +
                    "  }";
            joiner.add(spec);
        });
        writeFile(getOutputFile().getAsFile().get(), joiner.toString());
    }

    // Loop through the possible locations and return the first one that exists
    private static Optional<String> getFirstResolvableLocation(List<String> locations) {
        for (var location : locations) {
            if (exists(location)) {
                return Optional.of(location);
            }
        }
        return Optional.empty();
    }

    // Check if the file in this URL exists, without downloading it
    private static boolean exists(String url) {
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
        } catch (IOException e) {
            return false; // File existence couldn't be determined
        }
    }

    // Generate a SHA-512 hash for a file using MessageDigest
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

    // Write String to file
    private void writeFile(File destination, String content) throws IOException {
        try (BufferedWriter output = new BufferedWriter(new FileWriter(destination))) {
            output.write(content);
        }
    }
}
