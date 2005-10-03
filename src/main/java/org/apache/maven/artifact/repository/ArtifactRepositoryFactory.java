package org.apache.maven.artifact.repository;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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

import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;

/**
 * @author jdcasey
 */
public interface ArtifactRepositoryFactory
{
    String ROLE = ArtifactRepositoryFactory.class.getName();

    ArtifactRepository createArtifactRepository( String id, String url, ArtifactRepositoryLayout repositoryLayout );

    ArtifactRepository createArtifactRepository( String id, String url, ArtifactRepositoryLayout repositoryLayout,
                                                 ArtifactRepositoryPolicy snapshots,
                                                 ArtifactRepositoryPolicy releases );

    void setGlobalUpdatePolicy( String snapshotPolicy );

    void setGlobalChecksumPolicy( String checksumPolicy );

    /**
     * @deprecated Not needed, use WagonManager.set/isOnline() instead.
     */
    void setGlobalEnable( boolean enable );
}