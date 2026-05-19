package org.miaixz.maven.groom.extendedinterpolation;

import javax.inject.Named;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.interpolation.StringVisitorModelInterpolator;
import org.codehaus.plexus.interpolation.ValueSource;

/**
 * String-search model interpolator that can resolve values from a separate effective model.
 */
@Named
public class ExtendedStringSearchModelInterpolator extends StringVisitorModelInterpolator
        implements ExtendedModelInterpolator {

    /**
     * Expressions that must keep their original model-local values during interpolation.
     */
    private static final List<String> NOT_INTERPOLATABLES = Stream.of(
                    "basedir",
                    "baseUri",
                    "build.directory",
                    "build.outputDirectory",
                    "build.sourceDirectory",
                    "build.scriptSourceDirectory",
                    "build.testSourceDirectory",
                    "reporting.outputDirectory")
            .flatMap(suffix -> Stream.of(suffix, "pom." + suffix, "project." + suffix))
            .collect(Collectors.toList());

    /**
     * Effective model currently used as value-source origin for interpolation.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Optional<Model> valueSourceOriginModel = Optional.empty();

    /**
     * Creates the interpolator and wires the value-source wrapper class loader.
     */
    public ExtendedStringSearchModelInterpolator() {
        FilteringValueSourceWrapper.setClassLoader(getClass().getSuperclass().getClassLoader());
    }

    /**
     * Creates value sources, optionally from the effective model currently being used as the interpolation origin.
     *
     * @param model the model being interpolated
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     * @return the value sources used by the interpolation process
     */
    @Override
    protected List<ValueSource> createValueSources(
            Model model, File projectDir, ModelBuildingRequest config, ModelProblemCollector problems) {

        if (valueSourceOriginModel.isPresent()) {
            return FilteringValueSourceWrapper.wrap(
                    super.createValueSources(this.valueSourceOriginModel.get(), projectDir, config, problems),
                    this::interpolatable);
        }
        return super.createValueSources(model, projectDir, config, problems);
    }

    /**
     * Tests whether the supplied expression may be interpolated from the effective model.
     *
     * @param expression the expression name
     * @return {@code true} when the expression can be resolved from the effective model
     */
    private boolean interpolatable(String expression) {

        return !NOT_INTERPOLATABLES.contains(expression);
    }

    /**
     * Interpolates the supplied model while resolving values from the effective model.
     *
     * @param valueSourceOriginModel the effective model used as the value source
     * @param model the model whose text values should be interpolated
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     * @return the interpolated model
     */
    @Override
    public Model interpolateModel(
            Model valueSourceOriginModel,
            Model model,
            File projectDir,
            ModelBuildingRequest config,
            ModelProblemCollector problems) {
        if (valueSourceOriginModel == null) {
            throw new IllegalArgumentException("effectiveModel is null");
        }

        if (model == null) {
            throw new IllegalArgumentException("model is null");
        }

        this.valueSourceOriginModel = Optional.of(valueSourceOriginModel);
        try {
            return super.interpolateModel(model, projectDir, config, problems);
        } finally {
            this.valueSourceOriginModel = Optional.empty();
        }
    }
    
}
