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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.plexus.interpolation.InterpolationCycleException;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.InterpolationPostProcessor;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.SimpleRecursionInterceptor;
import org.codehaus.plexus.interpolation.ValueSource;

/**
 * Based on StringSearchInterpolator from plexus-interpolation,
 * see {@link org.codehaus.plexus.interpolation.StringSearchInterpolator}.
 * This interpolates only the Maven CI Friendly variables revision, sha1 and changelist.
 */
public class CiInterpolatorImpl implements Interpolator {

    /**
     * Cached interpolation answers keyed by expression.
     */
    private final Map existingAnswers = new HashMap();

    /**
     * Value sources consulted when an expression needs to be resolved.
     */
    private final List<ValueSource> valueSources = new ArrayList<>();

    /**
     * Post processors applied after a value source resolves an expression.
     */
    private final List<InterpolationPostProcessor> postProcessors = new ArrayList<>();

    /**
     * Whether resolved values should remain cached across interpolation calls.
     */
    private boolean cacheAnswers = false;

    /**
     * Default opening delimiter for Maven expressions.
     */
    public static final String DEFAULT_START_EXPR = "${";

    /**
     * Default closing delimiter for Maven expressions.
     */
    public static final String DEFAULT_END_EXPR = "}";

    /**
     * Opening delimiter used by this interpolator instance.
     */
    private final String startExpr;

    /**
     * Closing delimiter used by this interpolator instance.
     */
    private final String endExpr;

    /**
     * Creates an interpolator that uses Maven's default expression delimiters.
     */
    public CiInterpolatorImpl() {
        this.startExpr = DEFAULT_START_EXPR;
        this.endExpr = DEFAULT_END_EXPR;
    }

    /**
     * Creates an interpolator with custom expression delimiters.
     *
     * @param startExpr the expression opening delimiter
     * @param endExpr the expression closing delimiter
     */
    public CiInterpolatorImpl(String startExpr, String endExpr) {
        this.startExpr = startExpr;
        this.endExpr = endExpr;
    }

    /**
     * Adds a value source to the interpolation lookup chain.
     *
     * @param valueSource the value source to add
     */
    @Override
    public void addValueSource(ValueSource valueSource) {
        valueSources.add(valueSource);
    }

    /**
     * Removes a value source from the interpolation lookup chain.
     *
     * @param valueSource the value source to remove
     */
    @Override
    public void removeValuesSource(ValueSource valueSource) {
        valueSources.remove(valueSource);
    }

    /**
     * Adds a post processor for resolved interpolation values.
     *
     * @param postProcessor the post processor to add
     */
    @Override
    public void addPostProcessor(InterpolationPostProcessor postProcessor) {
        postProcessors.add(postProcessor);
    }

    /**
     * Removes a post processor for resolved interpolation values.
     *
     * @param postProcessor the post processor to remove
     */
    @Override
    public void removePostProcessor(InterpolationPostProcessor postProcessor) {
        postProcessors.remove(postProcessor);
    }

    /**
     * Interpolates CI-friendly placeholders using a default recursion interceptor.
     *
     * @param input the source text to interpolate
     * @param thisPrefixPattern ignored compatibility parameter from the Plexus contract
     * @return the interpolated text
     * @throws InterpolationException if interpolation detects an invalid recursive expression
     */
    @Override
    public String interpolate(String input, String thisPrefixPattern) throws InterpolationException {
        return interpolate(input, new SimpleRecursionInterceptor());
    }

    /**
     * Interpolates CI-friendly placeholders with the supplied recursion interceptor.
     *
     * @param input the source text to interpolate
     * @param thisPrefixPattern ignored compatibility parameter from the Plexus contract
     * @param recursionInterceptor the recursion interceptor used to detect expression cycles
     * @return the interpolated text
     * @throws InterpolationException if interpolation detects an invalid recursive expression
     */
    @Override
    public String interpolate(String input, String thisPrefixPattern, RecursionInterceptor recursionInterceptor)
            throws InterpolationException {
        return interpolate(input, recursionInterceptor);
    }

    /**
     * Interpolates CI-friendly placeholders using a default recursion interceptor.
     *
     * @param input the source text to interpolate
     * @return the interpolated text
     * @throws InterpolationException if interpolation detects an invalid recursive expression
     */
    @Override
    public String interpolate(String input) throws InterpolationException {
        return interpolate(input, new SimpleRecursionInterceptor());
    }

    /**
     * Entry point for recursive resolution of an expression and all of its
     * nested expressions.
     *
     * @param input the source text to interpolate
     * @param recursionInterceptor the recursion interceptor used to detect expression cycles
     * @return the interpolated text
     * @throws InterpolationException if interpolation detects an invalid recursive expression
     */
    @Override
    public String interpolate(String input, RecursionInterceptor recursionInterceptor) throws InterpolationException {
        try {
            return interpolate(input, recursionInterceptor, new HashSet<>());
        } finally {
            if (!cacheAnswers) {
                existingAnswers.clear();
            }
        }
    }

    /**
     * Interpolates placeholders while tracking expressions that cannot be resolved in the current pass.
     *
     * @param input the source text to interpolate
     * @param recursionInterceptor the recursion interceptor used to detect expression cycles
     * @param unresolvable expressions already known to be unresolved
     * @return the interpolated text
     * @throws InterpolationException if interpolation detects an invalid recursive expression
     */
    private String interpolate(String input, RecursionInterceptor recursionInterceptor, Set<String> unresolvable)
            throws InterpolationException {
        if (input == null) {
            // return empty String to prevent NPE too
            return "";
        }
        StringBuilder result = new StringBuilder(input.length() * 2);

        int startIdx;
        int endIdx = -1;
        while ((startIdx = input.indexOf(startExpr, endIdx + 1)) > -1) {
            result.append(input, endIdx + 1, startIdx);

            endIdx = input.indexOf(endExpr, startIdx + 1);
            if (endIdx < 0) {
                break;
            }

            final String wholeExpr = input.substring(startIdx, endIdx + endExpr.length());
            String realExpr = wholeExpr.substring(startExpr.length(), wholeExpr.length() - endExpr.length());

            boolean resolved = false;
            if (!unresolvable.contains(wholeExpr)) {
                if (realExpr.startsWith(".")) {
                    realExpr = realExpr.substring(1);
                }

                if (recursionInterceptor.hasRecursiveExpression(realExpr)) {
                    throw new InterpolationCycleException(recursionInterceptor, realExpr, wholeExpr);
                }

                recursionInterceptor.expressionResolutionStarted(realExpr);
                try {
                    Object value = null;
                    if (wholeExpr.equals("${revision}")
                            || wholeExpr.contains("${sha1}")
                            || wholeExpr.contains("${changelist}")) {
                        value = existingAnswers.get(realExpr);
                        Object bestAnswer = null;
                        for (ValueSource valueSource : valueSources) {
                            if (value != null) {
                                break;
                            }

                            value = valueSource.getValue(realExpr);
                            if (value != null && value.toString().contains(wholeExpr)) {
                                bestAnswer = value;
                                value = null;
                            }
                        }

                        // this is the simplest recursion check to catch exact recursion
                        // (non synonym), and avoid the extra effort of more string
                        // searching.
                        if (value == null && bestAnswer != null) {
                            throw new InterpolationCycleException(recursionInterceptor, realExpr, wholeExpr);
                        }
                    }

                    if (value != null) {
                        value = interpolate(String.valueOf(value), recursionInterceptor, unresolvable);

                        if (!postProcessors.isEmpty()) {
                            for (InterpolationPostProcessor postProcessor : postProcessors) {
                                Object newVal = postProcessor.execute(realExpr, value);
                                if (newVal != null) {
                                    value = newVal;
                                    break;
                                }
                            }
                        }

                        // could use:
                        // result = matcher.replaceFirst( stringValue );
                        // but this could result in multiple lookups of stringValue, and replaceAll is not correct
                        // behaviour
                        result.append(value);
                        resolved = true;
                    } else {
                        unresolvable.add(wholeExpr);
                    }
                } finally {
                    recursionInterceptor.expressionResolutionFinished(realExpr);
                }
            }

            if (!resolved) {
                result.append(wholeExpr);
            }

            endIdx += endExpr.length() - 1;
        }

        if (endIdx == -1 && startIdx > -1) {
            result.append(input, startIdx, input.length());
        } else if (endIdx < input.length()) {
            result.append(input, endIdx + 1, input.length());
        }

        return result.toString();
    }

    /**
     * Return any feedback messages and errors that were generated - but
     * suppressed - during the interpolation process. Since unresolvable
     * expressions will be left in the source string as-is, this feedback is
     * optional, and will only be useful for debugging interpolation problems.
     *
     * @return a {@link List} that may be interspersed with {@link String} and
     * {@link Throwable} instances.
     */
    @Override
    public List getFeedback() {
        List<?> messages = new ArrayList();
        for (ValueSource vs : valueSources) {
            List feedback = vs.getFeedback();
            if (feedback != null && !feedback.isEmpty()) {
                messages.addAll(feedback);
            }
        }

        return messages;
    }

    /**
     * Clear the feedback messages from previous interpolate(..) calls.
     */
    @Override
    public void clearFeedback() {
        for (ValueSource vs : valueSources) {
            vs.clearFeedback();
        }
    }

    /**
     * Tests whether resolved values are cached across interpolation calls.
     *
     * @return {@code true} when answer caching is enabled
     */
    @Override
    public boolean isCacheAnswers() {
        return cacheAnswers;
    }

    /**
     * Configures whether resolved values are cached across interpolation calls.
     *
     * @param cacheAnswers {@code true} to keep resolved values cached
     */
    @Override
    public void setCacheAnswers(boolean cacheAnswers) {
        this.cacheAnswers = cacheAnswers;
    }

    /**
     * Clears cached interpolation answers.
     */
    @Override
    public void clearAnswers() {
        existingAnswers.clear();
    }

}
