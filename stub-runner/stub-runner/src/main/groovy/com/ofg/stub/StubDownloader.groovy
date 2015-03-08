package com.ofg.stub

import com.ofg.stub.util.ZipCategory
import groovy.util.logging.Slf4j
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.*
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.version.Version

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.concurrent.ConcurrentHashMap

import static groovy.grape.Grape.addResolver
import static groovy.grape.Grape.resolve
import static groovy.io.FileType.FILES
import static java.nio.file.Files.createTempDirectory

/**
 * Downloads stubs from an external repository and unpacks them locally
 */
@Slf4j
class StubDownloader {

    private static final String LATEST_MODULE = '*'
    private static final String REPOSITORY_NAME = 'dependency-repository'
    private static final String STUB_RUNNER_TEMP_DIR_PREFIX = 'stub-runner'

    /**
     * Downloads stubs from an external repository and unpacks them locally.
     * Depending on the switch either uses only local repository to check for
     * stub presence.
     *
     * @param skipLocalRepo -flag that defines whether only local cache should be used
     * @param stubRepositoryRoot - address of the repo from which deps should be grabbed
     * @param stubsGroup - group name of the jar containing stubs
     * @param stubsModule - module name of the jar containing stubs
     * @return file where the stubs where unpacked
     */
    File downloadAndUnpackStubJar(boolean skipLocalRepo, String stubRepositoryRoot, String stubsGroup, String
            stubsModule) {
        URI stubJarUri = findGrabbedStubJars(skipLocalRepo, stubRepositoryRoot, stubsGroup, stubsModule)
        if (!stubJarUri) {
            log.warn("Failed to download stubs for group [$stubsGroup] and module [$stubsModule] from repository [$stubRepositoryRoot]")
            return null
        }
        File unzippedStubsDir = unpackStubJarToATemporaryFolder(stubJarUri)
        return unzippedStubsDir
    }

    private File unpackStubJarToATemporaryFolder(URI stubJarUri) {
        File tmpDirWhereStubsWillBeUnzipped = createTempDirectory(STUB_RUNNER_TEMP_DIR_PREFIX).toFile()
        tmpDirWhereStubsWillBeUnzipped.deleteOnExit()
        log.debug("Unpacking stub from JAR [URI: ${stubJarUri}]")
        use(ZipCategory) {
            new File(stubJarUri).unzipTo(tmpDirWhereStubsWillBeUnzipped)
        }
        return tmpDirWhereStubsWillBeUnzipped
    }

    private URI findGrabbedStubJars(boolean skipLocalRepo, String stubRepositoryRoot, String stubsGroup, String stubsModule) {
        Map depToGrab = [group: stubsGroup, module: stubsModule, version: LATEST_MODULE, transitive: false]


        RepositorySystem system = Booter.newRepositorySystem();

        RepositorySystemSession session = Booter.newRepositorySystemSession( system );

        Artifact artifact = new DefaultArtifact( "$stubsGroup:$stubsModule:[,)" );

        VersionRangeRequest rangeRequest = new VersionRangeRequest();
        rangeRequest.setArtifact( artifact );
        rangeRequest.setRepositories( Booter.newRepositories( system, session, stubRepositoryRoot ) );

        VersionRangeResult rangeResult = system.resolveVersionRange( session, rangeRequest );

        Version newestVersion = rangeResult.getHighestVersion();

        System.out.println( "Newest version " + newestVersion + " from repository "
                + rangeResult.getRepository( newestVersion ) );

        return buildResolver(skipLocalRepo).resolveDependency(stubRepositoryRoot, depToGrab)
    }

    private DependencyResolver buildResolver(boolean skipLocalRepo) {
        return skipLocalRepo ? new RemoteDependencyResolver() : new LocalFirstDependencyResolver()
    }

    /**
     * Dependency resolver providing {@link URI} to remote dependencies.
     */
    @Slf4j
    private class RemoteDependencyResolver extends DependencyResolver {

        URI resolveDependency(String stubRepositoryRoot, Map depToGrab) {
            try {
                return doResolveRemoteDependency(stubRepositoryRoot, depToGrab)
            } catch (UnknownHostException e) {
                failureHandler(stubRepositoryRoot, "unknown host error -> ${e.message}", e)
            } catch (Exception e) {
                failureHandler(stubRepositoryRoot, "connection error -> ${e.message}", e)
            }
        }

        private URI doResolveRemoteDependency(String stubRepositoryRoot, Map depToGrab) {
            addResolver(name: REPOSITORY_NAME, root: stubRepositoryRoot)
            log.info("Resolving dependency ${depToGrab} location in remote repository...")
            URI resolvedUri = resolveDependencyLocation(depToGrab)
            ensureThatLatestVersionWillBePicked(resolvedUri)
            return resolveDependencyLocation(depToGrab)
        }

        private void failureHandler(String stubRepository, String reason, Exception cause) {
            log.error("Unable to resolve dependency in stub repository [$stubRepository]. Reason: [$reason]", cause)
        }

        private void ensureThatLatestVersionWillBePicked(URI resolvedUri) {
            getStubRepositoryGrapeRoot(resolvedUri).eachFileRecurse(FILES, {
                if (it.name.endsWith('.xml')) {
                    log.info("removing ${it}"); it.delete()
                }
            })
        }

        private File getStubRepositoryGrapeRoot(URI resolvedUri) {
            return new File(resolvedUri).parentFile.parentFile
        }

    }

    /**
     * Dependency resolver that first checks if a dependency is available in the local repository.
     * If not, it will try to provide {@link URI} from the remote repository.
     *
     * @see RemoteDependencyResolver
     */
    @Slf4j
    private class LocalFirstDependencyResolver extends DependencyResolver {

        private DependencyResolver delegate = new RemoteDependencyResolver()

        URI resolveDependency(String stubRepositoryRoot, Map depToGrab) {
            try {
                log.info("Resolving dependency ${depToGrab} location in local repository...")
                return resolveDependencyLocation(depToGrab)
            } catch (Exception e) { //Grape throws ordinary RuntimeException
                log.warn("Unable to find dependency $depToGrab in local repository, trying $stubRepositoryRoot")
                log.debug("Unable to find dependency $depToGrab in local repository: ${e.getClass()}: ${e.message}")
                return delegate.resolveDependency(stubRepositoryRoot, depToGrab)
            }
        }
    }

    /**
     * Base class of dependency resolvers providing {@link URI} to required dependency.
     */
    abstract class DependencyResolver {

        /**
         * Returns {@link URI} to a dependency.
         *
         * @param stubRepositoryRoot root of the repository where the dependency should be found
         * @param depToGrab parameters describing dependency to search for
         *
         * @return {@link URI} to dependency
         */
        abstract URI resolveDependency(String stubRepositoryRoot, Map depToGrab)

        URI resolveDependencyLocation(Map depToGrab) {
            return resolve([classLoader: new GroovyClassLoader()], depToGrab).first()
        }

    }

    class DependencyResolutionException extends RuntimeException {

        DependencyResolutionException(String message, Throwable cause) {
            super(message, cause)
        }
    }

    /**
     * A factory for repository system instances that employs Aether's built-in service locator infrastructure to wire up
     * the system's components.
     */
    static class ManualRepositorySystemFactory
    {

        public static RepositorySystem newRepositorySystem()
        {
            /*
             * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and using the
             * prepopulated DefaultServiceLocator, we only need to register the repository connector and transporter
             * factories.
             */
            DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
            locator.addService( RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class );
            locator.addService( TransporterFactory.class, FileTransporterFactory.class );
            locator.addService( TransporterFactory.class, HttpTransporterFactory.class );

            locator.setErrorHandler( new DefaultServiceLocator.ErrorHandler() {
                @Override
                public void serviceCreationFailed( Class<?> type, Class<?> impl, Throwable exception )
                {
                    exception.printStackTrace();
                }
            } );

            return locator.getService( RepositorySystem.class );
        }

    }

    /**
     * A helper to boot the repository system and a repository system session.
     */
    static class Booter
    {

        public static RepositorySystem newRepositorySystem()
        {
            return ManualRepositorySystemFactory.newRepositorySystem();
            // return org.eclipse.aether.examples.guice.GuiceRepositorySystemFactory.newRepositorySystem();
            // return org.eclipse.aether.examples.sisu.SisuRepositorySystemFactory.newRepositorySystem();
            // return org.eclipse.aether.examples.plexus.PlexusRepositorySystemFactory.newRepositorySystem();
        }

        public static DefaultRepositorySystemSession newRepositorySystemSession( RepositorySystem system )
        {
            DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

            LocalRepository localRepo = new LocalRepository( System.getProperty('MAVEN_HOME', "${System.getProperty('user.home')}/.m2") );
            session.setLocalRepositoryManager( system.newLocalRepositoryManager( session, localRepo ) );

            session.setTransferListener( new ConsoleTransferListener() );
            session.setRepositoryListener( new ConsoleRepositoryListener() );

            // uncomment to generate dirty trees
            // session.setDependencyGraphTransformer( null );

            return session;
        }

        public static List<RemoteRepository> newRepositories( RepositorySystem system, RepositorySystemSession session, String stubRepositoryRoot)
        {
            return new ArrayList<RemoteRepository>( Arrays.asList( newCentralRepository(stubRepositoryRoot) ) );
        }

        private static RemoteRepository newCentralRepository(String stubRepositoryRoot)
        {
            return new RemoteRepository.Builder( "nexus", "default", stubRepositoryRoot ).build();
        }

    }

    static class ConsoleTransferListener
            extends AbstractTransferListener
    {

        private PrintStream out;

        private Map<TransferResource, Long> downloads = new ConcurrentHashMap<TransferResource, Long>();

        private int lastLength;

        public ConsoleTransferListener()
        {
            this( null );
        }

        public ConsoleTransferListener( PrintStream out )
        {
            this.out = ( out != null ) ? out : System.out;
        }

        @Override
        public void transferInitiated( TransferEvent event )
        {
            String message = event.getRequestType() == TransferEvent.RequestType.PUT ? "Uploading" : "Downloading";

            out.println( message + ": " + event.getResource().getRepositoryUrl() + event.getResource().getResourceName() );
        }

        @Override
        public void transferProgressed( TransferEvent event )
        {
            TransferResource resource = event.getResource();
            downloads.put( resource, Long.valueOf( event.getTransferredBytes() ) );

            StringBuilder buffer = new StringBuilder( 64 );

            for ( Map.Entry<TransferResource, Long> entry : downloads.entrySet() )
            {
                long total = entry.getKey().getContentLength();
                long complete = entry.getValue().longValue();

                buffer.append( getStatus( complete, total ) ).append( "  " );
            }

            int pad = lastLength - buffer.length();
            lastLength = buffer.length();
            pad( buffer, pad );
            buffer.append( '\r' );

            out.print( buffer );
        }

        private String getStatus( long complete, long total )
        {
            if ( total >= 1024 )
            {
                return toKB( complete ) + "/" + toKB( total ) + " KB ";
            }
            else if ( total >= 0 )
            {
                return complete + "/" + total + " B ";
            }
            else if ( complete >= 1024 )
            {
                return toKB( complete ) + " KB ";
            }
            else
            {
                return complete + " B ";
            }
        }

        private void pad( StringBuilder buffer, int spaces )
        {
            String block = "                                        ";
            while ( spaces > 0 )
            {
                int n = Math.min( spaces, block.length() );
                buffer.append( block, 0, n );
                spaces -= n;
            }
        }

        @Override
        public void transferSucceeded( TransferEvent event )
        {
            transferCompleted( event );

            TransferResource resource = event.getResource();
            long contentLength = event.getTransferredBytes();
            if ( contentLength >= 0 )
            {
                String type = ( event.getRequestType() == TransferEvent.RequestType.PUT ? "Uploaded" : "Downloaded" );
                String len = contentLength >= 1024 ? toKB( contentLength ) + " KB" : contentLength + " B";

                String throughput = "";
                long duration = System.currentTimeMillis() - resource.getTransferStartTime();
                if ( duration > 0 )
                {
                    long bytes = contentLength - resource.getResumeOffset();
                    DecimalFormat format = new DecimalFormat( "0.0", new DecimalFormatSymbols( Locale.ENGLISH ) );
                    double kbPerSec = ( bytes / 1024.0 ) / ( duration / 1000.0 );
                    throughput = " at " + format.format( kbPerSec ) + " KB/sec";
                }

                out.println( type + ": " + resource.getRepositoryUrl() + resource.getResourceName() + " (" + len
                        + throughput + ")" );
            }
        }

        @Override
        public void transferFailed( TransferEvent event )
        {
            transferCompleted( event );

            if ( !( event.getException() instanceof MetadataNotFoundException ) )
            {
                event.getException().printStackTrace( out );
            }
        }

        private void transferCompleted( TransferEvent event )
        {
            downloads.remove( event.getResource() );

            StringBuilder buffer = new StringBuilder( 64 );
            pad( buffer, lastLength );
            buffer.append( '\r' );
            out.print( buffer );
        }

        public void transferCorrupted( TransferEvent event )
        {
            event.getException().printStackTrace( out );
        }

        protected long toKB( long bytes )
        {
            return ( bytes + 1023 ) / 1024;
        }

    }

    /**
     * A simplistic repository listener that logs events to the console.
     */
    static class ConsoleRepositoryListener
            extends AbstractRepositoryListener
    {

        private PrintStream out;

        public ConsoleRepositoryListener()
        {
            this( null );
        }

        public ConsoleRepositoryListener( PrintStream out )
        {
            this.out = ( out != null ) ? out : System.out;
        }

        public void artifactDeployed( RepositoryEvent event )
        {
            out.println( "Deployed " + event.getArtifact() + " to " + event.getRepository() );
        }

        public void artifactDeploying( RepositoryEvent event )
        {
            out.println( "Deploying " + event.getArtifact() + " to " + event.getRepository() );
        }

        public void artifactDescriptorInvalid( RepositoryEvent event )
        {
            out.println( "Invalid artifact descriptor for " + event.getArtifact() + ": "
                    + event.getException().getMessage() );
        }

        public void artifactDescriptorMissing( RepositoryEvent event )
        {
            out.println( "Missing artifact descriptor for " + event.getArtifact() );
        }

        public void artifactInstalled( RepositoryEvent event )
        {
            out.println( "Installed " + event.getArtifact() + " to " + event.getFile() );
        }

        public void artifactInstalling( RepositoryEvent event )
        {
            out.println( "Installing " + event.getArtifact() + " to " + event.getFile() );
        }

        public void artifactResolved( RepositoryEvent event )
        {
            out.println( "Resolved artifact " + event.getArtifact() + " from " + event.getRepository() );
        }

        public void artifactDownloading( RepositoryEvent event )
        {
            out.println( "Downloading artifact " + event.getArtifact() + " from " + event.getRepository() );
        }

        public void artifactDownloaded( RepositoryEvent event )
        {
            out.println( "Downloaded artifact " + event.getArtifact() + " from " + event.getRepository() );
        }

        public void artifactResolving( RepositoryEvent event )
        {
            out.println( "Resolving artifact " + event.getArtifact() );
        }

        public void metadataDeployed( RepositoryEvent event )
        {
            out.println( "Deployed " + event.getMetadata() + " to " + event.getRepository() );
        }

        public void metadataDeploying( RepositoryEvent event )
        {
            out.println( "Deploying " + event.getMetadata() + " to " + event.getRepository() );
        }

        public void metadataInstalled( RepositoryEvent event )
        {
            out.println( "Installed " + event.getMetadata() + " to " + event.getFile() );
        }

        public void metadataInstalling( RepositoryEvent event )
        {
            out.println( "Installing " + event.getMetadata() + " to " + event.getFile() );
        }

        public void metadataInvalid( RepositoryEvent event )
        {
            out.println( "Invalid metadata " + event.getMetadata() );
        }

        public void metadataResolved( RepositoryEvent event )
        {
            out.println( "Resolved metadata " + event.getMetadata() + " from " + event.getRepository() );
        }

        public void metadataResolving( RepositoryEvent event )
        {
            out.println( "Resolving metadata " + event.getMetadata() + " from " + event.getRepository() );
        }

    }
}
