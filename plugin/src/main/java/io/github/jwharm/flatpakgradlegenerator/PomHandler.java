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

import javax.xml.stream.XMLInputFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Helper class to parse POM files for parent declarations and dependencies
 */
final class PomHandler {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    private final ArtifactResolver resolver;
    private final XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
    private HashMap<String, String> properties;

    // Use getInstance()
    private PomHandler(ArtifactResolver resolver) {
        this.resolver = resolver;
    }

    static PomHandler getInstance(ArtifactResolver resolver) {
        return new PomHandler(resolver);
    }

    /**
     * A POM can refer to a parent POM, which must be available in the local repository.
     * This method will parse the parent groupId:artifactId:version from the POM,
     * download it from the repository, and generate and append the json, recursively.
     * @param pom the contents of the POM file
     * @param repository the repository from which this POM was downloaded
     * @param joiner the StringJoiner to build the JSON sources list
     */
    void addParentPOMs(byte[] pom, String repository, StringJoiner joiner) {
        // Does this pom have a parent pom?
        parsePomForParentDetails(pom, repository, joiner);
    }

    /**
     * Parse an XML file to retrieve the groupId, artifactId and version elements from the parent element
     * @param contents XML file contents
     * @param repository the repository from which this POM was downloaded
     * @param joiner the StringJoiner to build the JSON sources list
     */
    private void parsePomForParentDetails(byte[] contents, String repository, StringJoiner joiner) {
        try {
            // Construct XML parser
            var reader = xmlInputFactory.createXMLEventReader(new ByteArrayInputStream(contents));

            // Buffer for characters inside elements
            StringBuilder characters = new StringBuilder();

            // This is used to construct the Maven coordinates
            StringBuilder groupId = new StringBuilder();
            StringBuilder artifactId = new StringBuilder();
            StringBuilder versionId = new StringBuilder();

            var xpath = new XPath();
            properties = new HashMap<>();

            // Parse the XML
            while (reader.hasNext()) {
                var nextEvent = reader.nextEvent();

                // Start element
                if (nextEvent.isStartElement()) {
                    xpath.add(nextEvent.asStartElement().getName().getLocalPart());
                    if (xpath.inParentElement() || xpath.inDependencyElement()) {
                        characters = new StringBuilder();
                    }
                }
                // Characters read
                else if (nextEvent.isCharacters()) {
                    characters.append(nextEvent.asCharacters().getData().strip());
                }
                // End element
                else if (nextEvent.isEndElement()) {
                    String name = nextEvent.asEndElement().getName().getLocalPart();
                    if (xpath.inProperties()) {
                        properties.put(name, characters.toString());
                    }
                    if (xpath.inParentElement() || xpath.inDependencyElement()) {
                        switch (name) {
                            case "groupId" -> groupId.append(parse(characters));
                            case "artifactId" -> artifactId.append(parse(characters));
                            case "version" -> versionId.append(parse(characters));
                            case "parent", "dependency" -> {
                                // We are leaving the parent element
                                // Get the Maven coordinates of the parent artifact
                                String id = "%s:%s:%s".formatted(groupId, artifactId, versionId);
                                var dep = DependencyDetails.of(id);

                                // Download and add the parent pom to the list
                                var parent = resolver.tryResolve(dep, repository, dep.filename("pom"), joiner);

                                // Reset string builders
                                groupId = new StringBuilder();
                                artifactId = new StringBuilder();
                                versionId = new StringBuilder();

                                // Recursively add the parent pom of the parent pom
                                if (name.equals("parent")) {
                                    parent.ifPresent(bytes -> addParentPOMs(bytes, repository, joiner));
                                }
                            }
                            default -> {} // ignored
                        }
                    }
                    // Reset the characters buffer
                    characters = new StringBuilder();
                    xpath.remove();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Replace "${property-name} references with the value of the property
     * @param raw the raw text from the XML
     * @return the text with property references replaced by their value
     */
    private String parse(Object raw) {
        var matcher = VAR_PATTERN.matcher(raw.toString());
        return matcher.find()
                ? parse(matcher.replaceFirst(properties.get(matcher.group(1))))
                : raw.toString();
    }

    /**
     * Small helper class to construct the path to the current XML element
     */
    private static class XPath {
        private final ArrayList<String> list = new ArrayList<>();

        void add(String element) {
            list.add(element);
        }

        void remove() {
            list.remove(list.size() - 1);
        }

        String current() {
            return "/" + String.join("/", list);
        }

        boolean inProperties() {
            return current().startsWith("/project/properties");
        }

        boolean inParentElement() {
            return current().startsWith("/project/parent");
        }

        boolean inDependencyElement() {
            return current().startsWith("/project/dependencyManagement/dependencies/dependency");
        }
    }
}
