# Crux Maven 插件集

Crux 是 Miaixz 项目使用的 Maven 插件合集，用于沉淀可复用的构建期工具。它关注 Maven 项目的发布元数据、生成 POM、版本占位符解析和发布流程一致性。

本仓库按插件套件组织：每个子目录都应是一个独立 Maven 插件模块，拥有自己的 `pom.xml`、目标、参数和发布元数据。

## 模块清单

| 模块 | 坐标 | 说明 | 状态 |
| --- | --- | --- | --- |
| [`groom-maven-plugin`](groom-maven-plugin) | `org.miaixz.maven:groom-maven-plugin` | 根据当前项目 POM 生成规范化的发布 POM；支持解析 CI Friendly 版本占位符、整理依赖元数据、保留必要发布信息，并可在后续生命周期中切换 Maven 使用生成后的 POM。 | 可用 |

## 环境要求

- JDK 21+
- Maven 3.9.11+

以上版本来自当前插件模块配置，是本地构建和发布流水线的预期基线。

## groom-maven-plugin

`groom-maven-plugin` 是当前插件集中的第一个插件，主要用于 Maven 发布场景下的 POM 规范化。

插件默认生成 `.groomed-pom.xml`。该文件面向发布使用，可以移除构建期细节、解析版本占位符、统一 XML 格式，并保留消费者需要的公开元数据。

### 目标

| 目标 | 说明 |
| --- | --- |
| `groom:groom` | 生成规范化后的发布 POM。 |
| `groom:normalize` | `groom:groom` 的别名，用于 POM 规范化流程。 |
| `groom:clean` | 删除生成的发布 POM，绑定 Maven `clean` 阶段。 |

### 直接执行

生成规范化 POM：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.3:groom
```

使用别名目标：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.3:normalize
```

删除生成文件：

```bash
mvn org.miaixz.maven:groom-maven-plugin:2.0.3:clean
```

### 插件配置

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

发布流水线如果需要让后续生命周期目标使用生成后的 POM，可以开启：

```bash
mvn verify -Dgroom.updatePomFile=true
```

### 常用参数

| 参数 / 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `groomMode` / `groom.mode` | 未设置 | 预定义 POM 元素处理模式。支持 `oss`、`ossrh`、`bom`、`defaults`、`clean`、`fatjar`、`resolveCiFriendliesOnly`。 |
| `groomDependencyMode` / `groom.dependency.mode` | 未设置 | 依赖整理模式。支持 `direct`、`inherited`、`all`。 |
| `groomedPomFilename` / `groom.pom.filename` | `.groomed-pom.xml` | 生成 POM 的文件名。 |
| `outputDirectory` | `${project.basedir}` | 生成 POM 的输出目录。 |
| `updatePomFile` / `groom.updatePomFile` | `false` | 是否让当前 `MavenProject` 在后续生命周期中使用生成后的 POM。 |
| `resolveProjectVersion` / `groom.resolveProjectVersion` | `false` | 在生成结果中解析 `${project.version}`、`${revision}`、`${bus.version}`、`${groom.version}` 占位符。 |
| `keepCommentsInPom` / `groom.dependency.keepComments` | `false` | 尽可能恢复源 POM 中的注释。 |
| `omitExclusions` | `false` | 是否从生成 POM 中省略依赖排除项。 |
| `skip` / `groom.skip` | `false` | 跳过插件的全部目标。 |
| `skipGroom` / `groom.groom.skip` | `false` | 仅跳过 `groom` 和 `normalize` 目标。 |
| `skipClean` / `groom.clean.skip` | `false` | 仅跳过 `clean` 目标。 |

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

向本插件集增加新 Maven 插件时建议遵循：

1. 新建 `<name>-maven-plugin` 模块目录。
2. 使用 `maven-plugin` 作为模块 `packaging`。
3. 在源码 Javadoc 中说明插件目标和参数，便于 Maven Plugin Tools 生成描述文件。
4. 在本 README 的模块清单中补充新插件。
5. 示例优先覆盖直接执行和绑定 Maven 生命周期两类用法。

## 许可证

本项目使用 Apache License, Version 2.0。详见 [`LICENSE`](LICENSE)。
