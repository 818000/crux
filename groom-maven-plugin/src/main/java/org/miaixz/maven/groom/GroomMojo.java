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
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Activation;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.miaixz.maven.groom.cifriendly.CiInterpolator;
import org.miaixz.maven.groom.cifriendly.CiModelInterpolator;
import org.miaixz.maven.groom.extendedinterpolation.ExtendedModelInterpolator;
import org.miaixz.maven.groom.extendedinterpolation.ExtendedStringSearchModelInterpolator;
import org.miaixz.maven.groom.model.resolution.GroomModelResolver;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.xml.sax.Attributes;
import org.xml.sax.ext.DefaultHandler2;

/**
 * Mojo that creates the groomed publication POM for the current Maven project.
 * <p>
 * The generated POM keeps consumption metadata, resolves configured version placeholders, applies the selected
 * {@link GroomMode}, and writes the final POM with stable Bus formatting. When configured, the mojo also updates the
 * current {@link MavenProject} to point at the generated POM before later Maven lifecycle goals run.
 *
 * @author Joerg Hohwiller (hohwille at users.sourceforge.net)
 */
@Mojo(name = "groom", requiresDependencyCollection = ResolutionScope.RUNTIME, threadSafe = true)
public class GroomMojo extends AbstractGroomMojo {

    /**
     * Initial in-memory buffer size used when writing POM XML.
     */
    private static final int INITIAL_POM_WRITER_SIZE = 4096;

    /**
     * Pattern used to remove explicit Maven default compile scopes.
     */
    private static final Pattern COMPILE_SCOPE_PATTERN =
            Pattern.compile("(?m)^[ \\t]*<scope>compile</scope>\\r?\\n?");

    /**
     * Pattern that normalizes all line-ending variants.
     */
    private static final Pattern NEW_LINE_PATTERN = Pattern.compile("\\n|\\r\\n?");

    /**
     * Pattern that matches the root project tag in generated POM XML.
     */
    private static final Pattern PROJECT_TAG_PATTERN = Pattern.compile("(?s)<project\\b[^>]*>");

    /**
     * Pattern that captures the properties block for property order restoration.
     */
    private static final Pattern PROPERTY_BLOCK_PATTERN = Pattern.compile("(?s)<properties>(.*?)</properties>");

    /**
     * Pattern that extracts property names from one XML property line.
     */
    private static final Pattern PROPERTY_NAME_PATTERN = Pattern.compile("(?m)^\\s*<([A-Za-z0-9_.-]+)>");

    /**
     * Pattern that normalizes empty XML elements to the project style.
     */
    private static final Pattern EMPTY_ELEMENT_PATTERN = Pattern.compile("<([A-Za-z][^<>]*?)(?<!\\s)/>");

    /**
     * Canonical project tag used by generated publication POM files.
     */
    private static final String PROJECT_TAG = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "        xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0"
            + " http://maven.apache.org/xsd/maven-4.0.0.xsd\">";

    /**
     * Top-level POM elements that should be separated with a blank line.
     */
    private static final Set<String> SPACED_TOP_LEVEL_ELEMENTS = Set.of(
            "parent",
            "groupId",
            "name",
            "licenses",
            "developers",
            "contributors",
            "mailingLists",
            "scm",
            "issueManagement",
            "ciManagement",
            "properties",
            "dependencyManagement",
            "dependencies",
            "repositories",
            "pluginRepositories",
            "build",
            "reporting",
            "profiles",
            "distributionManagement");

    /**
     * Public project metadata elements whose Maven project expressions can be resolved without touching inherited build
     * plugin configuration.
     */
    private static final List<PomProperty<?>> PROJECT_EXPRESSION_PROPERTIES = Arrays.asList(
            PomProperty.GROUP_ID,
            PomProperty.ARTIFACT_ID,
            PomProperty.VERSION,
            PomProperty.PACKAGING,
            PomProperty.NAME,
            PomProperty.DESCRIPTION,
            PomProperty.URL,
            PomProperty.INCEPTION_YEAR,
            PomProperty.ORGANIZATION,
            PomProperty.LICENSES,
            PomProperty.DEVELOPERS,
            PomProperty.CONTRIBUTORS,
            PomProperty.MAILING_LISTS,
            PomProperty.SCM,
            PomProperty.ISSUE_MANAGEMENT,
            PomProperty.CI_MANAGEMENT,
            PomProperty.DISTRIBUTION_MANAGEMENT,
            PomProperty.PREREQUISITES);

    /**
     * The {@link Settings} used to get active profile properties.
     */
    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    private Settings settings;

    /**
     * The {@link MavenSession} used to get user properties.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The Maven Project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The flag indicating whether the generated groomed POM should replace the current project POM for later lifecycle
     * goals. The default value is <code>true</code>, so install and deploy goals use the generated groomed POM unless
     * callers explicitly disable it with <code>-Dgroom.updatePomFile=false</code> or the matching plugin configuration.
     */
    @Parameter(property = "groom.updatePomFile", defaultValue = "true")
    private boolean updatePomFile;

    /**
     * Profiles activated by OS or JDK are valid ways to have different dependencies per environment. However, profiles
     * activated by property of file are less clear. When setting this parameter to <code>true</code>, the latter
     * dependencies will be written as direct dependencies of the project. <strong>This is not how Maven2 and Maven3
     * handles dependencies</strong>. When keeping this property <code>false</code>, all profiles will stay in the
     * flattened-pom.
     */
    @Parameter(defaultValue = "false")
    private Boolean embedBuildProfileDependencies;

    /**
     * The {@link MojoExecution} used to get access to the raw configuration of {@link #pomElements} as empty tags are
     * mapped to null.
     */
    @Parameter(defaultValue = "${mojo}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    /**
     * The {@link Model} that defines how to handle additional POM elements. Please use <code>groomMode</code> in
     * preference if possible. This parameter is only for ultimate flexibility.
     */
    @Parameter(required = false)
    private GroomDescriptor pomElements;

    /**
     * Dictates whether dependency exclusions stanzas should be included in the flattened POM. By default exclusions
     * will be included in the flattened POM but if you wish to omit exclusions stanzas from being present then set
     * this configuration property to <code>true</code>.
     *
     * @since 1.3.0
     */
    @Parameter(defaultValue = "false", required = false)
    private boolean omitExclusions;

    /**
     * Predefined grooming mode used to select a complete {@link GroomDescriptor}.
     * <p>
     * Supported values are {@code oss}, {@code ossrh}, {@code bom}, {@code defaults}, {@code clean}, {@code fatjar},
     * and {@code resolveCiFriendliesOnly}. Use {@link #pomElements} for element-by-element handling when these modes are
     * not specific enough.
     * </p>
     */
    @Parameter(property = "groom.mode", required = false)
    private GroomMode groomMode;

    /**
     * The different possible values for groomDependencyMode:
     * <table border="1" summary="">
     * <thead>
     * <tr>
     * <td>Mode</td>
     * <td>Description</td>
     * </tr>
     * </thead><tbody>
     * <tr>
     * <td>direct</td>
     * <td><p>Groom only the direct dependency versions, excluding inherited dependencies from a parent module.</p>
     * <p>This was the default mode with Groom Plugin in versions 1.4.0 up to 1.6.0.
     * </td>
     * </tr>
     * <tr>
     * <td>inherited</td>
     * <td><p>Groom the dependency versions, including inherited dependencies from a parent module</p>
     * <p>This is the default mode and compatible with Groom Plugin prior to 1.2.0, this mode was called <tt>direct</tt> between versions 1.2.0 and 1.3.0.</p>
     * </td>
     * </tr>
     * <tr>
     * <td>all</td>
     * <td><p>Groom both direct and transitive dependencies. This will examine the full dependency tree, and pull up
     * all transitive dependencies as a direct dependency, and setting their versions appropriately.</p>
     * <p>This is recommended if you are releasing a library that uses dependency management to manage dependency
     * versions.</p></td>
     * </tr>
     * </tbody>
     * </table>
     */
    @Parameter(property = "groom.dependency.mode", required = false)
    private GroomDependencyMode groomDependencyMode;

    /**
     * The core maven model readers/writers are discarding the comments of the pom.xml.
     * By setting keepCommentsInPom to true the current comments are moved to the flattened pom.xml.
     * Default value is false (= not re-adding comments).
     *
     * @since 1.3.0
     */
    @Parameter(property = "groom.dependency.keepComments", required = false, defaultValue = "false")
    private boolean keepCommentsInPom;

    /**
     * If {@code true} the groom goal will be skipped.
     *
     * @since 1.6.0
     */
    @Parameter(property = "groom.groom.skip", defaultValue = "false")
    private boolean skipGroom;

    /**
     * The default operation to use when no element handling is given. Defaults to <code>flatten</code>.
     *
     * @since 1.6.0
     */
    @Parameter(property = "groom.dependency.defaultOperation", required = false, defaultValue = "flatten")
    private ElementHandling defaultOperation;

    /**
     * Applies removable POM element handling to projects packaged as {@code pom}. Set this to {@code false} for
     * internal build parents that must keep their build contract while still sharing the same inherited grooming
     * configuration with child modules.
     */
    @Parameter(property = "groom.applyProjectElementRemovalsToPomPackaging", defaultValue = "true")
    private boolean applyProjectElementRemovalsToPomPackaging;

    /**
     * Resolves {@code ${project.version}} expressions in the generated POM to the current Maven project version.
     */
    @Parameter(property = "groom.resolveProjectVersion", defaultValue = "false")
    private boolean resolveProjectVersion;

    /**
     * Resolves Maven project expressions in public project metadata while leaving build plugin configuration untouched.
     */
    @Parameter(property = "groom.resolveProjectExpressions", defaultValue = "false")
    private boolean resolveProjectExpressions;

    /**
     * Command line override for active {@code build/plugins} handling under {@code pomElements}.
     */
    @Parameter(property = "groom.pomElements.buildPlugins")
    private ElementHandling buildPlugins;

    /**
     * Effective setting that removes explicit {@code compile} dependency scopes from the generated POM.
     */
    private boolean effectiveCompileScopeRemoval;

    /**
     * Effective setting that removes active {@code build/plugins} from the generated POM.
     */
    private boolean effectiveBuildPluginsRemoval;

    /**
     * Inheritance assembler used when building resolved Maven models.
     */
    @Inject
    private DirectDependenciesInheritanceAssembler inheritanceAssembler;

    /**
     * The {@link ModelInterpolator} used to resolve variables outside CI-friendly-only mode.
     */
    private final ExtendedModelInterpolator extendedModelInterpolator = new ExtendedStringSearchModelInterpolator();

    /**
     * The {@link ModelInterpolator} used to resolve variables.
     */
    @Inject
    private CiInterpolator modelCiFriendlyInterpolator;

    /**
     * Workaround that serializes access to Maven model builder internals.
     */
    @Inject
    private ModelBuilderThreadSafetyWorkaround modelBuilderThreadSafetyWorkaround;

    /**
     * Maven artifact handler manager used when resolving effective models.
     */
    @Inject
    private ArtifactHandlerManager artifactHandlerManager;

    /**
     * Maven repository system used for dependency collection and descriptor resolution.
     */
    @Inject
    private RepositorySystem repositorySystem;

    /**
     * Maven profile selector used to calculate active profiles.
     */
    @Inject
    private ProfileSelector profileSelector;

    /**
     * The constructor.
     */
    public GroomMojo() {
        super();
    }

    /**
     * Executes POM grooming for the current Maven project.
     *
     * @throws MojoExecutionException if POM generation fails
     * @throws MojoFailureException if Maven detects a logical grooming failure
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip()) {
            getLog().info("Groom skipped.");
            return;
        }

        getLog().info("Generating groomed POM of project " + this.project.getId() + "...");

        inheritanceAssembler.groomDependencyMode = this.groomDependencyMode;

        File originalPomFile = this.project.getFile();
        Path flattenedPomFile = getGroomedPomFile();
        Model flattenedPom;
        /*
         * Non-destructive CI-friendly version flattening?
         *
         * NOTE: Regular flattening implies POM rebuilding from scratch, losing original formatting, ordering and
         * comments (`KeepCommentsInPom` itself is testament to this anything-but-ideal arrangement). Such side
         * effects are undesirable when it comes to POMs requiring just CI-friendly version interpolation, which are
         * typically expected to retain their original representation for later inspection. Despite far from elegant,
         * this dedicated solution ensures POMs are flattened non-destructively.
         */
        if (groomMode == GroomMode.resolveCiFriendliesOnly && this.pomElements == null && !hasRawPomElements()) {
            ModelsFactory modelsFactory = new ModelsFactory(originalPomFile);
            String modelEncoding = getModelEncoding(modelsFactory.getEffectivePom());

            // Load original POM content!
            String pomString;
            try {
                pomString = new String(Files.readAllBytes(originalPomFile.toPath()), modelEncoding);
            } catch (IOException e) {
                throw new MojoExecutionException("Original POM file loading FAILED: " + originalPomFile, e);
            }

            // Interpolate POM content!
            CiModelInterpolator interpolator = (CiModelInterpolator) this.modelCiFriendlyInterpolator;
            Model originalPom = this.project.getModel();
            File projectDir = modelsFactory.getResolvedPom().getProjectDirectory();
            ModelBuildingRequest config = createModelBuildingRequest(originalPomFile);
            LoggingModelProblemCollector problems = new LoggingModelProblemCollector(getLog());
            pomString = interpolator.interpolateModelContent(pomString, originalPom, projectDir, config, problems);

            // Save flattened POM content!
            writeStringToFile(pomString, flattenedPomFile, modelEncoding);

            // Load flattened POM!
            flattenedPom = createOriginalPom(flattenedPomFile);
        } else {
            KeepCommentsInPom commentsOfOriginalPomFile = null;
            if (keepCommentsInPom) {
                commentsOfOriginalPomFile = KeepCommentsInPom.create(getLog(), originalPomFile);
            }
            flattenedPom = createGroomedPom(originalPomFile);
            String headerComment = extractHeaderComment(originalPomFile);

            writePom(flattenedPom, flattenedPomFile, headerComment, commentsOfOriginalPomFile);
        }
        if (isUpdatePomFile()) {
            this.project.setPomFile(flattenedPomFile.toFile());
            this.project.setOriginalModel(flattenedPom);
        }
    }

    /**
     * Tests whether the main groom goal should be skipped.
     *
     * @return {@code true} when the goal-specific skip flag is enabled
     */
    @Override
    protected boolean shouldSkipGoal() {
        if (skipGroom) {
            return true;
        }
        return shouldSkipPomPackagedModulePublishExecution();
    }

    /**
     * Tests whether a module publication execution is running on a POM-packaged parent project.
     *
     * @return {@code true} when the execution should be skipped for the current POM-packaged project
     */
    private boolean shouldSkipPomPackagedModulePublishExecution() {
        if (!"pom".equals(project.getPackaging()) || applyProjectElementRemovalsToPomPackaging) {
            return false;
        }
        return getRawPomElementHandling("parent") == ElementHandling.remove
                && getRawPomElementHandling("build") == ElementHandling.remove;
    }

    /**
     * This method extracts the XML header comment if available.
     *
     * @param xmlFile is the XML {@link File} to parse.
     * @return the XML comment between the XML header declaration and the root tag or <code>null</code> if NOT
     * available.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected String extractHeaderComment(File xmlFile) throws MojoExecutionException {

        try {
            SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            SaxHeaderCommentHandler handler = new SaxHeaderCommentHandler();
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            parser.parse(xmlFile, handler);
            return handler.getHeaderComment();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse XML from " + xmlFile, e);
        }
    }

    /**
     * Writes the given POM {@link Model} to the given {@link File}.
     *
     * @param pom           the {@link Model} of the POM to write.
     * @param pomFile       the {@link Path} where to write the given POM will be written to.
     *                      {@link File#getParentFile()
     *                      Parent directories} are {@link File#mkdirs() created} automatically.
     * @param headerComment is the content of a potential XML comment at the top of the XML (after XML declaration and
     *                      before root tag). May be <code>null</code> if not present and to be omitted in target POM.
     * @param anOriginalCommentsPath comment restoration helper for original POM comments, or {@code null}
     * @throws MojoExecutionException if the operation failed (e.g. due to an {@link IOException}).
     */
    protected void writePom(Model pom, Path pomFile, String headerComment, KeepCommentsInPom anOriginalCommentsPath)
            throws MojoExecutionException {

        // MavenXpp3Writer could internally add the comment but does not expose such feature to API!
        // Instead we have to write POM XML to String and do post processing on that :(
        MavenXpp3Writer pomWriter = new MavenXpp3Writer();
        StringWriter stringWriter = new StringWriter(INITIAL_POM_WRITER_SIZE);
        try {
            pomWriter.write(stringWriter, pom);
        } catch (IOException e) {
            throw new MojoExecutionException("Internal I/O error!", e);
        }
        StringBuffer buffer = stringWriter.getBuffer();
        if (!StringUtils.isEmpty(headerComment)) {
            int projectStartIndex = buffer.indexOf("<project");
            if (projectStartIndex >= 0) {
                buffer.insert(projectStartIndex, "<!--" + headerComment + "-->\n");
            } else {
                getLog().warn("POM XML post-processing failed: no project tag found!");
            }
        }
        String xmlString;
        if (anOriginalCommentsPath == null) {
            xmlString = buffer.toString();
        } else {
            xmlString = anOriginalCommentsPath.restoreOriginalComments(buffer.toString(), pom.getModelEncoding());
        }
        writeStringToFile(xmlString, pomFile, pom.getModelEncoding());
    }

    /**
     * Writes the given <code>data</code> to the given <code>file</code> using the specified <code>encoding</code>.
     *
     * @param data     is the {@link String} to write.
     * @param file     is the {@link Path} to write to.
     * @param encoding is the encoding to use for writing the file.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected void writeStringToFile(String data, Path file, String encoding) throws MojoExecutionException {
        if (resolveProjectVersion) {
            data = GroomVersion.replacePlaceholders(data, GroomVersion.resolve(this.session, this.project));
        }
        if (shouldRemoveCompileScope()) {
            data = COMPILE_SCOPE_PATTERN.matcher(data).replaceAll("");
        }
        data = NEW_LINE_PATTERN.matcher(data).replaceAll(System.lineSeparator());
        data = normalizePomFormat(data);

        try {
            byte[] binaryData = data.getBytes(encoding);
            if (Files.isReadable(file) && Files.size(file) == binaryData.length) {
                try {
                    byte[] buffer = Files.readAllBytes(file);
                    if (Arrays.equals(buffer, binaryData)) {
                        getLog().debug("Arrays.equals( buffer, binaryData ) ");
                        return;
                    }
                    getLog().debug("Not Arrays.equals( buffer, binaryData ) ");
                } catch (IOException e) {
                    // ignore those exceptions, we will overwrite the file
                    getLog().debug("Issue reading file: " + file, e);
                }
            } else {
                getLog().debug("file: " + file + ", not exist or has the same length as binaryData.length: "
                        + binaryData.length);
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(
                    file,
                    binaryData,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write to " + file, e);
        }
    }

    /**
     * Applies final Bus publication POM formatting rules.
     *
     * @param data the generated POM XML
     * @return formatted POM XML
     * @throws MojoExecutionException if the source POM cannot be read for property ordering
     */
    private String normalizePomFormat(String data) throws MojoExecutionException {
        if (data == null || !data.contains("<project")) {
            return data;
        }
        String normalized = NEW_LINE_PATTERN.matcher(data).replaceAll("\n");
        normalized = expandMavenWriterIndent(normalized);
        normalized = PROJECT_TAG_PATTERN.matcher(normalized).replaceFirst(Matcher.quoteReplacement(PROJECT_TAG));
        normalized = EMPTY_ELEMENT_PATTERN.matcher(normalized).replaceAll("<$1 />");
        normalized = ensureDefaultPackaging(normalized);
        normalized = restorePropertiesOrder(normalized);
        normalized = insertTopLevelSpacing(normalized);
        return normalized.replace("\n", System.lineSeparator());
    }

    /**
     * Converts Maven writer two-space indentation to the Bus four-space XML style.
     *
     * @param data the POM XML to format
     * @return POM XML with expanded indentation
     */
    private String expandMavenWriterIndent(String data) {
        String[] lines = data.split("\n", -1);
        StringBuilder formatted = new StringBuilder(data.length() * 2);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                formatted.append('\n');
            }
            String line = lines[i];
            int spaces = countLeadingSpaces(line);
            if (spaces > 0 && spaces < line.length() && line.charAt(spaces) == '<') {
                formatted.append(" ".repeat(spaces * 2)).append(line.substring(spaces));
            } else {
                formatted.append(line);
            }
        }
        return formatted.toString();
    }

    /**
     * Adds the default {@code jar} packaging element when Maven omitted it from a generated publication POM.
     *
     * @param data the POM XML to inspect
     * @return POM XML containing an explicit jar packaging element when required
     */
    private String ensureDefaultPackaging(String data) {
        if (this.project == null || !"jar".equals(this.project.getPackaging())
                || hasTopLevelElement(data, "packaging")) {
            return data;
        }
        String[] lines = data.split("\n", -1);
        StringBuilder formatted = new StringBuilder(data.length() + 32);
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                formatted.append('\n');
            }
            String line = lines[i];
            formatted.append(line);
            if (!inserted && "version".equals(topLevelElementName(line))) {
                formatted.append('\n').append("    <packaging>jar</packaging>");
                inserted = true;
            }
        }
        return formatted.toString();
    }

    /**
     * Tests whether a top-level POM element is present.
     *
     * @param data the POM XML to inspect
     * @param element the element name to find
     * @return {@code true} when the top-level element exists
     */
    private boolean hasTopLevelElement(String data, String element) {
        for (String line : data.split("\n")) {
            if (element.equals(topLevelElementName(line))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Restores the source POM property order in the generated POM.
     *
     * @param data the generated POM XML
     * @return generated POM XML with source property ordering restored
     * @throws MojoExecutionException if the source POM cannot be read
     */
    private String restorePropertiesOrder(String data) throws MojoExecutionException {
        List<String> propertyOrder = readOriginalPropertyOrder();
        if (propertyOrder.isEmpty()) {
            return data;
        }
        String open = "    <properties>\n";
        String close = "    </properties>";
        int openIndex = data.indexOf(open);
        int closeIndex = data.indexOf(close, openIndex + open.length());
        if (openIndex < 0 || closeIndex < 0) {
            return data;
        }
        String body = data.substring(openIndex + open.length(), closeIndex);
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            String name = propertyName(line);
            if (name != null) {
                properties.put(name, line);
            }
        }
        if (properties.isEmpty()) {
            return data;
        }
        StringBuilder sorted = new StringBuilder(body.length());
        Set<String> used = new HashSet<>();
        for (String name : propertyOrder) {
            String line = properties.get(name);
            if (line != null && used.add(name)) {
                sorted.append(line).append('\n');
            }
        }
        properties.keySet().stream().filter(name -> !used.contains(name)).sorted().forEach(name -> {
            sorted.append(properties.get(name)).append('\n');
        });
        return data.substring(0, openIndex) + open + sorted + data.substring(closeIndex);
    }

    /**
     * Reads the property declaration order from the source POM.
     *
     * @return source POM property names in declaration order
     * @throws MojoExecutionException if the source POM cannot be read
     */
    private List<String> readOriginalPropertyOrder() throws MojoExecutionException {
        File originalPom = this.project == null ? null : this.project.getFile();
        if (originalPom == null || !originalPom.isFile()) {
            return Collections.emptyList();
        }
        try {
            String xml = Files.readString(originalPom.toPath());
            Matcher block = PROPERTY_BLOCK_PATTERN.matcher(xml);
            if (!block.find()) {
                return Collections.emptyList();
            }
            Matcher names = PROPERTY_NAME_PATTERN.matcher(block.group(1));
            List<String> order = new ArrayList<>();
            while (names.find()) {
                order.add(names.group(1));
            }
            return order;
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read property order from " + originalPom, e);
        }
    }

    /**
     * Inserts blank lines before configured top-level POM sections.
     *
     * @param data the POM XML to format
     * @return POM XML with top-level section spacing
     */
    private String insertTopLevelSpacing(String data) {
        String[] lines = data.split("\n", -1);
        List<String> spaced = new ArrayList<>(lines.length + 16);
        for (String line : lines) {
            if (needsTopLevelBlankLine(line) && !spaced.isEmpty() && !spaced.get(spaced.size() - 1).isEmpty()) {
                spaced.add("");
            }
            spaced.add(line);
        }
        return String.join("\n", spaced);
    }

    /**
     * Tests whether a line should be preceded by a top-level blank line.
     *
     * @param line the POM XML line to inspect
     * @return {@code true} when a blank line should be inserted before the line
     */
    private boolean needsTopLevelBlankLine(String line) {
        if ("</project>".equals(line)) {
            return true;
        }
        String name = topLevelElementName(line);
        return name != null && SPACED_TOP_LEVEL_ELEMENTS.contains(name);
    }

    /**
     * Extracts a top-level element name from a POM XML line.
     *
     * @param line the POM XML line to inspect
     * @return the top-level element name or {@code null}
     */
    private String topLevelElementName(String line) {
        if (!line.startsWith("    <") || line.startsWith("    </") || line.startsWith("    <!--")) {
            return null;
        }
        int start = 5;
        int end = start;
        while (end < line.length()) {
            char ch = line.charAt(end);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') {
                end++;
            } else {
                break;
            }
        }
        return end > start ? line.substring(start, end) : null;
    }

    /**
     * Extracts a property name from a one-line XML property declaration.
     *
     * @param line the POM XML line to inspect
     * @return the property name or {@code null}
     */
    private String propertyName(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("<") || trimmed.startsWith("</") || trimmed.startsWith("<!--")) {
            return null;
        }
        int end = trimmed.indexOf('>');
        if (end < 2) {
            return null;
        }
        String name = trimmed.substring(1, end);
        return trimmed.endsWith("</" + name + ">") ? name : null;
    }

    /**
     * Counts leading spaces in a line.
     *
     * @param line the line to inspect
     * @return the number of leading spaces
     */
    private int countLeadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    /**
     * This method creates the flattened POM what is the main task of this plugin.
     *
     * @param pomFile is the name of the original POM file to read and transform.
     * @return the {@link Model} of the flattened POM.
     * @throws MojoExecutionException if anything goes wrong (e.g. POM can not be processed).
     * @throws MojoFailureException   if anything goes wrong (logical error).
     */
    protected Model createGroomedPom(File pomFile) throws MojoExecutionException, MojoFailureException {

        ModelsFactory modelsFactory = new ModelsFactory(pomFile);

        Model flattenedPom = new Model();

        // keep original encoding (we could also normalize to UTF-8 here)
        String modelEncoding = getModelEncoding(modelsFactory.getEffectivePom());
        flattenedPom.setModelEncoding(modelEncoding);

        GroomDescriptor descriptor = getGroomDescriptor();

        for (PomProperty<?> property : PomProperty.getPomProperties()) {
            if (property.isElement()) {
                Model sourceModel = getSourceModel(descriptor, property, modelsFactory);
                if (sourceModel == null) {
                    if (property.isRequired()) {
                        throw new MojoFailureException(
                                "Property " + property.getName() + " is required and can not be removed!");
                    }
                } else {
                    property.copy(sourceModel, flattenedPom);
                }
            }
        }
        applyBuildPluginsHandling(flattenedPom);

        return flattenedPom;
    }

    /**
     * Removes active build plugins from the generated model when configured.
     *
     * @param model the generated POM model
     */
    private void applyBuildPluginsHandling(Model model) {
        if (!effectiveBuildPluginsRemoval || model == null || model.getBuild() == null) {
            return;
        }
        model.getBuild().setPlugins(Collections.emptyList());
    }

    /**
     * Tests whether explicit {@code compile} scopes should be removed from generated POM text.
     *
     * @return {@code true} when compile scopes should be removed
     */
    private boolean shouldRemoveCompileScope() {
        return effectiveCompileScopeRemoval || getRawPomElementHandling("compileScope") == ElementHandling.remove;
    }

    /**
     * Creates the effective POM and wraps model building failures as Mojo execution failures.
     *
     * @param buildingRequest the Maven model building request.
     * @return the effective POM model.
     * @throws MojoExecutionException when the effective POM cannot be created.
     */
    Model createEffectivePom(ModelBuildingRequest buildingRequest) throws MojoExecutionException {
        try {
            return createEffectivePomImpl(buildingRequest);
        } catch (Exception e) {
            throw new MojoExecutionException("failed to create the effective pom", e);
        }
    }

    /**
     * Creates a cleaned POM model and wraps model transformation failures as Mojo execution failures.
     *
     * @param effectivePom the effective POM used as source data.
     * @return the cleaned POM model.
     * @throws MojoExecutionException when the cleaned POM cannot be created.
     */
    private Model createCleanPom(Model effectivePom) throws MojoExecutionException {
        try {
            return createCleanPomImpl(effectivePom);
        } catch (Exception e) {
            throw new MojoExecutionException("failed to create a clean pom", e);
        }
    }

    /**
     * Creates an interpolated POM from the original model.
     *
     * @param buildingRequest the Maven model building request.
     * @param originalPom the original POM model.
     * @param projectDirectory the project directory used for path interpolation.
     * @return the interpolated POM model.
     */
    private Model createInterpolatedPom(
            ModelBuildingRequest buildingRequest, Model originalPom, File projectDirectory) {
        LoggingModelProblemCollector problems = new LoggingModelProblemCollector(getLog());
        if (this.groomMode == GroomMode.resolveCiFriendliesOnly) {
            return this.modelCiFriendlyInterpolator.interpolateModel(
                    originalPom, projectDirectory, buildingRequest, problems);
        }
        return extendedModelInterpolator.interpolateModel(originalPom, projectDirectory, buildingRequest, problems);
    }

    /**
     * Creates an interpolated POM using the effective model as value-source origin.
     *
     * @param buildingRequest the Maven model building request.
     * @param originalPom the original POM model.
     * @param effectivePom the effective POM model used as interpolation source.
     * @param projectDirectory the project directory used for path interpolation.
     * @return the extended interpolated POM model.
     */
    private Model createExtendedInterpolatedPom(
            ModelBuildingRequest buildingRequest, Model originalPom, Model effectivePom, File projectDirectory) {
        LoggingModelProblemCollector problems = new LoggingModelProblemCollector(getLog());
        if (this.groomMode == GroomMode.resolveCiFriendliesOnly) {
            return this.modelCiFriendlyInterpolator.interpolateModel(
                    originalPom, projectDirectory, buildingRequest, problems);
        }
        final Model extendedInterpolatedPom = extendedModelInterpolator
                .interpolateModel(effectivePom, originalPom, projectDirectory, buildingRequest, problems)
                .clone();

        // interpolate parent explicitly because parent is excluded from interpolation
        if (effectivePom.getParent() != null) {
            extendedInterpolatedPom.setParent(effectivePom.getParent().clone());
        }

        return extendedInterpolatedPom;
    }

    /**
     * Reads the original POM model from disk.
     *
     * @param pomFile the original POM file path.
     * @return the parsed original POM model.
     * @throws MojoExecutionException when the original POM cannot be read.
     */
    private Model createOriginalPom(Path pomFile) throws MojoExecutionException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try {
            return reader.read(Files.newInputStream(pomFile));
        } catch (IOException | XmlPullParserException e) {
            throw new MojoExecutionException("Error reading raw model.", e);
        }
    }

    /**
     * This method creates the clean POM as a {@link Model} where to copy elements from that shall be
     * {@link ElementHandling#flatten flattened}. Will be mainly empty but contains some the minimum elements that have
     * to be kept in flattened POM.
     *
     * @param effectivePom is the effective POM.
     * @return the clean POM.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected Model createCleanPomImpl(Model effectivePom) throws MojoExecutionException {
        Model cleanPom = new Model();

        cleanPom.setGroupId(effectivePom.getGroupId());
        cleanPom.setArtifactId(effectivePom.getArtifactId());
        cleanPom.setVersion(effectivePom.getVersion());
        cleanPom.setPackaging(effectivePom.getPackaging());
        cleanPom.setLicenses(effectivePom.getLicenses());
        // fixed to 4.0.0 forever :)
        cleanPom.setModelVersion("4.0.0");

        // plugins with extensions must stay
        Build build = effectivePom.getBuild();
        if (build != null) {
            for (Plugin plugin : build.getPlugins()) {
                if (plugin.isExtensions()) {
                    Build cleanBuild = cleanPom.getBuild();
                    if (cleanBuild == null) {
                        cleanBuild = new Build();
                        cleanPom.setBuild(cleanBuild);
                    }
                    Plugin cleanPlugin = new Plugin();
                    cleanPlugin.setGroupId(plugin.getGroupId());
                    cleanPlugin.setArtifactId(plugin.getArtifactId());
                    cleanPlugin.setVersion(plugin.getVersion());
                    cleanPlugin.setExtensions(true);
                    cleanBuild.addPlugin(cleanPlugin);
                }
            }
        }

        // transform profiles...
        Dependencies managedDependencies = new Dependencies();
        if (effectivePom.getDependencyManagement() != null
                && effectivePom.getDependencyManagement().getDependencies() != null) {
            managedDependencies.addAll(effectivePom.getDependencyManagement().getDependencies());
        }

        for (Profile profile : effectivePom.getProfiles()) {
            if (!isEmbedBuildProfileDependencies() || !isBuildTimeDriven(profile.getActivation())) {
                if (!isEmpty(profile.getDependencies()) || !isEmpty(profile.getRepositories())) {
                    List<Dependency> strippedDependencies = new ArrayList<>();
                    for (Dependency dep : profile.getDependencies()) {
                        Dependency parsedDep = dep.clone();
                        if (managedDependencies.contains(parsedDep)) {
                            parsedDep.setVersion(
                                    managedDependencies.resolve(parsedDep).getVersion());
                            String managedDepScope =
                                    managedDependencies.resolve(parsedDep).getScope();
                            if (managedDepScope != null) {
                                parsedDep.setScope(managedDepScope);
                            }
                            if (parsedDep.getScope() == null) {
                                parsedDep.setScope("compile");
                            }
                            String managedDepOptional =
                                    managedDependencies.resolve(parsedDep).getOptional();
                            if (managedDepOptional != null) {
                                parsedDep.setOptional(managedDepOptional);
                            }
                            if (parsedDep.getOptional() == null) {
                                parsedDep.setOptional("false");
                            }
                        }
                        Dependency flattenedDep = createGroomedDependency(parsedDep);
                        if (flattenedDep != null) {
                            strippedDependencies.add(flattenedDep);
                        }
                    }
                    if (!strippedDependencies.isEmpty() || !isEmpty(profile.getRepositories())) {
                        Profile strippedProfile = new Profile();
                        strippedProfile.setId(profile.getId());
                        strippedProfile.setActivation(profile.getActivation());
                        strippedProfile.setDependencies(strippedDependencies.isEmpty() ? null : strippedDependencies);
                        strippedProfile.setRepositories(profile.getRepositories());
                        cleanPom.addProfile(strippedProfile);
                    }
                }
            }
        }

        // transform dependencies...
        List<Dependency> dependencies = createGroomedDependencies(effectivePom);
        cleanPom.setDependencies(dependencies);
        return cleanPom;
    }

    /**
     * Resolves the source model that should provide a property value for the generated POM.
     *
     * @param descriptor the POM element handling descriptor.
     * @param property the POM property being copied.
     * @param modelsFactory the lazy model factory.
     * @return the source model for the property, or {@code null} when the property is removed.
     * @throws MojoExecutionException when a required source model cannot be created.
     */
    private Model getSourceModel(GroomDescriptor descriptor, PomProperty<?> property, ModelsFactory modelsFactory)
            throws MojoExecutionException {

        ElementHandling handling = descriptor.getHandling(property);
        if (shouldKeepPomPackagingElement(property, handling)) {
            handling = ElementHandling.keep;
        }
        getLog().debug("Property " + property.getName() + " will be handled using " + handling + " in flattened POM.");
        return modelsFactory.getModel(handling);
    }

    /**
     * Tests whether a removable project-structure element should be kept for POM-packaged projects.
     *
     * @param property the POM property
     * @param handling the configured element handling
     * @return {@code true} when the property should be kept
     */
    private boolean shouldKeepPomPackagingElement(PomProperty<?> property, ElementHandling handling) {
        if (applyProjectElementRemovalsToPomPackaging || !"pom".equals(this.project.getPackaging())) {
            return false;
        }
        if (handling != ElementHandling.remove && handling != ElementHandling.flatten) {
            return false;
        }
        return property == PomProperty.PARENT
                || property == PomProperty.PROPERTIES
                || property == PomProperty.DEPENDENCY_MANAGEMENT
                || property == PomProperty.DISTRIBUTION_MANAGEMENT
                || property == PomProperty.REPOSITORIES
                || property == PomProperty.PLUGIN_REPOSITORIES
                || property == PomProperty.REPORTING
                || property == PomProperty.BUILD
                || property == PomProperty.PROFILES
                || property == PomProperty.MODULES;
    }

    /**
     * Creates a flattened {@link List} of {@link Repository} elements where those from super-POM are omitted.
     *
     * @param repositories is the {@link List} of {@link Repository} elements. May be <code>null</code>.
     * @return the flattened {@link List} of {@link Repository} elements or <code>null</code> if <code>null</code> was
     * given.
     */
    protected static List<Repository> createGroomedRepositories(List<Repository> repositories) {
        if (repositories != null) {
            List<Repository> flattenedRepositories = new ArrayList<>(repositories.size());
            for (Repository repo : repositories) {
                // filter inherited repository section from super POM (see MOJO-2042)...
                if (!isCentralRepositoryFromSuperPom(repo)) {
                    flattenedRepositories.add(repo);
                }
            }
            return flattenedRepositories;
        }
        return null;
    }

    /**
     * Resolves the final POM element handling descriptor for the current execution.
     *
     * @return the descriptor used to create the generated POM.
     */
    private GroomDescriptor getGroomDescriptor() {
        GroomDescriptor descriptor = createRawPomElementsDescriptor();
        if (descriptor == null) {
            descriptor = this.pomElements;
        }
        if (descriptor == null) {
            GroomMode mode = this.groomMode;
            if (mode == null) {
                mode = GroomMode.defaults;
            }
            descriptor = mode.getDescriptor();
            if ("maven-plugin".equals(this.project.getPackaging())) {
                descriptor.setPrerequisites(ElementHandling.expand);
            }
            applyProjectExpressionResolution(descriptor, false);
        } else {
            if (descriptor.isEmpty()) {
                // legacy approach...
                // Can't use Model itself as empty elements are never null, so you can't recognize if it was set or not
                Xpp3Dom rawDescriptor = this.mojoExecution.getConfiguration().getChild("pomElements");
                descriptor = new GroomDescriptor(rawDescriptor);
            }

            applyProjectExpressionResolution(descriptor, true);
            if (this.groomMode != null) {
                descriptor = descriptor.merge(this.groomMode.getDescriptor());
            }
        }
        descriptor.setDefaultOperation(defaultOperation);
        if (buildPlugins != null) {
            descriptor.setBuildPlugins(buildPlugins);
        }
        effectiveCompileScopeRemoval = descriptor.isCompileScopeRemoved();
        effectiveBuildPluginsRemoval = descriptor.isBuildPluginsRemoved();
        return descriptor;
    }

    /**
     * Applies project expression resolution to public metadata fields.
     *
     * @param descriptor   the descriptor to update
     * @param keepExisting whether existing handling values should be preserved
     */
    private void applyProjectExpressionResolution(GroomDescriptor descriptor, boolean keepExisting) {
        if (!resolveProjectExpressions) {
            return;
        }
        Map<String, ElementHandling> handlers = descriptor.getName2handlingMap();
        for (PomProperty<?> property : PROJECT_EXPRESSION_PROPERTIES) {
            if (!keepExisting || !handlers.containsKey(property.getName())) {
                descriptor.setHandling(property, ElementHandling.expand);
            }
        }
    }

    /**
     * Creates a descriptor from the raw {@code pomElements} configuration. This keeps empty-tag and text-value
     * handling deterministic across Maven versions.
     *
     * @return the raw descriptor, or {@code null} when no raw configuration exists
     */
    private GroomDescriptor createRawPomElementsDescriptor() {
        Xpp3Dom configuration = this.mojoExecution == null ? null : this.mojoExecution.getConfiguration();
        Xpp3Dom rawDescriptor = configuration == null ? null : configuration.getChild("pomElements");
        if (rawDescriptor == null) {
            return null;
        }
        GroomDescriptor descriptor = new GroomDescriptor();
        for (Xpp3Dom child : rawDescriptor.getChildren()) {
            if (isPomElementOption(child.getName())) {
                applyPomElementOption(descriptor, child);
                continue;
            }
            String value = child.getValue();
            ElementHandling handling = value == null || value.isBlank()
                    ? ElementHandling.expand
                    : ElementHandling.valueOf(value.trim());
            descriptor.setHandling(child.getName(), handling);
        }
        return descriptor;
    }

    /**
     * Tests whether the child name is a non-element option under {@code pomElements}.
     *
     * @param name the child element name
     * @return {@code true} when the child is a {@code pomElements} option
     */
    private boolean isPomElementOption(String name) {
        return "compileScope".equals(name) || "buildPlugins".equals(name);
    }

    /**
     * Applies a non-element option under {@code pomElements}.
     *
     * @param descriptor the descriptor receiving the option
     * @param option     the raw option node
     */
    private void applyPomElementOption(GroomDescriptor descriptor, Xpp3Dom option) {
        ElementHandling handling = getPomElementOptionHandling(option);
        if ("compileScope".equals(option.getName())) {
            descriptor.setCompileScope(handling);
        } else if ("buildPlugins".equals(option.getName())) {
            descriptor.setBuildPlugins(handling);
        }
    }

    /**
     * Reads element handling from a non-model {@code pomElements} option.
     *
     * @param option the raw option node
     * @return the configured element handling
     */
    private ElementHandling getPomElementOptionHandling(Xpp3Dom option) {
        String value = option.getValue();
        return value == null || value.isBlank() ? ElementHandling.expand : ElementHandling.valueOf(value.trim());
    }

    /**
     * Reads an option handling value from raw {@code pomElements}.
     *
     * @param name the option element name
     * @return the option handling, or {@code null} when absent
     */
    private ElementHandling getRawPomElementHandling(String name) {
        Xpp3Dom configuration = this.mojoExecution == null ? null : this.mojoExecution.getConfiguration();
        Xpp3Dom rawDescriptor = configuration == null ? null : configuration.getChild("pomElements");
        Xpp3Dom child = rawDescriptor == null ? null : rawDescriptor.getChild(name);
        return child == null ? null : getPomElementOptionHandling(child);
    }

    /**
     * Tests whether the raw Mojo configuration contains {@code pomElements}.
     *
     * @return {@code true} when raw {@code pomElements} are configured
     */
    private boolean hasRawPomElements() {
        Xpp3Dom configuration = this.mojoExecution == null ? null : this.mojoExecution.getConfiguration();
        return configuration != null && configuration.getChild("pomElements") != null;
    }

    /**
     * Resolves the model encoding, defaulting to UTF-8 when Maven does not specify one.
     *
     * @param pom the POM model.
     * @return the model encoding.
     */
    private static String getModelEncoding(Model pom) {
        // keep original encoding (we could also normalize to UTF-8 here)
        String modelEncoding = pom.getModelEncoding();
        if (StringUtils.isEmpty(modelEncoding)) {
            modelEncoding = "UTF-8";
        }
        return modelEncoding;
    }

    /**
     * This method determines if the given {@link Repository} section is identical to what is defined from the super
     * POM.
     *
     * @param repo is the {@link Repository} section to check.
     * @return <code>true</code> if maven central default configuration, <code>false</code> otherwise.
     */
    private static boolean isCentralRepositoryFromSuperPom(Repository repo) {
        if (repo != null) {
            if ("central".equals(repo.getId())) {
                RepositoryPolicy snapshots = repo.getSnapshots();
                return snapshots != null && !snapshots.isEnabled();
            }
        }
        return false;
    }

    /**
     * Creates a model building request that mirrors Maven core profile and repository state.
     *
     * @param pomFile the POM file to build.
     * @return the model building request.
     */
    private ModelBuildingRequest createModelBuildingRequest(File pomFile) {

        RequestTrace trace = new RequestTrace(pomFile);
        String context = mojoExecution.getExecutionId();
        GroomModelResolver resolver = new GroomModelResolver(
                session.getRepositorySession(),
                repositorySystem,
                trace,
                context,
                project.getRemoteProjectRepositories(),
                getReactorModelsFromSession());
        Properties userAndActiveExternalProfilesProperties = new Properties();
        userAndActiveExternalProfilesProperties.putAll(this.session.getUserProperties());
        this.settings.getProfiles().stream()
                .filter(p -> this.settings.getActiveProfiles().contains(p.getId())
                        && !this.session.getRequest().getInactiveProfiles().contains(p.getId()))
                .forEach(
                        activeProfile -> userAndActiveExternalProfilesProperties.putAll(activeProfile.getProperties()));

        List<String> activeProfiles = Stream.concat(
                        this.session.getRequest().getActiveProfiles().stream(),
                        this.settings.getActiveProfiles().stream())
                .collect(Collectors.toList());

        return new DefaultModelBuildingRequest()
                .setUserProperties(userAndActiveExternalProfilesProperties)
                .setSystemProperties(System.getProperties())
                .setPomFile(pomFile)
                .setModelResolver(resolver)
                .setActiveProfileIds(activeProfiles)
                .setInactiveProfileIds(this.session.getRequest().getInactiveProfiles());
    }

    /**
     * Returns reactor models from the Maven session using fallbacks for embedded Maven environments.
     *
     * @return the reactor projects available in the current Maven session.
     */
    private List<MavenProject> getReactorModelsFromSession() {
        // robust approach for 'special' environments like m2e (Eclipse plugin) which don't provide allProjects
        List<MavenProject> models = this.session.getAllProjects();
        if (models == null) {
            models = this.session.getProjects();
        }
        if (models == null) {
            models = Collections.emptyList();
        }
        return models;
    }

    /**
     * Creates the effective POM for the given <code>pomFile</code> trying its best to match the core maven behaviour.
     *
     * @param buildingRequest {@link ModelBuildingRequest}
     * @return the parsed and calculated effective POM.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected Model createEffectivePomImpl(ModelBuildingRequest buildingRequest) throws MojoExecutionException {
        ModelBuildingResult buildingResult;
        try {
            ProfileInjector customInjector = (model, profile, request, problems) -> {
                Properties merged = new Properties();
                merged.putAll(model.getProperties());
                merged.putAll(profile.getProperties());
                model.setProperties(merged);

                // Copied from org.apache.maven.model.profile.DefaultProfileInjector
                DependencyManagement profileDependencyManagement = profile.getDependencyManagement();
                if (profileDependencyManagement != null) {
                    DependencyManagement modelDependencyManagement = model.getDependencyManagement();
                    if (modelDependencyManagement == null) {
                        modelDependencyManagement = new DependencyManagement();
                        model.setDependencyManagement(modelDependencyManagement);
                    }

                    List<Dependency> src = profileDependencyManagement.getDependencies();
                    if (!src.isEmpty()) {
                        List<Dependency> tgt = modelDependencyManagement.getDependencies();
                        Map<Object, Dependency> mergedDependencies = new LinkedHashMap<>((src.size() + tgt.size()) * 2);

                        for (Dependency element : tgt) {
                            mergedDependencies.put(element.getManagementKey(), element);
                        }

                        for (Dependency element : src) {
                            String key = element.getManagementKey();
                            if (!mergedDependencies.containsKey(key)) {
                                mergedDependencies.put(key, element);
                            }
                        }

                        modelDependencyManagement.setDependencies(new ArrayList<>(mergedDependencies.values()));
                    }
                }
            };

            buildingResult = modelBuilderThreadSafetyWorkaround.build(buildingRequest, customInjector, profileSelector);
        } catch (ModelBuildingException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        Model effectivePom = buildingResult.getEffectiveModel();

        // LoggingModelProblemCollector problems = new LoggingModelProblemCollector( getLog() );
        // Model interpolatedModel =
        // this.modelInterpolator.interpolateModel( this.project.getOriginalModel(),
        // effectivePom.getProjectDirectory(), buildingRequest, problems );

        // remove Repositories from super POM (central)
        effectivePom.setRepositories(createGroomedRepositories(effectivePom.getRepositories()));
        return effectivePom;
    }

    /**
     * Null-safe check for {@link Collection#isEmpty()}.
     *
     * @param collection is the {@link Collection} to test. May be <code>null</code>.
     * @return <code>true</code> if <code>null</code> or {@link Collection#isEmpty() empty}, <code>false</code>
     * otherwise.
     */
    private boolean isEmpty(Collection<?> collection) {
        if (collection == null) {
            return true;
        }
        return collection.isEmpty();
    }

    /**
     * Checks whether build-dependent profiles are embedded into the generated POM.
     *
     * @return <code>true</code> if build-dependent profiles (triggered by OS or JDK) should be evaluated and their
     * effect (variables and dependencies) are resolved and embedded into the flattened POM while the profile itself is
     * stripped. Otherwise if <code>false</code> the profiles will remain untouched.
     */
    public boolean isEmbedBuildProfileDependencies() {
        return this.embedBuildProfileDependencies;
    }

    /**
     * Checks whether the profile activation is driven by build inputs instead of OS or JDK constraints.
     *
     * @param activation is the {@link Activation} of a {@link Profile}.
     * @return <code>true</code> if the given {@link Activation} is build-time driven, <code>false</code> otherwise (if
     * it is triggered by OS or JDK).
     */
    protected static boolean isBuildTimeDriven(Activation activation) {

        if (activation == null) {
            return true;
        }
        return StringUtils.isEmpty(activation.getJdk()) && activation.getOs() == null;
    }

    /**
     * Creates the {@link List} of {@link Dependency dependencies} for the flattened POM. These are all resolved
     * {@link Dependency dependencies} except for those added from {@link Profile profiles}.
     *
     * @param effectiveModel is the effective POM {@link Model} to process.
     * @return the {@link List} of {@link Dependency dependencies}.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected List<Dependency> createGroomedDependencies(Model effectiveModel) throws MojoExecutionException {
        List<Dependency> flattenedDependencies = new ArrayList<>();
        // resolve all direct and inherited dependencies...
        try {
            createGroomedDependencies(effectiveModel, flattenedDependencies);
        } catch (Exception e) {
            throw new MojoExecutionException("unable to create flattened dependencies", e);
        }
        if (isEmbedBuildProfileDependencies()) {
            Model projectModel = this.project.getModel();
            Dependencies modelDependencies = new Dependencies();
            modelDependencies.addAll(projectModel.getDependencies());
            for (Profile profile : projectModel.getProfiles()) {
                // build-time driven activation (by property or file)?
                if (isBuildTimeDriven(profile.getActivation())) {
                    List<Dependency> profileDependencies = profile.getDependencies();
                    for (Dependency profileDependency : profileDependencies) {
                        if (modelDependencies.contains(profileDependency)) {
                            // our assumption here is that the profileDependency has been added to model because of
                            // this build-time driven profile. Therefore we need to add it to the flattened POM.
                            // Non build-time driven profiles will remain in the flattened POM with their dependencies
                            // and
                            // allow dynamic dependencies due to OS or JDK.
                            Dependency resolvedProfileDependency = modelDependencies.resolve(profileDependency);
                            if (omitExclusions) {
                                resolvedProfileDependency.setExclusions(Collections.emptyList());
                            }
                            flattenedDependencies.add(resolvedProfileDependency);
                        }
                    }
                }
            }
            getLog().debug("Resolved " + flattenedDependencies.size() + " dependency/-ies for flattened POM.");
        }
        return flattenedDependencies;
    }

    /**
     * Collects the resolved {@link Dependency dependencies} from the given <code>effectiveModel</code>.
     *
     * @param projectDependencies   is the effective POM {@link Model}'s current dependencies
     * @param flattenedDependencies is the {@link List} where to add the collected {@link Dependency dependencies}.
     */
    private void createGroomedDependenciesDirect(
            List<Dependency> projectDependencies, List<Dependency> flattenedDependencies) {
        for (Dependency projectDependency : projectDependencies) {
            Dependency flattenedDependency = createGroomedDependency(projectDependency);
            if (flattenedDependency != null) {
                flattenedDependencies.add(flattenedDependency);
            }
        }
    }

    /**
     * Collects the resolved direct and transitive {@link Dependency dependencies} from the given
     * <code>effectiveModel</code>.
     * The collected dependencies are stored in order, so that the leaf dependencies are prioritized in front of direct
     * dependencies.
     * In addition, every non-leaf dependencies will exclude its own direct dependency, since all transitive
     * dependencies
     * will be collected.
     * <p>
     * Transitive dependencies are all going to be collected and become a direct dependency. Maven should already
     * resolve
     * versions properly because now the transitive dependencies are closer to the artifact. However, when this artifact
     * is
     * being consumed, Maven Enforcer Convergence rule will fail because there may be multiple versions for the same
     * transitive dependency.
     * <p>
     * Typically, exclusion can be done by using the wildcard. However, a known Maven issue prevents convergence
     * enforcer from
     * working properly w/ wildcard exclusions. Thus, this will exclude each dependencies explicitly rather than using
     * the wildcard.
     *
     * @param projectDependencies   is the effective POM {@link Model}'s current dependencies
     * @param flattenedDependencies is the {@link List} where to add the collected {@link Dependency dependencies}.
     * @throws DependencyCollectionException
     * @throws ArtifactDescriptorException
     */
    private void createGroomedDependenciesAll(
            List<Dependency> projectDependencies,
            List<Dependency> managedDependencies,
            List<Dependency> flattenedDependencies)
            throws ArtifactDescriptorException, DependencyCollectionException {
        final Queue<DependencyNode> dependencyNodeLinkedList = new LinkedList<>();
        final Set<String> processedDependencies = new HashSet<>();
        final Artifact projectArtifact = this.project.getArtifact();

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(project.getRemoteProjectRepositories());
        collectRequest.setRootArtifact(RepositoryUtils.toArtifact(projectArtifact));
        for (Dependency dependency : projectDependencies) {
            collectRequest.addDependency(RepositoryUtils.toDependency(
                    dependency, session.getRepositorySession().getArtifactTypeRegistry()));
        }

        for (Artifact artifact : project.getArtifacts()) {
            collectRequest.addDependency(RepositoryUtils.toDependency(artifact, null));
        }

        for (Dependency dependency : managedDependencies) {
            collectRequest.addManagedDependency(RepositoryUtils.toDependency(
                    dependency, session.getRepositorySession().getArtifactTypeRegistry()));
        }

        DefaultRepositorySystemSession derived = new DefaultRepositorySystemSession(session.getRepositorySession());
        derived.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        derived.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);

        CollectResult collectResult = repositorySystem.collectDependencies(derived, collectRequest);

        final DependencyNode root = collectResult.getRoot();
        final Set<String> directDependencyKeys = Stream.concat(
                        projectDependencies.stream().map(this::getKey),
                        project.getArtifacts().stream().map(this::getKey))
                .collect(Collectors.toSet());

        root.accept(new DependencyVisitor() {
            /**
             * Visits a dependency node before its children and collects dependencies that should be written to the
             * groomed POM.
             *
             * @param node the dependency node being entered.
             * @return {@code true} when child nodes should be visited.
             */
            @Override
            public boolean visitEnter(DependencyNode node) {
                if (root == node) {
                    return true;
                }
                if (JavaScopes.PROVIDED.equals(node.getDependency().getScope())) {
                    String dependencyKey = getKey(node.getDependency());
                    if (!directDependencyKeys.contains(dependencyKey)) {
                        return false; // skip non-direct provided ones
                    }
                }
                if (node.getDependency().isOptional()) {
                    return false; // skip optional ones
                }
                dependencyNodeLinkedList.add(node);
                return true;
            }

            /**
             * Visits a dependency node after its children.
             *
             * @param node the dependency node being left.
             * @return {@code true} to continue dependency traversal.
             */
            @Override
            public boolean visitLeave(DependencyNode node) {
                return true;
            }
        });

        while (!dependencyNodeLinkedList.isEmpty()) {
            DependencyNode node = dependencyNodeLinkedList.poll();

            org.eclipse.aether.graph.Dependency d = node.getDependency();
            Artifact artifact = RepositoryUtils.toArtifact(node.getArtifact());

            Dependency dependency = new Dependency();
            dependency.setGroupId(artifact.getGroupId());
            dependency.setArtifactId(artifact.getArtifactId());
            dependency.setVersion(artifact.getBaseVersion());
            dependency.setClassifier(artifact.getClassifier());
            dependency.setOptional(d.isOptional());
            dependency.setScope(d.getScope());
            dependency.setType(artifact.getType());

            if (!omitExclusions) {
                List<Exclusion> exclusions = new LinkedList<>();

                org.eclipse.aether.artifact.Artifact aetherArtifact = new DefaultArtifact(
                        artifact.getGroupId(), artifact.getArtifactId(), null, artifact.getVersion());
                ArtifactDescriptorRequest request = new ArtifactDescriptorRequest(aetherArtifact, null, null);
                ArtifactDescriptorResult artifactDescriptorResult =
                        repositorySystem.readArtifactDescriptor(this.session.getRepositorySession(), request);

                for (org.eclipse.aether.graph.Dependency artifactDependency :
                        artifactDescriptorResult.getDependencies()) {
                    if (JavaScopes.TEST.equals(artifactDependency.getScope())) {
                        continue;
                    }
                    Exclusion exclusion = new Exclusion();
                    exclusion.setGroupId(artifactDependency.getArtifact().getGroupId());
                    exclusion.setArtifactId(artifactDependency.getArtifact().getArtifactId());
                    exclusions.add(exclusion);
                }

                dependency.setExclusions(exclusions);
            }

            // convert dependency to string for the set, since Dependency doesn't implement equals, etc.
            String dependencyString = dependency.getManagementKey();

            if (!processedDependencies.add(dependencyString)) {
                continue;
            }

            Dependency flattenedDependency = createGroomedDependency(dependency);
            if (flattenedDependency != null) {
                flattenedDependencies.add(flattenedDependency);
            }
        }
    }

    /**
     * Keep in sync with {@link #getKey(org.eclipse.aether.graph.Dependency)}
     * and {@link #getKey(Dependency)}.
     */
    private String getKey(Artifact a) {
        final String ext =
                artifactHandlerManager.getArtifactHandler(a.getType()).getExtension();
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + ext
                + (a.getClassifier() != null ? ":" + a.getClassifier() : "");
    }

    /**
     * Keep in sync with {@link #getKey(org.eclipse.aether.graph.Dependency)}
     * and {@link #getKey(Artifact)}
     */
    private String getKey(Dependency d) {
        final String ext =
                artifactHandlerManager.getArtifactHandler(d.getType()).getExtension();
        return d.getGroupId() + ":" + d.getArtifactId() + ":" + ext
                + (d.getClassifier() != null ? ":" + d.getClassifier() : "");
    }

    /**
     * Keep in sync with {@link #getKey(Dependency)}
     * and {@link #getKey(Artifact)}.
     */
    private String getKey(org.eclipse.aether.graph.Dependency dependency) {
        final org.eclipse.aether.artifact.Artifact a = dependency.getArtifact();
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getExtension()
                + (!a.getClassifier().isEmpty() ? ":" + a.getClassifier() : "");
    }

    /**
     * Collects the resolved {@link Dependency dependencies} from the given <code>effectiveModel</code>.
     *
     * @param effectiveModel        is the effective POM {@link Model} to process.
     * @param flattenedDependencies is the {@link List} where to add the collected {@link Dependency dependencies}.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected void createGroomedDependencies(Model effectiveModel, List<Dependency> flattenedDependencies)
            throws MojoExecutionException {
        getLog().debug("Resolving dependencies of " + effectiveModel.getId());
        // this.project.getDependencies() already contains the inherited dependencies but also those from profiles
        // List<Dependency> projectDependencies = currentProject.getOriginalModel().getDependencies();
        List<Dependency> projectDependencies = effectiveModel.getDependencies();

        if (groomDependencyMode == null
                || groomDependencyMode == GroomDependencyMode.direct
                || groomDependencyMode == GroomDependencyMode.inherited) {
            createGroomedDependenciesDirect(projectDependencies, flattenedDependencies);
        } else if (groomDependencyMode == GroomDependencyMode.all) {
            try {
                createGroomedDependenciesAll(
                        projectDependencies,
                        effectiveModel.getDependencyManagement() != null
                                ? effectiveModel.getDependencyManagement().getDependencies()
                                : Collections.emptyList(),
                        flattenedDependencies);
            } catch (Exception e) {
                throw new MojoExecutionException("caught exception when flattening dependencies", e);
            }
        }
    }

    /**
     * Creates a publication dependency or removes it when it is not relevant to consumers.
     *
     * @param projectDependency is the project {@link Dependency}.
     * @return the flattened {@link Dependency} or <code>null</code> if the given {@link Dependency} is NOT relevant for
     * flattened POM.
     */
    protected Dependency createGroomedDependency(Dependency projectDependency) {
        if (JavaScopes.TEST.equals(projectDependency.getScope())) {
            return null;
        }

        if (omitExclusions) {
            projectDependency.setExclusions(Collections.emptyList());
        }

        return projectDependency;
    }

    /**
     * Tests whether Maven should use the generated groomed POM for later lifecycle goals.
     *
     * @return <code>true</code> if the generated groomed POM shall be {@link MavenProject#setFile(java.io.File) set}
     * as POM artifact of the {@link MavenProject}, <code>false</code> otherwise.
     */
    public boolean isUpdatePomFile() {
        return this.updatePomFile;
    }

    /**
     * Lazy factory for the different model views needed by POM element handling.
     */
    private class ModelsFactory {

        /**
         * Source POM file.
         */
        private final File pomFile;

        /**
         * Cached effective model.
         */
        private Model effectivePom;

        /**
         * Cached original model loaded from the source POM.
         */
        private Model originalPom;

        /**
         * Cached Maven-resolved project model.
         */
        private Model resolvedPom;

        /**
         * Cached CI-friendly interpolated model.
         */
        private Model interpolatedPom;

        /**
         * Cached extended interpolated model.
         */
        private Model extendedInterpolatedPom;

        /**
         * Cached clean model.
         */
        private Model cleanPom;

        /**
         * Creates a lazy model factory for one source POM.
         *
         * @param pomFile the source POM file
         */
        private ModelsFactory(File pomFile) {
            this.pomFile = pomFile;
        }

        /**
         * Returns the effective model.
         *
         * @return the effective model
         * @throws MojoExecutionException if the effective model cannot be created
         */
        public Model getEffectivePom() throws MojoExecutionException {
            if (effectivePom == null) {
                this.effectivePom = GroomMojo.this
                        .createEffectivePom(createModelBuildingRequest(pomFile))
                        .clone();
            }
            return this.effectivePom;
        }

        /**
         * Returns the original model loaded from the source POM.
         *
         * @return the original model
         * @throws MojoExecutionException if the original model cannot be loaded
         */
        public Model getOriginalPom() throws MojoExecutionException {
            if (this.originalPom == null) {
                this.originalPom = createOriginalPom(this.pomFile.toPath());
            }
            return this.originalPom;
        }

        /**
         * Returns the Maven-resolved project model.
         *
         * @return the Maven-resolved project model
         */
        public Model getResolvedPom() {
            if (this.resolvedPom == null) {
                this.resolvedPom = GroomMojo.this.project.getModel();
            }
            return this.resolvedPom;
        }

        /**
         * Returns the CI-friendly interpolated model.
         *
         * @return the CI-friendly interpolated model
         * @throws MojoExecutionException if interpolation cannot be completed
         */
        public Model getInterpolatedPom() throws MojoExecutionException {
            if (this.interpolatedPom == null) {
                this.interpolatedPom = createInterpolatedPom(
                        createModelBuildingRequest(pomFile),
                        getOriginalPom().clone(),
                        getResolvedPom().getProjectDirectory())
                        .clone();
            }
            return this.interpolatedPom;
        }

        /**
         * Returns the extended interpolated model.
         *
         * @return the extended interpolated model
         * @throws MojoExecutionException if interpolation cannot be completed
         */
        public Model getExtendedInterpolatedPom() throws MojoExecutionException {
            if (this.extendedInterpolatedPom == null) {
                this.extendedInterpolatedPom = createExtendedInterpolatedPom(
                        createModelBuildingRequest(pomFile),
                        getOriginalPom().clone(),
                        getEffectivePom().clone(),
                        getResolvedPom().getProjectDirectory())
                        .clone();
            }
            return this.extendedInterpolatedPom;
        }

        /**
         * Returns the clean model.
         *
         * @return the clean model
         * @throws MojoExecutionException if the clean model cannot be created
         */
        public Model getCleanPom() throws MojoExecutionException {
            if (this.cleanPom == null) {
                this.cleanPom = createCleanPom(getEffectivePom().clone()).clone();
            }
            return this.cleanPom;
        }

        /**
         * Selects the model view that matches the requested element handling.
         *
         * @param handling the element handling to resolve
         * @return the matching model view, or {@code null} for removed elements
         * @throws MojoExecutionException if the required model view cannot be created
         */
        public Model getModel(ElementHandling handling) throws MojoExecutionException {
            switch (handling) {
                case expand:
                    return this.getEffectivePom();
                case keep:
                    return this.getOriginalPom();
                case resolve:
                    return this.getResolvedPom();
                case interpolate:
                    return this.getInterpolatedPom();
                case extended_interpolate:
                    return this.getExtendedInterpolatedPom();
                case flatten:
                    return this.getCleanPom();
                case remove:
                    return null;
                default:
                    throw new IllegalStateException(handling.toString());
            }
        }
    }

    /**
     * This class is a simple SAX handler that extracts the first comment located before the root tag in an XML
     * document.
     */
    private class SaxHeaderCommentHandler extends DefaultHandler2 {

        /**
         * <code>true</code> if root tag has already been visited, <code>false</code> otherwise.
         */
        private boolean rootTagSeen;

        /**
         * Header XML comment captured before the root project tag.
         *
         * @see #getHeaderComment()
         */
        private String headerComment;

        /**
         * The constructor.
         */
        SaxHeaderCommentHandler() {

            super();
            this.rootTagSeen = false;
        }

        /**
         * Returns the captured XML header comment.
         *
         * @return the XML comment from the header of the document or <code>null</code> if not present.
         */
        public String getHeaderComment() {

            return this.headerComment;
        }

        /**
         * Captures the first XML comment found before the root tag.
         *
         * @param ch the comment character buffer
         * @param start the start offset in the buffer
         * @param length the number of comment characters
         */
        @Override
        public void comment(char[] ch, int start, int length) {

            if (!this.rootTagSeen) {
                if (this.headerComment == null) {
                    this.headerComment = new String(ch, start, length);
                } else {
                    getLog().warn("Ignoring multiple XML header comment!");
                }
            }
        }

        /**
         * Marks that the root XML element has been reached.
         *
         * @param uri the namespace URI
         * @param localName the local element name
         * @param qName the qualified element name
         * @param attrs the element attributes
         */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {

            this.rootTagSeen = true;
        }
    }

}
