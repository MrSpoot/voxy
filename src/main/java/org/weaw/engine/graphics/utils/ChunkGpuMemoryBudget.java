package org.weaw.engine.graphics.utils;

public final class ChunkGpuMemoryBudget {
    private final long maxResidentBytes;
    private final long maxTransientBytes;
    private long residentBytes;

    public ChunkGpuMemoryBudget(long maxResidentBytes, long maxTransientBytes) {
        if (maxResidentBytes <= 0L || maxTransientBytes < maxResidentBytes) {
            throw new IllegalArgumentException("Invalid GPU memory budget");
        }
        this.maxResidentBytes = maxResidentBytes;
        this.maxTransientBytes = maxTransientBytes;
    }

    public synchronized boolean register(long bytes) {
        if (bytes < 0L || exceeds(residentBytes, bytes, maxResidentBytes)) {
            return false;
        }
        residentBytes += bytes;
        return true;
    }

    public synchronized boolean tryResize(long oldBytes, long newBytes) {
        if (oldBytes < 0L || newBytes < oldBytes || oldBytes > residentBytes) {
            return false;
        }
        long residentWithoutOld = residentBytes - oldBytes;
        if (exceeds(residentWithoutOld, newBytes, maxResidentBytes)
                || exceeds(residentBytes, newBytes, maxTransientBytes)) {
            return false;
        }
        residentBytes = residentWithoutOld + newBytes;
        return true;
    }

    public synchronized void release(long bytes) {
        residentBytes = Math.max(0L, residentBytes - Math.max(0L, bytes));
    }

    public synchronized long getResidentBytes() {
        return residentBytes;
    }

    public long getMaxResidentBytes() {
        return maxResidentBytes;
    }

    public long getMaxTransientBytes() {
        return maxTransientBytes;
    }

    private static boolean exceeds(long current, long additional, long limit) {
        return additional > limit || current > limit - additional;
    }
}
