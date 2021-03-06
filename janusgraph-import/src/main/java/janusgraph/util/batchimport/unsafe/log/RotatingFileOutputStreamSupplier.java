package janusgraph.util.batchimport.unsafe.log;


import org.apache.commons.io.output.NullOutputStream;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import janusgraph.util.batchimport.unsafe.io.fs.FileSystem;

import static janusgraph.util.batchimport.unsafe.io.Files.createOrOpenAsOuputStream;


/**
 * A {@link Supplier} of {@link OutputStream}s backed by on-disk files, which
 * are rotated and archived when a specified size is reached. The {@link #get()} method
 * will always return an OutputStream to the current output file without directly performing
 * any IO or blocking, and, when necessary, will trigger rotation via the {@link Executor}
 * supplied during construction.
 */
public class RotatingFileOutputStreamSupplier implements Supplier<OutputStream>, Closeable
{
    /**
     * A listener for the rotation process
     */
    public static class RotationListener
    {
        public void outputFileCreated( OutputStream out )
        {
        }

        public void rotationCompleted( OutputStream out )
        {
        }

        public void rotationError( Exception e, OutputStream out )
        {
        }
    }

    private static final LongSupplier DEFAULT_CURRENT_TIME_SUPPLIER = System::currentTimeMillis;

    // Used only in case no new output file can be created during rotation
    private static final OutputStream nullStream = NullOutputStream.NULL_OUTPUT_STREAM;

    private final LongSupplier currentTimeSupplier;
    private final FileSystem fileSystem;
    private final File outputFile;
    private final long rotationThresholdBytes;
    private final long rotationDelay;
    private final int maxArchives;
    private final RotationListener rotationListener;
    private final Executor rotationExecutor;
    private final ReadWriteLock logFileLock = new ReentrantReadWriteLock( true );
    private final OutputStream streamWrapper;
    private final AtomicBoolean closed = new AtomicBoolean( false );
    private final AtomicBoolean rotating = new AtomicBoolean( false );
    private final AtomicLong earliestRotationTimeRef = new AtomicLong( 0 );
    private OutputStream outRef = nullStream;

    /**
     * @param fileSystem The filesystem to use
     * @param outputFile The file that the latest {@link OutputStream} should output to
     * @param rotationThresholdBytes The size above which the file should be rotated
     * @param rotationDelay The minimum time (ms) after last rotation before the file may be rotated again
     * @param maxArchives The maximum number of archived output files to keep
     * @param rotationExecutor An {@link Executor} for performing the rotation
     * @param rotationListener A {@link RotatingFileOutputStreamSupplier.RotationListener} that can
     * observe the rotation process and be notified of errors
     * @throws IOException If the output file cannot be created
     */
    public RotatingFileOutputStreamSupplier(FileSystem fileSystem, File outputFile,
                                            long rotationThresholdBytes, long rotationDelay, int maxArchives, Executor rotationExecutor,
                                            RotationListener rotationListener ) throws IOException
    {
        this( DEFAULT_CURRENT_TIME_SUPPLIER, fileSystem, outputFile, rotationThresholdBytes, rotationDelay,
                maxArchives, rotationExecutor, rotationListener );
    }

    RotatingFileOutputStreamSupplier(LongSupplier currentTimeSupplier, FileSystem fileSystem,
                                     File outputFile, long rotationThresholdBytes, long rotationDelay, int maxArchives,
                                     Executor rotationExecutor, RotationListener rotationListener ) throws IOException
    {
        this.currentTimeSupplier = currentTimeSupplier;
        this.fileSystem = fileSystem;
        this.outputFile = outputFile;
        this.rotationThresholdBytes = rotationThresholdBytes;
        this.rotationDelay = rotationDelay;
        this.maxArchives = maxArchives;
        this.rotationListener = rotationListener;
        this.rotationExecutor = rotationExecutor;
        this.outRef = openOutputFile();
        // Wrap the actual reference to prevent race conditions during log rotation
        this.streamWrapper = new OutputStream()
        {
            @Override
            public void write( int i ) throws IOException
            {
                logFileLock.readLock().lock();
                try
                {
                    outRef.write( i );
                }
                finally
                {
                    logFileLock.readLock().unlock();
                }
            }

            @Override
            public void write( byte[] bytes ) throws IOException
            {
                logFileLock.readLock().lock();
                try
                {
                    outRef.write( bytes );
                }
                finally
                {
                    logFileLock.readLock().unlock();
                }
            }

            @Override
            public void write( byte[] bytes, int off, int len ) throws IOException
            {
                logFileLock.readLock().lock();
                try
                {
                    outRef.write( bytes, off, len );
                }
                finally
                {
                    logFileLock.readLock().unlock();
                }
            }

            @Override
            public void flush() throws IOException
            {
                logFileLock.readLock().lock();
                try
                {
                    outRef.flush();
                }
                finally
                {
                    logFileLock.readLock().unlock();
                }
            }
        };
    }

    /**
     * @return A stream outputting to the latest output file
     */
    @Override
    public OutputStream get()
    {
        if ( !closed.get() && !rotating.get() )
        {
            // In case output file doesn't exist, call rotate so that it gets created
            if ( rotationDelayExceeded() && rotationThresholdExceeded() ||
                    !fileSystem.fileExists( outputFile ) )
            {
                rotate();
            }
        }
        return this.streamWrapper;
    }

    @Override
    public void close() throws IOException
    {
        logFileLock.writeLock().lock();
        try
        {
            closed.set( true );
            outRef.close();
        }
        finally
        {
            outRef = nullStream;
            logFileLock.writeLock().unlock();
        }
    }

    private boolean rotationThresholdExceeded()
    {
        return fileSystem.fileExists( outputFile ) && rotationThresholdBytes > 0 &&
                fileSystem.getFileSize( outputFile ) >= rotationThresholdBytes;
    }

    private boolean rotationDelayExceeded()
    {
        return earliestRotationTimeRef.get() <= currentTimeSupplier.getAsLong();
    }

    void rotate()
    {
        if ( rotating.getAndSet( true ) )
        {
            // Already rotating
            return;
        }

        ByteArrayOutputStream bufferingOutputStream = new ByteArrayOutputStream();
        Runnable runnable = () ->
        {
            logFileLock.writeLock().lock();
            try
            {
                try
                {
                    // Must close file prior to doing any operations on it or else it won't work on Windows
                    try
                    {
                        outRef.flush();
                        outRef.close();
                        outRef = nullStream;
                    }
                    catch ( Exception e )
                    {
                        rotationListener.rotationError( e, bufferingOutputStream );
                        return;
                    }

                    try
                    {
                        if ( fileSystem.fileExists( outputFile ) )
                        {
                            shiftArchivedOutputFiles();
                            fileSystem.renameFile( outputFile, archivedOutputFile( outputFile, 1 ) );
                        }
                    }
                    catch ( Exception e )
                    {
                        rotationListener.rotationError( e, bufferingOutputStream );
                        return;
                    }
                }
                finally
                {
                    try
                    {
                        if ( !closed.get() && outRef.equals( nullStream ) )
                        {
                            outRef = openOutputFile();
                            rotationListener.outputFileCreated( bufferingOutputStream );
                        }
                    }
                    catch ( IOException e )
                    {
                        System.err.println( "Failed to open log file after log rotation: " + e.getMessage() );
                        rotationListener.rotationError( e, bufferingOutputStream );
                    }
                }

                if ( rotationDelay > 0 )
                {
                    earliestRotationTimeRef.set( currentTimeSupplier.getAsLong() + rotationDelay );
                }
                rotationListener.rotationCompleted( bufferingOutputStream );
            }
            finally
            {
                rotating.set( false );
                try
                {
                    bufferingOutputStream.writeTo( streamWrapper );
                }
                catch ( IOException e )
                {
                    rotationListener.rotationError( e, streamWrapper );
                }
                logFileLock.writeLock().unlock();
            }
        };

        try
        {
            rotationExecutor.execute( runnable );
        }
        catch ( Exception e )
        {
            rotationListener.rotationError( e, streamWrapper );
            rotating.set( false );
        }
    }

    private OutputStream openOutputFile() throws IOException
    {
        return createOrOpenAsOuputStream( fileSystem, outputFile, true );
    }

    private void shiftArchivedOutputFiles() throws IOException
    {
        for ( int i = lastArchivedOutputFileNumber( fileSystem, outputFile ); i > 0; --i )
        {
            File archive = archivedOutputFile( outputFile, i );
            if ( i >= maxArchives )
            {
                fileSystem.deleteFile( archive );
            }
            else
            {
                fileSystem.renameFile( archive, archivedOutputFile( outputFile, i + 1 ) );
            }
        }
    }

    private static int lastArchivedOutputFileNumber(FileSystem fileSystem, File outputFile )
    {
        int i = 1;
        while ( fileSystem.fileExists( archivedOutputFile( outputFile, i ) ) )
        {
            i++;
        }
        return i - 1;
    }

    private static File archivedOutputFile( File outputFile, int archiveNumber )
    {
        return new File( String.format( "%s.%d", outputFile.getPath(), archiveNumber ) );
    }

    /**
     * Exposes the algorithm for collecting existing rotated log files.
     */
    public static List<File> getAllArchives(FileSystem fileSystem, File outputFile )
    {
        ArrayList<File> ret = new ArrayList<>();
        int i = 1;
        while ( true )
        {
            File file = archivedOutputFile( outputFile, i );
            if ( !fileSystem.fileExists( file ) )
            {
                break;
            }
            ret.add( file );
            i++;
        }
        return ret;
    }
}
