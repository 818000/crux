package org.miaixz.maven.groom.extendedinterpolation;

/**
 * Runtime exception used when the extended interpolation bridge cannot be created or invoked.
 */
public class ExtendedModelInterpolatorException extends RuntimeException {

    /**
     * Creates an exception that wraps the underlying interpolation bridge failure.
     *
     * @param cause the underlying failure
     */
    public ExtendedModelInterpolatorException(Throwable cause) {
        super(cause.getMessage(), cause);
    }

}
