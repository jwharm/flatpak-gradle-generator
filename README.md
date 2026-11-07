# flatpak-gradle-generator
A Gradle plugin to generate a dependency manifest for offline Flatpak builds.

The plugin will output all direct and transitive dependencies for all build 
configurations (including plugin dependencies) in a JSON file. The JSON file 
can be used in a Flatpak build process to download Maven dependencies before 
an offline build starts. The downloaded files will be stored in a local Maven 
repository layout.

Example `gradle.build`:

```groovy
plugins {
  id 'io.github.jwharm.flatpak-gradle-generator' version '1.9.0'
}

repositories {
  mavenCentral()
  maven { url './offline-repository' }
}

dependencies {
  // a random dependency for this example
  implementation 'commons-io:commons-io:2.14.0'
}

tasks.flatpakGradleGenerator {
  outputFile = file('flatpak-sources.json')
  downloadDirectory = './offline-repository'
}
```

Run `./gradlew flatpakGradleGenerator --no-configuration-cache` and it will
write a json file with information about the dependencies:

```
[
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/commons-io/commons-io/2.14.0/commons-io-2.14.0.jar",
    "sha512": "b98a5739f029d535207d50854a63c581334864e6021ec81ef960c23971d0b4fffaebbaee4830025a307d65b586017fe0ebc5901a2e5271f54fba401bde7f5e8b",
    "dest": "offline-repository/commons-io/commons-io/2.14.0",
    "dest-filename": "commons-io-2.14.0.jar"
  },
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/commons-io/commons-io/2.14.0/commons-io-2.14.0.pom",
    "sha512": "bf0b9feb8f7a64136a1d86967d86faf53e40b8d44186b6f09694f0dd06c5b2443c8c9b3b0a03f661b946c12bac82e70b0958497a96d5a4352ebe7bd55b969e77",
    "dest": "offline-repository/commons-io/commons-io/2.14.0",
    "dest-filename": "commons-io-2.14.0.pom"
  },
  ...etc...
```

Add the JSON filename to the `sources` list in your Flatpak manifest:

```yaml
    sources:
      - flatpak-sources.json
```

With this addition, flatpak-builder will automatically download all
dependencies into the offline Maven repository folder.

### Include and exclude configurations
Specific configurations can be included and excluded with the
`includeConfiguration` and `excludeConfiguration` options (the latter
overrides the former). For example, to exclude test dependencies:

```groovy
tasks.flatpakGradleGenerator {
    outputFile = file('flatpak-sources.json')
    excludeConfigurations = [
            'testCompileClasspath',
            'testRuntimeClasspath'
    ]
}
```

### Configuration cache
The `flatpakGradleGenerator` task is incompatible with the Gradle Configuration
Cache, so it needs to be run with the `--no-configuration-cache` command-line
argument for now. (I'm planning to fix this, but it requires some larger
changes in the plugin.)

You can still use the Configuration Cache for other tasks in your project.

### Modular builds
Because a task from one Gradle project [is not allowed](https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html#sub:resolving-unsafe-configuration-resolution-errors)
to directly resolve a configuration in another project, the plugin is limited
to the project context in which the `flatpakGradleGenerator` task is declared.

In a modular Gradle build, you can add a `tasks.flatpakGradleGenerator {}` 
block in the build files of the subprojects, to generate separate files for 
each project.

### Architecture-specific sources
Flatpak expects an `"only-arches"` identifier for architecture-specific
sources. Most Java libraries are architecture-independent, but some, like
JavaFX, contain different artifacts depending on the runtime platform. As far
as I know, it is not possible to automatically recognize these in Gradle.
However, as a workaround, we can generate json files with the `"only-arches"`
field with a fixed value (set as a plugin task parameter) to all entries in the
generated sources list.

To do this, run the `flatpakGradleGenerator` task on each platform with the
`"onlyArches"` parameter on the task set to a correct value such as `"x86_64"`,
to produce multiple files that explicitly only support one specific platform,
and then add all of those files as sources in the Flatpak manifest. Of course,
a majority of the dependencies will end up being the same in all generated
files, but they will be downloaded only once by `flatpak-builder`.

Check out the [`javafx` testcase](plugin/src/test/resources/javafx) for an
example on how to use the `"onlyArches"` parameter.

### Compatibility
The plugin has been tested with Gradle 8 and 9. The published jar is built
with Java 17.
