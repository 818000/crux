# Crux Maven Plugins

Crux is a Maven plugin collection maintained for Miaixz projects. It packages reusable build-time tools that help Maven projects keep publication metadata, generated POM files, and release output consistent.

The repository is intentionally organized as a plugin suite: each subdirectory is expected to be an independent Maven plugin module with its own `pom.xml`, goals, parameters, and release metadata.

## Modules

| Module | Artifact | Description | Status |
| --- | --- | --- | --- |
| [`groom-maven-plugin`](groom-maven-plugin) | `org.miaixz.maven:groom-maven-plugin` | Generates a normalized publication POM from the current project POM. It can resolve CI-friendly version placeholders, flatten dependency metadata, keep selected public metadata, and optionally switch the current Maven build to the generated POM for later lifecycle phases. | Available |

## Requirements

- JDK 21+
- Maven 3.9.11+

These versions come from the current plugin module configuration and are the expected baseline for local builds and release pipelines.

## groom-maven-plugin

`groom-maven-plugin` is the first plugin in this collection. It is focused on POM normalization for Maven publication workflows.

The plugin writes a generated POM to `.groomed-pom.xml` by default. That generated POM is suitable for publication because it can remove build-only details, resolve version placeholders, normalize XML formatting, and preserve the metadata consumers need from the published artifact.

### Goals

| Goal | Description |
| --- | --- |
| `groom:groom` | Generates the groomed publication POM. |
| `groom:normalize` | Alias of `groom:groom`, kept for publication normalization workflows. |
| `groom:clean` | Deletes the generated groomed POM. Bound to Maven's `clean` phase. |

### Direct Usage

Generate a normalized POM:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.3:groom
```

Use the alias goal:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.3:normalize
```

Remove the generated POM:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.3:clean
```

### Plugin Configuration

```xml
<plugin>
    <groupId>org.miaixz.maven</groupId>
    <artifactId>groom-maven-plugin</artifactId>
    <version>2.0.3</version>
    <configuration>
        <groomMode>oss</groomMode>
        <groomDependencyMode>inherited</groomDependencyMode>
        <groomedPomFilename>.groomed-pom.xml</groomedPomFilename>
        <updatePomFile>false</updatePomFile>
    </configuration>
    <executions>
        <execution>
            <id>normalize-publication-pom</id>
            <phase>process-resources</phase>
            <goals>
                <goal>groom</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

For release pipelines that need later lifecycle goals to use the generated POM, enable:

```bash
mvn verify -Dgroom.updatePomFile=true
```

### Common Parameters

| Parameter / Property | Default | Description |
| --- | --- | --- |
| `groomMode` / `groom.mode` | Not set | Predefined element handling mode. Supported values: `oss`, `ossrh`, `bom`, `defaults`, `clean`, `fatjar`, `resolveCiFriendliesOnly`. |
| `groomDependencyMode` / `groom.dependency.mode` | Not set | Dependency flattening mode. Supported values: `direct`, `inherited`, `all`. |
| `groomedPomFilename` / `groom.pom.filename` | `.groomed-pom.xml` | Output file name for the generated POM. |
| `outputDirectory` | `${project.basedir}` | Directory where the generated POM is written. |
| `updatePomFile` / `groom.updatePomFile` | `false` | Whether the generated POM should replace the current `MavenProject` POM for later lifecycle goals. |
| `resolveProjectVersion` / `groom.resolveProjectVersion` | `false` | Resolves `${project.version}`, `${revision}`, `${bus.version}`, and `${groom.version}` placeholders in generated output. |
| `keepCommentsInPom` / `groom.dependency.keepComments` | `false` | Restores comments from the source POM where possible. |
| `omitExclusions` | `false` | Omits dependency exclusion blocks from the generated POM. |
| `skip` / `groom.skip` | `false` | Skips all goals in the plugin. |
| `skipGroom` / `groom.groom.skip` | `false` | Skips only the `groom` and `normalize` goals. |
| `skipClean` / `groom.clean.skip` | `false` | Skips only the `clean` goal. |

## Building This Repository

Build the available plugin module:

```bash
mvn -f groom-maven-plugin/pom.xml clean package
```

Install it into the local Maven repository:

```bash
mvn -f groom-maven-plugin/pom.xml clean install
```

Generate plugin help metadata:

```bash
mvn -f groom-maven-plugin/pom.xml help:describe \
    -Dplugin=org.miaixz.maven:groom-maven-plugin \
    -Ddetail
```

## Adding More Plugins

When adding a new Maven plugin to this suite:

1. Create a new module directory named `<name>-maven-plugin`.
2. Use `packaging` value `maven-plugin`.
3. Keep plugin goals and parameters documented in source Javadocs so Maven Plugin Tools can generate descriptors.
4. Add the module to the table in this README.
5. Keep examples focused on direct Maven usage and lifecycle binding.

## License

This project is licensed under the Apache License, Version 2.0. See [`LICENSE`](LICENSE).
