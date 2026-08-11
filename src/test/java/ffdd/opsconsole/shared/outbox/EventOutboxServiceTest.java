package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class EventOutboxServiceTest {
    private final EventOutboxMapper mapper = Mockito.mock(EventOutboxMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final A4RuntimePolicyService a4Policy = Mockito.mock(A4RuntimePolicyService.class);
    private final EventOutboxService service = new EventOutboxService(mapper, objectMapper, new OutboxProperties(), a4Policy);

    EventOutboxServiceTest() {
        when(a4Policy.samplingPercent(anyString(), anyBoolean())).thenReturn(100);
    }

    @Test
    void trustedClientSamplingIsStableAndDropsBeforeAnyDurableInsert() {
        when(mapper.findActiveSchema("app.page_viewed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("acquisition", 9, false));
        when(a4Policy.samplingPercent("acquisition", false)).thenReturn(10);
        when(mapper.findLifecycleState("app.page_viewed")).thenReturn("full");
        when(mapper.listActiveProperties("app.page_viewed")).thenReturn(List.of());
        String sampledOutActor = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> "server-actor-" + index)
                .filter(actor -> !EventOutboxService.isSampledIn(actor, "app.page_viewed", 10))
                .findFirst().orElseThrow();

        EventOutboxService.ClientAnalyticsPublishResult result = service.publishTrustedClientAnalyticsEvent(
                "server-session", sampledOutActor, "app.page_viewed", Map.of("anon_id", sampledOutActor));

        assertThat(result.sampledIn()).isFalse();
        assertThat(EventOutboxService.isSampledIn(sampledOutActor, "app.page_viewed", 10)).isFalse();
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void trustedClientIngestHonorsAuthoritativeAdminSamplingAtZeroAndOneHundred() {
        when(mapper.findActiveSchema("app.page_viewed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("acquisition", 9, false));
        when(mapper.findLifecycleState("app.page_viewed")).thenReturn("full");
        when(mapper.listActiveProperties("app.page_viewed")).thenReturn(List.of(
                required("source_environment", "enum")));
        when(a4Policy.samplingPercent("acquisition", false)).thenReturn(0, 100);

        EventOutboxService.ClientAnalyticsPublishResult dropped = service.publishTrustedClientAnalyticsEvent(
                "session-1", "server-actor-1", "app.page_viewed", Map.of("source_environment", "PRODUCTION"));
        EventOutboxService.ClientAnalyticsPublishResult kept = service.publishTrustedClientAnalyticsEvent(
                "session-1", "server-actor-1", "app.page_viewed", Map.of("source_environment", "PRODUCTION"));

        assertThat(dropped.sampledIn()).isFalse();
        assertThat(kept.sampledIn()).isTrue();
        verify(mapper, org.mockito.Mockito.times(1)).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void lifecycleBlocksPendingAndDisabledAnalyticsBeforeOutboxWrite() {
        when(mapper.findActiveSchema("risk.multi_account_flagged"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("risk", 5, true));

        for (String state : List.of("pending_publish", "disabled")) {
            when(mapper.findLifecycleState("risk.multi_account_flagged")).thenReturn(state);
            assertThatThrownBy(() -> service.publish(
                    "RISK_CLUSTER", "cluster-7", "RISK_MULTI_ACCOUNT_FLAGGED", Map.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("A4_EVENT_LIFECYCLE_BLOCKED");
        }
        verify(mapper, never()).insertEvent(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void lifecycleGrayUsesStableTenPercentScopeAndFullAlwaysWrites() {
        String eventName = "risk.multi_account_flagged";
        when(mapper.findActiveSchema(eventName))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("risk", 5, true));
        when(mapper.listActiveProperties(eventName)).thenReturn(List.of());
        String grayAllowed = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(value -> "cluster-" + value)
                .filter(value -> EventOutboxService.isGrayEligible(eventName, "RISK_CLUSTER:" + value))
                .findFirst().orElseThrow();
        String grayDenied = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(value -> "cluster-" + value)
                .filter(value -> !EventOutboxService.isGrayEligible(eventName, "RISK_CLUSTER:" + value))
                .findFirst().orElseThrow();

        when(mapper.findLifecycleState(eventName)).thenReturn("gray");
        service.publish("RISK_CLUSTER", grayAllowed, "RISK_MULTI_ACCOUNT_FLAGGED", Map.of());
        assertThatThrownBy(() -> service.publish(
                "RISK_CLUSTER", grayDenied, "RISK_MULTI_ACCOUNT_FLAGGED", Map.of()))
                .hasMessageContaining("A4_EVENT_GRAY_SCOPE_REJECTED");

        when(mapper.findLifecycleState(eventName)).thenReturn("full");
        service.publish("RISK_CLUSTER", grayDenied, "RISK_MULTI_ACCOUNT_FLAGGED", Map.of());
        verify(mapper, org.mockito.Mockito.times(2)).insertEvent(anyString(), eq("RISK_CLUSTER"), anyString(),
                eq("RISK_MULTI_ACCOUNT_FLAGGED"), eq(eventName), eq("risk"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(5), eq(true), eq(true), anyString());
    }

    @Test
    void registeredAnalyticsEventIsPersistedWithCanonicalA4Envelope() throws Exception {
        when(mapper.findActiveSchema("risk.multi_account_flagged"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("risk", 5, true));
        when(mapper.listActiveProperties("risk.multi_account_flagged"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("actor_id", "id", false)));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish(
                "RISK_CLUSTER", "cluster-7", "RISK_MULTI_ACCOUNT_FLAGGED",
                Map.of("actor_id", "user-9", "phase", "P6", "cohort", "spoofed", "accountAgeMonths", -1));

        verify(mapper).insertEvent(
                eq(eventId), eq("RISK_CLUSTER"), eq("cluster-7"), eq("RISK_MULTI_ACCOUNT_FLAGGED"),
                eq("risk.multi_account_flagged"), eq("risk"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(5), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("event_id").asText()).isEqualTo(eventId);
        assertThat(envelope.path("event_name").asText()).isEqualTo("risk.multi_account_flagged");
        assertThat(envelope.path("actor_id").asText()).isEqualTo("user-9");
        assertThat(envelope.path("ts").asLong()).isPositive();
        assertThat(envelope.path("phase").asText()).isEqualTo("SYSTEM");
        assertThat(envelope.path("account_age_months").asInt()).isZero();
        assertThat(envelope.path("cohort").asText()).matches("\\d{4}-W\\d{2}");
        assertThat(envelope.has("user_id")).isTrue();
        assertThat(envelope.has("anon_id")).isTrue();
        assertThat(envelope.has("session_id")).isTrue();
        assertThat(envelope.has("ref")).isTrue();
        assertThat(envelope.has("source")).isTrue();
        assertThat(envelope.path("platform").asText()).isEqualTo("server");
        assertThat(envelope.path("is_server_authoritative").asBoolean()).isTrue();
        assertThat(envelope.path("schema_revision").asInt()).isEqualTo(5);
    }

    @Test
    void canonicalEventNameUsesRegistryWithoutHardcodedEventTypeMapping() {
        when(mapper.findActiveSchema("checkout.completed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("conversion", 8, true));
        when(mapper.listActiveProperties("checkout.completed"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true)));

        String eventId = service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("orderId", "order-7"));

        verify(mapper).insertEvent(
                eq(eventId), eq("ORDER"), eq("order-7"), eq("checkout.completed"),
                eq("checkout.completed"), eq("conversion"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(8), eq(true), eq(true), anyString());
    }

    @Test
    void walletLedgerPostedMatchesTheRegisteredCanonicalSnakeCaseContract() throws Exception {
        when(mapper.findActiveSchema("wallet.ledger_posted"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("money", 110, true));
        when(mapper.listActiveProperties("wallet.ledger_posted")).thenReturn(List.of(
                required("user_id", "id"),
                required("biz_type", "enum"),
                required("asset", "enum"),
                required("direction", "enum"),
                required("amount", "number"),
                required("balance_after", "number"),
                required("biz_no", "id"),
                required("status", "enum")));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish("WALLET_LEDGER", "BIZ-1", "wallet.ledger_posted", Map.of(
                "userId", 52L,
                "bizType", "ADJUSTMENT",
                "asset", "USDT",
                "direction", "IN",
                "amount", new java.math.BigDecimal("10.000000"),
                "balanceAfter", new java.math.BigDecimal("20.000000"),
                "bizNo", "BIZ-1",
                "status", "SUCCESS"));

        verify(mapper).insertEvent(
                eq(eventId), eq("WALLET_LEDGER"), eq("BIZ-1"), eq("wallet.ledger_posted"),
                eq("wallet.ledger_posted"), eq("money"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(110), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("user_id").asLong()).isEqualTo(52L);
        assertThat(envelope.path("biz_type").asText()).isEqualTo("ADJUSTMENT");
        assertThat(envelope.path("balance_after").decimalValue()).isEqualByComparingTo("20.000000");
        assertThat(envelope.path("biz_no").asText()).isEqualTo("BIZ-1");
    }

    @Test
    void legacyLearningCompletionDeliveryTypeUsesRegisteredLearnAnalyticsSchema() throws Exception {
        when(mapper.findActiveSchema("learn.course_completed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("learn", 116, true));
        when(mapper.listActiveProperties("learn.course_completed")).thenReturn(List.of(
                required("course_id", "id"), required("course_version", "id"),
                required("nex_reward", "number")));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish(
                "LEARNING", "42:test-course:v2", "LEARNING_COURSE_COMPLETED",
                Map.of("user_id", 42L, "course_id", "test-course", "course_version", "v2",
                        "nex_reward", new java.math.BigDecimal("20.000000")));

        verify(mapper).insertEvent(
                eq(eventId), eq("LEARNING"), eq("42:test-course:v2"), eq("LEARNING_COURSE_COMPLETED"),
                eq("learn.course_completed"), eq("learn"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(116), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("event_name").asText()).isEqualTo("learn.course_completed");
        assertThat(envelope.path("user_id").asLong()).isEqualTo(42L);
        assertThat(envelope.path("nex_reward").decimalValue()).isEqualByComparingTo("20.000000");
    }

    @Test
    void allC2HighRiskEventsSatisfyTheirRegisteredCanonicalPayloadContracts() {
        when(mapper.findActiveSchema("admin.user_frozen"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 25, true));
        when(mapper.findActiveSchema("admin.user_unfrozen"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 26, true));
        when(mapper.findActiveSchema("admin.user_impersonation_started"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 27, true));
        when(mapper.findActiveSchema("admin.user_impersonation_ended"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 28, true));
        List<EventOutboxMapper.SchemaPropertyGateRow> accountProperties = List.of(
                required("target_user_id", "id"), required("operator", "id"),
                required("reason", "string"), required("occurred_at", "timestamp"));
        when(mapper.listActiveProperties("admin.user_frozen")).thenReturn(accountProperties);
        when(mapper.listActiveProperties("admin.user_unfrozen")).thenReturn(accountProperties);
        when(mapper.listActiveProperties("admin.user_impersonation_started")).thenReturn(List.of(
                required("target_user_id", "id"), required("operator", "id"),
                required("reason", "string"), required("ttl_minutes", "number"),
                required("session_start", "timestamp"), required("occurred_at", "timestamp")));
        when(mapper.listActiveProperties("admin.user_impersonation_ended")).thenReturn(List.of(
                required("target_user_id", "id"), required("operator", "id"),
                required("reason", "string"), required("ttl_minutes", "number"),
                required("session_start", "timestamp"), required("session_end", "timestamp"),
                required("duration_sec", "number"), required("end_type", "enum"),
                required("occurred_at", "timestamp")));
        String now = "2026-07-18T12:00:00";

        service.publish("USER", "52", "admin.user_frozen", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "risk containment", "occurredAt", now));
        service.publish("USER", "52", "admin.user_unfrozen", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "risk cleared", "occurredAt", now));
        service.publish("USER_IMPERSONATION", "IMP-52", "admin.user_impersonation_started", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "support investigation", "ttlMinutes", 15,
                "sessionStart", now, "occurredAt", now));
        service.publish("USER_IMPERSONATION", "IMP-52", "admin.user_impersonation_ended", Map.of(
                "userId", 52L, "targetUserId", 52L, "operator", "superadmin",
                "reason", "investigation complete", "ttlMinutes", 15,
                "sessionStart", now, "sessionEnd", "2026-07-18T12:05:00",
                "durationSec", 300L, "endType", "TERMINATED", "occurredAt", "2026-07-18T12:05:00"));

        verify(mapper, org.mockito.Mockito.times(4)).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), eq("phase_admin"),
                eq("SYSTEM"), eq(0), anyString(), eq(true), anyInt(), eq(true), eq(true), anyString());
    }

    @Test
    void userEventUsesTrustedServerAttributionAndOverridesSpoofedEnvelopeFields() throws Exception {
        when(mapper.findActiveSchema("checkout.started"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("conversion", 11, true));
        when(mapper.listActiveProperties("checkout.started"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true)));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publishUserEvent(
                "ORDER", "order-9", "checkout.started", 42L, "P4", 7, "2025-W52",
                Map.of("orderId", "order-9", "userId", 999L, "phase", "P1",
                        "accountAgeMonths", 99, "cohort", "spoofed"));

        verify(mapper).insertEvent(
                eq(eventId), eq("ORDER"), eq("order-9"), eq("checkout.started"),
                eq("checkout.started"), eq("conversion"), eq("P4"), eq(7), eq("2025-W52"),
                eq(true), eq(11), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("user_id").asLong()).isEqualTo(42L);
        assertThat(envelope.has("userId")).isFalse();
        assertThat(envelope.path("phase").asText()).isEqualTo("P4");
        assertThat(envelope.path("account_age_months").asInt()).isEqualTo(7);
        assertThat(envelope.has("accountAgeMonths")).isFalse();
        assertThat(envelope.path("cohort").asText()).isEqualTo("2025-W52");
    }

    @Test
    void analyticsPayloadRejectsUnregisteredPropertiesRawPiiAndTypeMismatch() {
        when(mapper.findActiveSchema("checkout.completed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("conversion", 8, true));
        when(mapper.listActiveProperties("checkout.completed"))
                .thenReturn(List.of(new EventOutboxMapper.SchemaPropertyGateRow("order_id", "id", true)));

        assertThatThrownBy(() -> service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("phone", "123456789")))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_EVENT_PAYLOAD_PII_REJECTED");
        assertThatThrownBy(() -> service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("unknownField", "value")))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        assertThatThrownBy(() -> service.publish(
                "ORDER", "order-7", "checkout.completed", Map.of("orderId", Map.of("nested", true))))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void revision289RejectsTheNewL2ArtifactEvidenceBeforeOutboxInsert() {
        when(mapper.findActiveSchema("admin.report_exported"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 289, true));
        when(mapper.listActiveProperties("admin.report_exported"))
                .thenReturn(reportExportProperties(false));

        assertThatThrownBy(() -> service.publish(
                "BI_REPORT", "EXP-L2-289", "admin.report_exported", reportExportPayload(true)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void revision302AcceptsTheCanonicalL2ArtifactEvidenceAndKeepsLegacyProducerCompatibility()
            throws Exception {
        when(mapper.findActiveSchema("admin.report_exported"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 302, true));
        when(mapper.listActiveProperties("admin.report_exported"))
                .thenReturn(reportExportProperties(true));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String l2EventId = service.publish(
                "BI_REPORT", "EXP-L2-302", "admin.report_exported", reportExportPayload(true));
        String legacyEventId = service.publish(
                "BI_REPORT", "EXP-LEGACY-302", "admin.report_exported", reportExportPayload(false));

        verify(mapper, org.mockito.Mockito.times(2)).insertEvent(
                anyString(), eq("BI_REPORT"), anyString(), eq("admin.report_exported"),
                eq("admin.report_exported"), eq("phase_admin"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(302), eq(true), eq(true), payloadCaptor.capture());
        JsonNode l2Envelope = objectMapper.readTree(payloadCaptor.getAllValues().get(0));
        JsonNode legacyEnvelope = objectMapper.readTree(payloadCaptor.getAllValues().get(1));
        assertThat(l2Envelope.path("event_id").asText()).isEqualTo(l2EventId);
        assertThat(l2Envelope.path("artifact_store").asText()).isEqualTo("MINIO");
        assertThat(l2Envelope.path("artifact_sha256").asText()).hasSize(64);
        assertThat(l2Envelope.path("artifact_size_bytes").asLong()).isEqualTo(512L);
        assertThat(l2Envelope.path("schema_revision").asInt()).isEqualTo(302);
        assertThat(legacyEnvelope.path("event_id").asText()).isEqualTo(legacyEventId);
        assertThat(legacyEnvelope.has("artifact_store")).isFalse();
        assertThat(legacyEnvelope.has("artifact_sha256")).isFalse();
        assertThat(legacyEnvelope.has("artifact_size_bytes")).isFalse();
    }

    @Test
    void revision302StillRejectsWrongArtifactTypesAndMissingLegacyRequiredFields() {
        when(mapper.findActiveSchema("admin.report_exported"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 302, true));
        when(mapper.listActiveProperties("admin.report_exported"))
                .thenReturn(reportExportProperties(true));
        Map<String, Object> wrongArtifactType = new java.util.LinkedHashMap<>(reportExportPayload(true));
        wrongArtifactType.put("artifactSizeBytes", "512");
        Map<String, Object> missingRequired = new java.util.LinkedHashMap<>(reportExportPayload(false));
        missingRequired.remove("reportId");

        assertThatThrownBy(() -> service.publish(
                "BI_REPORT", "EXP-L2-BAD-TYPE", "admin.report_exported", wrongArtifactType))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        assertThatThrownBy(() -> service.publish(
                "BI_REPORT", "EXP-L2-MISSING", "admin.report_exported", missingRequired))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void unregisteredAnalyticsEventIsRejectedBeforeOutboxInsert() {
        when(mapper.findActiveSchema("admin.killswitch_toggled")).thenReturn(null);

        assertThatThrownBy(() -> service.publish(
                "KILL_SWITCH", "geo-block", "ADMIN_KILLSWITCH_TOGGLED", Map.of("enabled", true)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_NOT_REGISTERED");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void unregisteredC2AccountListAddIsRejectedBeforeTheMutationCanCommit() {
        when(mapper.findActiveSchema("admin.account_list_upserted")).thenReturn(null);

        assertThatThrownBy(() -> service.publish(
                "USER_ACCOUNT_LIST", "52", "admin.account_list_upserted", Map.of(
                        "userId", 52L,
                        "kind", "BLOCK",
                        "reason", "Confirmed account takeover evidence",
                        "idempotencyKey", "c2-block-52",
                        "expiresAt", "2026-07-30T12:00:00",
                        "sessionsRevoked", true)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_NOT_REGISTERED");
        verify(mapper, never()).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void registeredC2AccountListAddUsesTheCanonicalA4PayloadContract() throws Exception {
        when(mapper.findActiveSchema("admin.account_list_upserted"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 301, true));
        when(mapper.listActiveProperties("admin.account_list_upserted")).thenReturn(List.of(
                required("kind", "enum"),
                required("reason", "string"),
                required("idempotency_key", "id"),
                new EventOutboxMapper.SchemaPropertyGateRow("expires_at", "timestamp", false),
                required("sessions_revoked", "boolean")));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish("USER_ACCOUNT_LIST", "52", "admin.account_list_upserted", Map.of(
                "userId", 52L,
                "kind", "BLOCK",
                "reason", "Confirmed account takeover evidence",
                "idempotencyKey", "c2-block-52",
                "expiresAt", "2026-07-30T12:00:00",
                "sessionsRevoked", true));

        verify(mapper).insertEvent(
                eq(eventId), eq("USER_ACCOUNT_LIST"), eq("52"), eq("admin.account_list_upserted"),
                eq("admin.account_list_upserted"), eq("phase_admin"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(301), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("user_id").asLong()).isEqualTo(52L);
        assertThat(envelope.path("idempotency_key").asText()).isEqualTo("c2-block-52");
        assertThat(envelope.path("sessions_revoked").asBoolean()).isTrue();
    }

    @Test
    void governedE1ProductEventUsesRegisteredSchemaAndCanonicalEnvelope() throws Exception {
        when(mapper.findActiveSchema("admin.product_listed"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("phase_admin", 40, true));
        when(mapper.listActiveProperties("admin.product_listed")).thenReturn(List.of(
                required("sku_key", "id"),
                required("before_status", "enum"),
                required("after_status", "enum"),
                required("operator", "id"),
                required("reason", "string")));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish("DEVICE_SKU", "sku-1", "admin.product_listed", Map.of(
                "sku_key", "sku-1",
                "before_status", "pending",
                "after_status", "on",
                "operator", "superadmin",
                "reason", "Publish eligible catalogue item"));

        verify(mapper).insertEvent(
                eq(eventId), eq("DEVICE_SKU"), eq("sku-1"), eq("admin.product_listed"),
                eq("admin.product_listed"), eq("phase_admin"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(40), eq(true), eq(true), payloadCaptor.capture());
        JsonNode envelope = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(envelope.path("event_name").asText()).isEqualTo("admin.product_listed");
        assertThat(envelope.path("sku_key").asText()).isEqualTo("sku-1");
        assertThat(envelope.path("schema_revision").asInt()).isEqualTo(40);
        assertThat(envelope.path("is_server_authoritative").asBoolean()).isTrue();
    }

    @Test
    void internalDeviceMessagesRemainOperationalWithoutPollutingA4Analytics() throws Exception {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        String eventId = service.publish(
                "JANUS_DEVICE", "sid-1", "JANUS_DEVICE_STATUS_REQUESTED", Map.of("sid", "sid-1"));

        verify(mapper, never()).findActiveSchema(anyString());
        verify(mapper).insertEvent(
                eq(eventId), eq("JANUS_DEVICE"), eq("sid-1"), eq("JANUS_DEVICE_STATUS_REQUESTED"),
                eq("device.janus_device_status_requested"), eq("internal"), eq("SYSTEM"), eq(0), anyString(),
                eq(true), eq(null), eq(false), eq(false), payloadCaptor.capture());
        assertThat(objectMapper.readTree(payloadCaptor.getValue()).has("schema_revision")).isFalse();
    }

    private EventOutboxMapper.SchemaPropertyGateRow required(String name, String type) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, true);
    }

    private List<EventOutboxMapper.SchemaPropertyGateRow> reportExportProperties(
            boolean includeArtifactEvidence) {
        List<EventOutboxMapper.SchemaPropertyGateRow> properties = new java.util.ArrayList<>(List.of(
                required("report_id", "id"),
                required("export_type", "enum"),
                required("scope", "string"),
                required("row_count", "number"),
                required("contains_pii", "boolean"),
                required("masking_policy", "enum"),
                required("operator", "string"),
                required("reason", "string"),
                required("format", "enum"),
                new EventOutboxMapper.SchemaPropertyGateRow("template_code", "enum", false),
                new EventOutboxMapper.SchemaPropertyGateRow("jurisdiction_code", "string", false),
                new EventOutboxMapper.SchemaPropertyGateRow("disclosure_version", "string", false)));
        if (includeArtifactEvidence) {
            properties.add(new EventOutboxMapper.SchemaPropertyGateRow(
                    "artifact_store", "string", false));
            properties.add(new EventOutboxMapper.SchemaPropertyGateRow(
                    "artifact_sha256", "string", false));
            properties.add(new EventOutboxMapper.SchemaPropertyGateRow(
                    "artifact_size_bytes", "number", false));
        }
        return properties;
    }

    private Map<String, Object> reportExportPayload(boolean includeArtifactEvidence) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("reportId", "EXP-L2-302");
        payload.put("exportType", "FUNNEL_COHORT");
        payload.put("scope", "当前 cohort 窗口；切片=全部");
        payload.put("rowCount", 6L);
        payload.put("containsPii", false);
        payload.put("maskingPolicy", "NONE");
        payload.put("operator", "admin:1");
        payload.put("reason", "export the current L2 slice");
        payload.put("format", "CSV");
        if (includeArtifactEvidence) {
            payload.put("artifactStore", "MINIO");
            payload.put("artifactSha256", "a".repeat(64));
            payload.put("artifactSizeBytes", 512L);
        }
        return payload;
    }
}
