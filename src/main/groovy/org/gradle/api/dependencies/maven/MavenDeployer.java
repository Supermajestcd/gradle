/*
 * Copyright 2007-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.dependencies.maven;

import org.apache.maven.artifact.ant.RemoteRepository;
import org.apache.ivy.plugins.resolver.DependencyResolver;

import java.io.File;
import java.util.List;

/**
 * <p>A resolver that can only be used for uploading artifacts to a Maven repository. If you use this resolver for getting
 * dependencies from a Maven repository, an exception is thrown. This resolver support all aspects of Maven deployment,
 * including snapshot deployment and metadata.xml manipulation.</p>
 * <p/>
 * <p>You have to specify at least one repository. Otherwise, if there is only one artifact, usually there is not more to do.
 * If there is more than one artifact you have to decide what to do about this, as a Maven pom can only deal with one artifact.
 * There are two strategies. If you want to deploy only one artifact you have to specify a filter to select this artifact. If you
 * want to deploy more than one artifact, you have to specify filters which select each artifact. Associated with each filter is
 * a separate configurable pom.</p>
 *
 * <p>You can create an instance of this type via the {@link org.gradle.api.tasks.Upload#uploadResolvers} container</p> 
 *
 * @author Hans Dockter
 */
public interface MavenDeployer extends MavenResolver {

    /**
     * Returns the repository o be used for uploading artifacts.
     *
     * @see #setRepository(org.apache.maven.artifact.ant.RemoteRepository) 
     */
    RemoteRepository getRepository();

    /**
     * Sets the repository to be used for uploading artifacts. If {@link #getRepository()} is not set, this repository is
     * also used for uploading snapshot artifacts.
     *
     * @param repository The repository to be used
     */
    void setRepository(RemoteRepository repository);

    /**
     * Returns the repository o be used for uploading snapshot artifacts.
     * 
     * @see #setSnapshotRepository(org.apache.maven.artifact.ant.RemoteRepository)
     */
    RemoteRepository getSnapshotRepository();

    /**
     * Sets the repository to be used for uploading snapshot artifacts. If this is not set, the {@link #getRepository()} is used
     * for uploading snapshot artifacts.
     *  
     * @param snapshotRepository The repository to be used
     */
    void setSnapshotRepository(RemoteRepository snapshotRepository);

    /**
     * Out of the box only uploading to the filesysten and via http is supported. If other protocolls should be used, the
     * appropriate Maven wagon jars have to be passed via this method.
     * 
     * @param jars
     */
    void addProtocolProviderJars(List<File> jars);

    /**
     * Returns whether to assign snapshots a unique version comprised of the timestamp and build number, or to use
     * the same version each time. Defaults to true.
     */
    boolean isUniqueVersion();

    /**
     * Sets whether to assign snapshots a unique version comprised of the timestamp and build number, or to use
     * the same version each time. Defaults to true.
     */
    void setUniqueVersion(boolean uniqueVersion);
}
