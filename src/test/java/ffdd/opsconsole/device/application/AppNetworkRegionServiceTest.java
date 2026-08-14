package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppNetworkRegionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppNetworkRegionServiceTest {
    private final AppNetworkRegionMapper mapper = mock(AppNetworkRegionMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
    private final AppNetworkRegionService service = new AppNetworkRegionService(mapper, clock);

    @Test
    void returnsOnlyServerAggregatesAndDerivesTotals() {
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
                .containsEntry("generatedAt", "2026-08-13T00:00:00Z");
    }
}
