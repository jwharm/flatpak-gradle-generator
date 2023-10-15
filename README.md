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
  id 'io.github.jwharm.flatpak-gradle-generator' version '1.0.0'
}

repositories {
  mavenCentral()
  maven { url './maven-local' } // the offline repository
}

dependencies {
  // a random dependency for this example
  implementation 'io.github.jwharm.javagi:adw:0.7.1'
}

tasks.flatpakGradleGenerator {
  outputFile = file('flatpak-sources.json')
  downloadDirectory = './maven-local'
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
    "dest": "./maven-local/io/github/jwharm/javagi/adw/0.7.1",
    "dest-filename": "adw-0.7.1.module"
  },
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/io/github/jwharm/javagi/adw/0.7.1/adw-0.7.1.jar",
    "sha512": "356a1c8f8ae89d7212bdfccd9afcd607ae86301485dd850d11eb378cbfe6f05f00cee27be368f907b0b941a065564f7ca3fb7ee18b21f4aaf8bec4d4176ba65a",
    "dest": "./maven-local/io/github/jwharm/javagi/adw/0.7.1",
    "dest-filename": "adw-0.7.1.jar"
  },
  ...etc...
```

### Modular builds
In a modular Gradle build, you can add a `tasks.flatpakGradleGenerator {}` 
block in the build files of the subprojects, to generate a file for each 
project.

### Requirements
The plugin has been tested with Gradle 8.3 and 8.4. The published jar is built 
with Java 17.

### Current status
- The plugin has not been published yet. To test it, clone the repository, 
  publish the plugin to mavenLocal, and load it from there (follow
  [these instructions](https://elmland.blog/2019/08/10/add-mavenlocal-to-gradle-plugin-resolution/)).

