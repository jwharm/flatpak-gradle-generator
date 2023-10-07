/* flatpak-gradle-generator - a Gradle plugin to generate a list of dependencies
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

package io.github.jwharm.flatpakgradlegenerator;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

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
     * Parse the module json file and return the artifact filenames for the requested variant
     * @param contents the contents of the module file
     * @param variant the requested variant
     * @return the filenames
     * @throws RedirectedException when the module redirects to another module file
     */
    public List<FileDTO> process(String contents, String variant) throws RedirectedException {
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
        // We return only the filenames and urls.

        return module.variants.stream()
                .filter(v -> v.attributes.category == null || v.attributes.category.equals("library"))
                .filter(v -> v.files != null)
                .filter(v -> ! v.files.isEmpty())
                .map(v -> v.files)
                .flatMap(List::stream)
                .distinct()
                .toList();
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

    static class ModuleDTO {
        List<VariantDTO> variants;
    }

    static class VariantDTO {
        String name;
        AttributeDTO attributes;
        @SerializedName(value="available-at") AvailableAtDTO availableAt;
        List<FileDTO> files;
    }

    static class AttributeDTO {
        @SerializedName(value="org.gradle.category") String category;
    }

    static class AvailableAtDTO {
        String url;
    }

    static class FileDTO {
        String name;
        String url;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FileDTO fileDTO = (FileDTO) o;
            return Objects.equals(url, fileDTO.url) && Objects.equals(name, fileDTO.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, name);
        }
    }
}
