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
import java.nio.file.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * This is the abstract base class for {@link AbstractMojo MOJOs} that realize the different goals of this plugin.
 *
 * @author Joerg Hohwiller (hohwille at users.sourceforge.net)
 */
public abstract class AbstractGroomMojo extends AbstractMojo {

    /**
     * The directory where the generated groomed POM file will be written to.
     */
    @Parameter(defaultValue = "${project.basedir}")
    private File outputDirectory;

    /**
     * The filename of the generated groomed POM file.
     */
    @Parameter(property = "groom.pom.filename", defaultValue = ".groomed-pom.xml")
    private String groomedPomFilename;

    /**
     * If {@code true} the plugin will be skipped.
     *
     * @since 1.6.0
     */
    @Parameter(property = "groom.skip", defaultValue = "false")
    private boolean skip;

    /**
     * The constructor.
     */
    public AbstractGroomMojo() {

        super();
    }

    /**
     * Returns the configured groomed POM filename.
     *
     * @return the filename of the generated groomed POM file.
     */
    public String getGroomedPomFilename() {
        return this.groomedPomFilename;
    }

    /**
     * Returns the directory where the generated groomed POM file will be written.
     *
     * @return the directory where the generated groomed POM file will be written to.
     */
    public File getOutputDirectory() {
        return this.outputDirectory;
    }

    /**
     * Resolves the complete path of the generated groomed POM file.
     *
     * @return a {@link File} instance pointing to the groomed POM.
     */
    protected Path getGroomedPomFile() {
        return getOutputDirectory().toPath().resolve(getGroomedPomFilename());
    }

    /**
     * Tests whether the current goal execution should be skipped.
     *
     * @return {@code true} when either the global skip flag or the goal-specific skip flag is enabled.
     */
    protected boolean shouldSkip() {
        if (skip) {
            return true;
        }
        return shouldSkipGoal();
    }

    /**
     * Tests whether a concrete goal execution should be skipped.
     *
     * @return {@code true} when the concrete goal should not run.
     */
    protected abstract boolean shouldSkipGoal();

}
