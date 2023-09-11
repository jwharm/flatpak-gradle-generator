# sources-list-plugin
A Gradle plugin to generate a sources file for offline Flatpak builds.

Example `gradle.build`:

```groovy
plugins {
  id 'io.github.jwharm.sourceslistplugin'
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
  outputFile = file('sources-list.json')
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

### Current status
- The plugin has not been published yet. To test it, clone the repository 
  publish the plugin to mavenLocal, and load it from there (follow
  [these instructions](https://elmland.blog/2019/08/10/add-mavenlocal-to-gradle-plugin-resolution/)).

- There are a few issues when resolving snapshot dependencies.
