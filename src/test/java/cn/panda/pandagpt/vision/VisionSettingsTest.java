package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisionSettingsTest {
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
