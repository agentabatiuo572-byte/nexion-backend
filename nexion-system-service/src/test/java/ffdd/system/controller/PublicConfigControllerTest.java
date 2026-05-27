package ffdd.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.api.ApiResult;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.service.SystemConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicConfigControllerTest {
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final PublicConfigController controller =
            new PublicConfigController(systemConfigService, new ObjectMapper());

    @Test
    void exposesDeviceFleetMaxActiveSlotsFromPublicComputeConfig() {
        when(systemConfigService.listPublicByGroup("compute")).thenReturn(List.of(new ConfigItemResponse(
                21L,
                "compute.active_device_slots.default",
                "6",
                "NUMBER",
                "compute",
                "PUBLIC",
                "Default max active compute devices per user.",
                1,
                null,
                null)));

        ApiResult<Map<String, Object>> response = controller.deviceFleet();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).containsEntry("maxActiveSlots", 6);
    }
}
