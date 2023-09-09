# sources-list-plugin
A Gradle plugin to generate a sources file for use with Flatpak

**Currently work-in-progress and unfinished.**

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
  outputFile = file('location-of-output-file.json')
  downloadDirectory = 'local-directory'
}
```

Run `gradle sourcesList` and it will write a json file with information about the dependencies. For the above example:

```json
[
  {
    "type": "file",
    "url": "https://repo.maven.apache.org/maven2/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar"
    "sha512": "ac5879c0170b80106962881ed2d9a3d5f4b4ef0f6908806ab19c8418fab3b59e2dd7b5f03eb434544119c92c559bcab52a50dcac036b01ff4436c34411f80682"
    "dest": "localRepository"
    "dest-filename": "annotations-24.0.1.jar"
  }
]
```

The generated file can be used in a Flatpak build process to download Java dependencies before an offline build starts.
