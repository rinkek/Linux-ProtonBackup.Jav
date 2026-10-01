package protonbackup.ui.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import protonbackup.ui.viewmodel.TrayState;

class TrayTextsTest {

    @Test
    void everyStateHasItsOwnTooltip() {
        var tooltips = new HashSet<String>();
        for (var state : TrayState.values()) tooltips.add(TrayTexts.tooltip(state));

        assertEquals(TrayState.values().length, tooltips.size());
        assertEquals("Proton Drive backup — syncing", TrayTexts.tooltip(TrayState.BUSY));
    }

    @Test
    void everyStateHasAnIconThatCanBeRead() throws Exception {
        for (var state : TrayState.values()) {
            try (var stream = TrayTexts.class.getResourceAsStream(TrayTexts.iconResource(state))) {
                assertNotNull(stream, "missing " + TrayTexts.iconResource(state));
                var image = ImageIO.read(stream);
                assertNotNull(image);
                assertEquals(32, image.getWidth());
                assertEquals(32, image.getHeight());
            }
        }
    }

    @Test
    void theIconsDifferSoTheStateCanBeSeen() throws Exception {
        var checksums = new HashSet<Integer>();
        for (var state : TrayState.values()) {
            try (var stream = TrayTexts.class.getResourceAsStream(TrayTexts.iconResource(state))) {
                checksums.add(java.util.Arrays.hashCode(stream.readAllBytes()));
            }
        }

        assertNotEquals(1, checksums.size());
        assertEquals(TrayState.values().length, checksums.size());
    }
}
