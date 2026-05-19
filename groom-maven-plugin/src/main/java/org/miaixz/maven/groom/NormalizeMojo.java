package org.miaixz.maven.groom;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Alias goal for {@link GroomMojo} used by Bus publication POM generation.
 */
@Mojo(name = "normalize", requiresDependencyCollection = ResolutionScope.RUNTIME, threadSafe = true)
public class NormalizeMojo extends GroomMojo {

    /**
     * Creates a new normalize goal.
     */
    public NormalizeMojo() {
        super();
    }

}
