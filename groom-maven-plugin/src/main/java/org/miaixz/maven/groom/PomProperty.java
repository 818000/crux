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

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.model.CiManagement;
import org.apache.maven.model.Contributor;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Developer;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.IssueManagement;
import org.apache.maven.model.License;
import org.apache.maven.model.MailingList;
import org.apache.maven.model.Model;
import org.apache.maven.model.Organization;
import org.apache.maven.model.Parent;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Prerequisites;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Reporting;
import org.apache.maven.model.Repository;
import org.apache.maven.model.Scm;

/**
 * This class reflects a property of a {@link Model POM}. It contains {@link #getPomProperties() all available
 * properties} as constants and allows generic access to {@link #get(Model) read} and {@link #set(Model, Object) write}
 * the property via a {@link Model}.
 *
 * @param <V> is the generic type of the {@link #get(Model) property value}.
 * @author Joerg Hohwiller (hohwille at users.sourceforge.net)
 * @since 1.0.0-beta-4
 */
public abstract class PomProperty<V> {

    /**
     * POM property for the artifact identifier.
     *
     * @see Model#getArtifactId()
     */
    public static final PomProperty<String> ARTIFACT_ID = new PomProperty<String>("artifactId", String.class) {
        @Override
        public String get(Model model) {
            return model.getArtifactId();
        }

        @Override
        public void set(Model model, String value) {
            model.setArtifactId(value);
        }
    };

    /**
     * POM property for the build section.
     *
     * @see Model#getBuild()
     */
    public static final PomProperty<Build> BUILD = new PomProperty<Build>("build", Build.class) {
        @Override
        public Build get(Model model) {
            return model.getBuild();
        }

        @Override
        public void set(Model model, Build value) {
            model.setBuild(value);
        }
    };

    /**
     * POM property for the continuous integration management section.
     *
     * @see Model#getCiManagement()
     */
    public static final PomProperty<CiManagement> CI_MANAGEMENT =
            new PomProperty<CiManagement>("ciManagement", CiManagement.class) {
                @Override
                public CiManagement get(Model model) {
                    return model.getCiManagement();
                }

                @Override
                public void set(Model model, CiManagement value) {
                    model.setCiManagement(value);
                }
            };

    /**
     * POM property for contributor declarations.
     *
     * @see Model#getContributors()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<Contributor>> CONTRIBUTORS =
            new PomProperty<List<Contributor>>("contributors", (Class) List.class) {
                @Override
                public List<Contributor> get(Model model) {
                    return model.getContributors();
                }

                @Override
                public void set(Model model, List<Contributor> value) {
                    model.setContributors(value);
                }
            };

    /**
     * POM property for project dependencies.
     *
     * @see Model#getDependencies()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<Dependency>> DEPENDENCIES =
            new PomProperty<List<Dependency>>("dependencies", (Class) List.class) {
                @Override
                public List<Dependency> get(Model model) {
                    return model.getDependencies();
                }

                @Override
                public void set(Model model, List<Dependency> value) {
                    model.setDependencies(value);
                }
            };

    /**
     * POM property for dependency management.
     *
     * @see Model#getDependencyManagement()
     */
    public static final PomProperty<DependencyManagement> DEPENDENCY_MANAGEMENT =
            new PomProperty<DependencyManagement>("dependencyManagement", DependencyManagement.class) {
                @Override
                public DependencyManagement get(Model model) {
                    return model.getDependencyManagement();
                }

                @Override
                public void set(Model model, DependencyManagement value) {
                    model.setDependencyManagement(value);
                }
            };

    /**
     * POM property for the project description.
     *
     * @see Model#getDescription()
     */
    public static final PomProperty<String> DESCRIPTION = new PomProperty<String>("description", String.class) {
        @Override
        public String get(Model model) {
            return model.getDescription();
        }

        @Override
        public void set(Model model, String value) {
            model.setDescription(value);
        }
    };

    /**
     * POM property for project developers.
     *
     * @see Model#getDevelopers()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<Developer>> DEVELOPERS =
            new PomProperty<List<Developer>>("developers", (Class) List.class) {
                @Override
                public List<Developer> get(Model model) {
                    return model.getDevelopers();
                }

                @Override
                public void set(Model model, List<Developer> value) {
                    model.setDevelopers(value);
                }
            };

    /**
     * POM property for distribution management.
     *
     * @see Model#getDistributionManagement()
     */
    public static final PomProperty<DistributionManagement> DISTRIBUTION_MANAGEMENT =
            new PomProperty<DistributionManagement>("distributionManagement", DistributionManagement.class) {
                @Override
                public DistributionManagement get(Model model) {
                    return model.getDistributionManagement();
                }

                @Override
                public void set(Model model, DistributionManagement value) {
                    model.setDistributionManagement(value);
                }
            };

    /**
     * POM property for the group identifier.
     *
     * @see Model#getGroupId()
     */
    public static final PomProperty<String> GROUP_ID = new PomProperty<String>("groupId", String.class) {
        @Override
        public String get(Model model) {
            return model.getGroupId();
        }

        @Override
        public void set(Model model, String value) {
            model.setGroupId(value);
        }
    };

    /**
     * POM property for the inception year.
     *
     * @see Model#getInceptionYear()
     */
    public static final PomProperty<String> INCEPTION_YEAR = new PomProperty<String>("inceptionYear", String.class) {
        @Override
        public String get(Model model) {
            return model.getInceptionYear();
        }

        @Override
        public void set(Model model, String value) {
            model.setInceptionYear(value);
        }
    };

    /**
     * POM property for issue management.
     *
     * @see Model#getIssueManagement()
     */
    public static final PomProperty<IssueManagement> ISSUE_MANAGEMENT =
            new PomProperty<IssueManagement>("issueManagement", IssueManagement.class) {
                @Override
                public IssueManagement get(Model model) {
                    return model.getIssueManagement();
                }

                @Override
                public void set(Model model, IssueManagement value) {
                    model.setIssueManagement(value);
                }
            };

    /**
     * POM property for project licenses.
     *
     * @see Model#getLicenses()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<License>> LICENSES =
            new PomProperty<List<License>>("licenses", (Class) List.class) {
                @Override
                public List<License> get(Model model) {
                    return model.getLicenses();
                }

                @Override
                public void set(Model model, List<License> value) {
                    model.setLicenses(value);
                }
            };

    /**
     * POM property for mailing lists.
     *
     * @see Model#getMailingLists()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<MailingList>> MAILING_LISTS =
            new PomProperty<List<MailingList>>("mailingLists", (Class) List.class) {
                @Override
                public List<MailingList> get(Model model) {
                    return model.getMailingLists();
                }

                @Override
                public void set(Model model, List<MailingList> value) {
                    model.setMailingLists(value);
                }
            };

    /**
     * POM property for the model encoding.
     *
     * @see Model#getModelEncoding()
     */
    public static final PomProperty<String> MODEL_ENCODING = new PomProperty<String>("modelEncoding", String.class) {
        @Override
        public String get(Model model) {
            return model.getModelEncoding();
        }

        @Override
        public void set(Model model, String value) {
            model.setModelEncoding(value);
        }
    };

    /**
     * POM property for the Maven model version.
     *
     * @see Model#getModelVersion()
     */
    public static final PomProperty<String> MODEL_VERSION = new PomProperty<String>("modelVersion", String.class) {
        @Override
        public String get(Model model) {
            return model.getModelVersion();
        }

        @Override
        public void set(Model model, String value) {
            model.setModelVersion(value);
        }
    };

    /**
     * POM property for project modules.
     *
     * @see Model#getModules()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<String>> MODULES =
            new PomProperty<List<String>>("modules", (Class) List.class) {
                @Override
                public List<String> get(Model model) {
                    return model.getModules();
                }

                @Override
                public void set(Model model, List<String> value) {
                    model.setModules(value);
                }
            };

    /**
     * POM property for the project name.
     *
     * @see Model#getName()
     */
    public static final PomProperty<String> NAME = new PomProperty<String>("name", String.class) {
        @Override
        public String get(Model model) {
            return model.getName();
        }

        @Override
        public void set(Model model, String value) {
            model.setName(value);
        }
    };

    /**
     * POM property for organization metadata.
     *
     * @see Model#getOrganization()
     */
    public static final PomProperty<Organization> ORGANIZATION =
            new PomProperty<Organization>("organization", Organization.class) {
                @Override
                public Organization get(Model model) {
                    return model.getOrganization();
                }

                @Override
                public void set(Model model, Organization value) {
                    model.setOrganization(value);
                }
            };

    /**
     * POM property for the packaging type.
     *
     * @see Model#getPackaging()
     */
    public static final PomProperty<String> PACKAGING = new PomProperty<String>("packaging", String.class) {
        @Override
        public String get(Model model) {
            return model.getPackaging();
        }

        @Override
        public void set(Model model, String value) {
            model.setPackaging(value);
        }
    };

    /**
     * POM property for the parent coordinates.
     *
     * @see Model#getParent()
     */
    public static final PomProperty<Parent> PARENT = new PomProperty<Parent>("parent", Parent.class) {
        @Override
        public Parent get(Model model) {
            return model.getParent();
        }

        @Override
        public void set(Model model, Parent value) {
            model.setParent(value);
        }
    };

    /**
     * POM property for plugin management.
     *
     * @see Build#getPluginManagement()
     */
    public static final PomProperty<PluginManagement> PLUGIN_MANAGEMENT =
            new PomProperty<PluginManagement>("pluginManagement", PluginManagement.class) {
                @Override
                public PluginManagement get(Model model) {
                    if (model.getBuild() == null) {
                        return null;
                    }
                    return model.getBuild().getPluginManagement();
                }

                @Override
                public void set(Model model, PluginManagement value) {
                    if (model.getBuild() == null) {
                        model.setBuild(new Build());
                    }
                    model.getBuild().setPluginManagement(value);
                }
            };

    /**
     * POM property for plugin repositories.
     *
     * @see Model#getPluginRepositories()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<Repository>> PLUGIN_REPOSITORIES =
            new PomProperty<List<Repository>>("pluginRepositories", (Class) List.class) {
                @Override
                public List<Repository> get(Model model) {
                    return model.getPluginRepositories();
                }

                @Override
                public void set(Model model, List<Repository> value) {
                    model.setPluginRepositories(value);
                }
            };

    /**
     * POM property for the backing POM file.
     *
     * @see Model#getPomFile()
     */
    public static final PomProperty<File> POM_FILE = new PomProperty<File>("pomFile", File.class) {
        @Override
        public File get(Model model) {
            return model.getPomFile();
        }

        @Override
        public void set(Model model, File value) {
            model.setPomFile(value);
        }
    };

    /**
     * POM property for Maven prerequisites.
     *
     * @see Model#getPrerequisites()
     */
    public static final PomProperty<Prerequisites> PREREQUISITES =
            new PomProperty<Prerequisites>("prerequisites", Prerequisites.class) {
                @Override
                public Prerequisites get(Model model) {
                    return model.getPrerequisites();
                }

                @Override
                public void set(Model model, Prerequisites value) {
                    model.setPrerequisites(value);
                }
            };

    /**
     * POM property for project profiles.
     *
     * @see Model#getProfiles()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<Profile>> PROFILES =
            new PomProperty<List<Profile>>("profiles", (Class) List.class) {
                @Override
                public List<Profile> get(Model model) {
                    return model.getProfiles();
                }

                @Override
                public void set(Model model, List<Profile> value) {
                    model.setProfiles(value);
                }
            };

    /**
     * POM property for model properties.
     *
     * @see Model#getProperties()
     */
    public static final PomProperty<Properties> PROPERTIES =
            new PomProperty<Properties>("properties", Properties.class) {
                @Override
                public Properties get(Model model) {
                    return model.getProperties();
                }

                @Override
                public void set(Model model, Properties value) {
                    model.setProperties(value);
                }
            };

    /**
     * POM property for reporting configuration.
     *
     * @see Model#getReporting()
     */
    public static final PomProperty<Reporting> REPORTING = new PomProperty<Reporting>("reporting", Reporting.class) {
        @Override
        public Reporting get(Model model) {
            return model.getReporting();
        }

        @Override
        public void set(Model model, Reporting value) {
            model.setReporting(value);
        }
    };

    /**
     * POM property for remote repositories.
     *
     * @see Model#getRepositories()
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PomProperty<List<Repository>> REPOSITORIES =
            new PomProperty<List<Repository>>("repositories", (Class) List.class) {
                @Override
                public List<Repository> get(Model model) {
                    return model.getRepositories();
                }

                @Override
                public void set(Model model, List<Repository> value) {
                    model.setRepositories(value);
                }
            };

    /**
     * POM property for source control metadata.
     *
     * @see Model#getScm()
     */
    public static final PomProperty<Scm> SCM = new PomProperty<Scm>("scm", Scm.class) {
        @Override
        public Scm get(Model model) {
            return model.getScm();
        }

        @Override
        public void set(Model model, Scm value) {
            model.setScm(value);
        }
    };

    /**
     * POM property for the project URL.
     *
     * @see Model#getUrl()
     */
    public static final PomProperty<String> URL = new PomProperty<String>("url", String.class) {
        @Override
        public String get(Model model) {
            return model.getUrl();
        }

        @Override
        public void set(Model model, String value) {
            model.setUrl(value);
        }
    };

    /**
     * POM property for the project version.
     *
     * @see Model#getVersion()
     */
    public static final PomProperty<String> VERSION = new PomProperty<String>("version", String.class) {
        @Override
        public String get(Model model) {
            return model.getVersion();
        }

        @Override
        public void set(Model model, String value) {
            model.setVersion(value);
        }
    };

    /**
     * Ordered array of all exposed POM properties.
     */
    private static final PomProperty<?>[] POM_PROPERTIES_ARRAY = new PomProperty<?>[]{
            ARTIFACT_ID,
            BUILD,
            CI_MANAGEMENT,
            CONTRIBUTORS,
            DEPENDENCIES,
            DEPENDENCY_MANAGEMENT,
            DESCRIPTION,
            DEVELOPERS,
            DISTRIBUTION_MANAGEMENT,
            GROUP_ID,
            INCEPTION_YEAR,
            ISSUE_MANAGEMENT,
            LICENSES,
            MAILING_LISTS,
            MODEL_ENCODING,
            MODEL_VERSION,
            MODULES,
            NAME,
            ORGANIZATION,
            PACKAGING,
            PARENT,
            PLUGIN_MANAGEMENT,
            PLUGIN_REPOSITORIES,
            POM_FILE,
            PREREQUISITES,
            PROFILES,
            PROPERTIES,
            REPORTING,
            REPOSITORIES,
            SCM,
            URL,
            VERSION
    };

    /**
     * Immutable ordered list of all exposed POM properties.
     */
    private static final List<PomProperty<?>> POM_PROPERTIES =
            Collections.unmodifiableList(Arrays.asList(POM_PROPERTIES_ARRAY));

    /**
     * XML element name represented by this property.
     */
    private final String name;

    /**
     * Java value type represented by this property.
     */
    private final Class<V> valueType;

    /**
     * The constructor.
     *
     * @param name      - see {@link #getName()}.
     * @param valueType - see {@link #getValueType()}.
     */
    public PomProperty(String name, Class<V> valueType) {
        super();
        this.name = name;
        this.valueType = valueType;
    }

    /**
     * Returns the XML element name represented by this property.
     *
     * @return name
     */
    public String getName() {
        return this.name;
    }

    /**
     * Returns the Java value type represented by this property.
     *
     * @return {@link Class} reflecting the type of the {@link #get(Model) property value}.
     */
    public Class<V> getValueType() {
        return this.valueType;
    }

    /**
     * Checks whether this property is required in a generated POM.
     *
     * @return <code>true</code> if required for flattened POM, <code>false</code> otherwise.
     */
    public boolean isRequired() {
        return this == GROUP_ID || this == ARTIFACT_ID || this == VERSION;
    }

    /**
     * Checks whether this property maps to an XML element in the POM representation.
     *
     * @return <code>true</code> if this property represents an XML element of the POM representation,
     * <code>false</code> otherwise (if an internal property such as {@link Model#getPomFile()}).
     */
    public boolean isElement() {

        if (this == POM_FILE) {
            return false;
        }
        return this != MODEL_ENCODING;
    }

    /**
     * Generic getter for reading a {@link PomProperty} from a {@link Model}.
     *
     * @param model is the {@link Model} to read from.
     * @return the value of the property to read identified by this {@link PomProperty}.
     */
    public abstract V get(Model model);

    /**
     * Generic setter for writing a {@link PomProperty} in a {@link Model}.
     *
     * @param model is the {@link Model} to write to.
     * @param value is the value of the property to write identified by this {@link PomProperty}.
     */
    public abstract void set(Model model, V value);

    /**
     * Copies the value identified by this {@link PomProperty} from the given <code>source</code> {@link Model} to the
     * given <code>target</code> {@link Model}.
     *
     * @param source is the {@link Model} to copy from (read).
     * @param target is the {@link Model} to copy to (write).
     */
    public void copy(Model source, Model target) {

        V value = get(source);
        if (value != null) {
            set(target, value);
        }
    }

    /**
     * Returns all known POM properties in their stable processing order.
     *
     * @return an {@link Collections#unmodifiableList(List) unmodifiable} {@link List} with all {@link PomProperty
     * properties}.
     */
    public static List<PomProperty<?>> getPomProperties() {
        return POM_PROPERTIES;
    }

    /**
     * Returns the XML element name for logging and diagnostics.
     *
     * @return the XML element name.
     */
    @Override
    public String toString() {
        return getName();
    }

}
