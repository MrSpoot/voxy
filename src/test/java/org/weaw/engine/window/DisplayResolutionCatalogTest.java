package org.weaw.engine.window;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayResolutionCatalogTest {
    @Test
    void buildsStandardSixteenByNineResolutionsUpToNativeSize() {
        List<DisplayResolution> resolutions = DisplayResolutionCatalog.standardsFor(2560, 1440);

        assertEquals(List.of(
                new DisplayResolution(960, 540),
                new DisplayResolution(1280, 720),
                new DisplayResolution(1600, 900),
                new DisplayResolution(1920, 1080),
                new DisplayResolution(2560, 1440)
        ), resolutions);
    }

    @Test
    void recognizesSixteenByTenAndUltrawideFamilies() {
        assertTrue(DisplayResolutionCatalog.standardsFor(1920, 1200)
                .contains(new DisplayResolution(1680, 1050)));
        assertTrue(DisplayResolutionCatalog.standardsFor(3440, 1440)
                .contains(new DisplayResolution(2560, 1080)));
        assertTrue(DisplayResolutionCatalog.standardsFor(5120, 1440)
                .contains(new DisplayResolution(3840, 1080)));
    }

    @Test
    void keepsTheExactNativeResolutionForUnusualRatiosWithoutDuplicates() {
        DisplayResolution nativeResolution = new DisplayResolution(1500, 1000);
        List<DisplayResolution> resolutions = DisplayResolutionCatalog.standardsFor(
                nativeResolution.width(), nativeResolution.height()
        );

        assertEquals(nativeResolution, resolutions.getLast());
        assertEquals(resolutions.size(), resolutions.stream().distinct().count());
    }

    @Test
    void selectsTheClosestResolutionForLegacySettings() {
        List<DisplayResolution> resolutions = DisplayResolutionCatalog.standardsFor(1920, 1080);

        int index = DisplayResolutionCatalog.nearestIndex(resolutions, 1500, 850);

        assertEquals(new DisplayResolution(1600, 900), resolutions.get(index));
    }
}
