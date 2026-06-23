package org.miaixz.maven.groom;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Rewrites jar artifacts with consolidated native-image metadata.
 */
final class NativeImageJarRewriter {

    /**
     * The jar path prefix containing Maven metadata entries.
     */
    private static final String MAVEN_METADATA_PREFIX = "META-INF/maven/";

    /**
     * The jar path prefix containing GraalVM native-image metadata entries.
     */
    private static final String NATIVE_IMAGE_PREFIX = "META-INF/native-image/";

    /**
     * The manifest entry path.
     */
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";

    /**
     * Project artifact identifier written to manifest attributes.
     */
    private final String artifactId;

    /**
     * Project group identifier used to replace only consolidated project metadata.
     */
    private final String groupId;

    /**
     * Project version written to manifest attributes.
     */
    private final String version;

    /**
     * Project vendor written to manifest attributes.
     */
    private final String vendor;

    /**
     * Constructs a new jar rewriter.
     *
     * @param groupId the project group identifier
     * @param artifactId the project artifact identifier
     * @param version the project version
     * @param vendor the project vendor
     */
    NativeImageJarRewriter(String groupId, String artifactId, String version, String vendor) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.vendor = vendor;
    }

    /**
     * Rewrites a jar file by removing old metadata and adding consolidated native-image metadata.
     *
     * @param jarFile the jar file to rewrite
     * @param nativeImageDirectory the consolidated native-image metadata directory
     * @throws IOException when the jar cannot be rewritten
     */
    void rewrite(Path jarFile, Path nativeImageDirectory) throws IOException {
        if (!Files.exists(jarFile)) {
            return;
        }
        Path temporaryJar = Files.createTempFile(jarFile.getParent(), jarFile.getFileName().toString(), ".tmp");
        try {
            rewriteToTemporaryJar(jarFile, nativeImageDirectory, temporaryJar);
            Files.move(temporaryJar, jarFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }

    /**
     * Rewrites the source jar into a temporary jar file.
     *
     * @param jarFile the original jar file
     * @param nativeImageDirectory the consolidated native-image metadata directory
     * @param temporaryJar the temporary jar file
     * @throws IOException when reading or writing fails
     */
    private void rewriteToTemporaryJar(Path jarFile, Path nativeImageDirectory, Path temporaryJar) throws IOException {
        try (JarFile sourceJar = new JarFile(jarFile.toFile());
                OutputStream outputStream = Files.newOutputStream(temporaryJar);
                JarOutputStream targetJar = new JarOutputStream(outputStream, manifest(sourceJar))) {
            Set<String> writtenEntries = new HashSet<>();
            writtenEntries.add(MANIFEST_ENTRY);
            copyOriginalEntries(sourceJar, targetJar, writtenEntries);
            copyNativeImageEntries(nativeImageDirectory, targetJar, writtenEntries);
        }
    }

    /**
     * Creates the manifest written into the rewritten jar.
     *
     * @param sourceJar the original jar file
     * @return the manifest for the rewritten jar
     * @throws IOException when the original manifest cannot be read
     */
    private Manifest manifest(JarFile sourceJar) throws IOException {
        Manifest manifest = sourceJar.getManifest();
        if (manifest == null) {
            manifest = new Manifest();
        }
        Attributes attributes = manifest.getMainAttributes();
        attributes.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Implementation-Title", artifactId);
        attributes.putValue("Implementation-Version", version);
        attributes.putValue("Implementation-Vendor", vendor);
        attributes.putValue("Multi-Release", "true");
        attributes.putValue("Specification-Title", artifactId);
        attributes.putValue("Specification-Version", version);
        attributes.putValue("Specification-Vendor", vendor);
        return manifest;
    }

    /**
     * Copies original entries that should remain in the rewritten jar.
     *
     * @param sourceJar the original jar
     * @param targetJar the rewritten jar
     * @param writtenEntries entry names already written to the target jar
     * @throws IOException when reading or writing fails
     */
    private void copyOriginalEntries(JarFile sourceJar, JarOutputStream targetJar, Set<String> writtenEntries)
            throws IOException {
        Enumeration<JarEntry> entries = sourceJar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (shouldSkipOriginalEntry(name) || writtenEntries.contains(name)) {
                continue;
            }
            JarEntry copiedEntry = new JarEntry(entry);
            targetJar.putNextEntry(copiedEntry);
            if (!entry.isDirectory()) {
                try (InputStream inputStream = sourceJar.getInputStream(entry)) {
                    inputStream.transferTo(targetJar);
                }
            }
            targetJar.closeEntry();
            writtenEntries.add(name);
        }
    }

    /**
     * Tests whether an original jar entry should be skipped.
     *
     * @param entryName the jar entry name
     * @return {@code true} when the entry should not be copied
     */
    private boolean shouldSkipOriginalEntry(String entryName) {
        return MANIFEST_ENTRY.equalsIgnoreCase(entryName)
                || entryName.startsWith(MAVEN_METADATA_PREFIX)
                || shouldSkipOriginalNativeImageEntry(entryName);
    }

    /**
     * Tests whether an original native-image entry should be replaced by the consolidated metadata.
     * <p>
     * Third-party metadata must remain in shaded jars because it belongs to the original dependency coordinates. Only
     * the current project group metadata is consolidated and replaced.
     * </p>
     *
     * @param entryName the jar entry name
     * @return {@code true} when the native-image entry should be replaced
     */
    private boolean shouldSkipOriginalNativeImageEntry(String entryName) {
        if (!entryName.startsWith(NATIVE_IMAGE_PREFIX)) {
            return false;
        }
        return entryName.startsWith(NATIVE_IMAGE_PREFIX + groupId + "/");
    }

    /**
     * Copies consolidated native-image metadata into the rewritten jar.
     *
     * @param nativeImageDirectory the native-image metadata directory
     * @param targetJar the rewritten jar
     * @param writtenEntries entry names already written to the target jar
     * @throws IOException when reading or writing fails
     */
    private void copyNativeImageEntries(Path nativeImageDirectory, JarOutputStream targetJar, Set<String> writtenEntries)
            throws IOException {
        if (!Files.exists(nativeImageDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(nativeImageDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = NATIVE_IMAGE_PREFIX + nativeImageDirectory.relativize(path).toString()
                        .replace('\\', '/');
                if (writtenEntries.contains(entryName)) {
                    continue;
                }
                JarEntry entry = new JarEntry(entryName);
                targetJar.putNextEntry(entry);
                Files.copy(path, targetJar);
                targetJar.closeEntry();
                writtenEntries.add(entryName);
            }
        }
    }

}
