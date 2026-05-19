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
package org.miaixz.maven.groom;

import javax.inject.Named;
import javax.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.inheritance.DefaultInheritanceAssembler;
import org.apache.maven.model.merge.MavenModelMerger;

/**
 * The DefaultInheritanceAssembler is encapsulating the InheritanceModelMerger.
 * The only way to override functionality needed is to define an own InheritanceAssembler
 * to provide the needed ModelMerger.
 * The container is providing the InheritanceAssembler.
 * This class will be configured in the META-INF/sisu/javax.inject.Named by the sisu-maven-plugin.
 * <p>
 * see issue https://github.com/mojohaus/flatten-maven-plugin/issues/220
 *
 * @author kemalsoysal
 * @author ralfluebeck
 */
@Singleton
@Named
public class DirectDependenciesInheritanceAssembler extends DefaultInheritanceAssembler {

    /**
     * Model merger used to apply inheritance with Groom-specific dependency behavior.
     */
    protected InheritanceModelMerger merger = new DirectDependenciesInheritanceModelMerger();

    /**
     * copied from super implementation because it is private
     */
    private static final String CHILD_DIRECTORY = "child-directory";

    /**
     * copied from super implementation because it is private
     */
    private static final String CHILD_DIRECTORY_PROPERTY = "project.directory";

    /**
     * Dependency inheritance mode selected by the current Groom execution.
     */
    protected GroomDependencyMode groomDependencyMode;

    /**
     * Creates an inheritance assembler with the Groom dependency-aware merger.
     */
    public DirectDependenciesInheritanceAssembler() {
    }

    /**
     * Merges parent model values into the child model.
     *
     * @param child the child model receiving inherited values
     * @param parent the parent model providing inherited values
     * @param request the model building request
     * @param problems the collector used to report model problems
     */
    @Override
    public void assembleModelInheritance(
            Model child, Model parent, ModelBuildingRequest request, ModelProblemCollector problems) {
        Map<Object, Object> hints = new HashMap<>();
        String childPath = child.getProperties().getProperty(CHILD_DIRECTORY_PROPERTY, child.getArtifactId());
        hints.put(CHILD_DIRECTORY, childPath);
        hints.put(MavenModelMerger.CHILD_PATH_ADJUSTMENT, getChildPathAdjustment(child, parent, childPath));
        merger.merge(child, parent, false, hints);
    }

    /**
     * copied from super implementation because it is private though the adjustment
     * is only for compatibility due to the comment with Maven 2.0
     *
     * @param child the child model
     * @param parent the parent model
     * @param childDirectory the child directory hint
     * @return the path adjustment used by Maven model inheritance
     */
    private String getChildPathAdjustment(Model child, Model parent, String childDirectory) {
        String adjustment = "";

        if (parent != null) {
            String childName = child.getArtifactId();

            /*
             * This logic (using filesystem, against wanted independence from the user
             * environment) exists only for the sake of backward-compat with 2.x (MNG-5000).
             * In general, it is wrong to base URL inheritance on the module directory names
             * as this information is unavailable for POMs in the repository. In other
             * words, modules where artifactId != moduleDirName will see different effective
             * URLs depending on how the model was constructed (from filesystem or from
             * repository).
             */
            if (child.getProjectDirectory() != null) {
                childName = child.getProjectDirectory().getName();
            }

            for (String module : parent.getModules()) {
                module = module.replace('\\', '/');

                if (module.regionMatches(true, module.length() - 4, ".xml", 0, 4)) {
                    module = module.substring(0, module.lastIndexOf('/') + 1);
                }

                String moduleName = module;
                if (moduleName.endsWith("/")) {
                    moduleName = moduleName.substring(0, moduleName.length() - 1);
                }

                int lastSlash = moduleName.lastIndexOf('/');

                moduleName = moduleName.substring(lastSlash + 1);

                if ((moduleName.equals(childName) || (moduleName.equals(childDirectory))) && lastSlash >= 0) {
                    adjustment = module.substring(0, lastSlash);
                    break;
                }
            }
        }

        return adjustment;
    }

    /**
     * Model merger that can suppress inherited dependencies for the direct dependency mode.
     */
    protected class DirectDependenciesInheritanceModelMerger
            extends DefaultInheritanceAssembler.InheritanceModelMerger {

        /**
         * Creates a model merger bound to the enclosing inheritance assembler configuration.
         */
        protected DirectDependenciesInheritanceModelMerger() {
            super();
        }

        /**
         * Merges a source model into a target model.
         *
         * @param target the target model receiving values
         * @param source the source model providing values
         * @param sourceDominant whether source values override target values
         * @param hints merge hints supplied by Maven
         */
        @Override
        public void merge(Model target, Model source, boolean sourceDominant, Map<?, ?> hints) {
            super.merge(target, source, sourceDominant, hints);
        }

        /**
         * Merges model dependencies unless Groom is configured to keep only direct dependencies.
         *
         * @param target the target model base receiving dependencies
         * @param source the source model base providing dependencies
         * @param sourceDominant whether source values override target values
         * @param context merge context supplied by Maven
         */
        @Override
        protected void mergeModelBase_Dependencies(
                ModelBase target, ModelBase source, boolean sourceDominant, Map<Object, Object> context) {
            if (groomDependencyMode == GroomDependencyMode.direct) {
                return;
            }
            super.mergeModelBase_Dependencies(target, source, sourceDominant, context);
        }
    }

}
