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
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * This MOJO realizes the goal <code>groom:clean</code> that deletes any files created by
 * <code>{@link GroomMojo groom:normalize}</code> (more specific the groomed POM file which is by default
 * <code>.groomed-pom.xml</code>).
 *
 * @author Joerg Hohwiller (hohwille at users.sourceforge.net)
 * @since 1.0.0-beta-2
 */
@Mojo(
        name = "clean",
        defaultPhase = LifecyclePhase.CLEAN,
        requiresProject = true,
        requiresDirectInvocation = false,
        executionStrategy = "once-per-session",
        threadSafe = true)
public class CleanMojo extends AbstractGroomMojo {

    /**
     * If {@code true} the clean goal will be skipped.
     *
     * @since 1.6.0
     */
    @Parameter(property = "groom.clean.skip", defaultValue = "false")
    private boolean skipClean;

    /**
     * The constructor.
     */
    public CleanMojo() {
        super();
    }

    /**
     * Deletes the generated groomed POM file when it exists.
     *
     * @throws MojoExecutionException when Maven cannot execute the goal
     * @throws MojoFailureException when the groomed POM file cannot be deleted
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip()) {
            getLog().info("Clean skipped.");
            return;
        }

        Path groomedPomFile = getGroomedPomFile();
        if (Files.isRegularFile(groomedPomFile)) {
            getLog().info("Deleting " + groomedPomFile);
            try {
                Files.delete(groomedPomFile);
            } catch (IOException e) {
                throw new MojoFailureException("Could not delete " + groomedPomFile, e);
            }
        }
    }

    /**
     * Tests whether the clean goal should be skipped.
     *
     * @return {@code true} when the clean skip parameter is enabled
     */
    @Override
    protected boolean shouldSkipGoal() {
        return skipClean;
    }
    
}
