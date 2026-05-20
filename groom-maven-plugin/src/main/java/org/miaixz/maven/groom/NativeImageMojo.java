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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Mojo that consolidates GraalVM native-image metadata and rewrites project jar artifacts.
 */
@Mojo(name = "native-image", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class NativeImageMojo extends AbstractGroomMojo {

    /**
     * The jar path segment that stores native-image metadata.
     */
    private static final String NATIVE_IMAGE_PATH = "META-INF/native-image";

    /**
     * The default vendor written to rewritten jar manifests.
     */
    private static final String DEFAULT_VENDOR = "miaixz.org";

    /**
     * Default module directory names excluded from native-image metadata scans.
     */
    private static final List<String> DEFAULT_NATIVE_IMAGE_EXCLUDED_MODULES = List.of("bus-all", "bus-bom");

    /**
     * Current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Root directory that contains the module directories to scan.
     */
    @Parameter(property = "groom.nativeImage.projectRootDirectory", defaultValue = "${project.basedir}/..")
    private File projectRootDirectory;

    /**
     * Project classes directory used as native-image metadata output base.
     */
    @Parameter(property = "groom.nativeImage.classesDirectory", defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;

    /**
     * Main jar file to rewrite.
     */
    @Parameter(property = "groom.nativeImage.jarFile", defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
    private File jarFile;

    /**
     * Sources jar file to rewrite.
     */
    @Parameter(property = "groom.nativeImage.sourcesJarFile", defaultValue = "${project.build.directory}/${project.build.finalName}-sources.jar")
    private File sourcesJarFile;

    /**
     * Native-image metadata version to consolidate.
     */
    @Parameter(property = "groom.nativeImage.version", defaultValue = "${project.version}")
    private String nativeImageVersion;

    /**
     * Module directory names excluded from native-image metadata scans.
     */
    @Parameter(property = "groom.nativeImage.excludedModules")
    private List<String> excludedModules = DEFAULT_NATIVE_IMAGE_EXCLUDED_MODULES;

    /**
     * If {@code true}, the native-image goal will be skipped.
     */
    @Parameter(property = "groom.nativeImage.skip", defaultValue = "false")
    private boolean skipNativeImage;

    /**
     * If {@code true}, the main project jar will be rewritten.
     */
    @Parameter(property = "groom.nativeImage.rewriteJar", defaultValue = "true")
    private boolean rewriteJar;

    /**
     * If {@code true}, the sources jar will be rewritten when it exists.
     */
    @Parameter(property = "groom.nativeImage.rewriteSourcesJar", defaultValue = "true")
    private boolean rewriteSourcesJar;

    /**
     * Vendor value written into rewritten jar manifests.
     */
    @Parameter(property = "groom.nativeImage.vendor", defaultValue = DEFAULT_VENDOR)
    private String vendor;

    /**
     * Constructs a new native-image goal.
     */
    public NativeImageMojo() {

        super();
    }

    /**
     * Executes native-image metadata consolidation and artifact rewriting.
     *
     * @throws MojoExecutionException when the goal cannot complete
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (shouldSkip()) {
            getLog().info("Native-image grooming skipped.");
            return;
        }
        try {
            Path nativeImageDirectory = nativeImageDirectory();
            deleteDirectory(nativeImageDirectory);
            NativeImageMerger merger = new NativeImageMerger(
                    absolute(projectRootDirectory),
                    nativeImageDirectory.resolve(modulePath()),
                    moduleIdentifier(),
                    effectiveNativeImageExcludedModules(),
                    getLog());
            merger.execute(nativeImageVersion);

            NativeImageJarRewriter rewriter = new NativeImageJarRewriter(
                    project.getArtifactId(), project.getVersion(), vendor);
            if (rewriteJar) {
                rewriter.rewrite(absolute(jarFile), nativeImageDirectory);
            }
            if (rewriteSourcesJar) {
                rewriter.rewrite(absolute(sourcesJarFile), nativeImageDirectory);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Could not groom native-image metadata", e);
        }
    }

    /**
     * Tests whether the native-image goal should be skipped.
     *
     * @return {@code true} when the goal-specific skip flag is enabled
     */
    @Override
    protected boolean shouldSkipGoal() {
        return skipNativeImage;
    }

    /**
     * Resolves the native-image metadata directory under project classes.
     *
     * @return the native-image metadata directory
     */
    private Path nativeImageDirectory() {
        return absolute(classesDirectory).resolve(NATIVE_IMAGE_PATH);
    }

    /**
     * Returns the Maven module identifier.
     *
     * @return the Maven module identifier
     */
    private String moduleIdentifier() {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    /**
     * Returns the native-image module path.
     *
     * @return the native-image module path
     */
    private Path modulePath() {
        return Path.of(project.getGroupId(), project.getArtifactId());
    }

    /**
     * Resolves the effective native-image module exclusions.
     *
     * @return the effective native-image module exclusions
     */
    private LinkedHashSet<String> effectiveNativeImageExcludedModules() {
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        if (excludedModules != null) {
            addModuleNames(modules, excludedModules);
        }
        if (modules.isEmpty()) {
            addModuleNames(modules, DEFAULT_NATIVE_IMAGE_EXCLUDED_MODULES);
        }
        return modules;
    }

    /**
     * Adds normalized module names to the target set.
     *
     * @param target the target module name set
     * @param source the source module names
     */
    private void addModuleNames(Set<String> target, Collection<String> source) {
        for (String module : source) {
            if (module == null) {
                continue;
            }
            String name = module.trim();
            if (!name.isEmpty()) {
                target.add(name);
            }
        }
    }

    /**
     * Resolves a file to an absolute normalized path.
     *
     * @param file the file to resolve
     * @return the absolute normalized path
     */
    private Path absolute(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

    /**
     * Deletes a directory recursively when it exists.
     *
     * @param directory the directory to delete
     * @throws IOException when deletion fails
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

}
