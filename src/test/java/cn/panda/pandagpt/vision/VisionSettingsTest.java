package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisionSettingsTest {
    @org.junit.jupiter.api.Test void validatesCellBudget() {
        var config = cn.panda.pandagpt.TestSupport.config();
        config.set("panda.vision.cells_per_tick", 64); assertEquals(64, VisionSettings.load(config).cellsPerTick());
        for (Object invalid : java.util.List.of(0, 63, 32769, 64.5, "8192")) {
            config.set("panda.vision.cells_per_tick", invalid); assertThrows(IllegalArgumentException.class, () -> VisionSettings.load(config));
        }
    }
    @Test void samplingAndImageSizeAreBounded() {
        var settings = VisionSettings.load(TestSupport.config());
        assertEquals(160, settings.width()); assertEquals(90, settings.height());
        assertEquals(2_000_000, settings.nanosPerTick());
        for (String key : new String[]{"width", "height", "max_distance", "max_concurrent_captures", "rays_per_tick"}) {
            var config = TestSupport.config(); config.set("panda.vision." + key, 1000000);
            assertThrows(IllegalArgumentException.class, () -> VisionSettings.load(config));
        }
        var config = TestSupport.config(); config.set("panda.vision.vertical_fov", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> VisionSettings.load(config));
    }
}
