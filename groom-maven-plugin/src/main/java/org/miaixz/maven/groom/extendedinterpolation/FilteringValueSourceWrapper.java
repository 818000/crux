package org.miaixz.maven.groom.extendedinterpolation;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.codehaus.plexus.interpolation.ValueSource;

/**
 * Dynamic proxy wrapper that blocks selected interpolation expressions from a value source.
 */
public class FilteringValueSourceWrapper {

    /**
     * Class loader used to create value-source proxy instances.
     */
    private static ClassLoader classLoader;

    /**
     * Wrapped value source instance.
     */
    private final Object delegate;

    /**
     * Predicate used to decide whether an expression may be resolved.
     */
    private final Predicate<String> filter;

    /**
     * Value source interface loaded from the target class loader.
     */
    private final Class<?> valueSourceClass;

    /**
     * Creates a wrapper around one value source.
     *
     * @param delegate the value source instance to wrap
     * @param expressionFilter predicate used to decide whether expressions are allowed
     */
    private FilteringValueSourceWrapper(Object delegate, Predicate<String> expressionFilter) {
        this.delegate = delegate;
        this.filter = expressionFilter;
        try {
            this.valueSourceClass = classLoader.loadClass(ValueSource.class.getName());
        } catch (Exception e) {
            throw new ExtendedModelInterpolatorException(e);
        }
    }

    /**
     * Configures the class loader used for generated proxies.
     *
     * @param classLoader the class loader that can load the Plexus value-source interface
     */
    public static void setClassLoader(ClassLoader classLoader) {
        FilteringValueSourceWrapper.classLoader = classLoader;
    }

    /**
     * Wraps a list of value sources with the supplied expression filter.
     *
     * @param valueSources the value sources to wrap
     * @param expressionFilter predicate used to decide whether expressions are allowed
     * @return the wrapped value sources
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<ValueSource> wrap(List<ValueSource> valueSources, Predicate<String> expressionFilter) {
        return (List) new ArrayList<Object>(valueSources)
                .stream()
                .map(vs -> FilteringValueSourceWrapper.wrap(vs, expressionFilter))
                .collect(Collectors.toList());
    }

    /**
     * Wraps one value source with the supplied expression filter.
     *
     * @param valueSource the value source to wrap
     * @param expressionFilter predicate used to decide whether expressions are allowed
     * @return a proxy implementing the value-source interface
     */
    public static Object wrap(Object valueSource, Predicate<String> expressionFilter) {

        return new FilteringValueSourceWrapper(valueSource, expressionFilter).asProxy();
    }

    /**
     * Creates the proxy instance that enforces the expression filter.
     *
     * @return a proxy implementing the value-source interface
     */
    public Object asProxy() {

        return Proxy.newProxyInstance(classLoader, new Class[]{this.valueSourceClass}, (proxy, method, args) -> {
            if (method.getName().equals("getValue")) {
                if ((args.length != 1 && args.length != 3) || (args[0] != null && !(args[0] instanceof String))) {
                    throw new InternalError(
                            "The class " + valueSourceClass.getName() + " got a changed getValue method: " + method);
                }
                if (!filter.test((String) args[0])) {
                    return null;
                }
            }
            return method.invoke(this.delegate, args);
        });
    }

}
