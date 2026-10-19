package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class DisplayBrandingTest {
    @Test
    void normalizesOnlyExactSystemResetNames() {
        assertThat(DisplayBranding.resetNickname("NexGrid-F343FA44")).isEqualTo("UVEL-F343FA44");
        assertThat(DisplayBranding.resetNickname(null)).isNull();
        for (String custom : new String[] {"NexGrid-f343fa44", "NexGrid-F343FA4G", "NexGrid-F343FA44 ",
                "NexGrid-F343FA44\n", "Custom NexGrid-F343FA44", "NexGrid 3778", "UVEL-F343FA44", ""}) {
            assertThat(DisplayBranding.resetNickname(custom)).isEqualTo(custom);
        }
    }

    @Test
    void displaysLegacySystemSessionLabelsWithoutChangingCustomDeviceNames() {
        assertThat(DisplayBranding.sessionName("NexGrid H5")).isEqualTo("UVEL H5");
        assertThat(DisplayBranding.sessionName("NexGrid Phone App")).isEqualTo("UVEL Phone App");
        assertThat(DisplayBranding.sessionName("NexGrid App / H5")).isEqualTo("UVEL App / H5");
        assertThat(DisplayBranding.sessionName(null)).isEqualTo("UVEL App / H5");
        assertThat(DisplayBranding.sessionName("My NexGrid phone")).isEqualTo("My NexGrid phone");
    }
}
