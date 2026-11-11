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

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static java.util.stream.Collectors.joining;

/**
 * Helper class for resolving artifacts
 */
final class ArtifactResolver {
    private static final Logger LOGGER = Logging.getLogger(ArtifactResolver.class);

    private static final int  MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 500;
    private static final int  CONNECT_TIMEOUT_MS = 30_000;
    private static final int  READ_TIMEOUT_MS = 60_000;

    private final String dest;
    private final String onlyArches;
    private final ConcurrentHashMap<String, Boolean> checkedUrls;
    private final ConcurrentHashMap<String, byte[]> downloadedFiles;
    private final ConcurrentHashMap<String, String> output;
    private final Semaphore checksumComputationSemaphore;

    /**
     * Create a new ArtifactResolver.
     *
     * @param dest         the download directory
     * @param onlyArches   the supported architectures (optional)
     */
    ArtifactResolver(String dest, String onlyArches) {
        this.dest = dest;
        this.onlyArches = onlyArches;
        this.checkedUrls = new ConcurrentHashMap<>();
        this.downloadedFiles = new ConcurrentHashMap<>();
        this.output = new ConcurrentHashMap<>();
        this.checksumComputationSemaphore = new Semaphore(Runtime.getRuntime().availableProcessors(), false);
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
     */
    Optional<byte[]> tryResolve(DependencyDetails dep, String repository, String filename)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        // Build the url and try to download the file
        String url = repository + dep.path() + "/" + filename;
        var contents = getFileContentsFrom(url);

        if (contents.isPresent()) {
            String sha512 = calculateSHA512(new ByteArrayInputStream(contents.get()));
            String dest = dep.path();

            // If the filename contains a path, cut it out and append it to the dest field
            String destFilename = filename;
            if (filename.contains("/")) {
                dest += (filename.startsWith("/") ? "" : "/")
                        + filename.substring(0, filename.lastIndexOf("/"));
                destFilename = filename.substring(filename.lastIndexOf('/') + 1);
            }

            // Generate and append the JSON
            generateJsonBlock(url, sha512, dest, destFilename);

            // For snapshot versions, generate a second JSON with "-SNAPSHOT" instead of the actual filename
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
     * @param artifacts     resolved artifacts for the Gradle configuration containing the dependency
     * @param versionKey    the id of the dependency
     * @param dep           a DependencyDetail instance with the Maven coordinates of the artifact
     * @param repository    the repository to try to download from
     * @param fileUrl       the remote filename of the artifact
     * @param checkName     whether to verify if the cached file name matches fileUrl or fileName
     * @param fileName      the local filename of the artifact (as specified in module file)
     */
    void tryResolveCached(Map<String, File> artifacts, String versionKey, DependencyDetails dep,
                          String repository, String fileUrl, boolean checkName, String fileName)
            throws IOException, NoSuchAlgorithmException, InterruptedException {

        File file = artifacts.get(versionKey);
        if (file != null) {
            // Check against filenames in the .module file
            if (checkName)
                if (!(file.getName().equals(fileUrl) || file.getName().equals(fileName)))
                    return;

            // Skip files that are already included in the output
            if (output.containsKey(dep.path() + "/" + (checkName ? fileUrl : file.getName())))
                return;

            // Build the url and check if it exists
            String url = repository + dep.path() + "/" + file.getName();
            var isValid = isValid(url);

            // Try the filename from the .module
            if (!isValid) {
                url = repository + dep.path() + "/" + fileUrl;
                isValid = isValid(url);
            }

            if ((!isValid) && fileUrl.contains("SNAPSHOT")) {
                // Try again, but this time, replace SNAPSHOT with snapshot details
                url = repository + dep.path() + "/" + fileUrl.replace("SNAPSHOT", dep.snapshotDetail());
                isValid = isValid(url);
            }

            if (isValid) {
                // Read the file from the local Gradle cache and calculate the SHA-512 hash
                try (var fileInputStream = Files.newInputStream(file.toPath())) {
                    String sha512 = calculateSHA512(fileInputStream);
                    generateJsonBlock(url, sha512, dep.path(), checkName ? fileUrl : file.getName());
                }
            }
        }
    }

    // Test if the URL is valid (HTTP 200 OK response) and cache the result
    private boolean isValid(String url) {
        return checkedUrls.computeIfAbsent(url, this::testIsValid);
    }

    // Test if the URL is valid (HTTP 200 OK response)
    private boolean testIsValid(String url) {
        try {
            var httpURLConnection = (HttpURLConnection) new URI(url).toURL().openConnection();
            httpURLConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            httpURLConnection.setReadTimeout(READ_TIMEOUT_MS);
            httpURLConnection.setInstanceFollowRedirects(true);
            httpURLConnection.setRequestMethod("HEAD");
            return httpURLConnection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false; // URL couldn't be validated
        }
    }

    /**
     * Create a JSON string.
     *
     * @param url          the url
     * @param sha512       the hash
     * @param path         the path in the target directory
     * @param destFilename the filename
     */
    void generateJsonBlock(String url, String sha512, String path, String destFilename) {
        if (onlyArches == null || onlyArches.isBlank()) {
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
        } else {
            output.put(path + "/" + destFilename, """
                  {
                    "type": "file",
                    "url": "%s",
                    "sha512": "%s",
                    "dest": "%s",
                    "dest-filename": "%s",
                    "only-arches": ["%s"]
                """
                    .formatted(url, sha512, this.dest + path, destFilename, onlyArches)
                    + "  }");
        }
    }

    /**
     * Merge the JSON strings into one JSON list.
     *
     * @return the generated JSON list
     */
    String getJsonOutput() {
        return output.keySet().stream()
                .sorted()
                .map(output::get)
                .collect(joining(",\n", "[\n", "\n]\n"));
    }

    /**
     * Download the contents from the provided url into a byte array and cache
     * the results. Only successful downloads are cached; failures are retried
     * on subsequent calls.
     *
     * @param  url the url of the file to download
     * @return an {@link Optional} with the contents of the file,
     *         or {@link Optional#empty()} when the URL does not resolve
     */
    private Optional<byte[]> getFileContentsFrom(String url) {
        // Return cached successful download immediately
        byte[] cached = downloadedFiles.get(url);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Attempt download with retries
        var contents = getFileContentsWithRetry(url);

        // Only cache successful downloads — failures are not stored so they
        // can be retried by another thread processing the same dependency
        contents.ifPresent(bytes -> downloadedFiles.putIfAbsent(url, bytes));
        return contents;
    }

    /**
     * Download the contents from the provided url into a byte array, retrying
     * on transient failures.
     *
     * @param  url the url of the file to download
     * @return an {@link Optional} with the contents of the file,
     *         or {@link Optional#empty()} when the URL does not resolve
     */
    private Optional<byte[]> getFileContentsWithRetry(String url) {
        Exception lastException = null;
        int attempt;
        for (attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            var result = getFileContents(url);
            if (result.isPresent())
                return result.contents();

            if (!result.isNotFound())
                lastException = result.exception();

            // Only retry on transient errors (429 or 5xx) or network exceptions
            if (!result.isRetryable())
                break;

            if (attempt < MAX_RETRIES) {
                try {
                    long delay = result.retryAfterMs() > 0
                            ? result.retryAfterMs()
                            : RETRY_DELAY_MS * (1L << (attempt - 1)); // exponential backoff
                    LOGGER.warn("Retrying {} in {} ms (attempt {}/{})",
                            url, delay, attempt, MAX_RETRIES);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        if (lastException != null) {
            LOGGER.warn("Failed to download {} after {} attempt(s): {}",
                    url, Math.min(attempt, MAX_RETRIES), lastException.getMessage());
        }
        return Optional.empty();
    }

    // Result of a download attempt that preserves the exception on failure.
    private record DownloadResult(Optional<byte[]> contents, Exception exception, int httpStatus, long retryAfterMs) {
        DownloadResult(Optional<byte[]> contents, Exception exception) {
            this(contents, exception, -1, 0);
        }

        boolean isPresent() {
            return contents.isPresent();
        }

        boolean isNotFound() {
            return httpStatus == 404;
        }

        boolean isRetryable() {
            // Network exceptions are retryable
            if (httpStatus < 0) return exception != null;
            // 429 and 5xx are retryable; 4xx (like 404) are not
            return httpStatus == 429 || httpStatus >= 500;
        }
    }

    /**
     * Download the contents from the provided url into a byte array.
     *
     * @param  url the url of the file to download
     * @return a {@link DownloadResult} with the contents or the exception
     */
    private DownloadResult getFileContents(String url) {
        var outStream = new ByteArrayOutputStream();
        try {
            var connection = (HttpURLConnection) new URI(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                long retryAfter = 0;
                if (responseCode == 429) {
                    String retryHeader = connection.getHeaderField("Retry-After");
                    if (retryHeader != null) {
                        try {
                            retryAfter = Long.parseLong(retryHeader) * 1000L;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                connection.disconnect();
                return new DownloadResult(Optional.empty(),
                        new IOException("HTTP " + responseCode),
                        responseCode, retryAfter);
            }

            try (var inStream = new BufferedInputStream(connection.getInputStream())) {
                byte[] dataBuffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inStream.read(dataBuffer, 0, 8192)) != -1) {
                    outStream.write(dataBuffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            return new DownloadResult(Optional.empty(), e);
        }
        return new DownloadResult(Optional.of(outStream.toByteArray()), null);
    }

    /**
     * Generate an SHA-512 hash for a byte array using MessageDigest.
     *
     * @param  contentsInputStream the input stream for contents to compute hash of
     * @return the SHA-512 hash
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     * @throws InterruptedException interrupted during execution
     * @throws IOException error when reading input
     */
    private String calculateSHA512(InputStream contentsInputStream)
            throws NoSuchAlgorithmException, InterruptedException, IOException {
        byte[] buffer = new byte[4096];

        checksumComputationSemaphore.acquire();

        try {
            // Create a MessageDigest object for SHA-512
            MessageDigest md  = MessageDigest.getInstance("SHA-512");

            int numRead;
            do {
                numRead = contentsInputStream.read(buffer);
                if (numRead > 0) {
                    md.update(buffer, 0, numRead);
                }
            } while (numRead != -1);

            byte[] hashBytes = md.digest();

            // Convert the byte array to a hexadecimal string
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }

            // Return the SHA-512 hash as a string
            return sb.toString();
        } finally {
            checksumComputationSemaphore.release();
        }
    }
}
