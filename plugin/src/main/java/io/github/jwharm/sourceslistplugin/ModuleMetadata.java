package io.github.jwharm.sourceslistplugin;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;

final class ModuleMetadata {

    // Use getInstance()
    private ModuleMetadata() {
    }

    static ModuleMetadata getInstance() {
        return new ModuleMetadata();
    }

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

    static class RedirectedException extends RuntimeException {

        private final String url;

        RedirectedException(String url) {
            this.url = url;
        }

        String url() {
            return url;
        }
    }

    private record ModuleDTO(
            ComponentDTO component,
            List<VariantDTO> variants) {
    }
    private record ComponentDTO(
            String group,
            String module,
            String version) {
    }
    private record VariantDTO(
            String name,
            AttributeDTO attributes,
            @SerializedName(value="available-at")AvailableAtDTO availableAt,
            List<FileDTO> files) {
    }
    private record AttributeDTO(
            @SerializedName(value="org.gradle.category") String category) {
    }
    private record AvailableAtDTO(
            String url) {
    }
    private record FileDTO(
            String name,
            String url,
            String sha512) {
    }
}
