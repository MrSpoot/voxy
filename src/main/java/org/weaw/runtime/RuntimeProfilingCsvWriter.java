package org.weaw.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimeProfilingCsvWriter implements AutoCloseable {
    private final BufferedWriter writer;
    private final Path outputPath;

    private RuntimeProfilingCsvWriter(BufferedWriter writer, Path outputPath) {
        this.writer = writer;
        this.outputPath = outputPath;
    }

    public static RuntimeProfilingCsvWriter create(Path outputPath) throws IOException {
        Objects.requireNonNull(outputPath, "outputPath");
        Path absolutePath = outputPath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        BufferedWriter writer = Files.newBufferedWriter(absolutePath);
        writer.write(RuntimeFrameProfile.csvHeader());
        writer.newLine();
        writer.flush();
        return new RuntimeProfilingCsvWriter(writer, absolutePath);
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public void writeFrame(RuntimeFrameProfile frameProfile) throws IOException {
        writer.write(frameProfile.toCsvRow());
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to close runtime profiling CSV at " + outputPath, exception);
        }
    }
}
