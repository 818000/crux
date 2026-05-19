package org.miaixz.maven.groom.cifriendly;

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
import javax.inject.Singleton;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.interpolation.MavenBuildTimestamp;
import org.apache.maven.model.path.PathTranslator;
import org.apache.maven.model.path.UrlNormalizer;
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.InterpolationPostProcessor;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PrefixedValueSourceWrapper;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.ValueSource;
import org.codehaus.plexus.interpolation.util.ValueSourceUtils;

/**
 * Based on StringSearchModelInterpolator in maven-model-builder.
 */
@Named
@Singleton
public class CiModelInterpolator implements CiInterpolator {

    /**
     * Maven model expression prefixes that resolve against project values.
     */
    private static final List<String> PROJECT_PREFIXES = Arrays.asList("pom.", "project.");

    /**
     * Model expressions whose values must be translated to the current project directory.
     */
    private static final Collection<String> TRANSLATED_PATH_EXPRESSIONS;

    /**
     * Cached reflection metadata for model object interpolation.
     */
    private static final Map<Class<?>, InterpolateObjectAction.CacheItem> CACHED_ENTRIES =
            new ConcurrentHashMap<>(80, 0.75f, 2);
    // Empirical data from 3.x, actual =40

    static {
        Collection<String> translatedPrefixes = new HashSet<>();

        // MNG-1927, MNG-2124, MNG-3355:
        // If the build section is present and the project directory is
        // non-null, we should make
        // sure interpolation of the directories below uses translated paths.
        // Afterward, we'll double back and translate any paths that weren't
        // covered during interpolation via the
        // code below...
        translatedPrefixes.add("build.directory");
        translatedPrefixes.add("build.outputDirectory");
        translatedPrefixes.add("build.testOutputDirectory");
        translatedPrefixes.add("build.sourceDirectory");
        translatedPrefixes.add("build.testSourceDirectory");
        translatedPrefixes.add("build.scriptSourceDirectory");
        translatedPrefixes.add("reporting.outputDirectory");

        TRANSLATED_PATH_EXPRESSIONS = translatedPrefixes;
    }

    /**
     * Interpolator used to resolve CI-friendly placeholders.
     */
    private final Interpolator interpolator;

    /**
     * Recursion guard used while resolving nested expressions.
     */
    private RecursionInterceptor recursionInterceptor;

    /**
     * Maven path translator used for directory-like model values.
     */
    private final PathTranslator pathTranslator;

    /**
     * Maven URL normalizer used for URL-like model values.
     */
    private final UrlNormalizer urlNormalizer;

    /**
     * Creates the CI-friendly model interpolator.
     *
     * @param pathTranslator the path translator used for directory-like values
     * @param urlNormalizer the URL normalizer used for URL-like values
     */
    @Inject
    public CiModelInterpolator(PathTranslator pathTranslator, UrlNormalizer urlNormalizer) {
        this.pathTranslator = pathTranslator;
        this.urlNormalizer = urlNormalizer;
        this.interpolator = createInterpolator();
        this.recursionInterceptor = new PrefixAwareRecursionInterceptor(PROJECT_PREFIXES);
    }

    /**
     * Interpolates CI-friendly placeholders inside the supplied model.
     *
     * @param model the model to mutate
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     * @return the interpolated model
     */
    @Override
    public Model interpolateModel(
            Model model, File projectDir, ModelBuildingRequest config, ModelProblemCollector problems) {
        interpolateObject(model, model, projectDir, config, problems);

        return model;
    }

    /**
     * Interpolates CI-friendly placeholders in raw model content.
     *
     * @param modelContent the raw model content
     * @param model the model used as interpolation context
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     * @return the interpolated model content
     */
    public String interpolateModelContent(
            String modelContent,
            Model model,
            File projectDir,
            ModelBuildingRequest config,
            ModelProblemCollector problems) {
        try {
            List<? extends ValueSource> valueSources = createValueSources(model, projectDir, config, problems);
            List<? extends InterpolationPostProcessor> postProcessors = createPostProcessors(model, projectDir, config);

            return interpolateInternal(modelContent, valueSources, postProcessors, problems);
        } finally {
            getInterpolator().clearAnswers();
        }
    }

    /**
     * Interpolates CI-friendly placeholders inside one model object graph.
     *
     * @param obj the object graph root to mutate
     * @param model the model used as interpolation context
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     */
    protected void interpolateObject(
            Object obj, Model model, File projectDir, ModelBuildingRequest config, ModelProblemCollector problems) {
        try {
            List<? extends ValueSource> valueSources = createValueSources(model, projectDir, config, problems);
            List<? extends InterpolationPostProcessor> postProcessors = createPostProcessors(model, projectDir, config);

            new InterpolateObjectAction(obj, valueSources, postProcessors, this, problems).run();
        } finally {
            getInterpolator().clearAnswers();
        }
    }

    /**
     * Interpolates one string using the supplied value sources and post processors.
     *
     * @param src the source text
     * @param valueSources the value sources used to resolve placeholders
     * @param postProcessors post processors applied to resolved values
     * @param problems the collector used to report interpolation problems
     * @return the interpolated text
     */
    protected String interpolateInternal(
            String src,
            List<? extends ValueSource> valueSources,
            List<? extends InterpolationPostProcessor> postProcessors,
            ModelProblemCollector problems) {
        if (src != null && !src.contains("${revision}") && !src.contains("${sha1}") && !src.contains("${changelist}")) {
            return src;
        }

        String result = src;
        synchronized (this) {
            for (ValueSource vs : valueSources) {
                getInterpolator().addValueSource(vs);
            }

            for (InterpolationPostProcessor postProcessor : postProcessors) {
                getInterpolator().addPostProcessor(postProcessor);
            }

            try {
                try {
                    result = getInterpolator().interpolate(result, getRecursionInterceptor());
                } catch (InterpolationException e) {
                    problems.add(new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                            .setMessage(e.getMessage())
                            .setException(e));
                }

                getInterpolator().clearFeedback();
            } finally {
                for (ValueSource vs : valueSources) {
                    getInterpolator().removeValuesSource(vs);
                }

                for (InterpolationPostProcessor postProcessor : postProcessors) {
                    getInterpolator().removePostProcessor(postProcessor);
                }
            }
        }

        return result;
    }

    /**
     * Creates the expression interpolator used by this model interpolator.
     *
     * @return the configured interpolator
     */
    protected Interpolator createInterpolator() {
        CiInterpolatorImpl interpolator = new CiInterpolatorImpl();
        interpolator.setCacheAnswers(true);

        return interpolator;
    }

    /**
     * Reflection action that walks an object graph and interpolates string values in place.
     */
    private static final class InterpolateObjectAction implements Runnable {

        /**
         * Queue of objects still waiting for interpolation traversal.
         */
        private final LinkedList<Object> interpolationTargets;

        /**
         * Owning model interpolator.
         */
        private final CiModelInterpolator modelInterpolator;

        /**
         * Value sources used for placeholder resolution.
         */
        private final List<? extends ValueSource> valueSources;

        /**
         * Post processors applied to resolved values.
         */
        private final List<? extends InterpolationPostProcessor> postProcessors;

        /**
         * Collector used to report interpolation problems.
         */
        private final ModelProblemCollector problems;

        /**
         * Creates an object graph interpolation action.
         *
         * @param target the root object to mutate
         * @param valueSources the value sources used for placeholder resolution
         * @param postProcessors post processors applied to resolved values
         * @param modelInterpolator the owning model interpolator
         * @param problems the collector used to report interpolation problems
         */
        InterpolateObjectAction(
                Object target,
                List<? extends ValueSource> valueSources,
                List<? extends InterpolationPostProcessor> postProcessors,
                CiModelInterpolator modelInterpolator,
                ModelProblemCollector problems) {
            this.valueSources = valueSources;
            this.postProcessors = postProcessors;

            this.interpolationTargets = new LinkedList<>();
            interpolationTargets.add(target);

            this.modelInterpolator = modelInterpolator;

            this.problems = problems;
        }

        /**
         * Traverses all queued objects and interpolates each supported field.
         */
        @Override
        public void run() {
            while (!interpolationTargets.isEmpty()) {
                Object obj = interpolationTargets.removeFirst();

                traverseObjectWithParents(obj.getClass(), obj);
            }
        }

        /**
         * Interpolates one string value using the owning model interpolator.
         *
         * @param value the string value to interpolate
         * @return the interpolated value
         */
        private String interpolate(String value) {
            return modelInterpolator.interpolateInternal(value, valueSources, postProcessors, problems);
        }

        /**
         * Traverses the supplied object and its parent classes.
         *
         * @param cls the class currently being inspected
         * @param target the object instance being mutated
         */
        private void traverseObjectWithParents(Class<?> cls, Object target) {
            if (cls == null) {
                return;
            }

            CacheItem cacheEntry = getCacheEntry(cls);
            if (cacheEntry.isArray()) {
                evaluateArray(target, this);
            } else if (cacheEntry.isQualifiedForInterpolation) {
                cacheEntry.interpolate(target, this);

                traverseObjectWithParents(cls.getSuperclass(), target);
            }
        }

        /**
         * Returns cached reflection metadata for a class.
         *
         * @param cls the class to inspect
         * @return cached interpolation metadata
         */
        private CacheItem getCacheEntry(Class<?> cls) {
            CacheItem cacheItem = CACHED_ENTRIES.get(cls);
            if (cacheItem == null) {
                cacheItem = new CacheItem(cls);
                CACHED_ENTRIES.put(cls, cacheItem);
            }
            return cacheItem;
        }

        /**
         * Interpolates array entries and queues nested object values.
         *
         * @param target the array instance to mutate
         * @param ctx the current interpolation action
         */
        private static void evaluateArray(Object target, InterpolateObjectAction ctx) {
            int len = Array.getLength(target);
            for (int i = 0; i < len; i++) {
                Object value = Array.get(target, i);
                if (value != null) {
                    if (String.class == value.getClass()) {
                        String interpolated = ctx.interpolate((String) value);

                        if (!interpolated.equals(value)) {
                            Array.set(target, i, interpolated);
                        }
                    } else {
                        ctx.interpolationTargets.add(value);
                    }
                }
            }
        }

        /**
         * Cached reflection metadata for one class.
         */
        private static class CacheItem {

            /**
             * Whether the cached class represents an array type.
             */
            private final boolean isArray;

            /**
             * Whether the cached class can be traversed for interpolation.
             */
            private final boolean isQualifiedForInterpolation;

            /**
             * Fields that should be inspected or mutated during interpolation.
             */
            private final CacheField[] fields;

            /**
             * Tests whether a class can be traversed for interpolation.
             *
             * @param cls the class to inspect
             * @return {@code true} when the class can be traversed
             */
            private boolean isQualifiedForInterpolation(Class<?> cls) {
                return !cls.getName().startsWith("java");
            }

            /**
             * Tests whether a field can be traversed for interpolation.
             *
             * @param field the field to inspect
             * @param fieldType the field type
             * @return {@code true} when the field can be traversed
             */
            private boolean isQualifiedForInterpolation(Field field, Class<?> fieldType) {
                if (Map.class.equals(fieldType) && "locations".equals(field.getName())) {
                    return false;
                }

                // noinspection SimplifiableIfStatement
                if (fieldType.isPrimitive()) {
                    return false;
                }

                return !"parent".equals(field.getName());
            }

            /**
             * Creates cached interpolation metadata for a class.
             *
             * @param clazz the class to inspect
             */
            CacheItem(Class clazz) {
                this.isQualifiedForInterpolation = isQualifiedForInterpolation(clazz);
                this.isArray = clazz.isArray();
                List<CacheField> fields = new ArrayList<>();
                for (Field currentField : clazz.getDeclaredFields()) {
                    Class<?> type = currentField.getType();
                    if (isQualifiedForInterpolation(currentField, type)) {
                        if (String.class == type) {
                            if (!Modifier.isFinal(currentField.getModifiers())) {
                                fields.add(new StringField(currentField));
                            }
                        } else if (List.class.isAssignableFrom(type)) {
                            fields.add(new ListField(currentField));
                        } else if (Collection.class.isAssignableFrom(type)) {
                            throw new RuntimeException("We dont interpolate into collections, use a list instead");
                        } else if (Map.class.isAssignableFrom(type)) {
                            fields.add(new MapField(currentField));
                        } else {
                            fields.add(new ObjectField(currentField));
                        }
                    }
                }
                this.fields = fields.toArray(new CacheField[0]);
            }

            /**
             * Interpolates all cached fields on the supplied target.
             *
             * @param target the target object to mutate
             * @param interpolateObjectAction the active interpolation action
             */
            public void interpolate(Object target, InterpolateObjectAction interpolateObjectAction) {
                for (CacheField field : fields) {
                    field.interpolate(target, interpolateObjectAction);
                }
            }

            /**
             * Tests whether this cache item represents an array type.
             *
             * @return {@code true} when the cached class is an array
             */
            public boolean isArray() {
                return isArray;
            }
        }

        /**
         * Cached field accessor that knows how to interpolate one field type.
         */
        abstract static class CacheField {

            /**
             * Reflected field represented by this cache entry.
             */
            protected final Field field;

            /**
             * Creates a cached field accessor.
             *
             * @param field the reflected field to access
             */
            CacheField(Field field) {
                this.field = field;
            }

            /**
             * Interpolates this field on the supplied target.
             *
             * @param target the object instance to mutate
             * @param interpolateObjectAction the active interpolation action
             */
            void interpolate(Object target, InterpolateObjectAction interpolateObjectAction) {
                synchronized (field) {
                    boolean isAccessible = field.isAccessible();
                    field.setAccessible(true);
                    try {
                        doInterpolate(target, interpolateObjectAction);
                    } catch (IllegalArgumentException e) {
                        interpolateObjectAction.problems.add(
                                new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                                        .setMessage("Failed to interpolate field3: " + field + " on class: "
                                                + field.getType().getName())
                                        .setException(e)); // todo: Not entirely
                        // the same message
                    } catch (IllegalAccessException e) {
                        interpolateObjectAction.problems.add(
                                new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                                        .setMessage("Failed to interpolate field4: " + field + " on class: "
                                                + field.getType().getName())
                                        .setException(e));
                    } finally {
                        field.setAccessible(isAccessible);
                    }
                }
            }

            /**
             * Performs field-type-specific interpolation.
             *
             * @param target the object instance to mutate
             * @param ctx the active interpolation action
             * @throws IllegalAccessException if the field cannot be read or written
             */
            abstract void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException;

            /**
             * Returns the reflected field description.
             *
             * @return the reflected field description
             */
            @Override
            public String toString() {
                return field.toString();
            }
        }

        /**
         * Cached field accessor for string fields.
         */
        static final class StringField extends CacheField {

            /**
             * Creates a cached string field accessor.
             *
             * @param field the reflected field to access
             */
            StringField(Field field) {
                super(field);
            }

            /**
             * Interpolates a string field on the supplied target.
             *
             * @param target the object instance to mutate
             * @param ctx the active interpolation action
             * @throws IllegalAccessException if the field cannot be read or written
             */
            @Override
            void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
                String value = (String) field.get(target);
                if (value == null) {
                    return;
                }

                String interpolated = ctx.interpolate(value);

                if (!interpolated.equals(value)) {
                    field.set(target, interpolated);
                }
            }
        }

        /**
         * Cached field accessor for list fields.
         */
        static final class ListField extends CacheField {

            /**
             * Creates a cached list field accessor.
             *
             * @param field the reflected field to access
             */
            ListField(Field field) {
                super(field);
            }

            /**
             * Interpolates supported list entries on the supplied target.
             *
             * @param target the object instance to mutate
             * @param ctx the active interpolation action
             * @throws IllegalAccessException if the field cannot be read or written
             */
            @Override
            void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
                @SuppressWarnings("unchecked")
                List<Object> c = (List<Object>) field.get(target);
                if (c == null) {
                    return;
                }

                int size = c.size();
                Object value;
                for (int i = 0; i < size; i++) {

                    value = c.get(i);

                    if (value != null) {
                        if (String.class == value.getClass()) {
                            String interpolated = ctx.interpolate((String) value);

                            if (!interpolated.equals(value)) {
                                try {
                                    c.set(i, interpolated);
                                } catch (UnsupportedOperationException e) {
                                    return;
                                }
                            }
                        } else {
                            if (value.getClass().isArray()) {
                                evaluateArray(value, ctx);
                            } else {
                                ctx.interpolationTargets.add(value);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Cached field accessor for map fields.
         */
        static final class MapField extends CacheField {

            /**
             * Creates a cached map field accessor.
             *
             * @param field the reflected field to access
             */
            MapField(Field field) {
                super(field);
            }

            /**
             * Interpolates supported map values on the supplied target.
             *
             * @param target the object instance to mutate
             * @param ctx the active interpolation action
             * @throws IllegalAccessException if the field cannot be read or written
             */
            @Override
            void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
                @SuppressWarnings("unchecked")
                Map<Object, Object> m = (Map<Object, Object>) field.get(target);
                if (m == null || m.isEmpty()) {
                    return;
                }

                for (Map.Entry<Object, Object> entry : m.entrySet()) {
                    Object value = entry.getValue();

                    if (value == null) {
                        continue;
                    }

                    if (String.class == value.getClass()) {
                        String interpolated = ctx.interpolate((String) value);

                        if (!interpolated.equals(value)) {
                            try {
                                entry.setValue(interpolated);
                            } catch (UnsupportedOperationException ignore) {
                                // nop
                            }
                        }
                    } else if (value.getClass().isArray()) {
                        evaluateArray(value, ctx);
                    } else {
                        ctx.interpolationTargets.add(value);
                    }
                }
            }
        }

        /**
         * Cached field accessor for nested object fields.
         */
        static final class ObjectField extends CacheField {

            /**
             * Whether the nested object field stores an array.
             */
            private final boolean isArray;

            /**
             * Creates a cached nested object field accessor.
             *
             * @param field the reflected field to access
             */
            ObjectField(Field field) {
                super(field);
                this.isArray = field.getType().isArray();
            }

            /**
             * Queues the nested object or interpolates its array values.
             *
             * @param target the object instance to inspect
             * @param ctx the active interpolation action
             * @throws IllegalAccessException if the field cannot be read
             */
            @Override
            void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
                Object value = field.get(target);
                if (value != null) {
                    if (isArray) {
                        evaluateArray(value, ctx);
                    } else {
                        ctx.interpolationTargets.add(value);
                    }
                }
            }
        }
    }

    /**
     * Creates value sources used by model interpolation.
     *
     * @param model the model used as interpolation context
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @param problems the collector used to report interpolation problems
     * @return the value sources used for placeholder resolution
     */
    protected List<ValueSource> createValueSources(
            final Model model,
            final File projectDir,
            final ModelBuildingRequest config,
            final ModelProblemCollector problems) {
        Properties modelProperties = model.getProperties();

        ValueSource modelValueSource1 = new PrefixedObjectValueSource(PROJECT_PREFIXES, model, false);
        if (config.getValidationLevel() >= ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0) {
            modelValueSource1 = new ProblemDetectingValueSource(modelValueSource1, "pom.", "project.", problems);
        }

        ValueSource modelValueSource2 = new ObjectBasedValueSource(model);
        if (config.getValidationLevel() >= ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0) {
            modelValueSource2 = new ProblemDetectingValueSource(modelValueSource2, "", "project.", problems);
        }

        // NOTE: Order counts here!
        List<ValueSource> valueSources = new ArrayList<>(9);

        if (projectDir != null) {
            ValueSource basedirValueSource = new PrefixedValueSourceWrapper(
                    new AbstractValueSource(false) {
                            /**
                             * Resolves the project base directory expression.
                             *
                             * @param expression the expression name
                             * @return the project base directory or {@code null}
                             */
                            public Object getValue(String expression) {
                            if ("basedir".equals(expression)) {
                                return projectDir.getAbsolutePath();
                            }
                            return null;
                        }
                    },
                    PROJECT_PREFIXES,
                    true);
            valueSources.add(basedirValueSource);

            ValueSource baseUriValueSource = new PrefixedValueSourceWrapper(
                    new AbstractValueSource(false) {
                            /**
                             * Resolves the project base URI expression.
                             *
                             * @param expression the expression name
                             * @return the project base URI or {@code null}
                             */
                            public Object getValue(String expression) {
                            if ("baseUri".equals(expression)) {
                                return projectDir.getAbsoluteFile().toURI().toString();
                            }
                            return null;
                        }
                    },
                    PROJECT_PREFIXES,
                    false);
            valueSources.add(baseUriValueSource);
            valueSources.add(new BuildTimestampValueSource(config.getBuildStartTime(), modelProperties));
        }

        valueSources.add(modelValueSource1);

        valueSources.add(new MapBasedValueSource(config.getUserProperties()));

        valueSources.add(new MapBasedValueSource(modelProperties));

        valueSources.add(new MapBasedValueSource(config.getSystemProperties()));

        valueSources.add(new AbstractValueSource(false) {
            /**
             * Resolves environment variables from the model building request.
             *
             * @param expression the expression name
             * @return the environment property value or {@code null}
             */
            public Object getValue(String expression) {
                return config.getSystemProperties().getProperty("env." + expression);
            }
        });

        valueSources.add(modelValueSource2);

        return valueSources;
    }

    /**
     * Creates post processors used after placeholder resolution.
     *
     * @param model the model used as interpolation context
     * @param projectDir the project directory used for path interpolation
     * @param config the model building request
     * @return the post processors used by interpolation
     */
    protected List<? extends InterpolationPostProcessor> createPostProcessors(
            final Model model, final File projectDir, final ModelBuildingRequest config) {
        List<InterpolationPostProcessor> processors = new ArrayList<>(2);
        if (projectDir != null) {
            processors.add(new PathTranslatingPostProcessor(
                    PROJECT_PREFIXES, TRANSLATED_PATH_EXPRESSIONS, projectDir, pathTranslator));
        }
        processors.add(new UrlNormalizingPostProcessor(urlNormalizer));
        return processors;
    }

    /**
     * Returns the recursion interceptor used by this interpolator.
     *
     * @return the recursion interceptor
     */
    protected RecursionInterceptor getRecursionInterceptor() {
        return recursionInterceptor;
    }

    /**
     * Configures the recursion interceptor used by this interpolator.
     *
     * @param recursionInterceptor the recursion interceptor
     */
    protected void setRecursionInterceptor(RecursionInterceptor recursionInterceptor) {
        this.recursionInterceptor = recursionInterceptor;
    }

    /**
     * Returns the underlying expression interpolator.
     *
     * @return the underlying expression interpolator
     */
    protected final Interpolator getInterpolator() {
        return interpolator;
    }

    /**
     * Value source that resolves Maven build timestamp expressions.
     */
    static class BuildTimestampValueSource extends AbstractValueSource {

        /**
         * Maven timestamp formatter.
         */
        private final MavenBuildTimestamp mavenBuildTimestamp;

        /**
         * Creates a build timestamp value source.
         *
         * @param startTime the Maven build start time
         * @param properties model properties used by timestamp formatting
         */
        BuildTimestampValueSource(Date startTime, Properties properties) {
            super(false);
            this.mavenBuildTimestamp = new MavenBuildTimestamp(startTime, properties);
        }

        /**
         * Resolves Maven build timestamp expressions.
         *
         * @param expression the expression name
         * @return the formatted timestamp or {@code null}
         */
        public Object getValue(String expression) {
            if ("build.timestamp".equals(expression) || "maven.build.timestamp".equals(expression)) {
                return mavenBuildTimestamp.formattedTimestamp();
            }
            return null;
        }
    }

    /**
     * Value source wrapper that reports deprecated expression prefixes.
     */
    static class ProblemDetectingValueSource implements ValueSource {

        /**
         * Wrapped value source.
         */
        private final ValueSource valueSource;

        /**
         * Deprecated prefix that should trigger a warning.
         */
        private final String bannedPrefix;

        /**
         * Replacement prefix suggested in the warning message.
         */
        private final String newPrefix;

        /**
         * Collector used to report prefix warnings.
         */
        private final ModelProblemCollector problems;

        /**
         * Creates a value source wrapper that warns for deprecated prefixes.
         *
         * @param valueSource the wrapped value source
         * @param bannedPrefix the deprecated expression prefix
         * @param newPrefix the replacement expression prefix
         * @param problems the collector used to report warnings
         */
        ProblemDetectingValueSource(
                ValueSource valueSource, String bannedPrefix, String newPrefix, ModelProblemCollector problems) {
            this.valueSource = valueSource;
            this.bannedPrefix = bannedPrefix;
            this.newPrefix = newPrefix;
            this.problems = problems;
        }

        /**
         * Resolves a value and reports a warning when a deprecated expression prefix is used.
         *
         * @param expression the expression name
         * @return the resolved value or {@code null}
         */
        public Object getValue(String expression) {
            Object value = valueSource.getValue(expression);

            if (value != null && expression.startsWith(bannedPrefix)) {
                String msg = "The expression ${" + expression + "} is deprecated.";
                if (newPrefix != null && newPrefix.length() > 0) {
                    msg += " Please use ${" + newPrefix + expression.substring(bannedPrefix.length()) + "} instead.";
                }
                problems.add(new ModelProblemCollectorRequest(Severity.WARNING, Version.V20).setMessage(msg));
            }

            return value;
        }

        /**
         * Returns feedback from the wrapped value source.
         *
         * @return feedback messages from the wrapped value source
         */
        @SuppressWarnings("unchecked")
        public List getFeedback() {
            return valueSource.getFeedback();
        }

        /**
         * Clears feedback from the wrapped value source.
         */
        public void clearFeedback() {
            valueSource.clearFeedback();
        }
    }

    /**
     * Post processor that translates path-like values to the project base directory.
     */
    static class PathTranslatingPostProcessor implements InterpolationPostProcessor {

        /**
         * Path expression names without project or pom prefixes.
         */
        private final Collection<String> unprefixedPathKeys;

        /**
         * Base project directory.
         */
        private final File projectDir;

        /**
         * Maven path translator.
         */
        private final PathTranslator pathTranslator;

        /**
         * Expression prefixes accepted by this post processor.
         */
        private final List<String> expressionPrefixes;

        /**
         * Creates a path translation post processor.
         *
         * @param expressionPrefixes the expression prefixes to trim
         * @param unprefixedPathKeys path expression names without prefixes
         * @param projectDir the base project directory
         * @param pathTranslator the Maven path translator
         */
        PathTranslatingPostProcessor(
                List<String> expressionPrefixes,
                Collection<String> unprefixedPathKeys,
                File projectDir,
                PathTranslator pathTranslator) {
            this.expressionPrefixes = expressionPrefixes;
            this.unprefixedPathKeys = unprefixedPathKeys;
            this.projectDir = projectDir;
            this.pathTranslator = pathTranslator;
        }

        /**
         * Translates a path-like value when the expression matches a known path key.
         *
         * @param expression the expression name
         * @param value the resolved value
         * @return the translated path value or {@code null}
         */
        public Object execute(String expression, Object value) {
            if (value != null) {
                expression = ValueSourceUtils.trimPrefix(expression, expressionPrefixes, true);

                if (unprefixedPathKeys.contains(expression)) {
                    return pathTranslator.alignToBaseDirectory(String.valueOf(value), projectDir);
                }
            }

            return null;
        }
    }

    /**
     * Post processor that normalizes URL-like model values.
     */
    static class UrlNormalizingPostProcessor implements InterpolationPostProcessor {

        /**
         * Maven URL normalizer.
         */
        private UrlNormalizer normalizer;

        /**
         * Creates a URL normalizing post processor.
         *
         * @param normalizer the Maven URL normalizer
         */
        UrlNormalizingPostProcessor(UrlNormalizer normalizer) {
            this.normalizer = normalizer;
        }

        /**
         * Normalizes a URL-like value when the expression is a known URL field.
         *
         * @param expression the expression name
         * @param value the resolved value
         * @return the normalized URL value or {@code null}
         */
        public Object execute(String expression, Object value) {
            Set<String> expressions = new HashSet<>();
            expressions.add("project.url");
            expressions.add("project.scm.url");
            expressions.add("project.scm.connection");
            expressions.add("project.scm.developerConnection");
            expressions.add("project.distributionManagement.site.url");

            if (value != null && expressions.contains(expression)) {
                return normalizer.normalize(value.toString());
            }

            return null;
        }
    }

}
