package io.github.jwharm.sourceslistplugin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class TestModuleMetadata {

    @Test
    public void testModuleMetadata() throws IOException {
        String contents = Files.readString(Path.of("/home/jw/Downloads/kotlin-gradle-plugin-1.8.10.module"));
        var mod = ModuleMetadata.getInstance();
        var result = mod.process(contents, "gradle76ApiElements");
        assertEquals("kotlin-gradle-plugin-1.8.10-gradle76.jar", result.destFilename());
    }
}
