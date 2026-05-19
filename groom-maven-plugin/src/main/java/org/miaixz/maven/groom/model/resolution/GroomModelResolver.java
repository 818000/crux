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
import java.util.List;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;

/**
 * This is a custom implementation of {@link ModelResolver} to emulate the maven POM resolution in order to build the
 * flattened POM.
 *
 * @author Robert Scholte
 * @see org.miaixz.maven.groom.GroomMojo
 */
public class GroomModelResolver implements ModelResolver {

    /**
     * Repository system session used for artifact resolution.
     */
    private final RepositorySystemSession session;

    /**
     * Repository system used to resolve POM artifacts and version ranges.
     */
    private final RepositorySystem repositorySystem;

    /**
     * Aether request trace propagated to repository resolution requests.
     */
    private final RequestTrace trace;

    /**
     * Repository resolution context used by Aether requests.
     */
    private final String context;

    /**
     * Remote repositories available for model resolution.
     */
    private final List<RemoteRepository> repositories;

    /**
     * The modules of the project being built.
     */
    private final ReactorModelPool reactorModelPool;

    /**
     * Creates a model resolver for the current Maven session and reactor projects.
     *
     * @param session          the repository system session used for artifact resolution.
     * @param repositorySystem the repository system used to resolve POM artifacts.
     * @param trace            the request trace propagated to repository resolution requests.
     * @param context          the repository resolution context.
     * @param repositories     the remote repositories available for resolution.
     * @param reactorModels    the Maven projects already present in the current reactor.
     */
    public GroomModelResolver(
            RepositorySystemSession session,
            RepositorySystem repositorySystem,
            RequestTrace trace,
            String context,
            List<RemoteRepository> repositories,
            List<MavenProject> reactorModels) {
        this.session = session;
        this.repositorySystem = repositorySystem;
        this.trace = trace;
        this.context = context;
        this.repositories = repositories;

        this.reactorModelPool = new ReactorModelPool();
        reactorModelPool.addProjects(reactorModels);
    }

    /**
     * Creates a resolver copy that shares immutable resolution state with the source resolver.
     *
     * @param other the resolver to copy.
     */
    private GroomModelResolver(GroomModelResolver other) {
        this.session = other.session;
        this.repositorySystem = other.repositorySystem;
        this.trace = other.trace;
        this.context = other.context;
        this.repositories = other.repositories;
        this.reactorModelPool = other.reactorModelPool;
    }

    /**
     * Resolves a POM model by Maven coordinates.
     *
     * @param groupId    the group identifier of the POM artifact.
     * @param artifactId the artifact identifier of the POM artifact.
     * @param version    the version of the POM artifact.
     * @return the model source for the resolved POM artifact.
     * @throws UnresolvableModelException if the POM artifact cannot be resolved.
     */
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        File pomFile = reactorModelPool.find(groupId, artifactId, version);
        if (pomFile == null) {
            Artifact pomArtifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);

            try {
                ArtifactRequest request = new ArtifactRequest(pomArtifact, repositories, context);
                request.setTrace(trace);
                pomArtifact = repositorySystem.resolveArtifact(session, request).getArtifact();
            } catch (ArtifactResolutionException e) {
                throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
            }

            pomFile = pomArtifact.getFile();
        }
        return new FileModelSource(pomFile);
    }

    /**
     * Ignores repository additions because artifact resolution has already been prepared by Maven core.
     *
     * @param repository the repository Maven core requested to add.
     */
    public void addRepository(Repository repository) {
        // ignoring... artifact resolution via repository should already have happened before by maven core.
    }

    /**
     * Creates an independent resolver instance for Maven model building callbacks.
     *
     * @return a resolver copy for Maven model building.
     */
    public ModelResolver newCopy() {
        return new GroomModelResolver(this);
    }

    /**
     * Resolves the POM for the specified parent.
     *
     * @param parent the parent coordinates to resolve, must not be {@code null}
     * @return The source of the requested POM, never {@code null}
     * @throws UnresolvableModelException if the parent POM cannot be resolved.
     * @since Apache-Maven-3.2.2 (MNG-5639)
     */
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        parent.setVersion(resolveVersion(parent.getGroupId(), parent.getArtifactId(), parent.getVersion()));
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    /**
     * Resolves the POM for the specified dependency coordinates.
     *
     * @param dependency the dependency coordinates to resolve, must not be {@code null}.
     * @return the source of the requested POM, never {@code null}.
     * @throws UnresolvableModelException if the dependency POM cannot be resolved.
     */
    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        dependency.setVersion(
                resolveVersion(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion()));
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    /**
     * Resolves a version range to the highest bounded version accepted by Maven model resolution.
     *
     * @param groupId    the group identifier of the POM artifact.
     * @param artifactId the artifact identifier of the POM artifact.
     * @param version    the version or version range to resolve.
     * @return the resolved concrete version.
     * @throws UnresolvableModelException if the version range cannot be resolved.
     */
    private String resolveVersion(String groupId, String artifactId, String version) throws UnresolvableModelException {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);

        VersionRangeRequest versionRangeRequest = new VersionRangeRequest(artifact, repositories, context);
        versionRangeRequest.setTrace(trace);

        try {
            VersionRangeResult versionRangeResult = repositorySystem.resolveVersionRange(session, versionRangeRequest);

            if (versionRangeResult.getHighestVersion() == null) {
                throw new UnresolvableModelException(
                        "No versions matched the requested range '" + version + "'", groupId, artifactId, version);
            }

            if (versionRangeResult.getVersionConstraint() != null
                    && versionRangeResult.getVersionConstraint().getRange() != null
                    && versionRangeResult.getVersionConstraint().getRange().getUpperBound() == null) {
                throw new UnresolvableModelException(
                        "The requested version range '" + version + "' does not specify an upper bound",
                        groupId,
                        artifactId,
                        version);
            }

            return versionRangeResult.getHighestVersion().toString();
        } catch (VersionRangeResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
    }

    /**
     * Ignores repository additions because artifact resolution has already been prepared by Maven core.
     *
     * @param repository The repository to add to the internal search chain, must not be {@code null}.
     * @param replace    {true} when repository with same id should be replaced, otherwise {@code false}.
     * @since Apache-Maven-3.2.3 (MNG-5663)
     */
    public void addRepository(Repository repository, boolean replace) {
        // ignoring... artifact resolution via repository should already have happened before by maven core.
    }

}
