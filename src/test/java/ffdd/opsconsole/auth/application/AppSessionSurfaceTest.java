package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppSessionSurfaceTest {
    @Test
    void cookieModeTakesPrecedenceOverNativeDisplayClaim() {
        assertThat(AppSessionSurface.from(true, "APP")).isEqualTo(AppSessionSurface.H5);
        assertThat(AppSessionSurface.H5.deviceName()).isEqualTo("UVEL H5");
    }

    @Test
    void onlyExplicitNativeMarkerGetsPhoneLabel() {
        assertThat(AppSessionSurface.from(false, "APP")).isEqualTo(AppSessionSurface.APP);
        assertThat(AppSessionSurface.APP.deviceName()).isEqualTo("UVEL Phone App");
        for (String unrecognized : new String[] { "", "H5", "APP_OTHER", "app", " APP" }) {
            assertThat(AppSessionSurface.from(false, unrecognized)).isEqualTo(AppSessionSurface.UNKNOWN);
        }
        assertThat(AppSessionSurface.from(false, null)).isEqualTo(AppSessionSurface.UNKNOWN);
        assertThat(AppSessionSurface.UNKNOWN.deviceName()).isEqualTo("UVEL App / H5");
    }
}
