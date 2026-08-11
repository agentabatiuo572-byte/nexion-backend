package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BehaviorAnalyticsServiceTest {
    private final BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final BehaviorAnalyticsService service = new BehaviorAnalyticsService(
            mapper, outbox, mock(AuditLogService.class), "unit-test-pseudonym-secret", "PRODUCTION");

    @Test
    void requestRejectsUnknownFieldsEvenWhenGlobalJacksonIsPermissive() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        assertThatThrownBy(() -> objectMapper.readValue("""
                {"eventName":"app.page_viewed","rawText":"must-not-be-accepted"}
                """, BehaviorEventRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("L6_UNKNOWN_FIELD:rawText");
    }

    @Test
    void ingestDerivesHierarchyFromServerCatalogAndNeverStoresRawUserId() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("app.page_viewed"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("evt-1", true));

        var result = service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.page_viewed", "0123456789abcdef0123456789abcdef", "/pages/store/detail?sku=secret",
                1200L, null, null, null, null, Instant.now().toEpochMilli(), "H5", "zh-CN"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("app.page_viewed"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("source_environment", "PRODUCTION");
        verify(mapper).insertFact(org.mockito.ArgumentMatchers.argThat(row ->
                row.eventId().equals("evt-1") && row.route().equals("/pages/store/detail")
                        && !row.actorHash().equals("42") && row.actorHash().length() == 64 && row.pageLevel() == 3));
    }

    @Test
    void fixtureIngestPersistsAnExplicitMockSource() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("app.page_viewed"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("fixture-event", true));

        service.ingestFixture(9000001L, new BehaviorEventRequest(
                "dddddddddddddddddddddddddddddddd", "app.page_viewed",
                "fedcba9876543210fedcba9876543210", "/pages/store/detail",
                0L, null, null, null, null, Instant.now().toEpochMilli(), "APP", "en-US"));

        verify(mapper).insertFact(org.mockito.ArgumentMatchers.argThat(row ->
                "MOCK".equals(row.sourceEnvironment())));
    }

    @Test
    void sampledOutTrustedEventIsAcceptedWithoutWritingAQueryableFact() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("app.page_viewed"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EventOutboxService.ClientAnalyticsPublishResult(null, false));

        var result = service.ingest(42L, new BehaviorEventRequest(
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "app.page_viewed",
                "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                1200L, null, null, null, null, Instant.now().toEpochMilli(), "H5", "zh-CN"));

        assertThat(result.getData()).containsEntry("accepted", true).containsEntry("sampledIn", false);
        verify(mapper, org.mockito.Mockito.never()).insertFact(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ingestRejectsUnknownRoutesAndOutOfRangeCoordinates() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.element_clicked", "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                null, 1.2, 0.5, "CONTENT", null, Instant.now().toEpochMilli(), "H5", "zh-CN")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void ingestRejectsClientZoneThatConflictsWithNormalizedCoordinate() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));

        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.element_clicked", "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                null, 0.5, 0.1, "BOTTOM", null, Instant.now().toEpochMilli(), "H5", "zh-CN")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L6_ZONE_INVALID");
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutPublishingAgain() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        when(mapper.countByDedupeKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);

        var result = service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.page_viewed", "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                1200L, null, null, null, null, Instant.now().toEpochMilli(), "APP", "en-US"));

        assertThat(result.getData()).containsEntry("duplicate", true).containsEntry("accepted", false);
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(mapper, org.mockito.Mockito.never()).latestEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aggregateDepthCannotRequestCoordinateHeat() {
        assertThatThrownBy(() -> service.clickHeat("/pages/store/store", "7d", "ALL", "ALL", "L1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L6_AGGREGATE_NODE_NO_COORDINATES");
    }

    @Test
    void invalidQueryValuesFailClosedInsteadOfFallingBack() {
        assertThatThrownBy(() -> service.behavior("forever", "ALL", "ALL", "ALL", "pv"))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_WINDOW_INVALID");
        assertThatThrownBy(() -> service.behavior("7d", "ALL", "ALL", "L9", "pv"))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_DEPTH_INVALID");
        assertThatThrownBy(() -> service.behavior("7d", "ALL", "ALL", "ALL", "money"))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_SORT_INVALID");
    }

    @Test
    void missingStaleAndOutOfOrderClientTimesFailClosed() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        when(mapper.latestSessionEventAt(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.time.LocalDateTime.now().plusSeconds(1));

        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.page_viewed",
                "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                0L, null, null, null, null, null, "APP", "en-US")))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_CLIENT_TIME_INVALID");
        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "app.page_viewed",
                "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                0L, null, null, null, null, Instant.now().minusSeconds(86_401).toEpochMilli(), "APP", "en-US")))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_CLIENT_TIME_INVALID");
        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "cccccccccccccccccccccccccccccccc", "app.page_viewed",
                "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                0L, null, null, null, null, Instant.now().toEpochMilli(), "APP", "en-US")))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_EVENT_OUT_OF_ORDER");
    }
}
