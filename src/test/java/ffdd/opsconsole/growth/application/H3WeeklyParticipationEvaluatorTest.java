package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.H3WeeklyParticipationMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class H3WeeklyParticipationEvaluatorTest {
    private final H3WeeklyParticipationMapper mapper = org.mockito.Mockito.mock(H3WeeklyParticipationMapper.class);
    private final EventOutboxService outbox = org.mockito.Mockito.mock(EventOutboxService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);
    private final H3WeeklyParticipationEvaluator evaluator = new H3WeeklyParticipationEvaluator(mapper, outbox, clock);

    @Test
    void thirdDistinctStorefrontProductEmitsOneThresholdFactForTheCurrentWeek() {
        when(mapper.insertObservation(eq(42L), eq("WEEK:2026-W37"), eq("STOREFRONT_PRODUCT_DETAIL"), eq("NX-A"), any()))
                .thenReturn(1);
        when(mapper.lockThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED"))
                .thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(1L, null));
        when(mapper.lockObservationIds(42L, "WEEK:2026-W37", "STOREFRONT_PRODUCT_DETAIL")).thenReturn(java.util.List.of(1L, 2L, 3L));
        when(mapper.markThresholdEmitted(eq(42L), eq("WEEK:2026-W37"), eq("H3_STOREFRONT_THREE_PRODUCTS_VIEWED"), any()))
                .thenReturn(1);
        when(mapper.attribution(42L)).thenReturn(Map.of("phase", "P2", "accountAgeMonths", 4, "cohort", "2026-W10"));

        assertThat(evaluator.recordStorefrontProductDetail(42L, "NX-A")).isTrue();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publishUserEventAt(
                eq("H3_WEEKLY_PARTICIPATION"), eq("42:WEEK:2026-W37:H3_STOREFRONT_THREE_PRODUCTS_VIEWED"),
                eq("H3_STOREFRONT_THREE_PRODUCTS_VIEWED"), eq(42L), eq("P2"), eq(4), eq("2026-W10"),
                eq(LocalDateTime.of(2026, 9, 7, 8, 0)), payload.capture());
        assertThat(payload.getValue()).containsEntry("threshold", 3).containsEntry("observationType", "STOREFRONT_PRODUCT_DETAIL")
                .containsEntry("instanceKey", "WEEK:2026-W37")
                .containsEntry("sourceOccurredAt", "2026-09-07T08:00");
    }

    @Test
    void repeatedProductDoesNotAdvanceTheDistinctProductThreshold() {
        when(mapper.lockThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED"))
                .thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(1L, null));
        when(mapper.insertObservation(eq(42L), eq("WEEK:2026-W37"), eq("STOREFRONT_PRODUCT_DETAIL"), eq("NX-A"), any()))
                .thenReturn(0);

        assertThat(evaluator.recordStorefrontProductDetail(42L, "NX-A")).isFalse();

        verify(mapper).ensureThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED");
        verify(mapper).lockThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED");
        verify(mapper, never()).lockObservationIds(any(), any(), any());
        verify(outbox, never()).publishUserEventAt(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void thresholdIsNotReemittedWhenTheMarkerAlreadyExists() {
        when(mapper.insertObservation(eq(42L), eq("WEEK:2026-W37"), eq("GENESIS_SECONDARY_MARKET"), eq("SECONDARY_MARKET"), any()))
                .thenReturn(1);
        when(mapper.lockThreshold(42L, "WEEK:2026-W37", "H3_GENESIS_SECONDARY_MARKET_VIEWED"))
                .thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(1L, java.time.LocalDateTime.of(2026, 9, 7, 8, 0)));

        assertThat(evaluator.recordGenesisSecondaryMarketView(42L)).isFalse();

        verify(mapper, never()).lockObservationIds(any(), any(), any());
        verify(outbox, never()).publishUserEventAt(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void computeCompletionUsesOnlyThatUsersCurrentWeekObservations() {
        when(mapper.insertObservation(eq(43L), eq("WEEK:2026-W37"), eq("VERIFIED_PRODUCTION_COMPUTE_COMPLETION"), eq("CTA-9"), any()))
                .thenReturn(1);
        when(mapper.lockThreshold(43L, "WEEK:2026-W37", "H3_COMPUTE_COMPLETED_50"))
                .thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(1L, null));
        when(mapper.lockObservationIds(43L, "WEEK:2026-W37", "VERIFIED_PRODUCTION_COMPUTE_COMPLETION"))
                .thenReturn(java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList());
        when(mapper.markThresholdEmitted(eq(43L), eq("WEEK:2026-W37"), eq("H3_COMPUTE_COMPLETED_50"), any()))
                .thenReturn(1);
        when(mapper.attribution(43L)).thenReturn(Map.of("phase", "P3", "accountAgeMonths", 1, "cohort", "2026-W20"));

        assertThat(evaluator.recordVerifiedProductionComputeCompletion(43L, "CTA-9", java.time.LocalDateTime.of(2026, 9, 7, 8, 0))).isTrue();

        verify(mapper).lockObservationIds(43L, "WEEK:2026-W37", "VERIFIED_PRODUCTION_COMPUTE_COMPLETION");
        verify(mapper, never()).lockObservationIds(eq(42L), any(), any());
        verify(outbox).publishUserEventAt(
                eq("H3_WEEKLY_PARTICIPATION"), eq("43:WEEK:2026-W37:H3_COMPUTE_COMPLETED_50"),
                eq("H3_COMPUTE_COMPLETED_50"), eq(43L), eq("P3"), eq(1), eq("2026-W20"),
                eq(LocalDateTime.of(2026, 9, 7, 8, 0)), any());
    }

    @Test
    void weekBoundaryUsesShanghaiIsoWeek() {
        Clock sunday = Clock.fixed(Instant.parse("2026-09-06T15:59:59Z"), ZoneOffset.UTC);
        assertThat(new H3WeeklyParticipationEvaluator(mapper, outbox, sunday).currentWeeklyInstance())
                .isEqualTo("WEEK:2026-W36");
        Clock monday = Clock.fixed(Instant.parse("2026-09-06T16:00:00Z"), ZoneOffset.UTC);
        assertThat(new H3WeeklyParticipationEvaluator(mapper, outbox, monday).currentWeeklyInstance())
                .isEqualTo("WEEK:2026-W37");
    }

    @Test
    void preBoundaryParticipationKeepsTheSundayOccurrenceForOutboxAndLaterWeekFence() {
        Clock sunday = Clock.fixed(Instant.parse("2026-09-06T15:59:59Z"), ZoneOffset.UTC);
        H3WeeklyParticipationEvaluator sundayEvaluator = new H3WeeklyParticipationEvaluator(mapper, outbox, sunday);
        LocalDateTime sundayOccurrence = LocalDateTime.of(2026, 9, 6, 23, 59, 59);
        when(mapper.insertObservation(eq(42L), eq("WEEK:2026-W36"), eq("GENESIS_SECONDARY_MARKET"),
                eq("SECONDARY_MARKET"), eq(sundayOccurrence))).thenReturn(1);
        when(mapper.lockThreshold(42L, "WEEK:2026-W36", "H3_GENESIS_SECONDARY_MARKET_VIEWED"))
                .thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(1L, null));
        when(mapper.lockObservationIds(42L, "WEEK:2026-W36", "GENESIS_SECONDARY_MARKET"))
                .thenReturn(java.util.List.of(1L));
        when(mapper.markThresholdEmitted(42L, "WEEK:2026-W36", "H3_GENESIS_SECONDARY_MARKET_VIEWED", sundayOccurrence))
                .thenReturn(1);
        when(mapper.attribution(42L)).thenReturn(Map.of("phase", "P2", "accountAgeMonths", 4, "cohort", "2026-W10"));

        assertThat(sundayEvaluator.recordGenesisSecondaryMarketView(42L)).isTrue();

        verify(outbox).publishUserEventAt(
                eq("H3_WEEKLY_PARTICIPATION"), eq("42:WEEK:2026-W36:H3_GENESIS_SECONDARY_MARKET_VIEWED"),
                eq("H3_GENESIS_SECONDARY_MARKET_VIEWED"), eq(42L), eq("P2"), eq(4), eq("2026-W10"),
                eq(sundayOccurrence), any());
    }

    @Test
    void locksTheThresholdBeforeInsertingAndCurrentReadingObservations() {
        when(mapper.lockThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED"))
                .thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(1L, null));
        when(mapper.insertObservation(eq(42L), eq("WEEK:2026-W37"), eq("STOREFRONT_PRODUCT_DETAIL"), eq("NX-B"), any()))
                .thenReturn(1);
        when(mapper.lockObservationIds(42L, "WEEK:2026-W37", "STOREFRONT_PRODUCT_DETAIL"))
                .thenReturn(java.util.List.of(1L));

        assertThat(evaluator.recordStorefrontProductDetail(42L, "NX-B")).isTrue();

        InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
        inOrder.verify(mapper).ensureThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED");
        inOrder.verify(mapper).lockThreshold(42L, "WEEK:2026-W37", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED");
        inOrder.verify(mapper).insertObservation(eq(42L), eq("WEEK:2026-W37"), eq("STOREFRONT_PRODUCT_DETAIL"), eq("NX-B"), any());
        inOrder.verify(mapper).lockObservationIds(42L, "WEEK:2026-W37", "STOREFRONT_PRODUCT_DETAIL");
    }
}
