package io.github.jwharm.sourceslistplugin;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Helper class for resolving artifacts
 */
final class ArtifactResolver {

    private final String dest;

    private ArtifactResolver(String dest) {
        this.dest = dest;
    }

    static ArtifactResolver getInstance(String dest) {
        return new ArtifactResolver(dest);
    }

    /**
     * Build an url and try to download the contents. If that succeeds, an SHA-512 hash is calclated and
     * the filename, URL, path and hash are added to the JSON list
     * @param dep a DependencyDetail instance with the Maven coordinates of the artifact
     * @param repository the repository to try to download from
     * @param filename the filename of the artifact
     * @param joiner the StringJoiner to add the json snippet to
     * @return an {@link Optional} with the contents of the file,
     *         or {@link Optional#empty()} when the URL does not resolve
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
     Optional<byte[]> tryResolve(DependencyDetails dep, String repository, String filename, StringJoiner joiner)
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
                dest += (filename.startsWith("/") ? "" : "/") + filename.substring(0, filename.lastIndexOf("/"));
                destFilename = filename.substring(filename.lastIndexOf('/') + 1);
            }

            // Generate and append the json
            generateJsonBlock(url, sha512, dest, destFilename, joiner);
        }
        // Return the file contents
        return contents;
    }

    /**
     * Build an url and check if it is valid, without downloading the entire file. If it is valid,
     * find the artifact in the local Gradle file, and use that file to calculate the SHA-512 hash
     * and add it to the JSON output.
     * @param configuration the Gradle configuration that contains the artifact
     * @param id the id of the dependency
     * @param dep a DependencyDetail instance with the Maven coordinates of the artifact
     * @param repository the repository to try to download from
     * @param filename the filename of the artifact
     * @param joiner the StringJoiner to add the json snippet to
     * @throws IOException error while reading the jar file
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
    void tryResolveCached(Configuration configuration, ModuleVersionIdentifier id, DependencyDetails dep,
                          String repository, String filename, StringJoiner joiner) throws IOException, NoSuchAlgorithmException {
        // Build the url and check if it exists
        String url = repository + dep.path() + "/" + filename;
        if (isValid(url)) {

            // Find the jar in the local Gradle cache
            for (var artifact : configuration.getResolvedConfiguration().getResolvedArtifacts()) {
                if (artifact.getModuleVersion().getId().equals(id)) {

                    // Read the file from the local Gradle cache and calculate the SHA-512 hash
                    File jar = artifact.getFile();
                    byte[] bytes = Files.readAllBytes(jar.toPath());
                    String sha512 = calculateSHA512(bytes);

                    // Generate and append the json
                    generateJsonBlock(url, sha512, dep.path(), jar.getName(), joiner);
                }
            }
        }
    }

    private boolean isValid(String url) {
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
     * Create a json string
     * @param url the url
     * @param sha512 the hash
     * @param path the path in the target directory
     * @param destFilename the filename
     * @param joiner the StringJoiner to add the json string to
     */
    void generateJsonBlock(String url, String sha512, String path, String destFilename, StringJoiner joiner) {
        joiner.add("""
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
     * Download the contents from the provided url into a byte array
     * @param url the url of the file to download
     * @return an {@link Optional} with the contents of the file,
     *         or {@link Optional#empty()} when the URL does not resolve
     */
    private Optional<byte[]> getFileContentsFrom(String url) {
        var outStream = new ByteArrayOutputStream();
        try {
            try (var inStream = new BufferedInputStream(new URI(url).toURL().openStream())) {
                byte[] dataBuffer = new byte[8192]; // Adjust the buffer size as needed
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
     * @param contents the input byte array
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
