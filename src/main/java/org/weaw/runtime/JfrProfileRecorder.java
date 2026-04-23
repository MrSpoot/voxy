package org.weaw.runtime;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Objects;

public final class JfrProfileRecorder implements AutoCloseable {
    private final Recording recording;
    private final Path outputPath;
    private boolean closed;

    private JfrProfileRecorder(Recording recording, Path outputPath) {
        this.recording = recording;
        this.outputPath = outputPath;
    }

    public static JfrProfileRecorder start(Path outputPath) throws IOException, ParseException {
        Objects.requireNonNull(outputPath, "outputPath");
        Path absolutePath = outputPath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName("voxy-runtime-profile");
        recording.setToDisk(true);
        recording.start();
        return new JfrProfileRecorder(recording, absolutePath);
    }

    public Path getOutputPath() {
        return outputPath;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        try {
            if (recording.getState() == RecordingState.RUNNING) {
                recording.stop();
            }
            recording.dump(outputPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write JFR recording to " + outputPath, exception);
        } finally {
            recording.close();
        }
    }
}
