package dev.nathan.sbaagentic.runner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Guards the runner daemon against concurrent instances using an operating-system file lock.
 * A second daemon must not run startup crash recovery while the first is preparing an in-flight
 * task, because both processes share the same actor id and the second could release the first's
 * claim before its tmux session exists.
 */
public final class RunnerInstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private RunnerInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<RunnerInstanceLock> tryAcquire(Path lockFile) throws IOException {
        Path parent = lockFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                return Optional.empty();
            }
            return Optional.of(new RunnerInstanceLock(channel, lock));
        }
        catch (OverlappingFileLockException ex) {
            closeQuietly(channel);
            return Optional.empty();
        }
        catch (IOException | RuntimeException ex) {
            try {
                channel.close();
            }
            catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            lock.release();
        }
        catch (IOException ex) {
            failure = ex;
        }

        try {
            channel.close();
        }
        catch (IOException ex) {
            if (failure == null) {
                failure = ex;
            }
            else {
                failure.addSuppressed(ex);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        }
        catch (IOException ignored) {
            // The lock was not acquired, so there is no owned lock to release.
        }
    }
}
