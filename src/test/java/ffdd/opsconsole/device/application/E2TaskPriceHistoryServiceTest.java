package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class E2TaskPriceHistoryServiceTest {
    private final DeviceCatalogMapper mapper = Mockito.mock(DeviceCatalogMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
    private final E2TaskPriceHistoryService service = new E2TaskPriceHistoryService(mapper, clock);

    @Test
    void startupAcceptsOnlyTheCompletePermanentBusinessSchema() {
        when(mapper.taskPriceHistorySchema()).thenReturn(
                new DeviceCatalogMapper.TaskPriceHistorySchemaRow(1, 9, 1, 1, 1, 1));

        service.validateSchema();

        verify(mapper).taskPriceHistorySchema();
    }

    @Test
    void startupFailsClosedWhenMigrationLeftAPartialSchema() {
        when(mapper.taskPriceHistorySchema()).thenReturn(
                new DeviceCatalogMapper.TaskPriceHistorySchemaRow(1, 9, 0, 1, 1, 1));

        org.assertj.core.api.Assertions.assertThatThrownBy(service::validateSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("E2_TASK_PRICE_HISTORY_SCHEMA_INVALID");
    }

    @Test
    void developmentSeedCreatesPermanentTwentyFourHourAndOneHourSeriesIdempotently() {
        when(mapper.activeTaskPriceSeeds()).thenReturn(List.of(
                new DeviceCatalogMapper.TaskPriceSeedRow("TK-IG", "IG", new BigDecimal("0.045"), "/job")));
        when(mapper.insertTaskPriceHistory(anyString(), anyString(), any(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        int inserted = service.seedDevelopmentHistory();

        assertThat(inserted).isEqualTo(13);
        ArgumentCaptor<BigDecimal> prices = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<LocalDateTime> observed = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper, org.mockito.Mockito.times(13)).insertTaskPriceHistory(
                eq("TK-IG"), eq("IG"), prices.capture(), eq("/job"),
                eq(E2TaskPriceHistoryService.SOURCE_DEV_SEED), anyString(), observed.capture(), any(LocalDateTime.class));
        assertThat(prices.getAllValues().get(prices.getAllValues().size() - 1)).isEqualByComparingTo("0.045");
        assertThat(observed.getAllValues().get(0)).isEqualTo(LocalDateTime.parse("2026-08-19T08:05:00"));
        assertThat(observed.getAllValues().get(observed.getAllValues().size() - 1))
                .isEqualTo(LocalDateTime.parse("2026-08-20T08:00:00"));
    }

    @Test
    void developmentSeedRepairsMissingPerTaskSamplesWithoutDeletingBusinessHistory() {
        when(mapper.activeTaskPriceSeeds()).thenReturn(List.of(
                new DeviceCatalogMapper.TaskPriceSeedRow("TK-IG", "IG", new BigDecimal("0.045"), "/job")));
        when(mapper.insertTaskPriceHistory(anyString(), anyString(), any(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);

        assertThat(service.seedDevelopmentHistory()).isZero();

        verify(mapper, org.mockito.Mockito.times(13)).insertTaskPriceHistory(
                eq("TK-IG"), eq("IG"), any(), eq("/job"),
                eq(E2TaskPriceHistoryService.SOURCE_DEV_SEED), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void scheduledSnapshotUsesAStableFiveMinuteBucket() {
        service.snapshotCurrentPrices();

        verify(mapper).snapshotActiveTaskPrices(
                LocalDateTime.parse("2026-08-20T08:00:00"), "scheduled-20260820T0800");
    }
}
