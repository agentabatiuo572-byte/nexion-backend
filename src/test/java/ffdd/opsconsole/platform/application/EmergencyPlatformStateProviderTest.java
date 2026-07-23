package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.emergency.application.OpsEmergencyControlService;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmergencyPlatformStateProviderTest {
    @Mock
    private OpsKillSwitchService killSwitchService;

    @Mock
    private OpsEmergencyControlService emergencyControlService;

    @InjectMocks
    private EmergencyPlatformStateProvider provider;

    @Test
    void exposesTheActualPublicJ1AndJ2SourceRoutes() {
        when(killSwitchService.matrix()).thenReturn(ApiResult.ok(Map.of(
                "activeGates", List.of(Map.of(
                        "key", "exchange",
                        "name", "兑换闸",
                        "enabled", false)))));
        when(emergencyControlService.geoBlockOverview()).thenReturn(ApiResult.ok(Map.of(
                "blocked", List.of(),
                "limited", List.of(),
                "recentChanges", List.of())));

        List<Map<String, Object>> rows = provider.currentKillSwitches();

        assertThat(rows).extracting(row -> row.get("source"))
                .containsExactly(
                        "J1:/api/admin/emergency/kill-switches",
                        "J2:/api/admin/emergency/geo-block");
    }
}
