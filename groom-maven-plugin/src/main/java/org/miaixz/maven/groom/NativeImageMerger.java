/*
 ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~
 ~                                                                           ~
 ~ Copyright (c) 2015-2026 miaixz.org and other contributors.                ~
 ~                                                                           ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");           ~
 ~ you may not use this file except in compliance with the License.          ~
 ~ You may obtain a copy of the License at                                   ~
 ~                                                                           ~
 ~      https://www.apache.org/licenses/LICENSE-2.0                          ~
 ~                                                                           ~
 ~ Unless required by applicable law or agreed to in writing, software       ~
 ~ distributed under the License is distributed on an "AS IS" BASIS,         ~
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  ~
 ~ See the License for the specific language governing permissions and       ~
 ~ limitations under the License.                                            ~
 ~                                                                           ~
 ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~
*/
package org.miaixz.maven.groom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.plugin.logging.Log;

/**
 * GraalVM Native Image Configuration Consolidator
 *
 * An enterprise-grade Java implementation for cross-platform consolidation of GraalVM Native Image configuration
 * artifacts. This sophisticated utility dynamically discovers and merges all native-image configuration files from
 * multiple bus-* modules into a unified bus-all distribution package.
 *
 * Core Capabilities: - Dynamic discovery and consolidation of all configuration file types - Intelligent artifact
 * merging with advanced deduplication algorithms - Adaptive JSON structure preservation and validation - Cross-platform
 * compatibility (Windows, macOS, Linux) - Automatic project metadata detection and version management - Extensible
 * architecture supporting arbitrary configuration types
 *
 * Architecture: This consolidator follows a modular design pattern with clear separation of concerns: - Discovery
 * Layer: Dynamic project and artifact detection - Processing Layer: Configuration parsing and merging logic -
 * Validation Layer: Structure preservation and integrity checks - Output Layer: Standardized file generation and
 * metadata management
 *
 * Usage Examples:
 *
 * <pre>
 * // Maven integration (recommended):
 * {@code <java classname="org.miaixz.bus.Nativex"
 *           classpath="${project.build.outputDirectory}"
 *           fork="true"
 *           failonerror="false">
 *     <arg value="${project.version}" />
 * </java>}
 *
 * // Direct execution:
 * {@code
 * java - cp < classpath > org.miaixz.bus.Nativex[version]
 * }
 * </pre>
 *
 * @see <a href="https://graalvm.org/latest/reference/native-image/BuildConfiguration/">GraalVM Native Image
 *      Configuration</a>
 *
 * @author Kimi Liu
 * @since Java 21+
 */
final class NativeImageMerger {

    /**
     * The GraalVM serialization configuration file name.
     */
    private static final String SERIALIZATION_CONFIG = "serialization-config.json";

    /**
     * The GraalVM 25 unified reachability metadata file name.
     */
    private static final String REACHABILITY_METADATA_CONFIG = "reachability-metadata.json";

    /**
     * The GraalVM 25 reachability metadata serialization section name.
     */
    private static final String REACHABILITY_SERIALIZATION_SECTION = "serialization";

    /**
     * Ordered top-level sections used by GraalVM serialization configuration metadata.
     */
    private static final List<String> SERIALIZATION_SECTIONS = List.of("types", "lambdaCapturingTypes", "proxies");

    /**
     * Ordered top-level array sections used by GraalVM 25 reachability metadata.
     */
    private static final List<String> REACHABILITY_ARRAY_SECTIONS = List.of("reflection", "resources");

    /**
     * Ordered foreign function metadata sections used by GraalVM 25 reachability metadata.
     */
    private static final List<String> FOREIGN_SECTIONS = List.of("downcalls", "upcalls", "directUpcalls");

    /**
     * Matcher used to read the native-image default-for selector from module metadata.
     */
    private static final Pattern DEFAULT_FOR_PROPERTY_PATTERN = Pattern.compile("\"default-for\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Root directory that contains the module directories to scan.
     */
    private final Path projectRootDirectory;

    /**
     * Base output directory for consolidated native-image configurations.
     */
    private final Path outputBaseDirectory;

    /**
     * Fully qualified module identifier for metadata.
     */
    private final String moduleIdentifier;

    /**
     * Module directory names excluded from the consolidation scan.
     */
    private final Set<String> excludedModules;

    /**
     * Maven logger used for status output.
     */
    private final Log log;

    /**
     * Constructs a new native-image configuration merger.
     *
     * @param projectRootDirectory the root directory that contains module directories
     * @param outputBaseDirectory the output directory for the consolidated native-image metadata
     * @param moduleIdentifier the consolidated module identifier
     * @param excludedModules the module directory names excluded from scans
     * @param log the Maven logger used for status output
     */
    NativeImageMerger(
            Path projectRootDirectory,
            Path outputBaseDirectory,
            String moduleIdentifier,
            Set<String> excludedModules,
            Log log) {
        this.projectRootDirectory = projectRootDirectory;
        this.outputBaseDirectory = outputBaseDirectory;
        this.moduleIdentifier = moduleIdentifier;
        this.excludedModules = new LinkedHashSet<>(excludedModules);
        this.log = log;
    }

    /**
     * Executes the complete configuration consolidation workflow.
     *
     * @param targetVersion the target version to process, or null to process all versions
     * @throws IOException if file operations fail
     */
    void execute(String targetVersion) throws IOException {
        Set<String> allVersions = discoverAvailableVersions();
        boolean includeUnversionedMetadata = targetVersion != null && hasUnversionedConfigurationFiles();
        if (includeUnversionedMetadata && !allVersions.contains(targetVersion)) {
            allVersions = new LinkedHashSet<>(allVersions);
            allVersions.add(targetVersion);
        }
        log.info("Discovered versions: " + String.join(", ", allVersions));

        Set<String> versionsToProcess = determineVersionsToProcess(allVersions, targetVersion);

        boolean includeVersionedDependencyMetadata = targetVersion != null;

        for (String version : versionsToProcess) {
            log.info("=== Processing version " + version + " ===");
            processVersion(
                    version,
                    includeUnversionedMetadata && Objects.equals(version, targetVersion),
                    includeVersionedDependencyMetadata && Objects.equals(version, targetVersion));
        }

        generateTopIndex(versionsToProcess, targetVersion);
    }

    /**
     * Discovers all available versions by scanning for version directories across all modules.
     *
     * @return a sorted set of version strings (highest version first)
     * @throws IOException if file system access fails
     */
    private Set<String> discoverAvailableVersions() throws IOException {
        Set<String> versions = new LinkedHashSet<>();
        Path projectRoot = projectRootDirectory;

        try (java.util.stream.Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile).filter(this::isNativeImageConfigurationFile)
                    .map(this::metadataVersion).flatMap(Optional::stream).forEach(versions::add);
        }

        try (java.util.stream.Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isDirectory).filter(path -> path.toString().contains("native-image")).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.matches("^\\d+(\\.\\d+)*$")) {
                    versions.add(name);
                }
            });
        }

        return versions.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Processes a specific version by consolidating all configuration files for that version.
     *
     * @param version the version to process
     * @param includeUnversionedMetadata whether direct native-image metadata should be processed as this version
     * @throws IOException if file operations fail
     */
    private void processVersion(
            String version, boolean includeUnversionedMetadata, boolean includeVersionedDependencyMetadata)
            throws IOException {
        Path outputPath = outputBaseDirectory.resolve(version);
        Files.createDirectories(outputPath);

        log.info("Consolidating native-image configurations for version " + version + "...");

        Set<String> configurationTypes =
                discoverConfigurationFileTypes(version, includeUnversionedMetadata, includeVersionedDependencyMetadata);
        log.info("Discovered configuration types: " + String.join(", ", configurationTypes));

        for (String configType : configurationTypes) {
            List<Path> configFiles = locateConfigurationFiles(
                    version, configType, includeUnversionedMetadata, includeVersionedDependencyMetadata);

            if (configFiles.isEmpty()) {
                log.info("No " + configType + " files discovered");
                continue;
            }

            String consolidatedContent = consolidateConfigurationFiles(configFiles, configType);
            Files.writeString(outputPath.resolve(configType), consolidatedContent);

            log.info("Consolidated " + configFiles.size() + " " + configType + " files");
        }

        generateVersionIndex(outputPath.resolve("index.json"), configurationTypes);

        // Generate consolidation statistics
        displayConsolidationStatistics(version, outputPath, configurationTypes);
    }

    /**
     * Discovers all configuration file types for a specific version.
     *
     * @param version the version to scan for configuration files
     * @param includeUnversionedMetadata whether direct native-image metadata should be included
     * @return a sorted set of configuration file names (alphabetical order)
     * @throws IOException if file system access fails
     */
    private Set<String> discoverConfigurationFileTypes(
            String version, boolean includeUnversionedMetadata, boolean includeVersionedDependencyMetadata)
            throws IOException {
        Set<String> configTypes = new LinkedHashSet<>();
        Path projectRoot = projectRootDirectory;

        try (java.util.stream.Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile).filter(path -> path.toString().contains("native-image"))
                    .filter(path -> matchesMetadataVersion(
                            path, version, includeUnversionedMetadata, includeVersionedDependencyMetadata))
                    .filter(path -> !shouldExcludeModule(path))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("index.json"))
                    .forEach(path -> configTypes.add(path.getFileName().toString()));
        }

        return configTypes.stream().sorted().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Locates all configuration files of a specific type for a given version.
     *
     * @param version        the version to search for
     * @param configFileName the configuration file name to locate
     * @param includeUnversionedMetadata whether direct native-image metadata should be included
     * @return a list of paths to the discovered configuration files
     * @throws IOException if file system access fails
     */
    private List<Path> locateConfigurationFiles(
            String version,
            String configFileName,
            boolean includeUnversionedMetadata,
            boolean includeVersionedDependencyMetadata)
            throws IOException {
        List<Path> files = new ArrayList<>();
        Path projectRoot = projectRootDirectory;

        try (java.util.stream.Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile).filter(path -> path.toString().contains("native-image"))
                    .filter(path -> matchesMetadataVersion(
                            path, version, includeUnversionedMetadata, includeVersionedDependencyMetadata))
                    .filter(path -> path.getFileName().toString().equals(configFileName))
                    .filter(path -> !shouldExcludeModule(path)).forEach(files::add);
        }

        return files;
    }

    /**
     * Tests whether direct native-image metadata files exist without a metadata version directory.
     *
     * @return true when unversioned native-image configuration files are present
     * @throws IOException if file system access fails
     */
    private boolean hasUnversionedConfigurationFiles() throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(projectRootDirectory)) {
            return paths.filter(Files::isRegularFile).anyMatch(path -> isNativeImageConfigurationFile(path)
                    && metadataVersion(path).isEmpty());
        }
    }

    /**
     * Tests whether a path is a native-image JSON configuration file.
     *
     * @param path the path to evaluate
     * @return true when the path is a native-image JSON configuration file
     */
    private boolean isNativeImageConfigurationFile(Path path) {
        return path.toString().contains("native-image")
                && path.getFileName().toString().endsWith(".json")
                && !path.getFileName().toString().equals("index.json")
                && !shouldExcludeModule(path);
    }

    /**
     * Tests whether a native-image file belongs to the requested metadata version.
     *
     * @param path the path to evaluate
     * @param version the metadata version being processed
     * @param includeUnversionedMetadata whether direct native-image metadata should be included
     * @return true when the path should be included for the requested version
     */
    private boolean matchesMetadataVersion(
            Path path, String version, boolean includeUnversionedMetadata, boolean includeVersionedDependencyMetadata) {
        Optional<String> pathVersion = metadataVersion(path);
        if (pathVersion.isPresent()) {
            String normalizedPath = path.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (includeVersionedDependencyMetadata && !normalizedPath.contains("/native-image/org.miaixz/")) {
                return true;
            }
            return pathVersion.get().equals(version);
        }
        return path.toString().contains(version)
                || includeUnversionedMetadata && isNativeImageConfigurationFile(path);
    }

    /**
     * Extracts the metadata version segment from a native-image path.
     *
     * @param path the native-image configuration path
     * @return the metadata version segment, or empty when the file is placed directly under the module metadata path
     */
    private Optional<String> metadataVersion(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (int i = 0; i < normalized.getNameCount(); i++) {
            if (!"native-image".equals(normalized.getName(i).toString())) {
                continue;
            }
            int versionIndex = i + 3;
            if (versionIndex < normalized.getNameCount() - 1) {
                return Optional.of(normalized.getName(versionIndex).toString());
            }
        }
        return Optional.empty();
    }

    /**
     * Determines if a module path should be excluded from processing. Excludes the current module and aggregated
     * modules to prevent circular dependencies.
     *
     * @param path the path to evaluate
     * @return true if the path should be excluded, false otherwise
     */
    private boolean shouldExcludeModule(Path path) {
        Path relativePath = projectRootDirectory.relativize(path.toAbsolutePath().normalize());
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        String moduleName = relativePath.getName(0).toString();
        return excludedModules.contains(moduleName);
    }

    /**
     * Consolidates configuration files using appropriate merging strategy.
     *
     * @param files      list of configuration files to consolidate
     * @param configType the type/name of the configuration file
     * @return consolidated configuration content as a string
     */
    private String consolidateConfigurationFiles(List<Path> files, String configType) {
        if (REACHABILITY_METADATA_CONFIG.equals(configType)) {
            return consolidateReachabilityMetadataConfigurations(files);
        } else if (configType.contains("resource-config")) {
            return consolidateResourceConfigurations(files);
        } else if (SERIALIZATION_CONFIG.equals(configType)) {
            return consolidateSerializationConfigurations(files);
        } else {
            // Default to array merging for most JSON configuration files
            return consolidateJsonArrayConfigurations(files);
        }
    }

    /**
     * Consolidates GraalVM 25 unified reachability metadata files.
     * <p>
     * Unlike the legacy {@code *-config.json} files, {@code reachability-metadata.json} is a single object whose
     * top-level fields contain arrays of metadata entries. Treating the whole file as an array entry produces invalid
     * nested metadata, so it needs a dedicated merge path.
     * </p>
     *
     * @param files list of reachability-metadata.json files to consolidate
     * @return consolidated reachability metadata content
     */
    private String consolidateReachabilityMetadataConfigurations(List<Path> files) {
        ReachabilityMetadata metadata = new ReachabilityMetadata();

        for (Path file : files) {
            try {
                String content = Files.readString(file).trim();
                if (content.isEmpty()) {
                    continue;
                }
                mergeReachabilityArraySections(content, metadata);
                mergeSerializationReachabilitySection(content, metadata);
                mergeJniReachabilitySection(content, metadata);
                mergeForeignReachabilitySection(content, metadata);
            } catch (IOException e) {
                log.warn("Warning: Could not process " + file + ": " + e.getMessage());
            }
        }

        return formatReachabilityMetadata(metadata);
    }

    /**
     * Merges direct top-level array sections from a reachability metadata object.
     *
     * @param content  reachability metadata content
     * @param metadata target metadata accumulator
     */
    private void mergeReachabilityArraySections(String content, ReachabilityMetadata metadata) {
        for (String section : REACHABILITY_ARRAY_SECTIONS) {
            String arrayContent = extractNamedArrayContent(content, section);
            if (arrayContent == null || arrayContent.isEmpty()) {
                continue;
            }
            for (String object : extractJsonObjects(arrayContent)) {
                metadata.add(section, normalizeReachabilityMetadataObject(object));
            }
        }
    }

    /**
     * Merges legacy top-level serialization entries into GraalVM 25 reflection metadata.
     *
     * @param content  reachability metadata content
     * @param metadata target metadata accumulator
     */
    private void mergeSerializationReachabilitySection(String content, ReachabilityMetadata metadata) {
        String arrayContent = extractNamedArrayContent(content, REACHABILITY_SERIALIZATION_SECTION);
        if (arrayContent == null || arrayContent.isEmpty()) {
            return;
        }
        for (String object : extractJsonObjects(arrayContent)) {
            metadata.add("reflection", normalizeSerializationReachabilityMetadataObject(object));
        }
    }

    /**
     * Merges the accepted {@code jni} section into {@code reflection} entries marked as JNI-accessible.
     *
     * @param content  reachability metadata content
     * @param metadata target metadata accumulator
     */
    private void mergeJniReachabilitySection(String content, ReachabilityMetadata metadata) {
        String arrayContent = extractNamedArrayContent(content, "jni");
        if (arrayContent == null || arrayContent.isEmpty()) {
            return;
        }
        for (String object : extractJsonObjects(arrayContent)) {
            metadata.add("reflection", normalizeJniReachabilityMetadataObject(object));
        }
    }

    /**
     * Merges the nested {@code foreign} reachability metadata object.
     *
     * @param content  reachability metadata content
     * @param metadata target metadata accumulator
     */
    private void mergeForeignReachabilitySection(String content, ReachabilityMetadata metadata) {
        String foreignContent = extractNamedObjectContent(content, "foreign");
        if (foreignContent == null || foreignContent.isEmpty()) {
            return;
        }
        for (String section : FOREIGN_SECTIONS) {
            String arrayContent = extractNamedArrayContent(foreignContent, section);
            if (arrayContent == null || arrayContent.isEmpty()) {
                continue;
            }
            for (String object : extractJsonObjects(arrayContent)) {
                metadata.addForeign(section, normalizeReachabilityMetadataObject(object));
            }
        }
    }

    /**
     * Normalizes a reachability metadata object for GraalVM 25.
     *
     * @param object the metadata object
     * @return normalized metadata object
     */
    private String normalizeReachabilityMetadataObject(String object) {
        return object.strip()
                .replace("\"typeReachable\"", "\"typeReached\"")
                .replace("\"jniAccessibleType\"", "\"jniAccessible\"");
    }

    /**
     * Normalizes a JNI metadata object and marks it as accessible through JNI.
     *
     * @param object the JNI metadata object
     * @return reflection metadata object with {@code jniAccessible}
     */
    private String normalizeJniReachabilityMetadataObject(String object) {
        String normalized = normalizeReachabilityMetadataObject(object);
        if (normalized.contains("\"jniAccessible\"")) {
            return normalized;
        }
        return appendBooleanProperty(normalized, "jniAccessible");
    }

    /**
     * Normalizes a serialization metadata object and marks it as serializable through reflection metadata.
     *
     * @param object the serialization metadata object
     * @return reflection metadata object with {@code serializable}
     */
    private String normalizeSerializationReachabilityMetadataObject(String object) {
        String normalized = normalizeReachabilityMetadataObject(object);
        if (!normalized.contains("\"type\"") && normalized.contains("\"name\"")) {
            normalized = normalized.replaceFirst("\"name\"\\s*:", "\"type\":");
        }
        if (normalized.contains("\"serializable\"")) {
            return normalized;
        }
        return appendBooleanProperty(normalized, "serializable");
    }

    /**
     * Appends a boolean property to a JSON object.
     *
     * @param object the JSON object
     * @param propertyName the property name
     * @return the JSON object with the boolean property appended
     */
    private String appendBooleanProperty(String object, String propertyName) {
        int insertPosition = object.lastIndexOf('}');
        if (insertPosition == -1) {
            return object;
        }
        String prefix = object.substring(0, insertPosition).stripTrailing();
        String suffix = object.substring(insertPosition);
        String separator = prefix.endsWith("{") ? "\n" : ",\n";
        return prefix + separator + "  \"" + propertyName + "\": true\n" + suffix;
    }

    /**
     * Formats consolidated GraalVM 25 reachability metadata.
     *
     * @param metadata consolidated reachability metadata
     * @return formatted reachability-metadata.json content
     */
    private String formatReachabilityMetadata(ReachabilityMetadata metadata) {
        List<String> sections = new ArrayList<>();
        for (String section : REACHABILITY_ARRAY_SECTIONS) {
            List<String> entries = metadata.entries(section);
            if (!entries.isEmpty()) {
                sections.add(formatNamedJsonArray(section, entries, 2));
            }
        }
        if (metadata.hasForeignEntries()) {
            sections.add(formatForeignMetadata(metadata));
        }

        if (sections.isEmpty()) {
            return "{}";
        }
        return "{\n" + String.join(",\n", sections) + "\n}";
    }

    /**
     * Formats a named JSON array section.
     *
     * @param name    the section name
     * @param entries the JSON object entries
     * @param indent  the indentation width of the section name
     * @return formatted JSON array section
     */
    private String formatNamedJsonArray(String name, List<String> entries, int indent) {
        String sectionIndent = " ".repeat(indent);
        StringBuilder builder = new StringBuilder();
        builder.append(sectionIndent).append("\"").append(name).append("\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            builder.append(indentJsonObject(entries.get(i), indent + 2));
            if (i < entries.size() - 1) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append(sectionIndent).append("]");
        return builder.toString();
    }

    /**
     * Formats nested foreign function metadata.
     *
     * @param metadata consolidated reachability metadata
     * @return formatted foreign metadata section
     */
    private String formatForeignMetadata(ReachabilityMetadata metadata) {
        List<String> sections = new ArrayList<>();
        for (String section : FOREIGN_SECTIONS) {
            List<String> entries = metadata.foreignEntries(section);
            if (!entries.isEmpty()) {
                sections.add(formatNamedJsonArray(section, entries, 4));
            }
        }
        return "  \"foreign\": {\n" + String.join(",\n", sections) + "\n  }";
    }

    /**
     * Consolidates GraalVM serialization configuration files while preserving the native-image agent object format.
     * <p>
     * Serialization metadata generated by the native-image agent is an object with {@code types},
     * {@code lambdaCapturingTypes}, and {@code proxies} arrays. Treating each full file object as a generic array entry
     * creates descriptors without a {@code name} attribute and makes GraalVM reject the final {@code bus-all} jar.
     * </p>
     *
     * @param files list of serialization configuration files to consolidate
     * @return consolidated serialization configuration content
     */
    private String consolidateSerializationConfigurations(List<Path> files) {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        Map<String, Set<String>> processedKeys = new LinkedHashMap<>();
        for (String section : SERIALIZATION_SECTIONS) {
            entries.put(section, new ArrayList<>());
            processedKeys.put(section, new LinkedHashSet<>());
        }

        for (Path file : files) {
            try {
                String content = Files.readString(file).trim();
                if (content.isEmpty()) {
                    continue;
                }
                if (content.startsWith("{")) {
                    mergeSerializationObject(content, entries, processedKeys);
                } else if (content.startsWith("[")) {
                    mergeSerializationArray(content, entries, processedKeys);
                }
            } catch (IOException e) {
                log.warn("Warning: Could not process " + file + ": " + e.getMessage());
            }
        }

        return formatSerializationConfiguration(entries);
    }

    /**
     * Merges a serialization configuration object into the section entry map.
     *
     * @param content       the object-form serialization configuration content
     * @param entries       section entries keyed by section name
     * @param processedKeys de-duplication keys keyed by section name
     */
    private void mergeSerializationObject(
            String content, Map<String, List<String>> entries, Map<String, Set<String>> processedKeys) {
        for (String section : SERIALIZATION_SECTIONS) {
            String arrayContent = extractNamedArrayContent(content, section);
            if (arrayContent != null) {
                addSerializationObjects(section, arrayContent, entries, processedKeys);
            }
        }
    }

    /**
     * Merges an array-form serialization configuration into the section entry map.
     *
     * @param content       the array-form serialization configuration content
     * @param entries       section entries keyed by section name
     * @param processedKeys de-duplication keys keyed by section name
     */
    private void mergeSerializationArray(
            String content, Map<String, List<String>> entries, Map<String, Set<String>> processedKeys) {
        String arrayContent = content.substring(1, content.length() - 1).trim();
        for (String object : extractJsonObjects(arrayContent)) {
            if (object.contains("\"types\"") || object.contains("\"lambdaCapturingTypes\"") || object.contains("\"proxies\"")) {
                mergeSerializationObject(object, entries, processedKeys);
            } else if (object.contains("\"name\"")) {
                addSerializationObject("types", object, entries, processedKeys);
            }
        }
    }

    /**
     * Extracts the content of a named JSON array property.
     *
     * @param content   the JSON object content
     * @param arrayName the array property name
     * @return the inner array content, or {@code null} when the property is absent or malformed
     */
    private String extractNamedArrayContent(String content, String arrayName) {
        int nameStart = content.indexOf("\"" + arrayName + "\"");
        if (nameStart == -1) {
            return null;
        }
        int arrayStart = content.indexOf("[", nameStart);
        if (arrayStart == -1) {
            return null;
        }
        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayEnd == -1) {
            return null;
        }
        return content.substring(arrayStart + 1, arrayEnd).trim();
    }

    /**
     * Extracts the content of a named JSON object property.
     *
     * @param content    the JSON object content
     * @param objectName the object property name
     * @return the inner object content, or {@code null} when the property is absent or malformed
     */
    private String extractNamedObjectContent(String content, String objectName) {
        int nameStart = content.indexOf("\"" + objectName + "\"");
        if (nameStart == -1) {
            return null;
        }
        int objectStart = content.indexOf("{", nameStart);
        if (objectStart == -1) {
            return null;
        }
        int objectEnd = findMatchingBrace(content, objectStart);
        if (objectEnd == -1) {
            return null;
        }
        return content.substring(objectStart + 1, objectEnd).trim();
    }

    /**
     * Adds all JSON objects from a section array to the target map.
     *
     * @param section       the target serialization section
     * @param arrayContent  the inner array content
     * @param entries       section entries keyed by section name
     * @param processedKeys de-duplication keys keyed by section name
     */
    private void addSerializationObjects(
            String section, String arrayContent, Map<String, List<String>> entries, Map<String, Set<String>> processedKeys) {
        if (arrayContent.isEmpty()) {
            return;
        }
        for (String object : extractJsonObjects(arrayContent)) {
            addSerializationObject(section, object, entries, processedKeys);
        }
    }

    /**
     * Adds a JSON object to a serialization section if it has not already been added.
     *
     * @param section       the target serialization section
     * @param object        the JSON object content
     * @param entries       section entries keyed by section name
     * @param processedKeys de-duplication keys keyed by section name
     */
    private void addSerializationObject(
            String section, String object, Map<String, List<String>> entries, Map<String, Set<String>> processedKeys) {
        String trimmed = object.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return;
        }
        String key = generateUniqueObjectKey(trimmed);
        if (processedKeys.get(section).add(key)) {
            entries.get(section).add(trimmed);
        }
    }

    /**
     * Formats merged serialization metadata using the native-image agent object structure.
     *
     * @param entries section entries keyed by section name
     * @return formatted serialization configuration content
     */
    private String formatSerializationConfiguration(Map<String, List<String>> entries) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        for (int sectionIndex = 0; sectionIndex < SERIALIZATION_SECTIONS.size(); sectionIndex++) {
            String section = SERIALIZATION_SECTIONS.get(sectionIndex);
            builder.append("  \"").append(section).append("\": [");
            List<String> objects = entries.get(section);
            if (!objects.isEmpty()) {
                builder.append("\n");
                for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
                    builder.append(indentJsonObject(objects.get(objectIndex), 4));
                    if (objectIndex < objects.size() - 1) {
                        builder.append(",");
                    }
                    builder.append("\n");
                }
                builder.append("  ");
            }
            builder.append("]");
            if (sectionIndex < SERIALIZATION_SECTIONS.size() - 1) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("}");
        return builder.toString();
    }

    /**
     * Applies a consistent left indentation to a JSON object.
     *
     * @param object the JSON object content
     * @param spaces the number of spaces to prepend
     * @return the indented object content
     */
    private String indentJsonObject(String object, int spaces) {
        String prefix = " ".repeat(spaces);
        return Arrays.stream(object.strip().split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Consolidates JSON array configuration files with deduplication and structure preservation. Handles
     * reflect-config.json, proxy-config.json, and other array-based configurations.
     *
     * @param files list of JSON configuration files to consolidate
     * @return consolidated JSON array as a string
     */
    private String consolidateJsonArrayConfigurations(List<Path> files) {
        List<String> uniqueObjects = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        for (Path file : files) {
            try {
                String content = Files.readString(file).trim();

                // Remove outer array brackets if present
                if (content.startsWith("[") && content.endsWith("]")) {
                    content = content.substring(1, content.length() - 1).trim();
                }

                if (!content.isEmpty()) {
                    List<String> objects = extractJsonObjects(content);
                    for (String obj : objects) {
                        obj = obj.trim();
                        if (obj.startsWith("{") && obj.endsWith("}")) {
                            String key = generateUniqueObjectKey(obj);
                            if (!processedKeys.contains(key)) {
                                uniqueObjects.add(obj);
                                processedKeys.add(key);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Warning: Could not process " + file + ": " + e.getMessage());
            }
        }

        return formatJsonArray(uniqueObjects);
    }

    /**
     * Extracts individual JSON objects from a JSON string content.
     *
     * @param content the JSON content containing multiple objects
     * @return list of individual JSON object strings
     */
    private List<String> extractJsonObjects(String content) {
        List<String> objects = new ArrayList<>();
        StringBuilder currentObject = new StringBuilder();
        boolean inObject = false;
        int braceDepth = 0;
        boolean inString = false;
        int index = 0;

        while (index < content.length()) {
            char character = content.charAt(index);

            // Handle string boundary detection
            if (character == '"' && (index == 0 || content.charAt(index - 1) != '\\')) {
                inString = !inString;
            }

            // Detect object start
            if (character == '{' && !inString && !inObject) {
                inObject = true;
                braceDepth = 0;
                currentObject.setLength(0);
            }

            if (inObject) {
                currentObject.append(character);
                if (!inString) {
                    if (character == '{') {
                        braceDepth++;
                    } else if (character == '}') {
                        braceDepth--;
                    }
                }

                // Object completion detection
                if (braceDepth == 0 && character == '}') {
                    String objectStr = currentObject.toString().trim();
                    if (!objectStr.isEmpty()) {
                        objects.add(objectStr);
                    }
                    inObject = false;
                }
            }
            index++;
        }

        return objects;
    }

    /**
     * Generates a unique key for a JSON object for deduplication purposes. Uses normalized JSON content to ensure
     * consistent comparison.
     *
     * @param jsonStr the JSON object string
     * @return a unique key string for the JSON object
     */
    private String generateUniqueObjectKey(String jsonStr) {
        String normalizedJson = normalizeJsonForComparison(jsonStr);
        return "hash:" + normalizedJson.hashCode();
    }

    /**
     * Normalizes JSON content for consistent comparison by removing formatting differences.
     *
     * @param jsonStr the JSON string to normalize
     * @return normalized JSON string in lowercase
     */
    private String normalizeJsonForComparison(String jsonStr) {
        StringBuilder normalized = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < jsonStr.length(); i++) {
            char character = jsonStr.charAt(i);

            if (escaped) {
                normalized.append(character);
                escaped = false;
                continue;
            }

            if (character == '\\') {
                normalized.append(character);
                escaped = true;
                continue;
            }

            if (character == '"') {
                inString = !inString;
                normalized.append(character);
                continue;
            }

            if (inString) {
                normalized.append(character);
            } else {
                // Skip whitespace outside of strings
                if (!Character.isWhitespace(character)) {
                    normalized.append(character);
                }
            }
        }

        return normalized.toString().toLowerCase();
    }

    /**
     * Formats a list of JSON objects into a properly formatted JSON array string.
     *
     * @param objects list of JSON object strings
     * @return formatted JSON array string
     */
    private String formatJsonArray(List<String> objects) {
        StringBuilder jsonArray = new StringBuilder();
        jsonArray.append("[\n");

        for (int i = 0; i < objects.size(); i++) {
            jsonArray.append("  ").append(objects.get(i));
            if (i < objects.size() - 1) {
                jsonArray.append(",");
            }
            jsonArray.append("\n");
        }

        jsonArray.append("]");
        return jsonArray.toString();
    }

    /**
     * Determines which versions should be processed based on target version and available versions.
     *
     * @param allVersions   all discovered versions
     * @param targetVersion specific target version, or null for all versions
     * @return set of versions to process
     */
    private Set<String> determineVersionsToProcess(Set<String> allVersions, String targetVersion) {
        if (allVersions.contains(targetVersion)) {
            return Set.of(targetVersion);
        } else if (targetVersion != null) {
            log.info("Warning: Version " + targetVersion + " not found, processing all available versions");
            return allVersions;
        } else {
            return allVersions;
        }
    }

    /**
     * Consolidates resource configuration files with adaptive structure preservation. Sorts resource patterns by Java
     * package alphabetical order.
     *
     * @param files list of resource-config.json files to consolidate
     * @return consolidated resource configuration as a string
     */
    private String consolidateResourceConfigurations(List<Path> files) {
        Set<String> allIncludes = new LinkedHashSet<>();
        Set<String> allBundles = new LinkedHashSet<>();
        boolean foundExistingIncludes = false;

        // Analyze all files for structure and content
        for (Path file : files) {
            try {
                String content = Files.readString(file);

                if (content.contains("\"includes\"")) {
                    foundExistingIncludes = true;
                    extractResourcePatterns(content, allIncludes);
                }

                if (content.contains("\"bundles\"")) {
                    extractBundles(content, allBundles);
                }
            } catch (IOException e) {
                log.warn("Warning: Could not analyze " + file + ": " + e.getMessage());
            }
        }

        if (foundExistingIncludes) {
            return mergeExistingResourceConfigurations(allIncludes, allBundles);
        } else {
            return createNewResourceConfiguration(allIncludes, allBundles);
        }
    }

    /**
     * Extracts resource patterns from configuration file content. Only extracts patterns from the "includes" array, not
     * from "bundles".
     *
     * @param content     the configuration file content
     * @param allIncludes set to store extracted patterns
     */
    private void extractResourcePatterns(String content, Set<String> allIncludes) {
        // Find the "includes" array
        int includesStart = content.indexOf("\"includes\"");
        if (includesStart == -1)
            return;

        int arrayStart = content.indexOf("[", includesStart);
        if (arrayStart == -1)
            return;

        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayEnd == -1)
            return;

        String includesArray = content.substring(arrayStart, arrayEnd + 1);

        // Extract pattern values from the includes array
        Pattern patternRegex = Pattern.compile("\"pattern\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = patternRegex.matcher(includesArray);

        while (matcher.find()) {
            String pattern = matcher.group(1);
            if (!pattern.isEmpty()) {
                allIncludes.add(pattern);
            }
        }
    }

    /**
     * Finds the matching closing bracket for an opening bracket.
     *
     * @param content the content to search
     * @param openPos position of the opening bracket
     * @return position of the matching closing bracket, or -1 if not found
     */
    private int findMatchingBracket(String content, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Finds the matching closing brace for an opening brace.
     *
     * @param content the content to search
     * @param openPos position of an opening brace
     * @return position of the matching closing brace, or -1 if not found
     */
    private int findMatchingBrace(String content, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Extracts bundle objects from configuration file content.
     *
     * @param content    the configuration file content
     * @param allBundles set to store extracted bundle objects
     */
    private void extractBundles(String content, Set<String> allBundles) {
        // Find the "bundles" array
        int bundlesStart = content.indexOf("\"bundles\"");
        if (bundlesStart == -1)
            return;

        int arrayStart = content.indexOf("[", bundlesStart);
        if (arrayStart == -1)
            return;

        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayEnd == -1)
            return;

        String bundlesArray = content.substring(arrayStart + 1, arrayEnd).trim();
        if (bundlesArray.isEmpty())
            return;

        // Extract complete bundle objects (keep original indentation for now)
        List<String> bundleObjects = extractJsonObjects(bundlesArray);
        allBundles.addAll(bundleObjects);
    }

    /**
     * Merges existing resource-config.json files while preserving original structure. Currently simplified to use new
     * creation approach.
     *
     * @param allIncludes all discovered resource patterns
     * @param allBundles  all discovered bundle objects
     * @return merged resource configuration
     */
    private String mergeExistingResourceConfigurations(Set<String> allIncludes, Set<String> allBundles) {
        // Simplified approach: use new creation for now
        return createNewResourceConfiguration(allIncludes, allBundles);
    }

    /**
     * Creates a new resource-config.json file with standard structure.
     *
     * @param allIncludes resource patterns to include
     * @param allBundles  bundle objects to include
     * @return formatted resource configuration
     */
    private String createNewResourceConfiguration(Set<String> allIncludes, Set<String> allBundles) {
        List<String> sortedIncludes = new ArrayList<>(allIncludes);
        sortedIncludes.sort(this::compareByJavaPackage);

        StringBuilder configBuilder = new StringBuilder();
        configBuilder.append("{\n");
        configBuilder.append("  \"resources\": {\n");
        configBuilder.append("    \"includes\": [\n");

        for (int i = 0; i < sortedIncludes.size(); i++) {
            if (i > 0) {
                configBuilder.append(",\n");
            }
            configBuilder.append("      {\n");
            configBuilder.append("        \"pattern\": \"").append(sortedIncludes.get(i)).append("\"\n");
            configBuilder.append("      }");
        }

        configBuilder.append("\n    ]\n");
        configBuilder.append("  }");

        if (!allBundles.isEmpty()) {
            configBuilder.append(",\n  \"bundles\": [\n");
            int i = 0;
            for (String bundle : allBundles) {
                if (i > 0) {
                    configBuilder.append(",\n");
                }
                // Format bundle with standard 2-space indentation
                // Bundle object starts at 4 spaces (2 levels: bundles array + object)
                configBuilder.append(formatBundleObject(bundle));
                i++;
            }
            configBuilder.append("\n  ]");
        }

        configBuilder.append("\n}");
        return configBuilder.toString();
    }

    /**
     * Formats a bundle object with proper 2-space indentation. Ensures consistent formatting matching the rest of the
     * file.
     *
     * @param bundle the bundle JSON object to format
     * @return properly formatted bundle string
     */
    private String formatBundleObject(String bundle) {
        // Parse the bundle to extract name and locales
        String name = extractBundleName(bundle);
        List<String> locales = extractBundleLocales(bundle);

        StringBuilder result = new StringBuilder();
        result.append("    {\n"); // 4 spaces - bundle object start
        result.append("      \"name\": \"").append(name).append("\",\n"); // 6 spaces
        result.append("      \"locales\": [\n"); // 6 spaces

        for (int i = 0; i < locales.size(); i++) {
            result.append("        \"").append(locales.get(i)).append("\""); // 8 spaces
            if (i < locales.size() - 1) {
                result.append(",");
            }
            result.append("\n");
        }

        result.append("      ]\n"); // 6 spaces
        result.append("    }"); // 4 spaces - bundle object end

        return result.toString();
    }

    /**
     * Extracts the bundle name from a bundle JSON object.
     *
     * @param bundle the bundle JSON string
     * @return the bundle name
     */
    private String extractBundleName(String bundle) {
        Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(bundle);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    /**
     * Extracts the locales array from a bundle JSON object.
     *
     * @param bundle the bundle JSON string
     * @return list of locale strings
     */
    private List<String> extractBundleLocales(String bundle) {
        List<String> locales = new ArrayList<>();

        // Find the locales array
        int localesStart = bundle.indexOf("\"locales\"");
        if (localesStart == -1)
            return locales;

        int arrayStart = bundle.indexOf("[", localesStart);
        if (arrayStart == -1)
            return locales;

        int arrayEnd = findMatchingBracket(bundle, arrayStart);
        if (arrayEnd == -1)
            return locales;

        String localesArray = bundle.substring(arrayStart + 1, arrayEnd);

        // Extract all quoted strings from the array
        Pattern pattern = Pattern.compile("\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(localesArray);

        while (matcher.find()) {
            locales.add(matcher.group(1));
        }

        return locales;
    }

    /**
     * Compares two resource paths by Java package alphabetical order.
     *
     * @param path1 the first resource path
     * @param path2 the second resource path
     * @return negative if path1 < path2, zero if equal, positive if path1 > path2
     */
    private int compareByJavaPackage(String path1, String path2) {
        String pkg1 = extractJavaPackageName(path1);
        String pkg2 = extractJavaPackageName(path2);

        int packageComparison = pkg1.compareTo(pkg2);
        if (packageComparison != 0) {
            return packageComparison;
        }

        return path1.compareTo(path2);
    }

    /**
     * Extracts Java package name from a resource path for sorting purposes.
     *
     * @param path the resource path
     * @return extracted package name
     */
    private String extractJavaPackageName(String path) {
        path = path.replaceFirst("^/", "");

        if (path.startsWith("META-INF/")) {
            return "META-INF";
        } else if (path.contains("/")) {
            String[] segments = path.split("/");
            if (segments.length >= 2) {
                return segments[0] + "." + segments[1];
            } else if (segments.length == 1) {
                return segments[0];
            }
        }

        return path;
    }

    /**
     * Generates a version index file listing all configuration file types.
     *
     * @param outputPath         the path where the index file should be written
     * @param configurationTypes set of configuration file types
     * @throws IOException if file writing fails
     */
    private void generateVersionIndex(Path outputPath, Set<String> configurationTypes) throws IOException {
        StringBuilder indexBuilder = new StringBuilder();
        indexBuilder.append("[\n");

        List<String> sortedTypes = configurationTypes.stream().sorted().toList();

        for (int i = 0; i < sortedTypes.size(); i++) {
            indexBuilder.append("  \"").append(sortedTypes.get(i)).append("\"");
            if (i < sortedTypes.size() - 1) {
                indexBuilder.append(",");
            }
            indexBuilder.append("\n");
        }
        indexBuilder.append("]");

        Files.writeString(outputPath, indexBuilder.toString());
    }

    /**
     * Generates the master metadata index file for the consolidated module.
     *
     * @param processedVersions set of versions that were actually processed
     * @param targetVersion the target project version requested by the build
     * @throws IOException if file operations fail
     */
    private void generateTopIndex(Set<String> processedVersions, String targetVersion) throws IOException {
        Set<String> allTestedVersions = collectAllTestedVersions(processedVersions);
        Set<String> allowedPackages = collectAllowedPackages();
        Optional<String> defaultForPattern = collectDefaultForPattern();

        // metadata-version should be the latest actually processed version directory
        String latestVersion = processedVersions.stream().max(Comparator.naturalOrder()).orElse("unknown");

        StringBuilder metadataBuilder = new StringBuilder();
        metadataBuilder.append("[\n");
        metadataBuilder.append("  {\n");
        metadataBuilder.append("    \"latest\": true,\n");
        metadataBuilder.append("    \"override\": true,\n");
        metadataBuilder.append("    \"metadata-version\": \"").append(latestVersion).append("\",\n");
        if (defaultForPattern.isPresent()) {
            metadataBuilder.append("    \"default-for\": \"").append(defaultForPattern.get()).append("\",\n");
        }
        metadataBuilder.append("    \"tested-versions\": [\n");

        List<String> sortedVersions = allTestedVersions.stream().sorted(Comparator.naturalOrder()).toList();

        for (int i = 0; i < sortedVersions.size(); i++) {
            metadataBuilder.append("      \"").append(sortedVersions.get(i)).append("\"");
            if (i < sortedVersions.size() - 1) {
                metadataBuilder.append(",");
            }
            metadataBuilder.append("\n");
        }

        metadataBuilder.append("    ],\n");
        metadataBuilder.append("    \"allowed-packages\": [\n");

        List<String> sortedPackages = allowedPackages.stream().sorted().toList();
        for (int i = 0; i < sortedPackages.size(); i++) {
            metadataBuilder.append("      \"").append(sortedPackages.get(i)).append("\"");
            if (i < sortedPackages.size() - 1) {
                metadataBuilder.append(",");
            }
            metadataBuilder.append("\n");
        }

        metadataBuilder.append("    ]\n");
        metadataBuilder.append("  }\n");
        metadataBuilder.append("]");

        Path metadataPath = outputBaseDirectory.resolve("index.json");
        Files.createDirectories(metadataPath.getParent());
        Files.writeString(metadataPath, metadataBuilder.toString());

        log.info("\nLatest version: " + latestVersion);
        log.info("All tested versions: " + String.join(", ", sortedVersions));
        log.info("Allowed packages: " + String.join(", ", sortedPackages));
        defaultForPattern.ifPresent(pattern -> log.info("Default-for: " + pattern));
    }

    /**
     * Collects the native-image default-for selector from module source metadata.
     *
     * @return the discovered selector, or empty when source metadata does not define one
     */
    private Optional<String> collectDefaultForPattern() {
        Set<String> patterns = new LinkedHashSet<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(projectRootDirectory)) {
            paths.filter(Files::isRegularFile).filter(this::isSourceNativeImageIndex)
                    .filter(path -> !shouldExcludeModule(path)).map(this::extractDefaultForPattern)
                    .filter(Objects::nonNull).forEach(patterns::add);
        } catch (IOException e) {
            log.warn("Warning: Could not scan for default-for metadata: " + e.getMessage());
        }
        if (patterns.isEmpty()) {
            return Optional.empty();
        }
        if (patterns.size() == 1) {
            return Optional.of(patterns.iterator().next());
        }
        List<String> sortedPatterns = patterns.stream().sorted().toList();
        log.warn("Warning: Multiple default-for selectors found; merging them with regex alternation: "
                + String.join(", ", sortedPatterns));
        return Optional.of(sortedPatterns.stream().map(pattern -> "(?:" + pattern + ")").collect(Collectors.joining("|")));
    }

    /**
     * Tests whether a path points to a source native-image index file.
     *
     * @param path the path to evaluate
     * @return true if the path is a source native-image index file
     */
    private boolean isSourceNativeImageIndex(Path path) {
        String pathText = path.toString().replace('\\', '/');
        return pathText.contains("/src/main/resources/META-INF/native-image/")
                && path.getFileName().toString().equals("index.json");
    }

    /**
     * Extracts the native-image default-for selector from a metadata file.
     *
     * @param path the metadata file path
     * @return the selector value, or null when the file does not define one
     */
    private String extractDefaultForPattern(Path path) {
        try {
            String content = Files.readString(path);
            Matcher matcher = DEFAULT_FOR_PROPERTY_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IOException e) {
            log.warn("Warning: Could not read " + path + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Collects all tested versions from module index files.
     *
     * @param processedVersions set of versions that were processed
     * @return comprehensive set of all tested versions
     */
    private Set<String> collectAllTestedVersions(Set<String> processedVersions) {
        Set<String> allTestedVersions = new LinkedHashSet<>(processedVersions);

        try {
            Path projectRoot = projectRootDirectory;
            try (java.util.stream.Stream<Path> paths = Files.walk(projectRoot)) {
                paths.filter(Files::isRegularFile).filter(path -> path.toString().contains("native-image"))
                        .filter(path -> path.getFileName().toString().equals("index.json"))
                        .filter(path -> !shouldExcludeModule(path)).forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                extractTestedVersions(content, allTestedVersions);
                            } catch (IOException e) {
                                log.warn("Warning: Could not read " + path + ": " + e.getMessage());
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("Warning: Could not scan for index.json files: " + e.getMessage());
        }

        return allTestedVersions;
    }

    /**
     * Collects allowed packages from module index files.
     *
     * @return allowed packages for the generated metadata index
     */
    private Set<String> collectAllowedPackages() {
        Set<String> allowedPackages = new LinkedHashSet<>();

        try (java.util.stream.Stream<Path> paths = Files.walk(projectRootDirectory)) {
            paths.filter(Files::isRegularFile).filter(this::isSourceNativeImageIndex)
                    .filter(path -> !shouldExcludeModule(path)).forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            extractAllowedPackages(content, allowedPackages);
                        } catch (IOException e) {
                            log.warn("Warning: Could not read " + path + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Warning: Could not scan for allowed-packages metadata: " + e.getMessage());
        }

        if (allowedPackages.isEmpty()) {
            allowedPackages.add(moduleIdentifier.split(":", 2)[0]);
        }
        return allowedPackages;
    }

    /**
     * Extracts version numbers from tested-versions array in JSON content.
     *
     * @param content           the JSON content containing tested-versions
     * @param allTestedVersions set to add extracted versions to
     */
    private void extractTestedVersions(String content, Set<String> allTestedVersions) {
        Pattern pattern = Pattern.compile("\"tested-versions\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String versionsStr = matcher.group(1);
            Pattern versionPattern = Pattern.compile("\"([0-9]+\\.[0-9]+\\.[0-9]+)\"");
            Matcher versionMatcher = versionPattern.matcher(versionsStr);

            while (versionMatcher.find()) {
                allTestedVersions.add(versionMatcher.group(1));
            }
        }
    }

    /**
     * Extracts package names from allowed-packages array in JSON content.
     *
     * @param content         the JSON content containing allowed-packages
     * @param allowedPackages set to add extracted packages to
     */
    private void extractAllowedPackages(String content, Set<String> allowedPackages) {
        Pattern pattern = Pattern.compile("\"allowed-packages\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String packagesString = matcher.group(1);
            Pattern packagePattern = Pattern.compile("\"([^\"]+)\"");
            Matcher packageMatcher = packagePattern.matcher(packagesString);

            while (packageMatcher.find()) {
                allowedPackages.add(packageMatcher.group(1));
            }
        }
    }

    /**
     * Displays comprehensive consolidation statistics for a processed version.
     *
     * @param version            the version that was processed
     * @param outputPath         path to the generated configuration files
     * @param configurationTypes types of configuration files that were processed
     * @throws IOException if reading statistics fails
     */
    private void displayConsolidationStatistics(String version, Path outputPath, Set<String> configurationTypes)
            throws IOException {
        StringBuilder statistics = new StringBuilder();
        statistics.append("Version ").append(version).append(": ");

        for (String configType : configurationTypes) {
            Path configFile = outputPath.resolve(configType);
            if (Files.exists(configFile)) {
                String content = Files.readString(configFile);
                if (configType.contains("proxy-config")) {
                    statistics.append(countJsonArrayEntries(content)).append(" proxy configurations, ");
                } else if (configType.contains("reflect-config")) {
                    statistics.append(countJsonArrayEntries(content)).append(" reflection entries, ");
                } else if (configType.contains("resource-config")) {
                    statistics.append(countResourcePatterns(content)).append(" resource patterns, ");
                } else if (REACHABILITY_METADATA_CONFIG.equals(configType)) {
                    statistics.append(countReachabilityMetadataEntries(content)).append(" reachability metadata entries, ");
                } else {
                    statistics.append(configType).append(" consolidated, ");
                }
            }
        }

        // Remove trailing comma and space
        if (statistics.length() > 2) {
            statistics.setLength(statistics.length() - 2);
        }

        log.info(statistics);
    }

    /**
     * Counts the number of entries in a JSON array.
     *
     * @param jsonContent the JSON array content
     * @return number of array entries
     */
    private int countJsonArrayEntries(String jsonContent) {
        return (int) jsonContent.chars().filter(ch -> ch == '{').count();
    }

    /**
     * Counts the number of resource patterns in resource-config.json.
     *
     * @param jsonContent the resource configuration content
     * @return number of resource patterns
     */
    private int countResourcePatterns(String jsonContent) {
        int includesIndex = jsonContent.indexOf("\"includes\"");
        if (includesIndex == -1)
            return 0;

        int arrayStart = jsonContent.indexOf("[", includesIndex);
        int arrayEnd = jsonContent.indexOf("]", arrayStart);
        if (arrayStart == -1 || arrayEnd == -1)
            return 0;

        String arrayContent = jsonContent.substring(arrayStart, arrayEnd + 1);
        return (int) arrayContent.chars().filter(ch -> ch == '"').count() / 2;
    }

    /**
     * Counts entries in GraalVM 25 reachability metadata.
     *
     * @param jsonContent reachability metadata content
     * @return number of metadata entries
     */
    private int countReachabilityMetadataEntries(String jsonContent) {
        int count = 0;
        for (String section : REACHABILITY_ARRAY_SECTIONS) {
            String arrayContent = extractNamedArrayContent(jsonContent, section);
            if (arrayContent != null) {
                count += extractJsonObjects(arrayContent).size();
            }
        }
        String foreignContent = extractNamedObjectContent(jsonContent, "foreign");
        if (foreignContent != null) {
            for (String section : FOREIGN_SECTIONS) {
                String arrayContent = extractNamedArrayContent(foreignContent, section);
                if (arrayContent != null) {
                    count += extractJsonObjects(arrayContent).size();
                }
            }
        }
        return count;
    }

    /**
     * Accumulates reachability metadata entries while preserving insertion order and avoiding duplicates.
     */
    private final class ReachabilityMetadata {

        /**
         * Top-level reachability metadata array entries.
         */
        private final Map<String, List<String>> entries = new LinkedHashMap<>();

        /**
         * De-duplication keys for top-level reachability metadata entries.
         */
        private final Map<String, Set<String>> keys = new LinkedHashMap<>();

        /**
         * Nested foreign metadata entries.
         */
        private final Map<String, List<String>> foreignEntries = new LinkedHashMap<>();

        /**
         * De-duplication keys for nested foreign metadata entries.
         */
        private final Map<String, Set<String>> foreignKeys = new LinkedHashMap<>();

        /**
         * Constructs a reachability metadata accumulator.
         */
        private ReachabilityMetadata() {
            for (String section : REACHABILITY_ARRAY_SECTIONS) {
                entries.put(section, new ArrayList<>());
                keys.put(section, new LinkedHashSet<>());
            }
            for (String section : FOREIGN_SECTIONS) {
                foreignEntries.put(section, new ArrayList<>());
                foreignKeys.put(section, new LinkedHashSet<>());
            }
        }

        /**
         * Adds a top-level entry if it has not been seen.
         *
         * @param section the section name
         * @param object  the JSON object entry
         */
        private void add(String section, String object) {
            addEntry(entries.get(section), keys.get(section), object);
        }

        /**
         * Adds a foreign metadata entry if it has not been seen.
         *
         * @param section the foreign section name
         * @param object  the JSON object entry
         */
        private void addForeign(String section, String object) {
            addEntry(foreignEntries.get(section), foreignKeys.get(section), object);
        }

        /**
         * Returns top-level entries for a section.
         *
         * @param section the section name
         * @return section entries
         */
        private List<String> entries(String section) {
            return entries.get(section);
        }

        /**
         * Returns foreign entries for a section.
         *
         * @param section the foreign section name
         * @return foreign section entries
         */
        private List<String> foreignEntries(String section) {
            return foreignEntries.get(section);
        }

        /**
         * Tests whether any foreign metadata entries have been accumulated.
         *
         * @return true if the foreign object should be written
         */
        private boolean hasForeignEntries() {
            return foreignEntries.values().stream().anyMatch(list -> !list.isEmpty());
        }

        /**
         * Adds an entry to the supplied collection when unique.
         *
         * @param target target entry list
         * @param seen   de-duplication keys
         * @param object JSON object entry
         */
        private void addEntry(List<String> target, Set<String> seen, String object) {
            String trimmed = object == null ? "" : object.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return;
            }
            String key = generateUniqueObjectKey(trimmed);
            if (seen.add(key)) {
                target.add(trimmed);
            }
        }
    }

}
