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
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.apache.maven.artifact.repository.metadata.SnapshotArtifactRepositoryMetadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.transform.ArtifactTransformationManager;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Jason van Zyl
 * @plexus.component
 */
public class DefaultArtifactResolver
    extends AbstractLogEnabled
    implements ArtifactResolver
{
    // ----------------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------------

    /** @plexus.requirement */
    private WagonManager wagonManager;

    /** @plexus.requirement */
    private ArtifactTransformationManager transformationManager;

    /** @plexus.requirement */
    protected ArtifactFactory artifactFactory;

    /** @plexus.requirement */
    private ArtifactCollector artifactCollector;

    // ----------------------------------------------------------------------
    // Implementation
    // ----------------------------------------------------------------------

    public void resolve( Artifact artifact,
                         List remoteRepositories,
                         ArtifactRepository localRepository )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        resolve(
            artifact,
            remoteRepositories,
            localRepository,
            false );
    }

    public void resolveAlways( Artifact artifact,
                               List remoteRepositories,
                               ArtifactRepository localRepository )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        resolve(
            artifact,
            remoteRepositories,
            localRepository,
            true );
    }

    private void resolve( Artifact artifact,
                          List remoteRepositories,
                          ArtifactRepository localRepository,
                          boolean force )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        if ( artifact == null )
        {
            return;
        }

        if ( Artifact.SCOPE_SYSTEM.equals( artifact.getScope() ) )
        {
            File systemFile = artifact.getFile();

            if ( systemFile == null )
            {
                throw new ArtifactNotFoundException(
                    "System artifact: " + artifact + " has no file attached", artifact );
            }

            if ( !systemFile.isFile() )
            {
                throw new ArtifactNotFoundException( "System artifact: " + artifact
                    + " is not a file: " + systemFile, artifact );
            }

            if ( !systemFile.exists() )
            {
                throw new ArtifactNotFoundException(
                    "System artifact: " + artifact + " not found in path: " + systemFile,
                    artifact );
            }

            artifact.setResolved( true );
        }
        else if ( !artifact.isResolved() )
        {
            // ----------------------------------------------------------------------
            // Check for the existence of the artifact in the specified local
            // ArtifactRepository. If it is present then simply return as the
            // request for resolution has been satisfied.
            // ----------------------------------------------------------------------

            String localPath = localRepository.pathOf( artifact );

            artifact.setFile(
                new File(
                    localRepository.getBasedir(),
                    localPath ) );

            transformationManager.transformForResolve(
                artifact,
                remoteRepositories,
                localRepository );

            boolean localCopy = false;

            for ( Iterator i = artifact.getMetadataList().iterator(); i.hasNext(); )
            {
                ArtifactMetadata m = (ArtifactMetadata) i.next();

                if ( m instanceof SnapshotArtifactRepositoryMetadata )
                {
                    SnapshotArtifactRepositoryMetadata snapshotMetadata = (SnapshotArtifactRepositoryMetadata) m;

                    Metadata metadata = snapshotMetadata.getMetadata();

                    if ( metadata != null )
                    {
                        Versioning versioning = metadata.getVersioning();

                        if ( versioning != null )
                        {
                            Snapshot snapshot = versioning.getSnapshot();

                            if ( snapshot != null )
                            {
                                localCopy = snapshot.isLocalCopy();
                            }
                        }
                    }
                }
            }

            File destination = artifact.getFile();

            List repositories = remoteRepositories;

            // TODO: would prefer the snapshot transformation took care of this. Maybe we need a "shouldresolve" flag.
            if ( artifact.isSnapshot() && artifact.getBaseVersion().equals( artifact.getVersion() ) &&
                destination.exists() && !localCopy )
            {
                Date comparisonDate = new Date( destination.lastModified() );

                // cull to list of repositories that would like an update
                repositories = new ArrayList( remoteRepositories );
                for ( Iterator i = repositories.iterator(); i.hasNext(); )
                {
                    ArtifactRepository repository = (ArtifactRepository) i.next();

                    ArtifactRepositoryPolicy policy = repository.getSnapshots();

                    if ( !policy.isEnabled() || !policy.checkOutOfDate( comparisonDate ) )
                    {
                        i.remove();
                    }
                }

                if ( !repositories.isEmpty() )
                {
                    // someone wants to check for updates
                    force = true;
                }
            }
            boolean resolved = false;
            if ( !destination.exists() || force )
            {
                if ( !wagonManager.isOnline() )
                {
                    throw new ArtifactNotFoundException(
                        "System is offline.",
                        artifact );
                }

                try
                {
                    // TODO: force should be passed to the wagon manager
                    if ( artifact.getRepository() != null )
                    {
                        // the transformations discovered the artifact - so use it exclusively
                        wagonManager.getArtifact(
                            artifact,
                            artifact.getRepository() );
                    }
                    else
                    {
                        wagonManager.getArtifact(
                            artifact,
                            repositories );
                    }

                    if ( !artifact.isResolved() && !destination.exists() )
                    {
                        throw new ArtifactResolutionException(
                            "Failed to resolve artifact, possibly due to a repository list that is not appropriately equipped for this artifact's metadata.",
                            artifact,
                            remoteRepositories );
                    }
                }
                catch ( ResourceDoesNotExistException e )
                {
                    throw new ArtifactNotFoundException(
                        e.getMessage(),
                        artifact,
                        remoteRepositories,
                        e );
                }
                catch ( TransferFailedException e )
                {
                    throw new ArtifactResolutionException(
                        e.getMessage(),
                        artifact,
                        remoteRepositories,
                        e );
                }

                resolved = true;
            }
            else if ( destination.exists() )
            {
                // locally resolved...no need to hit the remote repo.
                artifact.setResolved( true );
            }

            if ( artifact.isSnapshot() && !artifact.getBaseVersion().equals( artifact.getVersion() ) )
            {
                String version = artifact.getVersion();

                artifact.selectVersion( artifact.getBaseVersion() );

                File copy = new File(
                    localRepository.getBasedir(),
                    localRepository.pathOf( artifact ) );

                if ( resolved || !copy.exists() )
                {
                    // recopy file if it was reresolved, or doesn't exist.
                    try
                    {
                        FileUtils.copyFile(
                            destination,
                            copy );
                    }
                    catch ( IOException e )
                    {
                        throw new ArtifactResolutionException(
                            "Unable to copy resolved artifact for local use: " + e.getMessage(),
                            artifact,
                            remoteRepositories,
                            e );
                    }
                }

                artifact.setFile( copy );

                artifact.selectVersion( version );
            }
        }
    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         ArtifactRepository localRepository,
                                                         List remoteRepositories,
                                                         ArtifactMetadataSource source,
                                                         ArtifactFilter filter )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        return resolveTransitively(
            artifacts,
            originatingArtifact,
            Collections.EMPTY_MAP,
            localRepository,
            remoteRepositories,
            source,
            filter );

    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         Map managedVersions,
                                                         ArtifactRepository localRepository,
                                                         List remoteRepositories,
                                                         ArtifactMetadataSource source )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        return resolveTransitively(
            artifacts,
            originatingArtifact,
            managedVersions,
            localRepository,
            remoteRepositories,
            source,
            null );
    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         Map managedVersions,
                                                         ArtifactRepository localRepository,
                                                         List remoteRepositories,
                                                         ArtifactMetadataSource source,
                                                         ArtifactFilter filter )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        return resolveTransitively(
            artifacts,
            originatingArtifact,
            managedVersions,
            localRepository,
            remoteRepositories,
            source,
            filter,
            null );
    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         List remoteRepositories,
                                                         ArtifactRepository localRepository,
                                                         ArtifactMetadataSource source )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        return resolveTransitively(
            artifacts,
            originatingArtifact,
            localRepository,
            remoteRepositories,
            source,
            null );
    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         List remoteRepositories,
                                                         ArtifactRepository localRepository,
                                                         ArtifactMetadataSource source,
                                                         List listeners )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        return resolveTransitively(
            artifacts,
            originatingArtifact,

            Collections.EMPTY_MAP,
            localRepository,
            remoteRepositories,
            source,
            null,
            listeners );
    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         Map managedVersions,
                                                         ArtifactRepository localRepository,
                                                         List remoteRepositories,
                                                         ArtifactMetadataSource source,
                                                         ArtifactFilter filter,
                                                         List listeners )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        return resolveTransitively( artifacts, originatingArtifact, managedVersions, localRepository,
            remoteRepositories, source, filter, listeners, null );
    }

    public ArtifactResolutionResult resolveTransitively( Set artifacts,
                                                         Artifact originatingArtifact,
                                                         Map managedVersions,
                                                         ArtifactRepository localRepository,
                                                         List remoteRepositories,
                                                         ArtifactMetadataSource source,
                                                         ArtifactFilter filter,
                                                         List listeners,
                                                         List conflictResolvers )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        if ( listeners == null )
        {
            // TODO: this is simplistic
            listeners = new ArrayList();
            if ( getLogger().isDebugEnabled() )
            {
                listeners.add( new DebugResolutionListener( getLogger() ) );
            }

            listeners.add( new WarningResolutionListener( getLogger() ) );
        }

        ArtifactResolutionResult result;

        result = artifactCollector.collect(
            artifacts,
            originatingArtifact,
            managedVersions,
            localRepository,
            remoteRepositories,
            source,
            filter,
            listeners,
            conflictResolvers );

        // We have collected all the problems so let's mimic the way the old code worked and just blow up right here.
        // That's right lets just let it rip right here and send a big incomprehensible blob of text at unsuspecting
        // users. Bad dog!

        // Metadata cannot be found

        if ( result.hasMetadataResolutionExceptions() )
        {
            throw result.getMetadataResolutionException( 0 );
        }

        // Metadata cannot be retrieved

        // Cyclic Dependency Error

        if ( result.hasCircularDependencyExceptions() )
        {
            throw result.getCircularDependencyException( 0 );
        }

        // Version Range Violation

        if ( result.hasVersionRangeViolations() )
        {
            throw result.getVersionRangeViolation( 0 );
        }

        List resolvedArtifacts = new ArrayList();

        List missingArtifacts = new ArrayList();

        for ( Iterator i = result.getArtifactResolutionNodes().iterator(); i.hasNext(); )
        {
            ResolutionNode node = (ResolutionNode) i.next();

            try
            {
                resolve(
                    node.getArtifact(),
                    node.getRemoteRepositories(),
                    localRepository );

                resolvedArtifacts.add( node.getArtifact() );
            }
            catch ( ArtifactNotFoundException anfe )
            {
                getLogger().debug(
                    anfe.getMessage(),
                    anfe );

                missingArtifacts.add( node.getArtifact() );
            }
        }

        if ( missingArtifacts.size() > 0 )
        {
            throw new MultipleArtifactsNotFoundException(
                originatingArtifact,
                resolvedArtifacts,
                missingArtifacts,
                remoteRepositories );
        }

        return result;
    }

    // ------------------------------------------------------------------------
    //
    // ------------------------------------------------------------------------

    public ArtifactResolutionResult resolve( ArtifactResolutionRequest request )
    {
        Artifact originatingArtifact = request.getArtifact();

        Set artifacts = request.getArtifactDependencies();

        Map managedVersions = request.getManagedVersionMap();

        ArtifactRepository localRepository = request.getLocalRepository();

        List remoteRepositories = request.getRemoteRepostories();

        ArtifactMetadataSource source = request.getMetadataSource();

        List listeners = request.getListeners();

        ArtifactFilter filter = request.getFilter();

        // This is an attempt to get the metadata for the artifacts we are ultimately trying to resolve.
        // We still

        ArtifactResolutionResult result = artifactCollector.collect(
            artifacts,
            originatingArtifact,
            managedVersions,
            localRepository,
            remoteRepositories,
            source,
            filter,
            listeners );

        // Let's grab all the repositories that were gleaned. This we should know up front. I'm not sure
        // what the metadata source is doing. Repositories in POMs are deadly.
        result.setRepositories( remoteRepositories );

        // We have metadata retrieval problems, or there are cycles that have been detected
        // so we give this back to the calling code and let them deal with this information
        // appropriately.

        if ( result.hasMetadataResolutionExceptions() || result.hasVersionRangeViolations() )
        {
            return result;
        }

        for ( Iterator i = result.getArtifactResolutionNodes().iterator(); i.hasNext(); )
        {
            ResolutionNode node = (ResolutionNode) i.next();

            try
            {
                resolve(
                    node.getArtifact(),
                    node.getRemoteRepositories(),
                    localRepository );
            }
            catch ( ArtifactNotFoundException anfe )
            {
                // These are cases where the artifact just isn't present in any of the remote repositories
                // because it wasn't deployed, or it was deployed in the wrong place.

                result.addMissingArtifact( node.getArtifact() );
            }
            catch ( ArtifactResolutionException e )
            {
                // This is really a wagon TransferFailedException so something went wrong after we successfully
                // retrieved the metadata.

                result.addErrorArtifactException( e );
            }
        }

        return result;
    }
}
