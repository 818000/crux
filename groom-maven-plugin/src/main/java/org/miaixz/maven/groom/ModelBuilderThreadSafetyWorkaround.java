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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.composition.DependencyManagementImporter;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.management.DependencyManagementInjector;
import org.apache.maven.model.management.PluginManagementInjector;
import org.apache.maven.model.normalization.ModelNormalizer;
import org.apache.maven.model.path.ModelPathTranslator;
import org.apache.maven.model.path.ModelUrlNormalizer;
import org.apache.maven.model.plugin.LifecycleBindingsInjector;
import org.apache.maven.model.plugin.PluginConfigurationExpander;
import org.apache.maven.model.plugin.ReportConfigurationExpander;
import org.apache.maven.model.plugin.ReportingConverter;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.apache.maven.model.validation.ModelValidator;
import org.eclipse.sisu.Nullable;

/**
 * Works around thread safety issues when modifying the global singleton
 * {@link org.apache.maven.model.building.DefaultModelBuilder DefaultModelBuilder}
 * with custom {@link ProfileInjector} and {@link ProfileSelector}. Instead of modifying the global
 * {@code DefaultModelBuilder}, this class
 * creates a new {@code DefaultModelBuilder} and equips it with the currently active components like
 * {@link ModelProcessor}, {@link ModelValidator} etc.
 * which might have been modified/provided by other Maven extensions.
 *
 * @author Falko Modler
 * @since 1.2.3
 */
@Named
public class ModelBuilderThreadSafetyWorkaround {

    /**
     * Maven model processor copied into each isolated model builder.
     */
    @Inject
    private ModelProcessor modelProcessor;

    /**
     * Maven model validator copied into each isolated model builder.
     */
    @Inject
    private ModelValidator modelValidator;

    /**
     * Maven model normalizer copied into each isolated model builder.
     */
    @Inject
    private ModelNormalizer modelNormalizer;

    /**
     * Maven model interpolator copied into each isolated model builder.
     */
    @Inject
    private ModelInterpolator modelInterpolator;

    /**
     * Maven model path translator copied into each isolated model builder.
     */
    @Inject
    private ModelPathTranslator modelPathTranslator;

    /**
     * Maven model URL normalizer copied into each isolated model builder.
     */
    @Inject
    private ModelUrlNormalizer modelUrlNormalizer;

    /**
     * Maven super POM provider copied into each isolated model builder.
     */
    @Inject
    private SuperPomProvider superPomProvider;

    /**
     * Inheritance assembler copied into each isolated model builder.
     */
    @Inject
    private DirectDependenciesInheritanceAssembler inheritanceAssembler;

    /**
     * Plugin management injector copied into each isolated model builder.
     */
    @Inject
    private PluginManagementInjector pluginManagementInjector;

    /**
     * Dependency management injector copied into each isolated model builder.
     */
    @Inject
    private DependencyManagementInjector dependencyManagementInjector;

    /**
     * Dependency management importer copied into each isolated model builder.
     */
    @Inject
    private DependencyManagementImporter dependencyManagementImporter;

    /**
     * Optional lifecycle bindings injector copied into each isolated model builder.
     */
    @Inject
    @Nullable
    private LifecycleBindingsInjector lifecycleBindingsInjector;

    /**
     * Plugin configuration expander copied into each isolated model builder.
     */
    @Inject
    private PluginConfigurationExpander pluginConfigurationExpander;

    /**
     * Report configuration expander copied into each isolated model builder.
     */
    @Inject
    private ReportConfigurationExpander reportConfigurationExpander;

    /**
     * Reporting converter copied into each isolated model builder.
     */
    @Inject
    private ReportingConverter reportingConverter;

    /**
     * Creates the workaround component for dependency injection.
     */
    public ModelBuilderThreadSafetyWorkaround() {
        super();
    }

    /**
     * Builds an effective model with the supplied profile components and the currently active Maven model components.
     *
     * @param buildingRequest is the Maven model building request.
     * @param customInjector  is the profile injector used for this isolated model build.
     * @param customSelector  is the profile selector used for this isolated model build.
     * @return the model building result produced by the isolated model builder.
     * @throws ModelBuildingException if Maven cannot build the requested model.
     */
    public ModelBuildingResult build(
            ModelBuildingRequest buildingRequest, ProfileInjector customInjector, ProfileSelector customSelector)
            throws ModelBuildingException {
        // note: there is neither DefaultModelBuilder.get*(), nor DefaultModelBuilder.clone()
        return new DefaultModelBuilderFactory()
                .newInstance()
                .setProfileInjector(customInjector)
                .setProfileSelector(customSelector)
                // apply currently active ModelProcessor etc. to support extensions like jgitver
                .setDependencyManagementImporter(dependencyManagementImporter)
                .setDependencyManagementInjector(dependencyManagementInjector)
                .setInheritanceAssembler(inheritanceAssembler)
                .setLifecycleBindingsInjector(lifecycleBindingsInjector)
                .setModelInterpolator(modelInterpolator)
                .setModelNormalizer(modelNormalizer)
                .setModelPathTranslator(modelPathTranslator)
                .setModelProcessor(modelProcessor)
                .setModelUrlNormalizer(modelUrlNormalizer)
                .setModelValidator(modelValidator)
                .setPluginConfigurationExpander(pluginConfigurationExpander)
                .setPluginManagementInjector(pluginManagementInjector)
                .setReportConfigurationExpander(reportConfigurationExpander)
                .setReportingConverter(reportingConverter)
                .setSuperPomProvider(superPomProvider)
                .build(buildingRequest);
    }

}
