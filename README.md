# Crux Maven Plugins

Crux is a Maven plugin collection maintained for Miaixz projects. It provides reusable build-time tools for Maven projects that need deterministic publication POMs, release metadata, and version placeholder handling.

The repository is organized as a plugin suite. Each subdirectory is an independent Maven plugin module with its own `pom.xml`, goals, parameters, and release metadata.

## Modules

| Module | Artifact | Description | Status |
| --- | --- | --- | --- |
| [`groom-maven-plugin`](groom-maven-plugin) | `org.miaixz.maven:groom-maven-plugin` | Generates a normalized publication POM from the current project POM. It resolves CI-friendly version placeholders, controls public POM metadata, removes build-only content when requested, and can switch the current Maven build to the generated POM for later lifecycle phases. | Available |

## Requirements

- JDK 21+
- Maven 3.9.11+

These versions are the current baseline for local builds and release pipelines.

## groom-maven-plugin

`groom-maven-plugin` normalizes Maven POM files for publication. It is designed for projects that keep rich internal build configuration in source POMs but need a clean published POM for consumers.

By default, the plugin writes `.groomed-pom.xml` under the project base directory and updates the current `MavenProject` to use that generated POM for later lifecycle goals. This makes `install`, `deploy`, signing, and Central publishing consume the normalized POM unless `groom.updatePomFile=false` is explicitly set.

### Goals

| Goal | Description |
| --- | --- |
| `groom:groom` | Generates the groomed publication POM. |
| `groom:normalize` | Alias of `groom:groom` for publication normalization workflows. |
| `groom:clean` | Deletes the generated groomed POM. Bound to Maven's `clean` phase. |
| `groom:native-image` | Consolidates multi-module GraalVM native-image metadata and rewrites main and sources jar metadata. Supports legacy GraalVM 21 `*-config.json` files and GraalVM 25 `reachability-metadata.json` files. |

### Direct Usage

Generate a normalized POM:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:groom
```

Use the alias goal:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:normalize
```

Remove the generated POM:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:clean
```

Generate a publication POM without switching the current Maven build to it:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:normalize -Dgroom.updatePomFile=false
```

Consolidate native-image metadata:

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:native-image
```

### Standard Configuration

```xml
<plugin>
    <groupId>org.miaixz.maven</groupId>
    <artifactId>groom-maven-plugin</artifactId>
    <version>2.0.7</version>
    <extensions>true</extensions>
    <configuration>
        <outputDirectory>${project.build.directory}</outputDirectory>
        <groomedPomFilename>${project.artifactId}-${project.version}.pom</groomedPomFilename>
        <groomMode>resolveCiFriendliesOnly</groomMode>
        <groomDependencyMode>inherited</groomDependencyMode>
        <resolveProjectVersion>true</resolveProjectVersion>
        <resolveProjectExpressions>true</resolveProjectExpressions>
        <pomElements>
            <parent>keep</parent>
            <properties>keep</properties>
            <dependencies>keep</dependencies>
            <dependencyManagement>keep</dependencyManagement>
            <distributionManagement>remove</distributionManagement>
            <repositories>remove</repositories>
            <pluginRepositories>remove</pluginRepositories>
            <build>keep</build>
            <buildPlugins>keep</buildPlugins>
            <compileScope>remove</compileScope>
            <profiles>remove</profiles>
        </pomElements>
    </configuration>
    <executions>
        <execution>
            <id>module-publish-pom</id>
            <phase>process-resources</phase>
            <goals>
                <goal>normalize</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Parent Publication

For internal build parents, keep active `build/plugins` during local builds and IDEA imports, then remove them only in the publication command:

```bash
mvn -Dgroom.updatePomFile=true -Dgroom.pomElements.buildPlugins=remove clean deploy
```

This keeps local module builds functional while ensuring the published parent POM exposes only `pluginManagement` and public metadata.

### Version File Support

When the plugin is loaded as a Maven extension with `<extensions>true</extensions>`, it can read the nearest repository `VERSION` file before Maven model resolution. The value is injected into:

- `${revision}`
- `${bus.version}`
- `${groom.version}`

The value must match Maven release-style versions such as `8.6.8`, `2.0.7`, or `2.0.7-RC1`.

### Project Expression Resolution

Set `resolveProjectExpressions=true` when public metadata contains expressions such as:

```xml
<name>${project.artifactId}</name>
<version>${revision}</version>
```

The plugin resolves public project metadata fields while leaving build plugin configuration untouched. This prevents published POMs from exposing placeholders such as `${project.artifactId}` and `${revision}` while preserving local build configuration semantics.

### Common Parameters

| Parameter / Property | Default | Description |
| --- | --- | --- |
| `groomMode` / `groom.mode` | `defaults` | Predefined element handling mode. Supported values: `oss`, `ossrh`, `bom`, `defaults`, `clean`, `fatjar`, `resolveCiFriendliesOnly`. |
| `groomDependencyMode` / `groom.dependency.mode` | Not set | Dependency grooming mode. Supported values: `direct`, `inherited`, `all`. |
| `groomedPomFilename` / `groom.pom.filename` | `.groomed-pom.xml` | Output file name for the generated POM. |
| `outputDirectory` | `${project.basedir}` | Directory where the generated POM is written. |
| `updatePomFile` / `groom.updatePomFile` | `true` | Whether the generated POM replaces the current `MavenProject` POM for later lifecycle goals. |
| `resolveProjectVersion` / `groom.resolveProjectVersion` | `false` | Resolves `${project.version}`, `${revision}`, `${bus.version}`, and `${groom.version}` in generated output. |
| `resolveProjectExpressions` / `groom.resolveProjectExpressions` | `false` | Resolves Maven project expressions in public metadata fields. |
| `applyProjectElementRemovalsToPomPackaging` / `groom.applyProjectElementRemovalsToPomPackaging` | `true` | Applies removable POM element handling to projects packaged as `pom`. |
| `pomElements` | Mode dependent | Element-by-element handling for generated POM content. |
| `pomElements.buildPlugins` / `groom.pomElements.buildPlugins` | Mode dependent | Controls active `build/plugins`. Use `keep` for local parent builds and `remove` for published parent POMs. |
| `pomElements.compileScope` | Mode dependent | Controls explicit `<scope>compile</scope>` entries. Use `remove` to omit compile scope from published dependencies. |
| `keepCommentsInPom` / `groom.dependency.keepComments` | `false` | Restores comments from the source POM where possible. |
| `omitExclusions` | `false` | Omits dependency exclusion blocks from the generated POM. |
| `skip` / `groom.skip` | `false` | Skips all plugin goals. |
| `skipGroom` / `groom.groom.skip` | `false` | Skips only the `groom` and `normalize` goals. |
| `skipClean` / `groom.clean.skip` | `false` | Skips only the `clean` goal. |
| `excludedModules` / `groom.nativeImage.excludedModules` | `bus-all,bus-bom` | Module directory names excluded from native-image metadata scans. |
| `skipNativeImage` / `groom.nativeImage.skip` | `false` | Skips only the `native-image` goal. |
| `rewriteJar` / `groom.nativeImage.rewriteJar` | `true` | Rewrites native-image metadata and the manifest in the main jar. |
| `rewriteSourcesJar` / `groom.nativeImage.rewriteSourcesJar` | `true` | Rewrites native-image metadata in the sources jar. |

### Element Handling Values

| Value | Meaning |
| --- | --- |
| `flatten` | Applies default grooming behavior for the element. |
| `expand` | Takes the element from the effective POM. |
| `resolve` | Takes the element from the resolved POM. |
| `interpolate` | Takes the element from the interpolated POM. |
| `extended_interpolate` | Interpolates using effective properties while keeping selected build path expressions unresolved. |
| `keep` | Copies the original source POM element unchanged. |
| `remove` | Removes the element from the generated POM. |

## Building This Repository

Build the plugin module:

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
2. Use `maven-plugin` as the module `packaging`.
3. Keep plugin goals and parameters documented in source Javadocs so Maven Plugin Tools can generate descriptors.
4. Add the module to the table in this README.
5. Keep examples focused on direct Maven usage and lifecycle binding.

## License

This project is licensed under the Apache License, Version 2.0. See [`LICENSE`](LICENSE).
