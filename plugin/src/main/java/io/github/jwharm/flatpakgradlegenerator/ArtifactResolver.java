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

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Helper class for resolving artifacts
 */
final class ArtifactResolver {

    private final String dest;
    private final HashMap<String, String> output;

    private ArtifactResolver(String dest) {
        this.dest = dest;
        this.output = new HashMap<>();
    }

    static ArtifactResolver getInstance(String dest) {
        return new ArtifactResolver(dest);
    }

    /**
     * Build an url and try to download the contents. If that succeeds, an SHA-512 hash is
     * calculated and the filename, URL, path and hash are added to the JSON list.
     *
     * @param  dep        a DependencyDetail instance with the Maven coordinates of the artifact
     * @param  repository the repository to try to download from
     * @param  filename   the filename of the artifact
     *
     * @return an {@link Optional} with the contents of the file,
     *         or {@link Optional#empty()} when the URL does not resolve
     *
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
     Optional<byte[]> tryResolve(DependencyDetails dep,
                                 String repository,
                                 String filename)
            throws NoSuchAlgorithmException {

        // Build the url and try to download the file
        String url = repository + dep.path() + "/" + filename;
        var contents = getFileContentsFrom(url);

        if (contents.isPresent()) {
            String sha512 = calculateSHA512(contents.get());
            String dest = dep.path();

            // If the filename contains a path, cut it out and append it to the dest field
            String destFilename = filename;
            if (filename.contains("/")) {
                dest += (filename.startsWith("/") ? "" : "/")
                        + filename.substring(0, filename.lastIndexOf("/"));
                destFilename = filename.substring(filename.lastIndexOf('/') + 1);
            }

            // Generate and append the json
            generateJsonBlock(url, sha512, dest, destFilename);

            // For snapshot versions, generate a second json with "-SNAPSHOT" instead of the actual filename
            if (dep.isSnapshot()) {
                String ext = destFilename.substring(destFilename.lastIndexOf(".") + 1);
                destFilename = "%s-%s.%s".formatted(dep.name(), dep.version(), ext);
                generateJsonBlock(url, sha512, dest, destFilename);
            }
        }
        // Return the file contents
        return contents;
    }

    /**
     * Build an url and check if it is valid, without downloading the entire file. If it is valid,
     * find the artifact in the local Gradle file, and use that file to calculate the SHA-512 hash
     * and add it to the JSON output.
     *
     * @param  configuration the Gradle configuration that contains the artifact
     * @param  id            the id of the dependency
     * @param  dep           a DependencyDetail instance with the Maven coordinates of the artifact
     * @param  repository    the repository to try to download from
     * @param  filename      the filename of the artifact
     *
     * @throws IOException              error while reading the jar file
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
    void tryResolveCached(Configuration configuration,
                          ModuleVersionIdentifier id,
                          DependencyDetails dep,
                          String repository,
                          String filename,
                          boolean checkName,
                          String altName)
            throws IOException, NoSuchAlgorithmException {

        // Find the jar in the local Gradle cache
        Set<ResolvedArtifact> artifacts = configuration
                .getResolvedConfiguration()
                .getResolvedArtifacts();

        for (var artifact : artifacts) {
            if (artifact.getModuleVersion().getId().equals(id)) {
                File file = artifact.getFile();

                // Check against filenames in the .module file
                if (checkName)
                    if (! (file.getName().equals(filename) ||
                            file.getName().equals(altName)))
                        continue;

                // Skip files that are already included in the output
                if (output.containsKey(dep.path() + "/" + (checkName ? filename : file.getName())))
                    continue;

                // Build the url and check if it exists
                String url = repository
                        + dep.path()
                        + "/"
                        + file.getName();
                var isValid = isValid(url);

                if ((! isValid) && filename.contains("SNAPSHOT")) {
                    // Try again, but this time, replace SNAPSHOT with snapshot details
                    url = repository
                            + dep.path()
                            + "/"
                            + filename.replace("SNAPSHOT", dep.snapshotDetail());
                    isValid = isValid(url);
                }

                if (isValid) {
                    // Read the file from the local Gradle cache and calculate the SHA-512 hash
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String sha512 = calculateSHA512(bytes);

                    // Generate and append the json
                    generateJsonBlock(
                            url,
                            sha512,
                            dep.path(),
                            checkName ? filename : file.getName()
                    );
                }
            }
        }
    }

    // Test if the URL is valid (HTTP 200 OK response)
    private boolean isValid(String url) {
        try {
            var fileUrl = new URI(url).toURL();
            var httpURLConnection = (HttpURLConnection) fileUrl.openConnection();

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
     * Create a json string.
     *
     * @param url          the url
     * @param sha512       the hash
     * @param path         the path in the target directory
     * @param destFilename the filename
     */
    void generateJsonBlock(String url,
                           String sha512,
                           String path,
                           String destFilename) {
        output.put(path + "/" + destFilename, """
                  {
                    "type": "file",
                    "url": "%s",
                    "sha512": "%s",
                    "dest": "%s",
                    "dest-filename": "%s"
                """
                .formatted(url, sha512, this.dest + path, destFilename)
                + "  }");
    }

    /**
     * Merge the json strings into one json list
     *
     * @return the generated json list
     */
    String getJsonOutput() {
        return output.keySet().stream().sorted().map(output::get).collect(
                Collectors.joining(",\n", "[\n", "\n]\n"));
    }

    /**
     * Download the contents from the provided url into a byte array
     * @param url the url of the file to download
     * @return an {@link Optional} with the contents of the file,
     *         or {@link Optional#empty()} when the URL does not resolve
     */
    private Optional<byte[]> getFileContentsFrom(String url) {
        var outStream = new ByteArrayOutputStream();
        try {
            try (var inStream = new BufferedInputStream(new URI(url).toURL().openStream())) {
                byte[] dataBuffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inStream.read(dataBuffer, 0, 8192)) != -1) {
                    outStream.write(dataBuffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.of(outStream.toByteArray());
    }

    /**
     * Generate an SHA-512 hash for a byte array using MessageDigest.
     *
     * @param  contents the input byte array
     * @return the SHA-512 hash
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
    private String calculateSHA512(byte[] contents) throws NoSuchAlgorithmException {
        // Create a MessageDigest object for SHA-512
        MessageDigest md = MessageDigest.getInstance("SHA-512");

        // Update the MessageDigest with the bytes from the input String
        md.update(contents);
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
