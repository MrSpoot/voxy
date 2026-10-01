package org.weaw.engine.window;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds compact, familiar resolution lists for the native monitor aspect ratio. */
public final class DisplayResolutionCatalog {
    private static final double FAMILY_TOLERANCE = 0.035d;
    private static final int MIN_WIDTH = 960;
    private static final int MIN_HEIGHT = 540;
    private static final int[] FALLBACK_HEIGHTS = {540, 720, 900, 1080, 1200, 1440, 1600, 2160, 2880, 4320};

    private static final List<AspectFamily> FAMILIES = List.of(
            new AspectFamily(16.0d / 9.0d, List.of(
                    resolution(960, 540), resolution(1280, 720), resolution(1600, 900),
                    resolution(1920, 1080), resolution(2560, 1440), resolution(3840, 2160),
                    resolution(5120, 2880), resolution(7680, 4320)
            )),
            new AspectFamily(16.0d / 10.0d, List.of(
                    resolution(960, 600), resolution(1280, 800), resolution(1440, 900),
                    resolution(1680, 1050), resolution(1920, 1200), resolution(2560, 1600),
                    resolution(3840, 2400)
            )),
            new AspectFamily(21.0d / 9.0d, List.of(
                    resolution(1280, 540), resolution(1920, 810), resolution(2560, 1080),
                    resolution(3440, 1440), resolution(5120, 2160)
            )),
            new AspectFamily(32.0d / 9.0d, List.of(
                    resolution(1920, 540), resolution(2560, 720), resolution(3840, 1080),
                    resolution(5120, 1440), resolution(7680, 2160)
            )),
            new AspectFamily(4.0d / 3.0d, List.of(
                    resolution(960, 720), resolution(1024, 768), resolution(1280, 960),
                    resolution(1600, 1200), resolution(2048, 1536), resolution(3200, 2400)
            ))
    );

    private DisplayResolutionCatalog() {
    }

    public static List<DisplayResolution> standardsFor(int nativeWidth, int nativeHeight) {
        DisplayResolution nativeResolution = new DisplayResolution(nativeWidth, nativeHeight);
        double nativeRatio = nativeWidth / (double) nativeHeight;
        AspectFamily family = FAMILIES.stream()
                .min(Comparator.comparingDouble(candidate -> relativeDifference(candidate.ratio(), nativeRatio)))
                .filter(candidate -> relativeDifference(candidate.ratio(), nativeRatio) <= FAMILY_TOLERANCE)
                .orElse(null);

        Set<DisplayResolution> candidates = new LinkedHashSet<>();
        if (family == null) {
            addFallbackResolutions(candidates, nativeResolution, nativeRatio);
        } else {
            candidates.addAll(family.resolutions());
        }
        candidates.add(nativeResolution);

        return candidates.stream()
                .filter(resolution -> resolution.equals(nativeResolution)
                        || resolution.width() >= MIN_WIDTH && resolution.height() >= MIN_HEIGHT)
                .filter(resolution -> resolution.width() <= nativeWidth && resolution.height() <= nativeHeight)
                .sorted(Comparator.comparingLong(DisplayResolutionCatalog::pixelCount)
                        .thenComparingInt(DisplayResolution::width)
                        .thenComparingInt(DisplayResolution::height))
                .toList();
    }

    public static int nearestIndex(List<DisplayResolution> resolutions, int width, int height) {
        if (resolutions == null || resolutions.isEmpty()) {
            throw new IllegalArgumentException("At least one display resolution is required");
        }
        int bestIndex = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < resolutions.size(); index++) {
            DisplayResolution candidate = resolutions.get(index);
            long widthDelta = candidate.width() - (long) width;
            long heightDelta = candidate.height() - (long) height;
            long distance = widthDelta * widthDelta + heightDelta * heightDelta;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static void addFallbackResolutions(
            Set<DisplayResolution> candidates,
            DisplayResolution nativeResolution,
            double nativeRatio
    ) {
        for (int height : FALLBACK_HEIGHTS) {
            int width = roundToEven((int) Math.round(height * nativeRatio));
            if (width <= nativeResolution.width() && height <= nativeResolution.height()) {
                candidates.add(new DisplayResolution(width, height));
            }
        }
    }

    private static int roundToEven(int value) {
        return (value & 1) == 0 ? value : value + 1;
    }

    private static long pixelCount(DisplayResolution resolution) {
        return resolution.width() * (long) resolution.height();
    }

    private static double relativeDifference(double left, double right) {
        return Math.abs(left - right) / right;
    }

    private static DisplayResolution resolution(int width, int height) {
        return new DisplayResolution(width, height);
    }

    private record AspectFamily(double ratio, List<DisplayResolution> resolutions) {
    }
}
