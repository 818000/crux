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

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Maven core extension that injects the repository {@code VERSION} value before project models are built.
 */
@Named
@Singleton
public class GroomVersionExtension extends AbstractMavenLifecycleParticipant {

    /**
     * Creates the Maven lifecycle participant used by Maven core extension loading.
     */
    public GroomVersionExtension() {
        super();
    }

    /**
     * Injects version properties into the Maven session.
     *
     * @param session the Maven session
     * @throws MavenExecutionException when the repository version is missing or invalid
     */
    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        try {
            GroomVersion.inject(session.getUserProperties(), GroomVersion.resolve(session));
        } catch (MojoExecutionException e) {
            throw new MavenExecutionException(e.getMessage(), e);
        }
    }
    
}
