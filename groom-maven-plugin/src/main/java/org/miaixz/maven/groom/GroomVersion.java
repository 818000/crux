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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * Resolves and validates repository and Groom extension versions.
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
     * Maven property containing the version of the loaded Groom extension.
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
    public static String resolveProjectVersion(MavenSession session) throws MojoExecutionException {
        File root = findRoot(session);
        return readProjectVersion(root);
    }

    /**
     * Resolves the version for generated build output.
     *
     * @param session the Maven session
     * @param project the Maven project
     * @return the resolved version
     * @throws MojoExecutionException when the version cannot be resolved
     */
    public static String resolveProjectVersion(MavenSession session, MavenProject project)
            throws MojoExecutionException {
        String projectVersion = property(session, REVISION_PROPERTY);
        if (projectVersion != null) {
            return validate(projectVersion, REVISION_PROPERTY);
        }
        if (project != null && project.getVersion() != null && !project.getVersion().isBlank()) {
            return validate(project.getVersion(), "project.version");
        }
        return resolveProjectVersion(session);
    }

    /**
     * Reads the version from the {@code VERSION} file under the given repository root.
     *
     * @param root the repository root
     * @return the validated version
     * @throws MojoExecutionException when the version file is missing or invalid
     */
    public static String readProjectVersion(File root) throws MojoExecutionException {
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
     * Injects the resolved project and Groom extension versions into Maven properties.
     *
     * @param properties     the Maven properties
     * @param projectVersion the repository project version
     * @throws MojoExecutionException when an existing value conflicts with a managed version
     */
    public static void inject(Properties properties, String projectVersion) throws MojoExecutionException {
        put(properties, REVISION_PROPERTY, projectVersion, VERSION_FILE);
        put(properties, BUS_VERSION_PROPERTY, projectVersion, VERSION_FILE);
        put(properties, GROOM_VERSION_PROPERTY, resolveGroomVersion(), "loaded Groom extension");
    }

    /**
     * Resolves the version of the Groom extension currently loaded by Maven.
     *
     * @return the loaded Groom extension version
     * @throws MojoExecutionException when the embedded Maven metadata cannot be read
     */
    public static String resolveGroomVersion() throws MojoExecutionException {
        String path = "/META-INF/maven/org.miaixz.maven/groom-maven-plugin/pom.properties";
        Properties properties = new Properties();
        try (InputStream input = GroomVersion.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new MojoExecutionException("Missing Groom Maven metadata: " + path);
            }
            properties.load(input);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to read Groom Maven metadata: " + path, e);
        }
        return validate(properties.getProperty("version"), path);
    }

    /**
     * Replaces project and Groom plugin version placeholders in generated text.
     *
     * @param text           the text to process
     * @param projectVersion the resolved repository version
     * @param groomVersion   the loaded Groom extension version
     * @return processed text
     */
    public static String replacePlaceholders(String text, String projectVersion, String groomVersion) {
        return replaceGroomVersionPlaceholder(
                replaceProjectVersionPlaceholders(text, projectVersion), groomVersion);
    }

    /**
     * Replaces repository project version placeholders in generated text.
     *
     * @param text           the text to process
     * @param projectVersion the resolved repository version
     * @return processed text
     */
    public static String replaceProjectVersionPlaceholders(String text, String projectVersion) {
        if (text == null) {
            return null;
        }
        return text.replace("${revision}", projectVersion)
                .replace("${project.version}", projectVersion)
                .replace("${bus.version}", projectVersion);
    }

    /**
     * Replaces the Groom extension version placeholder in generated text.
     *
     * @param text         the text to process
     * @param groomVersion the loaded Groom extension version
     * @return processed text
     */
    public static String replaceGroomVersionPlaceholder(String text, String groomVersion) {
        return text == null ? null : text.replace("${groom.version}", groomVersion);
    }

    /**
     * Tests whether the text contains a Groom managed version placeholder.
     *
     * @param text the text to test
     * @return {@code true} when the text contains a managed placeholder
     */
    public static boolean hasManagedPlaceholder(String text) {
        return hasProjectVersionPlaceholder(text) || hasGroomVersionPlaceholder(text);
    }

    /**
     * Tests whether the text contains a managed repository project version placeholder.
     *
     * @param text the text to test
     * @return {@code true} when the text contains a managed project version placeholder
     */
    public static boolean hasProjectVersionPlaceholder(String text) {
        return text != null && (text.contains("${revision}") || text.contains("${bus.version}"));
    }

    /**
     * Tests whether the text contains the Groom extension version placeholder.
     *
     * @param text the text to test
     * @return {@code true} when the text contains the Groom extension version placeholder
     */
    public static boolean hasGroomVersionPlaceholder(String text) {
        return text != null && text.contains("${groom.version}");
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
     * Writes a managed version property after validating that it does not conflict with its source.
     *
     * @param properties the property container to update.
     * @param name       the property name.
     * @param version    the resolved version.
     * @param source     the version source used in validation errors.
     * @throws MojoExecutionException when an existing value conflicts with the resolved version.
     */
    private static void put(Properties properties, String name, String version, String source)
            throws MojoExecutionException {
        String current = properties.getProperty(name);
        if (current != null && !current.isBlank() && !current.trim().equals(version)) {
            throw new MojoExecutionException("Maven property '" + name + "' is '" + current.trim() + "' but "
                    + source + " is '" + version + "'.");
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
