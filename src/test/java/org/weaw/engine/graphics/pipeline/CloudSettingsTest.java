package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudSettingsTest {

    @Test
    void usesVoxelCloudDefaults() {
        CloudSettings settings = new CloudSettings();

        assertTrue(settings.isEnabled());
        assertEquals(CloudSettings.DEFAULT_ALTITUDE, settings.getAltitude(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_SPEED, settings.getSpeed(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_DENSITY, settings.getDensity(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_CELL_SIZE, settings.getCellSize(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_CLOUD_SIZE, settings.getCloudSize(), 0.0001f);
    }

    @Test
    void resetRestoresEveryCloudParameter() {
        CloudSettings settings = new CloudSettings();
        settings.setEnabled(false);
        settings.altitudeRef()[0] = 350.0f;
        settings.speedRef()[0] = 12.0f;
        settings.densityRef()[0] = 0.9f;
        settings.cellSizeRef()[0] = 5.0f;
        settings.cloudSizeRef()[0] = 3.0f;

        settings.reset();

        assertTrue(settings.isEnabled());
        assertEquals(CloudSettings.DEFAULT_ALTITUDE, settings.getAltitude(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_SPEED, settings.getSpeed(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_DENSITY, settings.getDensity(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_CELL_SIZE, settings.getCellSize(), 0.0001f);
        assertEquals(CloudSettings.DEFAULT_CLOUD_SIZE, settings.getCloudSize(), 0.0001f);
    }
}
