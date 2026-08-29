package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppNetworkRegionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class AppNetworkRegionServiceTest {
    private final AppNetworkRegionMapper mapper = mock(AppNetworkRegionMapper.class);
    private final Environment environment = mock(Environment.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
    private final AppNetworkRegionService service = new AppNetworkRegionService(mapper, environment, clock);

    @Test
    void returnsOnlyServerAggregatesAndDerivesTotals() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.userScope(42L)).thenReturn(new AppNetworkRegionMapper.UserScope(0));
        when(mapper.regions(42L)).thenReturn(List.of(
                new AppNetworkRegionMapper.RegionRow("ap-southeast-1", "Asia", "Singapore", "Singapore",
                        2L, 1L, 8L, 1.35, 103.82, 1),
                new AppNetworkRegionMapper.RegionRow("eu-west-1", "Europe", "Ireland", "Dublin",
                        3L, 2L, 10L, null, null, 0)));

        var result = service.list(42L);
        assertThat(result.getCode()).isZero();
        assertThat((java.util.Map) result.getData())
                .containsEntry("activeNodes", 5L)
                .containsEntry("activeJobs", 3L)
                .containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("serverCanonical", true)
                .containsEntry("generatedAt", "2026-08-13T00:00:00Z");
    }

    @Test
    void rejectsAcceptanceBeforeReadingUserOrProjectionBecauseNoRunScopedDeviceProjectionExists() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        assertThatThrownBy(() -> service.list(42L))
                .hasMessage("NETWORK_REGION_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(42L);
        verify(mapper, never()).regions(42L);
    }

    @Test
    void rejectsMixedRuntimeBeforeReadingUserOrProjection() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod", "dev"});

        assertThatThrownBy(() -> service.list(42L))
                .hasMessage("NETWORK_REGION_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(42L);
        verify(mapper, never()).regions(42L);
    }

    @Test
    void rejectsUnknownRuntimeBeforeReadingUserOrProjection() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        assertThatThrownBy(() -> service.list(42L))
                .hasMessage("NETWORK_REGION_RUNTIME_UNSUPPORTED");
        verify(mapper, never()).userScope(42L);
        verify(mapper, never()).regions(42L);
    }

    @Test
    void rejectsSandboxUserInProductionBeforeAggregation() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(mapper.userScope(42L)).thenReturn(new AppNetworkRegionMapper.UserScope(1));

        assertThatThrownBy(() -> service.list(42L))
                .hasMessage("NETWORK_REGION_PRODUCTION_USER_REQUIRED");
        verify(mapper, never()).regions(42L);
    }
}
