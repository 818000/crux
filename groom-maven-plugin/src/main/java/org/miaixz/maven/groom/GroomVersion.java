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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * Reads and validates the Bus build version from the repository {@code VERSION} file.
 */
public final class GroomVersion {

    /**
     * Maven property used by CI-friendly project versions.
     */
    public static final String REVISION_PROPERTY = "revision";

    /**
     * Maven property used by Bus modules and generated metadata.
     */
    public static final String BUS_VERSION_PROPERTY = "bus.version";

    /**
     * Maven property used by the Groom plugin and generated metadata.
     */
    public static final String GROOM_VERSION_PROPERTY = "groom.version";

    /**
     * Repository-level version file name.
     */
    public static final String VERSION_FILE = "VERSION";

    /**
     * Version format accepted by the repository {@code VERSION} file.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+([.-][0-9A-Za-z]+)*");

    /**
     * Utility class constructor.
     */
    private GroomVersion() {
    }

    /**
     * Resolves the build version for a Maven session.
     *
     * @param session the Maven session
     * @return the resolved version
     * @throws MojoExecutionException when the version cannot be resolved
     */
    public static String resolve(MavenSession session) throws MojoExecutionException {
        File root = findRoot(session);
        return read(root);
    }

    /**
     * Resolves the version for generated build output.
     *
     * @param session the Maven session
     * @param project the Maven project
     * @return the resolved version
     * @throws MojoExecutionException when the version cannot be resolved
     */
    public static String resolve(MavenSession session, MavenProject project) throws MojoExecutionException {
        String version = property(session, REVISION_PROPERTY);
        if (version != null) {
            return validate(version, REVISION_PROPERTY);
        }
        if (project != null && project.getVersion() != null && !project.getVersion().isBlank()) {
            return validate(project.getVersion(), "project.version");
        }
        return resolve(session);
    }

    /**
     * Reads the version from the {@code VERSION} file under the given repository root.
     *
     * @param root the repository root
     * @return the validated version
     * @throws MojoExecutionException when the version file is missing or invalid
     */
    public static String read(File root) throws MojoExecutionException {
        Objects.requireNonNull(root, "root");
        File versionFile = new File(root, VERSION_FILE);
        if (!versionFile.isFile()) {
            throw new MojoExecutionException("Missing VERSION file: " + versionFile.getAbsolutePath());
        }
        try {
            return validate(Files.readString(versionFile.toPath(), StandardCharsets.UTF_8), versionFile.getPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to read VERSION file: " + versionFile.getAbsolutePath(), e);
        }
    }

    /**
     * Finds the repository root for a Maven session.
     *
     * @param session the Maven session
     * @return the repository root
     * @throws MojoExecutionException when no repository root can be found
     */
    public static File findRoot(MavenSession session) throws MojoExecutionException {
        File current = null;
        if (session != null && session.getRequest() != null && session.getRequest().getBaseDirectory() != null) {
            current = new File(session.getRequest().getBaseDirectory());
        }
        if ((current == null || !current.exists()) && session != null && session.getExecutionRootDirectory() != null) {
            current = new File(session.getExecutionRootDirectory());
        }
        if (current == null) {
            current = new File(System.getProperty("user.dir"));
        }
        current = current.getAbsoluteFile();
        while (current != null) {
            if (new File(current, VERSION_FILE).isFile()) {
                return current;
            }
            current = current.getParentFile();
        }
        throw new MojoExecutionException("Unable to find VERSION file from Maven session root.");
    }

    /**
     * Injects the resolved version into Maven properties.
     *
     * @param properties the Maven properties
     * @param version    the version to inject
     * @throws MojoExecutionException when an existing value conflicts with {@code VERSION}
     */
    public static void inject(Properties properties, String version) throws MojoExecutionException {
        put(properties, REVISION_PROPERTY, version);
        put(properties, BUS_VERSION_PROPERTY, version);
        put(properties, GROOM_VERSION_PROPERTY, version);
    }

    /**
     * Replaces known version placeholders in generated text.
     *
     * @param text    the text to process
     * @param version the resolved version
     * @return processed text
     */
    public static String replacePlaceholders(String text, String version) {
        if (text == null) {
            return null;
        }
        return text.replace("${revision}", version)
                .replace("${project.version}", version)
                .replace("${bus.version}", version)
                .replace("${groom.version}", version);
    }

    /**
     * Tests whether the text contains a Groom managed version placeholder.
     *
     * @param text the text to test
     * @return {@code true} when the text contains a managed placeholder
     */
    public static boolean hasManagedPlaceholder(String text) {
        return text != null && (text.contains("${revision}") || text.contains("${bus.version}")
                || text.contains("${groom.version}"));
    }

    /**
     * Reads a trimmed Maven user property.
     *
     * @param session the Maven session.
     * @param name    the property name.
     * @return the trimmed property value, or {@code null} when missing.
     */
    private static String property(MavenSession session, String name) {
        if (session == null || session.getUserProperties() == null) {
            return null;
        }
        String value = session.getUserProperties().getProperty(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Writes a managed version property after validating that it does not conflict with the repository version.
     *
     * @param properties the property container to update.
     * @param name       the property name.
     * @param version    the resolved repository version.
     * @throws MojoExecutionException when an existing value conflicts with {@code VERSION}.
     */
    private static void put(Properties properties, String name, String version) throws MojoExecutionException {
        String current = properties.getProperty(name);
        if (current != null && !current.isBlank() && !current.trim().equals(version)) {
            throw new MojoExecutionException(
                    "Maven property '" + name + "' is '" + current.trim() + "' but VERSION is '" + version + "'.");
        }
        properties.setProperty(name, version);
    }

    /**
     * Validates and trims a version value.
     *
     * @param raw    the raw version value.
     * @param source the source used in validation error messages.
     * @return the validated version.
     * @throws MojoExecutionException when the version is blank or malformed.
     */
    private static String validate(String raw, String source) throws MojoExecutionException {
        String version = raw == null ? "" : raw.trim();
        if (version.isEmpty()) {
            throw new MojoExecutionException("Version is empty in " + source + ".");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new MojoExecutionException("Invalid version '" + version + "' in " + source + ".");
        }
        return version;
    }

}
