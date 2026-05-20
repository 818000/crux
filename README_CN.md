# Crux Maven 插件集

Crux 是 Miaixz 项目使用的 Maven 插件合集，用于沉淀可复用的构建期工具。它主要处理 Maven 项目的发布 POM、发布元数据和版本占位符。

本仓库按插件套件组织。每个子目录都是一个独立 Maven 插件模块，拥有自己的 `pom.xml`、目标、参数和发布元数据。

## 模块清单

| 模块 | 坐标 | 说明 | 状态 |
| --- | --- | --- | --- |
| [`groom-maven-plugin`](groom-maven-plugin) | `org.miaixz.maven:groom-maven-plugin` | 根据当前项目 POM 生成规范化的发布 POM；支持解析 CI Friendly 版本占位符、控制公开 POM 元数据、按需移除构建期内容，并可让后续 Maven 生命周期使用生成后的 POM。 | 可用 |

## 环境要求

- JDK 21+
- Maven 3.9.11+

以上版本是当前本地构建和发布流水线的基线。

## groom-maven-plugin

`groom-maven-plugin` 用于 Maven 发布场景下的 POM 规范化。它适合源码 POM 保留完整内部构建配置、发布 POM 只暴露消费者需要内容的项目。

插件默认在项目根目录生成 `.groomed-pom.xml`，并且默认让当前 `MavenProject` 在后续生命周期中使用这个生成后的 POM。因此 `install`、`deploy`、签名和中央仓库发布会使用规范化后的 POM；如果只想生成文件而不切换当前 Maven 构建，需要显式设置 `groom.updatePomFile=false`。

### 目标

| 目标 | 说明 |
| --- | --- |
| `groom:groom` | 生成规范化后的发布 POM。 |
| `groom:normalize` | `groom:groom` 的别名，用于发布 POM 规范化流程。 |
| `groom:clean` | 删除生成的发布 POM，绑定 Maven `clean` 阶段。 |
| `groom:native-image` | 合并多模块 GraalVM native-image 元数据，并重写主包和源码包中的 native-image 配置。 |

### 直接执行

生成规范化 POM：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:groom
```

使用别名目标：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:normalize
```

删除生成文件：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:clean
```

只生成发布 POM，不让当前 Maven 构建切换到生成后的 POM：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:normalize -Dgroom.updatePomFile=false
```

合并 native-image 元数据：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.7:native-image
```

### 标准配置

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

### Parent 发布

内部构建 Parent 在本地构建和 IDEA 导入时保留 active `build/plugins`，只在发布命令中移除：

```bash
mvn -Dgroom.updatePomFile=true -Dgroom.pomElements.buildPlugins=remove clean deploy
```

这样可以保证本地模块构建正常，同时确保发布到中央仓库的 Parent POM 只暴露 `pluginManagement` 和公开元数据。

### VERSION 文件支持

当插件通过 `<extensions>true</extensions>` 作为 Maven 扩展加载时，会在 Maven 模型解析前读取最近的仓库 `VERSION` 文件，并注入以下版本属性：

- `${revision}`
- `${bus.version}`
- `${groom.version}`

版本值必须符合 Maven 发布版本格式，例如 `8.6.8`、`2.0.7` 或 `2.0.7-RC1`。

### 项目表达式解析

当公开元数据中存在以下内容时，设置 `resolveProjectExpressions=true`：

```xml
<name>${project.artifactId}</name>
<version>${revision}</version>
```

插件会解析公开项目元数据字段，但不会解析构建插件配置中的表达式。这样既可以避免发布 POM 出现 `${project.artifactId}`、`${revision}` 这类占位符，也不会破坏本地构建插件配置。

### 常用参数

| 参数 / 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `groomMode` / `groom.mode` | `defaults` | 预定义 POM 元素处理模式。支持 `oss`、`ossrh`、`bom`、`defaults`、`clean`、`fatjar`、`resolveCiFriendliesOnly`。 |
| `groomDependencyMode` / `groom.dependency.mode` | 未设置 | 依赖整理模式。支持 `direct`、`inherited`、`all`。 |
| `groomedPomFilename` / `groom.pom.filename` | `.groomed-pom.xml` | 生成 POM 的文件名。 |
| `outputDirectory` | `${project.basedir}` | 生成 POM 的输出目录。 |
| `updatePomFile` / `groom.updatePomFile` | `true` | 是否让当前 `MavenProject` 在后续生命周期中使用生成后的 POM。 |
| `resolveProjectVersion` / `groom.resolveProjectVersion` | `false` | 在生成结果中解析 `${project.version}`、`${revision}`、`${bus.version}`、`${groom.version}`。 |
| `resolveProjectExpressions` / `groom.resolveProjectExpressions` | `false` | 解析公开元数据字段中的 Maven 项目表达式。 |
| `applyProjectElementRemovalsToPomPackaging` / `groom.applyProjectElementRemovalsToPomPackaging` | `true` | 对 `pom` 打包类型项目应用可移除 POM 元素处理规则。 |
| `pomElements` | 取决于模式 | 按元素控制生成 POM 的内容处理方式。 |
| `pomElements.buildPlugins` / `groom.pomElements.buildPlugins` | 取决于模式 | 控制 active `build/plugins`。本地 Parent 构建使用 `keep`，发布 Parent POM 使用 `remove`。 |
| `pomElements.compileScope` | 取决于模式 | 控制显式 `<scope>compile</scope>`。发布依赖中需要省略 compile scope 时使用 `remove`。 |
| `keepCommentsInPom` / `groom.dependency.keepComments` | `false` | 尽可能恢复源 POM 中的注释。 |
| `omitExclusions` | `false` | 是否从生成 POM 中省略依赖排除项。 |
| `skip` / `groom.skip` | `false` | 跳过插件的全部目标。 |
| `skipGroom` / `groom.groom.skip` | `false` | 仅跳过 `groom` 和 `normalize` 目标。 |
| `skipClean` / `groom.clean.skip` | `false` | 仅跳过 `clean` 目标。 |
| `excludedModules` / `groom.nativeImage.excludedModules` | `bus-all,bus-bom` | native-image 元数据扫描时排除的模块目录名。 |
| `skipNativeImage` / `groom.nativeImage.skip` | `false` | 仅跳过 `native-image` 目标。 |
| `rewriteJar` / `groom.nativeImage.rewriteJar` | `true` | 是否重写主包中的 native-image 元数据和 Manifest。 |
| `rewriteSourcesJar` / `groom.nativeImage.rewriteSourcesJar` | `true` | 是否重写源码包中的 native-image 元数据。 |

### 元素处理值

| 值 | 含义 |
| --- | --- |
| `flatten` | 对该元素应用默认整理行为。 |
| `expand` | 从 effective POM 获取元素内容。 |
| `resolve` | 从 resolved POM 获取元素内容。 |
| `interpolate` | 从插值后的 POM 获取元素内容。 |
| `extended_interpolate` | 使用 effective properties 插值，同时保留选定的构建路径表达式。 |
| `keep` | 原样复制源码 POM 中的元素。 |
| `remove` | 从生成 POM 中移除该元素。 |

## 构建本仓库

构建当前插件模块：

```bash
mvn -f groom-maven-plugin/pom.xml clean package
```

安装到本地 Maven 仓库：

```bash
mvn -f groom-maven-plugin/pom.xml clean install
```

生成插件帮助信息：

```bash
mvn -f groom-maven-plugin/pom.xml help:describe \
    -Dplugin=org.miaixz.maven:groom-maven-plugin \
    -Ddetail
```

## 新增插件约定

向本插件集增加新 Maven 插件时遵循：

1. 新建 `<name>-maven-plugin` 模块目录。
2. 使用 `maven-plugin` 作为模块 `packaging`。
3. 在源码 Javadoc 中说明插件目标和参数，便于 Maven Plugin Tools 生成描述文件。
4. 在本 README 的模块清单中补充新插件。
5. 示例覆盖直接执行和绑定 Maven 生命周期两类用法。

## 许可证

本项目使用 Apache License, Version 2.0。详见 [`LICENSE`](LICENSE)。
