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
import java.io.Reader;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.locator.ModelLocator;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.sisu.Priority;
import org.eclipse.sisu.Typed;

/**
 * Maven model processor that resolves Groom version placeholders before Maven resolves parent models and plugins.
 */
@Named("core-default")
@Singleton
@Typed(ModelProcessor.class)
@Priority(1000)
public class GroomModelProcessor extends DefaultModelProcessor {

    /**
     * Creates a model processor.
     */
    public GroomModelProcessor() {
        super();
    }

    /**
     * Sets the model locator used by Maven.
     *
     * @param locator the model locator
     * @return this model processor
     */
    @Inject
    @Override
    public GroomModelProcessor setModelLocator(ModelLocator locator) {
        super.setModelLocator(locator);
        return this;
    }

    /**
     * Sets the model reader used by Maven.
     *
     * @param reader the model reader
     * @return this model processor
     */
    @Inject
    @Override
    public GroomModelProcessor setModelReader(ModelReader reader) {
        super.setModelReader(reader);
        return this;
    }

    /**
     * Reads a model from a POM file and resolves managed version placeholders.
     *
     * @param input   the POM file
     * @param options model reader options
     * @return the processed model
     * @throws IOException when the model cannot be read
     */
    @Override
    public Model read(File input, Map<String, ?> options) throws IOException {
        Model model = super.read(input, options);
        return resolveModel(model, input == null ? null : input.getParentFile());
    }

    /**
     * Reads a model from a reader and resolves managed version placeholders.
     *
     * @param input   the reader
     * @param options model reader options
     * @return the processed model
     * @throws IOException when the model cannot be read
     */
    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException {
        return resolveModel(super.read(input, options), null);
    }

    /**
     * Reads a model from an input stream and resolves managed version placeholders.
     *
     * @param input   the input stream
     * @param options model reader options
     * @return the processed model
     * @throws IOException when the model cannot be read
     */
    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        return resolveModel(super.read(input, options), null);
    }

    /**
     * Resolves all managed version placeholders in a Maven model.
     *
     * @param model         the model to inspect and update.
     * @param baseDirectory the directory used to locate the repository {@code VERSION} file.
     * @return the updated model, or {@code null} when the input model is {@code null}.
     * @throws IOException when the repository version cannot be resolved.
     */
    private Model resolveModel(Model model, File baseDirectory) throws IOException {
        if (model == null || !hasManagedPlaceholder(model)) {
            return model;
        }
        String projectVersion = resolveProjectVersion(baseDirectory);
        String groomVersion = resolveGroomVersion();
        model.setVersion(replace(model.getVersion(), projectVersion, groomVersion));
        Parent parent = model.getParent();
        if (parent != null) {
            parent.setVersion(replace(parent.getVersion(), projectVersion, groomVersion));
        }
        replaceModelBase(model, projectVersion, groomVersion);
        replaceBuild(model.getBuild(), projectVersion, groomVersion);
        for (Profile profile : model.getProfiles()) {
            replaceModelBase(profile, projectVersion, groomVersion);
            replaceBuild(profile.getBuild(), projectVersion, groomVersion);
        }
        return model;
    }

    /**
     * Resolves the repository version from the nearest {@code VERSION} file.
     *
     * @param baseDirectory the directory used as the root search point.
     * @return the resolved repository version.
     * @throws IOException when the version cannot be read.
     */
    private static String resolveProjectVersion(File baseDirectory) throws IOException {
        try {
            return GroomVersion.readProjectVersion(findRoot(baseDirectory));
        } catch (MojoExecutionException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Resolves the version of the Groom extension currently loaded by Maven.
     *
     * @return the loaded Groom extension version.
     * @throws IOException when the embedded Maven metadata cannot be read.
     */
    private static String resolveGroomVersion() throws IOException {
        try {
            return GroomVersion.resolveGroomVersion();
        } catch (MojoExecutionException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Finds the repository root that contains the {@code VERSION} file.
     *
     * @param baseDirectory the directory used as the root search point.
     * @return the repository root.
     * @throws MojoExecutionException when no {@code VERSION} file can be found.
     */
    private static File findRoot(File baseDirectory) throws MojoExecutionException {
        File current = baseDirectory == null ? new File(System.getProperty("user.dir")) : baseDirectory;
        current = current.getAbsoluteFile();
        while (current != null) {
            if (new File(current, GroomVersion.VERSION_FILE).isFile()) {
                return current;
            }
            current = current.getParentFile();
        }
        throw new MojoExecutionException("Unable to find VERSION file for Groom managed Maven model.");
    }

    /**
     * Checks whether a full model contains any managed version placeholder.
     *
     * @param model the Maven model to inspect.
     * @return {@code true} when the model contains a managed placeholder.
     */
    private static boolean hasManagedPlaceholder(Model model) {
        if (GroomVersion.hasManagedPlaceholder(model.getVersion())) {
            return true;
        }
        Parent parent = model.getParent();
        if (parent != null && GroomVersion.hasManagedPlaceholder(parent.getVersion())) {
            return true;
        }
        return hasManagedPlaceholder((ModelBase) model) || hasManagedPlaceholder(model.getBuild())
                || model.getProfiles().stream().anyMatch(GroomModelProcessor::hasManagedPlaceholder);
    }

    /**
     * Checks whether a Maven profile contains any managed version placeholder.
     *
     * @param profile the Maven profile to inspect.
     * @return {@code true} when the profile contains a managed placeholder.
     */
    private static boolean hasManagedPlaceholder(Profile profile) {
        return hasManagedPlaceholder((ModelBase) profile) || hasManagedPlaceholder(profile.getBuild());
    }

    /**
     * Checks whether a model base contains any managed version placeholder.
     *
     * @param model the model base to inspect.
     * @return {@code true} when the model base contains a managed placeholder.
     */
    private static boolean hasManagedPlaceholder(ModelBase model) {
        return propertiesHaveManagedPlaceholder(model.getProperties())
                || dependenciesHaveManagedPlaceholder(model.getDependencies())
                || dependencyManagementHasManagedPlaceholder(model.getDependencyManagement())
                || reportingHasManagedPlaceholder(model.getReporting());
    }

    /**
     * Checks whether model properties contain any managed version placeholder.
     *
     * @param properties the properties to inspect.
     * @return {@code true} when at least one property value contains a managed placeholder.
     */
    private static boolean propertiesHaveManagedPlaceholder(Properties properties) {
        return properties.values().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .anyMatch(GroomVersion::hasManagedPlaceholder);
    }

    /**
     * Checks whether dependency management contains any managed version placeholder.
     *
     * @param management the dependency management section to inspect.
     * @return {@code true} when at least one managed dependency contains a managed placeholder.
     */
    private static boolean dependencyManagementHasManagedPlaceholder(DependencyManagement management) {
        return management != null && dependenciesHaveManagedPlaceholder(management.getDependencies());
    }

    /**
     * Checks whether dependencies contain any managed version placeholder.
     *
     * @param dependencies the dependencies to inspect.
     * @return {@code true} when at least one dependency contains a managed placeholder.
     */
    private static boolean dependenciesHaveManagedPlaceholder(Iterable<Dependency> dependencies) {
        for (Dependency dependency : dependencies) {
            if (GroomVersion.hasManagedPlaceholder(dependency.getVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a build section contains any managed version placeholder.
     *
     * @param build the build section to inspect.
     * @return {@code true} when the build section contains a managed placeholder.
     */
    private static boolean hasManagedPlaceholder(BuildBase build) {
        if (build == null) {
            return false;
        }
        if (pluginsHaveManagedPlaceholder(build.getPlugins())) {
            return true;
        }
        PluginManagement management = build.getPluginManagement();
        if (management != null && pluginsHaveManagedPlaceholder(management.getPlugins())) {
            return true;
        }
        if (build instanceof Build concreteBuild) {
            return extensionsHaveManagedPlaceholder(concreteBuild.getExtensions());
        }
        return false;
    }

    /**
     * Checks whether plugins contain any managed version placeholder.
     *
     * @param plugins the plugins to inspect.
     * @return {@code true} when at least one plugin contains a managed placeholder.
     */
    private static boolean pluginsHaveManagedPlaceholder(Iterable<Plugin> plugins) {
        for (Plugin plugin : plugins) {
            if (GroomVersion.hasManagedPlaceholder(plugin.getVersion())
                    || dependenciesHaveManagedPlaceholder(plugin.getDependencies())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether build extensions contain any managed version placeholder.
     *
     * @param extensions the extensions to inspect.
     * @return {@code true} when at least one extension contains a managed placeholder.
     */
    private static boolean extensionsHaveManagedPlaceholder(Iterable<Extension> extensions) {
        for (Extension extension : extensions) {
            if (GroomVersion.hasManagedPlaceholder(extension.getVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether reporting plugins contain any managed version placeholder.
     *
     * @param reporting the reporting section to inspect.
     * @return {@code true} when at least one reporting plugin contains a managed placeholder.
     */
    private static boolean reportingHasManagedPlaceholder(Reporting reporting) {
        if (reporting == null) {
            return false;
        }
        for (ReportPlugin plugin : reporting.getPlugins()) {
            if (GroomVersion.hasManagedPlaceholder(plugin.getVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces managed placeholders in model base properties, dependencies and reporting sections.
     *
     * @param model          the model base to update.
     * @param projectVersion the resolved repository version.
     * @param groomVersion   the loaded Groom extension version.
     */
    private static void replaceModelBase(ModelBase model, String projectVersion, String groomVersion) {
        model.getProperties().replaceAll(
                (name, value) -> value == null ? null : replace(value.toString(), projectVersion, groomVersion));
        model.getProperties().setProperty(GroomVersion.REVISION_PROPERTY, projectVersion);
        model.getProperties().setProperty(GroomVersion.BUS_VERSION_PROPERTY, projectVersion);
        model.getProperties().setProperty(GroomVersion.GROOM_VERSION_PROPERTY, groomVersion);
        replaceDependencies(model.getDependencies(), projectVersion, groomVersion);
        DependencyManagement management = model.getDependencyManagement();
        if (management != null) {
            replaceDependencies(management.getDependencies(), projectVersion, groomVersion);
        }
        replaceReporting(model.getReporting(), projectVersion, groomVersion);
    }

    /**
     * Replaces managed placeholders in build plugins, plugin management and extensions.
     *
     * @param build          the build section to update.
     * @param projectVersion the resolved repository version.
     * @param groomVersion   the loaded Groom extension version.
     */
    private static void replaceBuild(BuildBase build, String projectVersion, String groomVersion) {
        if (build == null) {
            return;
        }
        replacePlugins(build.getPlugins(), projectVersion, groomVersion);
        PluginManagement management = build.getPluginManagement();
        if (management != null) {
            replacePlugins(management.getPlugins(), projectVersion, groomVersion);
        }
        if (build instanceof Build concreteBuild) {
            for (Extension extension : concreteBuild.getExtensions()) {
                extension.setVersion(replace(extension.getVersion(), projectVersion, groomVersion));
            }
        }
    }

    /**
     * Replaces managed placeholders in dependency versions.
     *
     * @param dependencies   the dependencies to update.
     * @param projectVersion the resolved repository version.
     * @param groomVersion   the loaded Groom extension version.
     */
    private static void replaceDependencies(
            Iterable<Dependency> dependencies, String projectVersion, String groomVersion) {
        for (Dependency dependency : dependencies) {
            dependency.setVersion(replace(dependency.getVersion(), projectVersion, groomVersion));
        }
    }

    /**
     * Replaces managed placeholders in plugin versions and plugin dependencies.
     *
     * @param plugins        the plugins to update.
     * @param projectVersion the resolved repository version.
     * @param groomVersion   the loaded Groom extension version.
     */
    private static void replacePlugins(Iterable<Plugin> plugins, String projectVersion, String groomVersion) {
        for (Plugin plugin : plugins) {
            plugin.setVersion(replace(plugin.getVersion(), projectVersion, groomVersion));
            replaceDependencies(plugin.getDependencies(), projectVersion, groomVersion);
        }
    }

    /**
     * Replaces managed placeholders in reporting plugin versions.
     *
     * @param reporting      the reporting section to update.
     * @param projectVersion the resolved repository version.
     * @param groomVersion   the loaded Groom extension version.
     */
    private static void replaceReporting(Reporting reporting, String projectVersion, String groomVersion) {
        if (reporting == null) {
            return;
        }
        for (ReportPlugin plugin : reporting.getPlugins()) {
            plugin.setVersion(replace(plugin.getVersion(), projectVersion, groomVersion));
        }
    }

    /**
     * Replaces managed version placeholders in a single string value.
     *
     * @param value          the value to update.
     * @param projectVersion the resolved repository version.
     * @param groomVersion   the loaded Groom extension version.
     * @return the updated value.
     */
    private static String replace(String value, String projectVersion, String groomVersion) {
        return GroomVersion.replacePlaceholders(value, projectVersion, groomVersion);
    }

}
