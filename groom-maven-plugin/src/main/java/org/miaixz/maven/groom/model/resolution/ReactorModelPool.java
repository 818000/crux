package org.miaixz.maven.groom.model.resolution;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.maven.project.MavenProject;

/**
 * Holds a list of models and allows to retrieve them by their coordinates.
 *
 * @author Christoph Böhme
 */
class ReactorModelPool {

    /**
     * Reactor project POM files keyed by Maven coordinates.
     */
    private final Map<Coordinates, File> models = new HashMap<>();

    /**
     * Finds a reactor project POM by Maven coordinates.
     *
     * @param groupId    the project group identifier.
     * @param artifactId the project artifact identifier.
     * @param version    the project version.
     * @return the reactor project POM file, or {@code null} when no matching project exists.
     */
    public File find(String groupId, String artifactId, String version) {
        return models.get(new Coordinates(groupId, artifactId, version));
    }

    /**
     * Adds all Maven projects from the current reactor.
     *
     * @param projects the Maven projects to add.
     */
    public void addProjects(List<MavenProject> projects) {
        projects.forEach(this::addProject);
    }

    /**
     * Adds a Maven project to the pool.
     *
     * @param project the Maven project to add.
     */
    public void addProject(MavenProject project) {
        Coordinates coordinates = new Coordinates(project.getGroupId(), project.getArtifactId(), project.getVersion());
        models.put(coordinates, project.getFile());
    }

    /**
     * Immutable Maven coordinate key used by the reactor model pool.
     */
    private static final class Coordinates {

        /**
         * Maven group identifier.
         */
        final String groupId;

        /**
         * Maven artifact identifier.
         */
        final String artifactId;

        /**
         * Maven project version.
         */
        final String version;

        /**
         * Creates a Maven coordinate key.
         *
         * @param groupId    the Maven group identifier.
         * @param artifactId the Maven artifact identifier.
         * @param version    the Maven project version.
         */
        Coordinates(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        /**
         * Compares this coordinate key to another object.
         *
         * @param obj the object to compare.
         * @return {@code true} when the other object has the same coordinates.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof Coordinates) {
                Coordinates other = (Coordinates) obj;
                return artifactId.equals(other.artifactId)
                        && groupId.equals(other.groupId)
                        && version.equals(other.version);
            }
            return false;
        }

        /**
         * Computes the hash code from Maven coordinates.
         *
         * @return the coordinate hash code.
         */
        @Override
        public int hashCode() {
            return Objects.hash(artifactId, groupId, version);
        }
    }

}
