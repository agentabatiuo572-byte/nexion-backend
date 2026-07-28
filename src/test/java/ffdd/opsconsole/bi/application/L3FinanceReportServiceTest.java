package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.mapper.L3FinanceFactMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class L3FinanceReportServiceTest {
    private final L3FinanceFactMapper mapper = mock(L3FinanceFactMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
    private final L3FinanceReportService service = new L3FinanceReportService(mapper, clock);

    @Test
    @SuppressWarnings("unchecked")
    void returnsFourRealRevenueStreamsForTheMonthlyWindow() {
        when(mapper.sumDeviceSalesGmv(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("400"));
        when(mapper.sumTeamCommission(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("300"));
        when(mapper.sumTokenEconomyVolume(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("200"));
        when(mapper.sumComputeMatchingFees(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("100"));

        Map<String, Object> result = service.revenue("month", null, null).getData();

        assertThat(result).containsEntry("totalUsdt", new BigDecimal("1000.00"));
        assertThat((List<Map<String, Object>>) result.get("streams"))
                .extracting(row -> row.get("stream"))
                .containsExactly("device_sales", "team_commission", "token_economy", "compute_matching");
        assertThat((Map<String, Object>) result.get("period"))
                .containsEntry("granularity", "month")
                .containsEntry("label", "2026-07")
                .containsEntry("timeZone", "Z");
    }

    @Test
    void returnsNullRatesForAnEmptyRedemptionDenominator() {
        when(mapper.redemptionSummary(any(LocalDateTime.class), any(LocalDateTime.class), isNull()))
                .thenReturn(Map.of("submitted", 0L, "confirmed", 0L, "rejected", 0L, "delayedCount", 0L, "frozen", 0L));

        Map<String, Object> result = service.redemption("month", null, null, null).getData();

        assertThat(result).containsEntry("submitted", 0L).containsEntry("confirmed", 0L);
        assertThat(result.get("redemptionRate")).isNull();
        assertThat(result.get("previousRate")).isNull();
    }

    @Test
    void rejectsAnInvalidCustomWindowBeforeQueryingFinancialTables() {
        assertThatThrownBy(() -> service.revenue("custom", "2026-07-20", "2026-07-01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L3_CUSTOM_RANGE_INVALID");
    }

    @Test
    void comparesCalendarMonthWithTheImmediatelyPreviousCalendarMonth() {
        when(mapper.sumDeviceSalesGmv(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);
        when(mapper.sumTeamCommission(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);
        when(mapper.sumTokenEconomyVolume(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);
        when(mapper.sumComputeMatchingFees(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);

        service.revenue("month", null, null);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper, times(2)).sumDeviceSalesGmv(from.capture(), to.capture());
        assertThat(from.getAllValues()).containsExactly(
                LocalDateTime.parse("2026-07-01T00:00:00"),
                LocalDateTime.parse("2026-06-01T00:00:00"));
        assertThat(to.getAllValues()).containsExactly(
                LocalDateTime.parse("2026-08-01T00:00:00"),
                LocalDateTime.parse("2026-07-01T00:00:00"));
    }

    @Test
    void failsClosedWhenARevenueSourceReturnsNullOrNegativeMoney() {
        when(mapper.sumDeviceSalesGmv(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(null);
        when(mapper.sumTeamCommission(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);
        when(mapper.sumTokenEconomyVolume(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);
        when(mapper.sumComputeMatchingFees(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.revenue("month", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L3_REVENUE_SOURCE_INVALID");
    }

    @Test
    void failsClosedWhenTheRedemptionProjectionOmitsARequiredCounter() {
        when(mapper.redemptionSummary(any(LocalDateTime.class), any(LocalDateTime.class), isNull()))
                .thenReturn(Map.of("submitted", 2L, "confirmed", 1L, "rejected", 0L, "delayedCount", 0L));

        assertThatThrownBy(() -> service.redemption("month", null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L3_REDEMPTION_SOURCE_INVALID");
    }

    @Test
    void failsClosedWhenARedemptionCounterIsFractional() {
        when(mapper.redemptionSummary(any(LocalDateTime.class), any(LocalDateTime.class), isNull()))
                .thenReturn(Map.of(
                        "submitted", new BigDecimal("2.5"),
                        "confirmed", 1L,
                        "rejected", 0L,
                        "delayedCount", 0L,
                        "frozen", 0L));

        assertThatThrownBy(() -> service.redemption("month", null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L3_REDEMPTION_SOURCE_INVALID");
    }

    @Test
    void redemptionIdentifiesTheServerAuthoritativeA4OccurrenceSource() {
        when(mapper.redemptionSummary(any(LocalDateTime.class), any(LocalDateTime.class), isNull()))
                .thenReturn(Map.of(
                        "submitted", 0L,
                        "confirmed", 1L,
                        "rejected", 0L,
                        "delayedCount", 0L,
                        "frozen", 0L,
                        "avgLatencyHours", new BigDecimal("5.25")));

        Map<String, Object> result = service.redemption("month", null, null, null).getData();

        assertThat(result)
                .containsEntry("confirmed", 1L)
                .containsEntry("source", "A4 服务端权威提现生命周期事件")
                .containsEntry("averageLatencyHours", new BigDecimal("5.25"));
    }
}
