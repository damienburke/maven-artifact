package org.apache.maven.artifact.repository.metadata;

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

import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Jason van Zyl
 * @plexus.component
 */
public class DefaultRepositoryMetadataManager
    extends AbstractLogEnabled
    implements RepositoryMetadataManager
{
    /** @plexus.requirement */
    private WagonManager wagonManager;

    protected DefaultRepositoryMetadataManager( WagonManager wagonManager, Logger logger )
    {
        this.wagonManager = wagonManager;
        enableLogging( logger );
    }

    public DefaultRepositoryMetadataManager()
    {
    }

    public void resolve( RepositoryMetadata metadata,
                         List remoteRepositories,
                         ArtifactRepository localRepository )
        throws RepositoryMetadataResolutionException
    {
//        MetadataTouchfile touchfile = new MetadataTouchfile( metadata, localRepository );

        for ( Iterator i = remoteRepositories.iterator(); i.hasNext(); )
        {
            ArtifactRepository repository = (ArtifactRepository) i.next();

            ArtifactRepositoryPolicy policy =
                metadata.isSnapshot() ? repository.getSnapshots() : repository.getReleases();

            if ( !policy.isEnabled() )
            {
                getLogger().debug( "Skipping disabled repository " + repository.getId() );
            }
            else if ( repository.isBlacklisted() )
            {
                getLogger().debug( "Skipping blacklisted repository " + repository.getId() );
            }
            else
            {
                File file = new File( localRepository.getBasedir(),
                    localRepository.pathOfLocalRepositoryMetadata( metadata, repository ) );

//                Date lastMod = touchfile.getLastModified( repository.getId(), getLogger() );

//                boolean checkForUpdates =
//                    ( lastMod == null ) || policy.checkOutOfDate( lastMod ) || !file.exists();

                boolean checkForUpdates =
                    policy.checkOutOfDate( new Date( file.lastModified() ) ) || !file.exists();

                if ( checkForUpdates )
                {
                    getLogger().info( metadata.getKey() + ": checking for updates from " + repository.getId() );

                    try
                    {
                        resolveAlways( metadata, repository, file, policy.getChecksumPolicy(), true );
                    }
                    catch ( TransferFailedException e )
                    {
                        // TODO: [jc; 08-Nov-2005] revisit this for 2.1
                        // suppressing logging to avoid logging this error twice.
                    }
//                    finally
//                    {
//                        touchfile.touch( repository.getId(), getLogger() );
//                    }
                }

                // TODO: should this be inside the above check?
                // touch file so that this is not checked again until interval has passed
                if ( file.exists() )
                {
                    file.setLastModified( System.currentTimeMillis() );
                }
            }
        }

        try
        {
            mergeMetadata( metadata, remoteRepositories, localRepository );
        }
        catch ( RepositoryMetadataStoreException e )
        {
            throw new RepositoryMetadataResolutionException(
                "Unable to store local copy of metadata: " + e.getMessage(), e );
        }
        catch ( RepositoryMetadataReadException e )
        {
            throw new RepositoryMetadataResolutionException( "Unable to read local copy of metadata: " + e.getMessage(),
                e );
        }
    }

    private void mergeMetadata( RepositoryMetadata metadata,
                                List remoteRepositories,
                                ArtifactRepository localRepository )
        throws RepositoryMetadataStoreException, RepositoryMetadataReadException
    {
        // TODO: currently this is first wins, but really we should take the latest by comparing either the
        // snapshot timestamp, or some other timestamp later encoded into the metadata.
        // TODO: this needs to be repeated here so the merging doesn't interfere with the written metadata
        //  - we'd be much better having a pristine input, and an ongoing metadata for merging instead

        Map previousMetadata = new HashMap();
        ArtifactRepository selected = null;
        for ( Iterator i = remoteRepositories.iterator(); i.hasNext(); )
        {
            ArtifactRepository repository = (ArtifactRepository) i.next();

            ArtifactRepositoryPolicy policy =
                metadata.isSnapshot() ? repository.getSnapshots() : repository.getReleases();

            if ( ( policy.isEnabled() && !repository.isBlacklisted() )
                && ( loadMetadata( metadata, repository, localRepository, previousMetadata ) ) )
            {
                metadata.setRepository( repository );
                selected = repository;
            }
        }
        if ( loadMetadata( metadata, localRepository, localRepository, previousMetadata ) )
        {
            metadata.setRepository( null );
            selected = localRepository;
        }

        updateSnapshotMetadata( metadata, previousMetadata, selected, localRepository );
    }

    private void updateSnapshotMetadata( RepositoryMetadata metadata,
                                         Map previousMetadata,
                                         ArtifactRepository selected,
                                         ArtifactRepository localRepository )
        throws RepositoryMetadataStoreException
    {
        // TODO: this could be a lot nicer... should really be in the snapshot transformation?
        if ( metadata.isSnapshot() )
        {
            Metadata prevMetadata = metadata.getMetadata();

            for ( Iterator i = previousMetadata.keySet().iterator(); i.hasNext(); )
            {
                ArtifactRepository repository = (ArtifactRepository) i.next();
                Metadata m = (Metadata) previousMetadata.get( repository );
                if ( repository.equals( selected ) )
                {
                    if ( m.getVersioning() == null )
                    {
                        m.setVersioning( new Versioning() );
                    }

                    if ( m.getVersioning().getSnapshot() == null )
                    {
                        m.getVersioning().setSnapshot( new Snapshot() );
                    }
                }
                else
                {
                    if ( ( m.getVersioning() != null ) && ( m.getVersioning().getSnapshot() != null ) &&
                        m.getVersioning().getSnapshot().isLocalCopy() )
                    {
                        m.getVersioning().getSnapshot().setLocalCopy( false );
                        metadata.setMetadata( m );
                        metadata.storeInLocalRepository( localRepository, repository );
                    }
                }
            }

            metadata.setMetadata( prevMetadata );
        }
    }

    private boolean loadMetadata( RepositoryMetadata repoMetadata,
                                  ArtifactRepository remoteRepository,
                                  ArtifactRepository localRepository,
                                  Map previousMetadata )
        throws RepositoryMetadataReadException
    {
        boolean setRepository = false;

        File metadataFile = new File( localRepository.getBasedir(),
            localRepository.pathOfLocalRepositoryMetadata( repoMetadata, remoteRepository ) );

        if ( metadataFile.exists() )
        {
            Metadata metadata = readMetadata( metadataFile );

            if ( repoMetadata.isSnapshot() && ( previousMetadata != null ) )
            {
                previousMetadata.put( remoteRepository, metadata );
            }

            if ( repoMetadata.getMetadata() != null )
            {
                setRepository = repoMetadata.getMetadata().merge( metadata );
            }
            else
            {
                repoMetadata.setMetadata( metadata );
                setRepository = true;
            }
        }
        return setRepository;
    }

    /** @todo share with DefaultPluginMappingManager. */
    protected static Metadata readMetadata( File mappingFile )
        throws RepositoryMetadataReadException
    {
        Metadata result;

        Reader reader = null;
        try
        {
            reader = ReaderFactory.newXmlReader( mappingFile );

            MetadataXpp3Reader mappingReader = new MetadataXpp3Reader();

            result = mappingReader.read( reader, false );
        }
        catch ( FileNotFoundException e )
        {
            throw new RepositoryMetadataReadException( "Cannot read metadata from '" + mappingFile + "'", e );
        }
        catch ( IOException e )
        {
            throw new RepositoryMetadataReadException(
                "Cannot read metadata from '" + mappingFile + "': " + e.getMessage(), e );
        }
        catch ( XmlPullParserException e )
        {
            throw new RepositoryMetadataReadException(
                "Cannot read metadata from '" + mappingFile + "': " + e.getMessage(), e );
        }
        finally
        {
            IOUtil.close( reader );
        }
        return result;
    }

    public void resolveAlways( RepositoryMetadata metadata,
                               ArtifactRepository localRepository,
                               ArtifactRepository remoteRepository )
        throws RepositoryMetadataResolutionException
    {
        if ( !wagonManager.isOnline() )
        {
            // metadata is required for deployment, can't be offline
            throw new RepositoryMetadataResolutionException(
                "System is offline. Cannot resolve required metadata:\n" + metadata.extendedToString() );
        }

        File file = new File( localRepository.getBasedir(),
            localRepository.pathOfLocalRepositoryMetadata( metadata, remoteRepository ) );

        try
        {
            resolveAlways( metadata, remoteRepository, file, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN, false );
        }
        catch ( TransferFailedException e )
        {
            // TODO: [jc; 08-Nov-2005] revisit this for 2.1
            // suppressing logging to avoid logging this error twice.
            // We don't want to interrupt program flow here. Just allow empty metadata instead.
            // rethrowing this would change behavior.
        }

        try
        {
            if ( file.exists() )
            {
                Metadata prevMetadata = readMetadata( file );
                metadata.setMetadata( prevMetadata );
            }
        }
        catch ( RepositoryMetadataReadException e )
        {
            throw new RepositoryMetadataResolutionException( e.getMessage(), e );
        }
    }

    private void resolveAlways( ArtifactMetadata metadata,
                                ArtifactRepository repository,
                                File file,
                                String checksumPolicy,
                                boolean allowBlacklisting )
        throws RepositoryMetadataResolutionException, TransferFailedException
    {
        if ( !wagonManager.isOnline() )
        {
            if ( allowBlacklisting )
            {
                getLogger().debug(
                    "System is offline. Cannot resolve metadata:\n" + metadata.extendedToString() + "\n\n" );
                return;
            }
            else
            {
                // metadata is required for deployment, can't be offline
                throw new RepositoryMetadataResolutionException(
                    "System is offline. Cannot resolve required metadata:\n" + metadata.extendedToString() );
            }
        }

        try
        {
            wagonManager.getArtifactMetadata( metadata, repository, file, checksumPolicy );
        }
        catch ( ResourceDoesNotExistException e )
        {
            getLogger().debug( metadata + " could not be found on repository: " + repository.getId() );

            // delete the local copy so the old details aren't used.
            createMetadataStub( metadata, file );
        }
        catch ( TransferFailedException e )
        {
            getLogger().warn(
                metadata + " could not be retrieved from repository: " + repository.getId() + " due to an error: " + e.getMessage() );
            getLogger().info( "Repository '" + repository.getId() + "' will be blacklisted" );
            getLogger().debug( "Exception", e );
            repository.setBlacklisted( allowBlacklisting );

            throw e;
        }
    }

    private void createMetadataStub( ArtifactMetadata metadata, File file )
    {
        Metadata md = new Metadata();

        md.setArtifactId( metadata.getArtifactId() );
        md.setGroupId( metadata.getGroupId() );
        md.setVersion( metadata.getBaseVersion() );

        Versioning versioning = new Versioning();
        versioning.updateTimestamp();

        md.setVersioning( versioning );

        FileWriter writer = null;
        try
        {
            file.getParentFile().mkdirs();
            writer = new FileWriter( file );
            new MetadataXpp3Writer().write( writer, md );
        }
        catch ( IOException e )
        {
            getLogger().debug( "Failed to write stub metadata to: " + file, e );
        }
        finally
        {
            IOUtil.close( writer );
        }
    }

    public void deploy( ArtifactMetadata metadata,
                        ArtifactRepository localRepository,
                        ArtifactRepository deploymentRepository )
        throws RepositoryMetadataDeploymentException
    {
        if ( !wagonManager.isOnline() )
        {
            // deployment shouldn't silently fail when offline
            throw new RepositoryMetadataDeploymentException(
                "System is offline. Cannot deploy metadata:\n" + metadata.extendedToString() );
        }

        getLogger().info( "Retrieving previous metadata from " + deploymentRepository.getId() );

        File file = new File( localRepository.getBasedir(),
            localRepository.pathOfLocalRepositoryMetadata( metadata, deploymentRepository ) );

        try
        {
            resolveAlways( metadata, deploymentRepository, file, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN, false );
        }
        catch ( RepositoryMetadataResolutionException e )
        {
            throw new RepositoryMetadataDeploymentException(
                "Unable to get previous metadata to update: " + e.getMessage(), e );
        }
        catch ( TransferFailedException e )
        {
            throw new RepositoryMetadataDeploymentException(
                "Unable to get previous metadata to update: " + e.getMessage(), e );
        }

        try
        {
            metadata.storeInLocalRepository( localRepository, deploymentRepository );
        }
        catch ( RepositoryMetadataStoreException e )
        {
            throw new RepositoryMetadataDeploymentException( "Error installing metadata: " + e.getMessage(), e );
        }

        try
        {
            wagonManager.putArtifactMetadata( file, metadata, deploymentRepository );
        }
        catch ( TransferFailedException e )
        {
            throw new RepositoryMetadataDeploymentException( "Error while deploying metadata: " + e.getMessage(), e );
        }
    }

    public void install( ArtifactMetadata metadata,
                         ArtifactRepository localRepository )
        throws RepositoryMetadataInstallationException
    {
        try
        {
            metadata.storeInLocalRepository( localRepository, localRepository );
        }
        catch ( RepositoryMetadataStoreException e )
        {
            throw new RepositoryMetadataInstallationException( "Error installing metadata: " + e.getMessage(), e );
        }
    }
}
