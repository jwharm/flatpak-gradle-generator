package io.github.jwharm.sourceslistplugin;

import javax.xml.stream.XMLInputFactory;
import java.io.ByteArrayInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.StringJoiner;

final class ParentPOM {

    private final ArtifactResolver resolver;

    // Use getInstance()
    private ParentPOM(ArtifactResolver resolver) {
        this.resolver = resolver;
    }

    static ParentPOM getInstance(ArtifactResolver resolver) {
        return new ParentPOM(resolver);
    }

    /**
     * A POM can refer to a parent POM, which must be available in the local repository.
     * This method will parse the parent groupId:artifactId:version from the POM,
     * download it from the repository, and generate and append the json, recursively.
     * @param pom the contents of the POM file
     * @param repository the repository from which this POM was downloaded
     * @param joiner the StringJoiner to build the JSON sources list
     * @throws NoSuchAlgorithmException no provider for the SHA-512 algorithm
     */
    void addParentPOMs(byte[] pom, String repository, StringJoiner joiner) throws NoSuchAlgorithmException {
        // Does this pom have a parent pom?
        var parent = parsePomForParentDetails(pom);
        if (parent.isPresent()) {
            // Download and add the parent pom to the list
            var contents = resolver.tryResolve(parent.get(), repository, parent.get().filename("pom"), joiner);

            // Recursively add the parent pom of the parent pom
            if (contents.isPresent())
                addParentPOMs(contents.get(), repository, joiner);
        }
    }

    /**
     * Parse an XML file to retrieve the groupId, artifactId and version elements from the parent element
     * @param contents XML file contents
     * @return a record with the Maven coordinates of the parent pom
     */
    private Optional<DependencyDetails> parsePomForParentDetails(byte[] contents) {
        try {
            // Construct XML parser
            var xmlInputFactory = XMLInputFactory.newInstance();
            var reader = xmlInputFactory.createXMLEventReader(new ByteArrayInputStream(contents));

            // Flag that we are in a parent element
            boolean inParentElement = false;

            // Buffer for characters inside elements
            StringBuilder characters = new StringBuilder();

            // This is used to construct the Maven coordinates
            StringBuilder id = new StringBuilder();

            // Name of the current element
            String name;

            // Parse the XML
            while (reader.hasNext()) {
                var nextEvent = reader.nextEvent();

                // Start element
                if (nextEvent.isStartElement()) {
                    name = nextEvent.asStartElement().getName().getLocalPart();
                    if ((!inParentElement) && name.equals("parent")) {
                        inParentElement = true;
                    } else if (inParentElement) {
                        characters = new StringBuilder();
                    }
                }
                // Characters read
                else if (inParentElement && nextEvent.isCharacters()) {
                    characters.append(nextEvent.asCharacters().getData().strip());
                }
                // End element
                else if (nextEvent.isEndElement()) {
                    name = nextEvent.asEndElement().getName().getLocalPart();
                    if (inParentElement) {
                        switch (name) {
                            case "groupId" -> id.append(characters);
                            case "artifactId", "version" -> id.append(":").append(characters);
                            case "parent" -> {
                                // We are leaving the parent element
                                // Return the Maven coordinates of the parent artifact
                                return Optional.of(DependencyDetails.of(id.toString()));
                            }
                            default -> {} // ignored
                        }
                        // Reset the characters buffer
                        characters = new StringBuilder();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // No parent element found
        return Optional.empty();
    }
}
