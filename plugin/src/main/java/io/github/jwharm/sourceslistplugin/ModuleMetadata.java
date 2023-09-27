package io.github.jwharm.sourceslistplugin;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Helper class to parse module files
 */
final class ModuleMetadata {

    // Use getInstance()
    private ModuleMetadata() {
    }

    static ModuleMetadata getInstance() {
        return new ModuleMetadata();
    }

    /**
     * Parse the module json file and return the jar filename for the requested variant
     * @param contents the contents of the module file
     * @param variant the requested variant
     * @return the jar filename
     * @throws RedirectedException when the module redirects to another module file
     */
    public String process(String contents, String variant) throws RedirectedException {
        var gson = new Gson();
        var module = gson.fromJson(contents, ModuleDTO.class);

        var redirectUrl = module.variants.stream()
                .filter(v -> v.name.equals(variant))
                .filter(v -> v.attributes.category == null || v.attributes.category.equals("library"))
                .filter(v -> v.availableAt != null && v.availableAt.url != null)
                .map(v -> v.availableAt.url)
                .findFirst();
        if (redirectUrl.isPresent()) {
            throw new RedirectedException(redirectUrl.get());
        }

        // The module file contains sha-512 strings, but they are not always correct.
        // We return only the filename.

        return module.variants.stream()
                .filter(v -> v.name.equals(variant))
                .filter(v -> v.attributes.category == null || v.attributes.category.equals("library"))
                .filter(v -> v.files.size() == 1)
                .map(v -> v.files.get(0).name)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Thrown when a module redirects to another module with an "available-at" field
     */
    static class RedirectedException extends RuntimeException {

        private final String url;

        RedirectedException(String url) {
            this.url = url;
        }

        String url() {
            return url;
        }
    }

    private static class ModuleDTO {
            List<VariantDTO> variants;
    }

    private static class VariantDTO {
            String name;
            AttributeDTO attributes;
            @SerializedName(value="available-at") AvailableAtDTO availableAt;
            List<FileDTO> files;
    }

    private static class AttributeDTO {
            @SerializedName(value="org.gradle.category") String category;
    }

    private static class AvailableAtDTO {
            String url;
    }

    private static class FileDTO {
            String name;
    }
}
