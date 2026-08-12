package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsSandboxMapper;
import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BehaviorAnalyticsServiceTest {
    private final BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
    private final BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final BehaviorAnalyticsService service = new BehaviorAnalyticsService(
            mapper, sandboxMapper, outbox, mock(AuditLogService.class), productionEnvironment(),
            "unit-test-pseudonym-secret", "unit-run");

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }

    @BeforeEach
    void productionServiceUsesOnlyANonSandboxAccount() {
        when(mapper.isSandboxUser(42L)).thenReturn(false);
        lenient().when(mapper.tryAcquireSessionLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        lenient().when(sandboxMapper.tryAcquireSessionLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
    }

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
        when(mapper.replaceClaimEventId(org.mockito.ArgumentMatchers.eq("evt-1"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("a".repeat(32)))).thenReturn(1);
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        var result = service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.page_viewed", "0123456789abcdef0123456789abcdef", "/pages/store/detail?sku=secret",
                1200L, null, null, null, null, Instant.now().toEpochMilli(), "H5", "zh-CN"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(outbox).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("app.page_viewed"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("source_environment", "PRODUCTION");
        ArgumentCaptor<BehaviorAnalyticsMapper.BehaviorFactRow> claimCaptor = ArgumentCaptor.forClass(BehaviorAnalyticsMapper.BehaviorFactRow.class);
        verify(mapper).insertFact(claimCaptor.capture());
        BehaviorAnalyticsMapper.BehaviorFactRow claim = claimCaptor.getValue();
        assertThat(claim.eventId()).isNotEqualTo("evt-1");
        assertThat(claim.route()).isEqualTo("/pages/store/detail");
        assertThat(claim.actorHash()).isNotEqualTo("42").hasSize(64);
        assertThat(claim.pageLevel()).isEqualTo(3);
        verify(mapper).replaceClaimEventId("evt-1", claim.eventId(), "a".repeat(32));
    }

    @Test
    void fixtureIngestPersistsOnlyAnExplicitSandboxSource() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("acceptance");
        BehaviorAnalyticsService sandboxService = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), environment, "unit-test-pseudonym-secret", "unit-run");
        when(mapper.isSandboxUser(9000001L)).thenReturn(true);
        sandboxService.ingestFixture(9000001L, new BehaviorEventRequest(
                "dddddddddddddddddddddddddddddddd", "app.page_viewed",
                "fedcba9876543210fedcba9876543210", "/pages/store/detail",
                0L, null, null, null, null, Instant.now().toEpochMilli(), "APP", "en-US"));

        verify(sandboxMapper).insertFact(org.mockito.ArgumentMatchers.any());
        verify(mapper, org.mockito.Mockito.never()).insertFact(org.mockito.ArgumentMatchers.any());
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
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
        when(mapper.deleteClaim(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("e".repeat(32))))
                .thenReturn(1);
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        var result = service.ingest(42L, new BehaviorEventRequest(
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "app.page_viewed",
                "0123456789abcdef0123456789abcdef", "/pages/store/detail",
                1200L, null, null, null, null, Instant.now().toEpochMilli(), "H5", "zh-CN"));

        assertThat(result.getData()).containsEntry("accepted", true).containsEntry("sampledIn", false);
        ArgumentCaptor<BehaviorAnalyticsMapper.BehaviorFactRow> claimCaptor = ArgumentCaptor.forClass(BehaviorAnalyticsMapper.BehaviorFactRow.class);
        verify(mapper).insertFact(claimCaptor.capture());
        verify(mapper).deleteClaim(claimCaptor.getValue().eventId(), "e".repeat(32));
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
        long clientTs = Instant.now().toEpochMilli();
        String sessionId = "0123456789abcdef0123456789abcdef";
        when(mapper.findByDedupeKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new BehaviorAnalyticsMapper.ExistingEventRow("app.page_viewed", hash("session", sessionId),
                        "/pages/store/detail", pageFingerprint("PRODUCTION", 42L, sessionId, clientTs, 1200L)));

        var result = service.ingest(42L, new BehaviorEventRequest(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "app.page_viewed", sessionId, "/pages/store/detail",
                1200L, null, null, null, null, clientTs, "APP", "en-US"));

        assertThat(result.getData()).containsEntry("duplicate", true).containsEntry("accepted", false);
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(mapper, org.mockito.Mockito.never()).latestEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateClientEventWithAChangedCanonicalElementFailsClosedWith409() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(
                new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                        "/pages/store/store", "/pages/store/store", true));
        long clientTs = Instant.now().toEpochMilli();
        String sessionId = "0123456789abcdef0123456789abcdef";
        String original = eventFingerprint("PRODUCTION", hash("actor", "42"), hash("session", sessionId), clientTs,
                "app.element_clicked", "/pages/store/detail", "APP", "en-US", null, 0.5, 0.5, "MAIN_CTA", "buy-now");
        when(mapper.findByClientEventId("a".repeat(32))).thenReturn(
                new BehaviorAnalyticsMapper.ExistingEventRow("app.element_clicked", hash("session", sessionId),
                        "/pages/store/detail", original));
        assertThatThrownBy(() -> service.ingest(42L, new BehaviorEventRequest(
                "a".repeat(32), "app.element_clicked", sessionId, "/pages/store/detail",
                null, 0.5, 0.5, "MAIN_CTA", "buy-later", clientTs, "APP", "en-US")))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_CLIENT_EVENT_ID_CONFLICT")
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(409));
    }

    @Test
    void productionUniqueInsertConflictRereadsTheWinnerAndAcknowledgesTheSameFullFingerprint() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        long clientTs = Instant.now().toEpochMilli();
        String requestId = "f".repeat(32);
        String sessionId = "0123456789abcdef0123456789abcdef";
        String fingerprint = pageFingerprint("PRODUCTION", 42L, sessionId, clientTs, 1200L);
        when(mapper.findByClientEventId(requestId)).thenReturn(null, null);
        when(mapper.findByDedupeKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(null,
                new BehaviorAnalyticsMapper.ExistingEventRow("app.page_viewed", hash("session", sessionId),
                        "/pages/store/detail", fingerprint));
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("evt-1", true));
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenThrow(new DuplicateKeyException("duplicate client key"));

        var result = service.ingest(42L, pageEvent(requestId, sessionId, clientTs, 1200L));

        assertThat(result.getData()).containsEntry("accepted", false).containsEntry("duplicate", true);
        verify(mapper, org.mockito.Mockito.times(1)).insertFact(org.mockito.ArgumentMatchers.any());
        verify(mapper, org.mockito.Mockito.times(2)).findByClientEventId(requestId);
        verify(mapper, org.mockito.Mockito.times(2)).findByDedupeKey(org.mockito.ArgumentMatchers.anyString());
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void productionUniqueInsertConflictWithChangedFullFingerprintFailsClosedWith409AndNeverRetriesInsert() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        long clientTs = Instant.now().toEpochMilli();
        String requestId = "c".repeat(32);
        String sessionId = "0123456789abcdef0123456789abcdef";
        when(mapper.findByClientEventId(requestId)).thenReturn(null,
                new BehaviorAnalyticsMapper.ExistingEventRow("app.page_viewed", hash("session", sessionId),
                        "/pages/store/detail", pageFingerprint("PRODUCTION", 42L, sessionId, clientTs, 99L)));
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("evt-1", true));
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenThrow(new DuplicateKeyException("duplicate client key"));

        assertThatThrownBy(() -> service.ingest(42L, pageEvent(requestId, sessionId, clientTs, 1200L)))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_CLIENT_EVENT_ID_CONFLICT")
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(409));
        verify(mapper, org.mockito.Mockito.times(1)).insertFact(org.mockito.ArgumentMatchers.any());
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentProductionReplayClaimsTheFactBeforeOneOutboxWriteAndTheLoserDoesNotPublish() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        long clientTs = Instant.now().toEpochMilli();
        String requestId = "7".repeat(32);
        String sessionId = "0123456789abcdef0123456789abcdef";
        String fingerprint = pageFingerprint("PRODUCTION", 42L, sessionId, clientTs, 1200L);
        when(mapper.findByClientEventId(requestId)).thenReturn(null, null,
                new BehaviorAnalyticsMapper.ExistingEventRow("app.page_viewed", hash("session", sessionId),
                        "/pages/store/detail", fingerprint));
        when(mapper.findByDedupeKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(null, null);
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenReturn(1).thenThrow(new DuplicateKeyException("concurrent client key"));
        when(mapper.replaceClaimEventId(org.mockito.ArgumentMatchers.eq("evt-winner"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(requestId))).thenReturn(1);
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("evt-winner", true));

        var winner = service.ingest(42L, pageEvent(requestId, sessionId, clientTs, 1200L));
        var loser = service.ingest(42L, pageEvent(requestId, sessionId, clientTs, 1200L));

        assertThat(winner.getData()).containsEntry("accepted", true).containsEntry("eventId", "evt-winner");
        assertThat(loser.getData()).containsEntry("accepted", false).containsEntry("duplicate", true);
        verify(mapper, org.mockito.Mockito.times(2)).insertFact(org.mockito.ArgumentMatchers.any());
        verify(outbox, org.mockito.Mockito.times(1)).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        InOrder order = org.mockito.Mockito.inOrder(mapper, outbox);
        order.verify(mapper).insertFact(org.mockito.ArgumentMatchers.any());
        order.verify(outbox).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void productionSessionAuthoritySerializesStaleConcurrentReadsAndRejectsTheLaterLockedOlderEvent() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        String sessionId = "0123456789abcdef0123456789abcdef";
        long newerTs = Instant.now().toEpochMilli();
        long olderTs = newerTs - 1_000;
        when(mapper.latestSessionEventAt(hash("session", sessionId))).thenReturn(null,
                java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(newerTs), java.time.ZoneOffset.ofHours(8)));
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.replaceClaimEventId(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("evt-serial", true));

        service.ingest(42L, pageEvent("1".repeat(32), sessionId, newerTs, 1200L));
        assertThatThrownBy(() -> service.ingest(42L, pageEvent("2".repeat(32), sessionId, olderTs, 1200L)))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_EVENT_OUT_OF_ORDER");

        verify(mapper, org.mockito.Mockito.times(2)).tryAcquireSessionLock(org.mockito.ArgumentMatchers.anyString());
        verify(mapper, org.mockito.Mockito.times(2)).releaseSessionLock(org.mockito.ArgumentMatchers.anyString());
        verify(outbox, org.mockito.Mockito.times(1)).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sandboxRunSessionAuthoritySerializesStaleConcurrentReadsWithoutCrossRunProductionLocking() {
        MockEnvironment acceptance = new MockEnvironment();
        acceptance.setActiveProfiles("acceptance");
        BehaviorAnalyticsService sandboxService = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), acceptance, "unit-test-pseudonym-secret", "run-A");
        when(mapper.isSandboxUser(42L)).thenReturn(true);
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        String sessionId = "0123456789abcdef0123456789abcdef";
        long newerTs = Instant.now().toEpochMilli();
        long olderTs = newerTs - 1_000;
        when(sandboxMapper.latestSessionEventAt("run-A", hash("session", sessionId))).thenReturn(null,
                java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(newerTs), java.time.ZoneOffset.ofHours(8)));
        when(sandboxMapper.insertFact(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        sandboxService.ingest(42L, pageEvent("3".repeat(32), sessionId, newerTs, 1200L));
        assertThatThrownBy(() -> sandboxService.ingest(42L, pageEvent("4".repeat(32), sessionId, olderTs, 1200L)))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_EVENT_OUT_OF_ORDER");

        verify(sandboxMapper, org.mockito.Mockito.times(2)).tryAcquireSessionLock(org.mockito.ArgumentMatchers.anyString());
        verify(sandboxMapper, org.mockito.Mockito.times(2)).releaseSessionLock(org.mockito.ArgumentMatchers.anyString());
        verify(mapper, org.mockito.Mockito.never()).tryAcquireSessionLock(org.mockito.ArgumentMatchers.anyString());
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unavailableSessionAuthorityLockFailsClosedWith429BeforeAnyFactOrOutboxWrite() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        when(mapper.tryAcquireSessionLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.ingest(42L, pageEvent("5".repeat(32), "0123456789abcdef0123456789abcdef",
                Instant.now().toEpochMilli(), 1200L)))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_SESSION_LOCK_BUSY")
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(429));
        verify(mapper, org.mockito.Mockito.never()).insertFact(org.mockito.ArgumentMatchers.any());
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sessionAuthorityLockIsReleasedOnlyAfterTheEnclosingTransactionCompletes() {
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        when(mapper.insertFact(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.replaceClaimEventId(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(outbox.publishTrustedClientAnalyticsEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new EventOutboxService.ClientAnalyticsPublishResult("evt-after-commit", true));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.ingest(42L, pageEvent("6".repeat(32), "0123456789abcdef0123456789abcdef",
                    Instant.now().toEpochMilli(), 1200L));

            verify(mapper, org.mockito.Mockito.never()).releaseSessionLock(org.mockito.ArgumentMatchers.anyString());
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(mapper).releaseSessionLock(org.mockito.ArgumentMatchers.anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void sandboxUniqueInsertConflictRereadsOnlyItsRunWinnerAndAcknowledgesTheSameFullFingerprint() {
        MockEnvironment acceptance = new MockEnvironment();
        acceptance.setActiveProfiles("acceptance");
        BehaviorAnalyticsService sandboxService = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), acceptance, "unit-test-pseudonym-secret", "run-A");
        when(mapper.isSandboxUser(42L)).thenReturn(true);
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        long clientTs = Instant.now().toEpochMilli();
        String requestId = "d".repeat(32);
        String sessionId = "0123456789abcdef0123456789abcdef";
        String fingerprint = pageFingerprint("SANDBOX", 42L, sessionId, clientTs, 1200L);
        when(sandboxMapper.findByClientEventId("run-A", requestId)).thenReturn(null, null);
        when(sandboxMapper.findByDedupeKey(org.mockito.ArgumentMatchers.eq("run-A"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null, new BehaviorAnalyticsSandboxMapper.ExistingEventRow("app.page_viewed", hash("session", sessionId),
                        "/pages/store/detail", fingerprint));
        when(sandboxMapper.insertFact(org.mockito.ArgumentMatchers.any())).thenThrow(new DuplicateKeyException("duplicate run key"));

        var result = sandboxService.ingest(42L, pageEvent(requestId, sessionId, clientTs, 1200L));

        assertThat(result.getData()).containsEntry("accepted", false).containsEntry("duplicate", true)
                .containsEntry("runId", "run-A");
        verify(sandboxMapper, org.mockito.Mockito.times(1)).insertFact(org.mockito.ArgumentMatchers.any());
        verify(sandboxMapper, org.mockito.Mockito.times(2)).findByClientEventId("run-A", requestId);
        verify(sandboxMapper, org.mockito.Mockito.times(2)).findByDedupeKey(org.mockito.ArgumentMatchers.eq("run-A"), org.mockito.ArgumentMatchers.anyString());
        verify(outbox, org.mockito.Mockito.never()).publishTrustedClientAnalyticsEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sandboxUniqueInsertConflictWithChangedFullFingerprintFailsClosedWith409AndNeverRetriesInsert() {
        MockEnvironment acceptance = new MockEnvironment();
        acceptance.setActiveProfiles("acceptance");
        BehaviorAnalyticsService sandboxService = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), acceptance, "unit-test-pseudonym-secret", "run-A");
        when(mapper.isSandboxUser(42L)).thenReturn(true);
        when(mapper.findTrackedPage("/pages/store/detail")).thenReturn(trackedDetail());
        long clientTs = Instant.now().toEpochMilli();
        String requestId = "9".repeat(32);
        String sessionId = "0123456789abcdef0123456789abcdef";
        when(sandboxMapper.findByClientEventId("run-A", requestId)).thenReturn(null,
                new BehaviorAnalyticsSandboxMapper.ExistingEventRow("app.page_viewed", hash("session", sessionId),
                        "/pages/store/detail", pageFingerprint("SANDBOX", 42L, sessionId, clientTs, 99L)));
        when(sandboxMapper.insertFact(org.mockito.ArgumentMatchers.any())).thenThrow(new DuplicateKeyException("duplicate run key"));

        assertThatThrownBy(() -> sandboxService.ingest(42L, pageEvent(requestId, sessionId, clientTs, 1200L)))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_CLIENT_EVENT_ID_CONFLICT")
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(409));
        verify(sandboxMapper, org.mockito.Mockito.times(1)).insertFact(org.mockito.ArgumentMatchers.any());
    }

    private static BehaviorAnalyticsMapper.CatalogRow trackedDetail() {
        return new BehaviorAnalyticsMapper.CatalogRow("/pages/store/detail", "商品", 3,
                "/pages/store/store", "/pages/store/store", true);
    }

    private static BehaviorEventRequest pageEvent(String clientEventId, String sessionId, long clientTs, long dwellMs) {
        return new BehaviorEventRequest(clientEventId, "app.page_viewed", sessionId, "/pages/store/detail",
                dwellMs, null, null, null, null, clientTs, "APP", "en-US");
    }

    private static String pageFingerprint(String source, long actorId, String sessionId, long clientTs, long dwellMs) {
        return eventFingerprint(source, hash("actor", String.valueOf(actorId)), hash("session", sessionId), clientTs,
                "app.page_viewed", "/pages/store/detail", "APP", "en-US", dwellMs, null, null, null, null);
    }

    private static String eventFingerprint(String source, String actorHash, String sessionHash, long clientTs,
            String eventName, String route, String device, String locale, Long dwellMs, Double xNorm, Double yNorm,
            String zone, String elementId) {
        return hash("event-fingerprint", String.join("\u001f", source, actorHash, sessionHash, String.valueOf(clientTs),
                eventName, route, device, locale, String.valueOf(dwellMs), String.valueOf(xNorm), String.valueOf(yNorm),
                String.valueOf(zone), String.valueOf(elementId)));
    }

    private static String hash(String namespace, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    ("unit-test-pseudonym-secret\u001f" + namespace + "\u001f" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
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
