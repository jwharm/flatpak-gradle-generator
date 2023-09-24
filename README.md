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
  maven { url './maven-local' }
}

dependencies {
  // a random dependency for this example
  implementation 'org.jetbrains:annotations:24.0.1'
}

tasks.sourcesList {
  outputFile = file('flatpak-sources.json')
  downloadDirectory = './maven-local'
}
```

Run `gradle sourcesList` and it will write a json file with information 
about the dependencies.

The plugin will output all direct and transitive dependencies for all 
build configurations, including plugin dependencies.

The generated file can be used in a Flatpak build process to download 
Maven dependencies before an offline build starts. The downloaded 
artifacts are pom, jar and module files and will be stored in a 
Maven repository layout.

### Modular builds
In a modular Gradle build, you can add a `tasks.sourcesList {}` block in 
the build files of the subprojects, to generate a file for each project.

### Requirements
The plugin has been tested with Gradle 8.3. The published jar is built 
with Java 17.

### Current status
- The plugin has not been published yet. To test it, clone the repository, 
  publish the plugin to mavenLocal, and load it from there (follow
  [these instructions](https://elmland.blog/2019/08/10/add-mavenlocal-to-gradle-plugin-resolution/)).
- Parent poms are not added to the list.
