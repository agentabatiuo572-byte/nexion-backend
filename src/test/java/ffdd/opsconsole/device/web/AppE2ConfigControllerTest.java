package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppE2ConfigControllerTest {
    @Mock
    private OpsDeviceService deviceService;

    @InjectMocks
    private AppE2ConfigController controller;

    @Test
    void exposesReadOnlyServerCanonicalE2ProjectionForAppAndH5() {
        ApiResult<Map<String, Object>> pricing = ApiResult.ok(Map.of(
                "taskClasses", List.of(Map.of("taskClass", "IG")),
                "queueSaturation", "0.35"));
        ApiResult<Map<String, Object>> tiers = ApiResult.ok(Map.of(
                "tiers", List.of(Map.of("tier", 1, "baseRateUsdt", "0.04"))));
        when(deviceService.e2TaskPricing()).thenReturn(pricing);
        when(deviceService.e2PhoneTiers()).thenReturn(tiers);

        assertThat(controller.taskPricing()).isSameAs(pricing);
        assertThat(controller.phoneTiers()).isSameAs(tiers);
    }
}
