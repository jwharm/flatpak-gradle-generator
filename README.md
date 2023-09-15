# sources-list-plugin
A Gradle plugin to generate a sources file for offline Flatpak builds.

Example `gradle.build`:

```groovy
plugins {
  id 'io.github.jwharm.sourceslistplugin' version '0.1'
  id 'application'
}

repositories {
  mavenCentral()
}

dependencies {
  // a random dependency for this example
  compileOnly 'org.jetbrains:annotations:24.0.1'
}

tasks.sourcesList {
  outputFile = file('flatpak-sources.json')
  downloadDirectory = 'lib'
}
```

Run `gradle sourcesList` and it will write a json file with information 
about the dependencies. For the above example:

```json
[
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar",
    "sha512": "ac5879c0170b80106962881ed2d9a3d5f4b4ef0f6908806ab19c8418fab3b59e2dd7b5f03eb434544119c92c559bcab52a50dcac036b01ff4436c34411f80682",
    "dest": "lib",
    "dest-filename": "annotations-24.0.1.jar"
  }
]
```

The plugin will output all direct and transitive dependencies for all 
build configurations, including plugin dependencies.

The generated file can be used in a Flatpak build process to download 
Maven dependencies before an offline build starts.

### Snapshot dependencies
Maven snapshot dependencies have a unique identifier in the filename. 
The default behavior of the plugin is to set the `"dest-filename"` field 
to this filename. If you want to set `"dest-filename"` to the more generic 
`library-SNAPSHOT.jar` name, set the option `actualJarName` to `false`.

### Modular builds
In a modular Gradle build, you can add a `tasks.sourcesList {}` block in 
the build files of the subprojects, to generate a file for each project.

### Output sorting
The JSON output is sorted by filename. This should make the output a bit 
more deterministic for the same set of dependencies.

### Requirements
The plugin has been tested with Gradle 8.3. The published jar is built 
with Java 17.

### Current status
- The plugin has not been published yet. To test it, clone the repository, 
  publish the plugin to mavenLocal, and load it from there (follow
  [these instructions](https://elmland.blog/2019/08/10/add-mavenlocal-to-gradle-plugin-resolution/)).
