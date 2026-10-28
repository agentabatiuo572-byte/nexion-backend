package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class DisplayBrandingTest {
    @Test
    void displaysLegacySystemSessionLabelsWithoutChangingCustomDeviceNames() {
        assertThat(DisplayBranding.sessionName("NexGrid H5")).isEqualTo("UVEL H5");
        assertThat(DisplayBranding.sessionName("NexGrid Phone App")).isEqualTo("UVEL Phone App");
        assertThat(DisplayBranding.sessionName("NexGrid App / H5")).isEqualTo("UVEL App / H5");
        assertThat(DisplayBranding.sessionName(null)).isEqualTo("UVEL App / H5");
        assertThat(DisplayBranding.sessionName("My NexGrid phone")).isEqualTo("My NexGrid phone");
    }
}
