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

class BehaviorAnalyticsServiceTest {
    private final BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final BehaviorAnalyticsService service = new BehaviorAnalyticsService(
            mapper, outbox, mock(AuditLogService.class), "unit-test-pseudonym-secret");

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
        when(outbox.publishClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("app.page_viewed"), org.mockito.ArgumentMatchers.any()))
                .thenReturn("evt-1");

        var result = service.ingest(42L, new BehaviorEventRequest(
                "app.page_viewed", "0123456789abcdef0123456789abcdef", "/pages/store/detail?sku=secret",
                1200L, null, null, null, null, Instant.now().toEpochMilli(), "H5", "zh-CN"));

        assertThat(result.getCode()).isZero();
        verify(mapper).insertFact(org.mockito.ArgumentMatchers.argThat(row ->
                row.eventId().equals("evt-1") && row.route().equals("/pages/store/detail")
                        && !row.actorHash().equals("42") && row.actorHash().length() == 64 && row.pageLevel() == 3));
    }

    @Test
    void ingestRejectsUnknownRoutesAndOutOfRangeCoordinates() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "app.element_clicked", "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                null, 1.2, 0.5, "CONTENT", null, Instant.now().toEpochMilli(), "H5", "zh-CN")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void ingestRejectsClientZoneThatConflictsWithNormalizedCoordinate() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));

        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "app.element_clicked", "0123456789abcdef0123456789abcdef", "/pages/store/detail",
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
                "app.page_viewed", "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                1200L, null, null, null, null, 123456789L, "APP", "en-US"));

        assertThat(result.getData()).containsEntry("duplicate", true).containsEntry("accepted", false);
        verify(outbox, org.mockito.Mockito.never()).publishClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(mapper, org.mockito.Mockito.never()).latestEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aggregateDepthCannotRequestCoordinateHeat() {
        assertThatThrownBy(() -> service.clickHeat("/pages/store/store", "7d", "ALL", "ALL", "L1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L6_AGGREGATE_NODE_NO_COORDINATES");
    }
}
