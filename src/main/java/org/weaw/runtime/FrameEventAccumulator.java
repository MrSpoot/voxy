package org.weaw.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/** Collects fixed-rate update samples produced during one rendered frame. */
public final class FrameEventAccumulator<T> {
    private final List<T> samples = new ArrayList<>(2);

    public void reset() {
        samples.clear();
    }

    public void add(T sample) {
        samples.add(Objects.requireNonNull(sample, "sample"));
    }

    public int size() {
        return samples.size();
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    public void replaceLast(T sample) {
        if (samples.isEmpty()) {
            throw new IllegalStateException("No frame event to replace");
        }
        samples.set(samples.size() - 1, Objects.requireNonNull(sample, "sample"));
    }

    public long sumLong(ToLongFunction<T> extractor) {
        long total = 0L;
        for (T sample : samples) {
            total += extractor.applyAsLong(sample);
        }
        return total;
    }

    public int sumInt(ToIntFunction<T> extractor) {
        int total = 0;
        for (T sample : samples) {
            total += extractor.applyAsInt(sample);
        }
        return total;
    }

    public T latestOr(T fallback) {
        return samples.isEmpty() ? fallback : samples.getLast();
    }

    public T firstOr(T fallback) {
        return samples.isEmpty() ? fallback : samples.getFirst();
    }
}
