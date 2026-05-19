package org.miaixz.maven.groom.extendedinterpolation;

import java.io.File;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.interpolation.ModelInterpolator;

/**
 * Model interpolator extension that allows one model to provide value sources while another model is interpolated.
 */
public interface ExtendedModelInterpolator extends ModelInterpolator {

    /**
     * Interpolates a model using values from the effective model.
     *
     * @param effectiveModel the effective model used as the value source
     * @param model the model whose text values should be interpolated
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     * @return the interpolated model
     */
    Model interpolateModel(
            Model effectiveModel,
            Model model,
            File projectDir,
            ModelBuildingRequest config,
            ModelProblemCollector problems);

}
