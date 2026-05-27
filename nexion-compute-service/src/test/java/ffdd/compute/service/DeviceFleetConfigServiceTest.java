package ffdd.compute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.common.api.ApiResult;
import ffdd.compute.client.SystemConfigClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeviceFleetConfigServiceTest {
    private final SystemConfigClient systemConfigClient = mock(SystemConfigClient.class);

    @Test
    void readsMaxActiveSlotsFromPublicSystemConfig() {
        when(systemConfigClient.deviceFleet()).thenReturn(ApiResult.ok(Map.of("maxActiveSlots", 8)));
        DeviceFleetConfigService service = new DeviceFleetConfigService(systemConfigClient, 6);

        assertThat(service.maxActiveSlots()).isEqualTo(8);
        assertThat(service.currentConfig().getMaxActiveSlots()).isEqualTo(8);
    }

    @Test
    void fallsBackToLocalDefaultWhenSystemConfigIsUnavailable() {
        when(systemConfigClient.deviceFleet()).thenThrow(new IllegalStateException("system down"));
        DeviceFleetConfigService service = new DeviceFleetConfigService(systemConfigClient, 6);

        assertThat(service.maxActiveSlots()).isEqualTo(6);
    }

    @Test
    void ignoresInvalidConfiguredSlotValues() {
        when(systemConfigClient.deviceFleet()).thenReturn(ApiResult.ok(Map.of("maxActiveSlots", 0)));
        DeviceFleetConfigService service = new DeviceFleetConfigService(systemConfigClient, 6);

        assertThat(service.maxActiveSlots()).isEqualTo(6);
    }
}
