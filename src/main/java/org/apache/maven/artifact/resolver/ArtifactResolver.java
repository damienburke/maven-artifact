package org.apache.maven.artifact.resolver;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * I want to use it for hidding the fact that sometime artifact must be
 * downloaded. I am just asking LocalRepository for given artifact and I don't
 * care if it is alredy there or how it will get there.
 *
 * @author Michal Maczka
 * @author Jason van Zyl
 * @version $Id$
 */
public interface ArtifactResolver
{
    String ROLE = ArtifactResolver.class.getName();

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    void resolve( Artifact artifact,
                  List remoteRepositories,
                  ArtifactRepository localRepository )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                  Artifact originatingArtifact,
                                                  List remoteRepositories,
                                                  ArtifactRepository localRepository,
                                                  ArtifactMetadataSource source )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                  Artifact originatingArtifact,
                                                  List remoteRepositories,
                                                  ArtifactRepository localRepository,
                                                  ArtifactMetadataSource source,
                                                  List listeners )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                  Artifact originatingArtifact,
                                                  ArtifactRepository localRepository,
                                                  List remoteRepositories,
                                                  ArtifactMetadataSource source,
                                                  ArtifactFilter filter )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                  Artifact originatingArtifact,
                                                  Map managedVersions,
                                                  ArtifactRepository localRepository,
                                                  List remoteRepositories,
                                                  ArtifactMetadataSource source )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                  Artifact originatingArtifact,
                                                  Map managedVersions,
                                                  ArtifactRepository localRepository,
                                                  List remoteRepositories,
                                                  ArtifactMetadataSource source,
                                                  ArtifactFilter filter )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                  Artifact originatingArtifact,
                                                  Map managedVersions,
                                                  ArtifactRepository localRepository,
                                                  List remoteRepositories,
                                                  ArtifactMetadataSource source,
                                                  ArtifactFilter filter,
                                                  List listeners )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    /**
     * @since 3.0
     * @deprecated use {@link #resolve(ArtifactResolutionRequest)}
     */
    ArtifactResolutionResult resolveTransitively( Set artifacts, Artifact originatingArtifact, Map managedVersions,
                                                  ArtifactRepository localRepository, List remoteRepositories,
                                                  ArtifactMetadataSource source, ArtifactFilter filter, List listeners,
                                                  List conflictResolvers )
        throws ArtifactResolutionException, ArtifactNotFoundException;
    
    /** @deprecated use {@link #resolve(ArtifactResolutionRequest)} */
    void resolveAlways( Artifact artifact,
                        List remoteRepositories,
                        ArtifactRepository localRepository )
        throws ArtifactResolutionException, ArtifactNotFoundException;

    // New API

    /**
     * @since 3.0
     */
    ArtifactResolutionResult resolve( ArtifactResolutionRequest request );
}