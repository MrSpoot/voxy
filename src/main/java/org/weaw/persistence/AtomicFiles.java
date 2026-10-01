package org.weaw.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class AtomicFiles {
    private AtomicFiles() {
    }

    static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            writeAndForce(temporary, bytes);
            if (Files.exists(target)) {
                Path backup = target.resolveSibling(target.getFileName() + ".bak");
                Path backupTemporary = backup.resolveSibling(backup.getFileName() + ".tmp-" + UUID.randomUUID());
                try {
                    Files.copy(target, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                    force(backupTemporary);
                    moveReplacing(backupTemporary, backup);
                } finally {
                    Files.deleteIfExists(backupTemporary);
                }
            }
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void writeImmutable(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            writeAndForce(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A concurrent writer published the identical content-addressed object.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written < 0) {
                    throw new IOException("Unexpected end of file while writing " + path);
                }
            }
            channel.force(true);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
