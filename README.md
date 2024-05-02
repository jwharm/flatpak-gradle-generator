# flatpak-gradle-generator
A Gradle plugin to generate a sources file for offline Flatpak builds.

The plugin will output all direct and transitive dependencies for all build 
configurations (including plugin dependencies) in a JSON file. The JSON file 
can be used in a Flatpak build process to download Maven dependencies before 
an offline build starts. The downloaded files will be stored in a local Maven 
repository layout.

Example `gradle.build`:

```groovy
plugins {
  id 'application'
  id 'io.github.jwharm.flatpak-gradle-generator' version '1.1.0'
}

repositories {
  mavenCentral()
  maven { url './offline-repository' }
}

dependencies {
  // a random dependency for this example
  implementation 'io.github.jwharm.javagi:adw:0.7.1'
}

tasks.flatpakGradleGenerator {
  outputFile = file('flatpak-sources.json')
  downloadDirectory = './offline-repository'
}
```

Run `gradle flatpakGradleGenerator` and it will write a json file with 
information about the dependencies:

```
[
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/io/github/jwharm/javagi/adw/0.7.1/adw-0.7.1.module",
    "sha512": "d265d970864b3fb4c97b0fe87030ba76eafb252531d9da37cd7a51296b32e95bb70154f0075f6a0d0bc1e41fbd7f23280bdc6b317a1d5808c5a0c4b3a5ac70b5",
    "dest": "./offline-repository/io/github/jwharm/javagi/adw/0.7.1",
    "dest-filename": "adw-0.7.1.module"
  },
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/io/github/jwharm/javagi/adw/0.7.1/adw-0.7.1.jar",
    "sha512": "356a1c8f8ae89d7212bdfccd9afcd607ae86301485dd850d11eb378cbfe6f05f00cee27be368f907b0b941a065564f7ca3fb7ee18b21f4aaf8bec4d4176ba65a",
    "dest": "./offline-repository/io/github/jwharm/javagi/adw/0.7.1",
    "dest-filename": "adw-0.7.1.jar"
  },
  ...etc...
```

Add the JSON filename to the `sources` list in your Flatpak manifest, and 
flatpak-builder will automatically download all dependencies into the offline 
Maven repository folder.

### Modular builds
Because a task from one Gradle project [is not allowed](https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html#sub:resolving-unsafe-configuration-resolution-errors)
to directly resolve a configuration in another project, the plugin is limited
to the project context in which the `flatpakGradleGenerator` task is declared.

In a modular Gradle build, you can add a `tasks.flatpakGradleGenerator {}` 
block in the build files of the subprojects, to generate separate files for 
each project.

### Requirements
The plugin has been tested with Gradle 8.3, 8.4, 8.6 and 8.7. The published jar
is built with Java 17.
