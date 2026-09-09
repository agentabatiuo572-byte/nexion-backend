package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.ExistingSchemaPropertyRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class OpsEventCenterServiceTest {
    private final FakeConfigFacade configFacade = new FakeConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final EventGovernanceMapper governanceMapper = mock(EventGovernanceMapper.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsEventCenterService service = new OpsEventCenterService(
            configFacade,
            auditLogService,
            OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            governanceMapper,
            idempotencyService);

    @BeforeEach
    void setUp() {
        AuditStatsSummaryResponse summary = new AuditStatsSummaryResponse();
        summary.setTotal(4200000L);
        when(auditLogService.summary(any(AuditStatsQueryRequest.class))).thenReturn(summary);
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(new AuditLogRecord()));
        when(auditLogService.topActions(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("A4_EVENT_PARAM_CHANGED", 3L)));
        when(auditLogService.countActionsByPrefixes(any(AuditStatsQueryRequest.class), any()))
                .thenReturn(List.of(
                        new AuditStatsBucket("app", 120L),
                        new AuditStatsBucket("admin", 80L),
                        new AuditStatsBucket("nex", 40L)));
        when(governanceMapper.currentRevision()).thenReturn(6);
        when(governanceMapper.lockCurrentRevision()).thenReturn(6);
        when(governanceMapper.countEventsSince(any())).thenReturn(97L);
        when(governanceMapper.countEventsByFamilySince(any())).thenReturn(List.of(
                new EventGovernanceMapper.EventFamilyCount("risk", 31L),
                new EventGovernanceMapper.EventFamilyCount("phase_admin", 66L)));
        when(governanceMapper.listSchemas(anyInt())).thenReturn(List.of());
        when(governanceMapper.listDomainExtensions(anyInt())).thenReturn(List.of());
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(4);
            return action.get();
        });
    }

    @Test
    void overviewIsServerCanonicalAndDoesNotExposeSunsetDomainsAsActive() {
        when(governanceMapper.listDomainExtensions(anyInt())).thenReturn(i3CompletedExtensions());

        ApiResult<EventCenterOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        EventCenterOverview overview = result.getData();
        assertThat(overview.stats().todayEvents()).isEqualTo("97");
        assertThat(overview.stats().todayAuditEvents()).isEqualTo(4200000L);
        assertThat(overview.commonFields())
                .extracting(EventCenterOverview.EventCommonField::key)
                .containsExactly(
                        "event_id", "event_name", "ts", "identity", "session_id",
                        "phase", "cohort", "attribution", "client", "is_server_authoritative");
        assertThat(overview.registeredDomains()).contains("app", "admin", "nex", "disclosure", "notification");
        assertThat(overview.pendingDomains()).contains("content", "learn");
        assertThat(overview.pendingDomains()).doesNotContain("notification", "disclosure", "premium", "points", "nexv2");
        assertThat(overview.stats().registeredDomains()).isEqualTo(overview.registeredDomains().size());
        assertThat(overview.stats().pendingDomains()).isEqualTo(overview.pendingDomains().size());
        assertThat(overview.sunsetDomains()).contains("premium", "points", "nexv2");
        assertThat(overview.domainExtensions()).flatExtracting(EventCenterOverview.EventDomainExtensionBatch::newDomains)
                .extracting(EventCenterOverview.EventDomainItem::name)
                .doesNotContain("premium", "points", "nexv2");
        assertThat(overview.recentLogs()).hasSize(1);
        assertThat(overview.topActions()).hasSize(1);
        EventCenterOverview.EventDomainExtensionBatch jSchema = overview.domainExtensions().stream()
                .filter(batch -> "j-schema".equals(batch.id()))
                .findFirst()
                .orElseThrow();
        assertThat(jSchema.state()).isEqualTo("inprogress");
        assertThat(jSchema.newDomains())
                .filteredOn(item -> "risk.tamper_detected".equals(item.name()))
                .singleElement()
                .extracting(EventCenterOverview.EventDomainItem::n)
                .isEqualTo(true);
    }

    @Test
    void notificationRemainsPendingWithoutCompletedSchemaExtensions() {
        EventCenterOverview overview = service.overview().getData();

        assertThat(overview.registeredDomains()).doesNotContain("notification");
        assertThat(overview.pendingDomains()).contains("notification");
    }

    @Test
    void partiallyCompletedNotificationDomainIsPendingAndNotDoubleCountedAsRegistered() {
        List<EventGovernanceMapper.DomainExtensionRecord> extensions = new java.util.ArrayList<>(i3CompletedExtensions());
        EventGovernanceMapper.DomainExtensionRecord open = extensions.get(2);
        extensions.set(2, new EventGovernanceMapper.DomainExtensionRecord(
                open.id(), open.domainName(), open.eventName(), open.producer(), open.consumer(), "REGISTERED"));
        when(governanceMapper.listDomainExtensions(anyInt())).thenReturn(extensions);

        EventCenterOverview overview = service.overview().getData();

        assertThat(overview.registeredDomains()).doesNotContain("notification");
        assertThat(overview.pendingDomains()).contains("notification");
    }

    @Test
    void paramUpdateRequiresIdempotencyKeyAndReason() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("120 秒", "adjust launch KPI");

        ApiResult<EventCenterOverview.EventDimensionParam> noKey = service.updateParam(" ", "day0", request);
        ApiResult<EventCenterOverview.EventDimensionParam> noReason =
                service.updateParam("idem-a4", "day0", new EventCenterMutationRequest("120 秒", " "));

        assertThat(noKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(noReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void paramUpdatePersistsConfigAndWritesAudit() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("120 秒", "phase launch change");

        ApiResult<EventCenterOverview.EventDimensionParam> result =
                service.updateParam("idem-a4-param", "day0", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("120 秒");
        assertThat(configFacade.values).containsEntry("admin.a4.event.kpi.day0", "120 秒");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A4_EVENT_PARAM_CHANGED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A4_EVENT_CENTER_PARAM");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("paramKey", "day0")
                .containsEntry("idempotencyKey", "idem-a4-param");
    }

    @Test
    void overviewUsesPrdDefaultsWhenConfigRowsAreAbsent() {
        EventCenterOverview overview = service.overview().getData();

        assertThat(overview.dimensionParams())
                .extracting(EventCenterOverview.EventDimensionParam::key, EventCenterOverview.EventDimensionParam::value)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("day0", "90 秒"),
                        org.assertj.core.groups.Tuple.tuple("retention", "D1·D7·D30"),
                        org.assertj.core.groups.Tuple.tuple("event_retention", "13 个月"),
                        org.assertj.core.groups.Tuple.tuple("sampling", "浏览/会话 10% · 资金/风控/转化 100%"));
    }

    @Test
    void paramUpdateRejectsMalformedValuesAndProtectedSampling() {
        ApiResult<EventCenterOverview.EventDimensionParam> badDay0 = service.updateParam(
                "idem-a4-bad-day0", "day0",
                new EventCenterMutationRequest("abc", "invalid day0 value"));
        ApiResult<EventCenterOverview.EventDimensionParam> shortRetention = service.updateParam(
                "idem-a4-bad-retention", "event_retention",
                new EventCenterMutationRequest("12 个月", "retention below minimum"));
        ApiResult<EventCenterOverview.EventDimensionParam> sampledMoney = service.updateParam(
                "idem-a4-bad-sampling", "sampling",
                new EventCenterMutationRequest("浏览 10% · 资金 1%", "money must stay complete"));

        assertThat(badDay0.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(badDay0.getMessage()).isEqualTo("A4_DAY0_VALUE_INVALID");
        assertThat(shortRetention.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(shortRetention.getMessage()).isEqualTo("A4_EVENT_RETENTION_INVALID");
        assertThat(sampledMoney.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(sampledMoney.getMessage()).isEqualTo("A4_PROTECTED_EVENT_SAMPLING_INVALID");
    }

    @Test
    void mutationReasonMustMeetConfirmWithReasonContract() {
        ApiResult<EventCenterOverview.EventDimensionParam> result = service.updateParam(
                "idem-a4-short-reason", "day0",
                new EventCenterMutationRequest("120 秒", "short"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("A4_REASON_LENGTH_INVALID");
    }

    @Test
    void lockedRetentionAndRawJsonValuesAreRejected() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("D1/D7", "try change");
        EventCenterMutationRequest rawJson = new EventCenterMutationRequest("{\"window\":90}", "manual json");

        ApiResult<EventCenterOverview.EventDimensionParam> locked =
                service.updateParam("idem-a4", "retention", request);
        ApiResult<EventCenterOverview.EventDimensionParam> json =
                service.updateParam("idem-a4", "day0", rawJson);

        assertThat(locked.getCode()).isEqualTo(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus());
        assertThat(locked.getMessage()).isEqualTo("A4_EVENT_PARAM_LOCKED");
        assertThat(json.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(json.getMessage()).isEqualTo("A4_RAW_JSON_REJECTED");
    }

    @Test
    void schemaRegistrationRejectsPiiAndWritesA4Audit() {
        EventSchemaRegistrationRequest pii = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "L1 BI", "phone_plaintext", "string",
                true, false, "10%", "v6", "reject plaintext PII field");
        EventSchemaRegistrationRequest ok = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "L1 BI", "session_id", "id",
                false, false, "10%", "v6", "register session identity field");
        EventGovernanceMapper.EventSchemaRecord inserted = new EventGovernanceMapper.EventSchemaRecord(
                101L, "app.session_started", "app", "acquisition", "client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 7);
        when(governanceMapper.findSchema("app.session_started")).thenReturn(null, inserted);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);

        ApiResult<EventCenterOverview> rejected = service.registerSchema("idem-a4-schema-1", pii);
        ApiResult<EventCenterOverview> accepted = service.registerSchema("idem-a4-schema-2", ok);

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(rejected.getMessage()).isEqualTo("A4_SCHEMA_PII_REJECTED");
        assertThat(accepted.getCode()).isZero();
        verify(governanceMapper).insertSchema(
                "app.session_started", "app", "acquisition", "client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 7, "authenticated-admin",
                "register session identity field");
        verify(governanceMapper).insertProperty(101L, "session_id", "id", 7);
    }

    @Test
    void schemaRegistrationRequiresAnExplicitConsumer() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", " ", "session_id", "id",
                false, false, "10%", "v6", "register session identity field");

        ApiResult<EventCenterOverview> result = service.registerSchema("idem-a4-schema-consumer", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("A4_SCHEMA_CONSUMER_REQUIRED");
    }

    @Test
    void schemaRegistrationAllowsHashedIdentifiersInsteadOfRawPii() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "app.identity_linked", "app", "server+client", "L1 BI", "phone_hash", "string",
                false, false, "10%", "v6", "register privacy safe identity hash");
        EventGovernanceMapper.EventSchemaRecord inserted = new EventGovernanceMapper.EventSchemaRecord(
                102L, "app.identity_linked", "app", "acquisition", "server+client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 7);
        when(governanceMapper.findSchema("app.identity_linked")).thenReturn(null, inserted);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);

        ApiResult<EventCenterOverview> result = service.registerSchema("idem-a4-schema-hash", request);

        assertThat(result.getCode()).isZero();
        verify(governanceMapper).insertProperty(102L, "phone_hash", "string", 7);
    }

    @Test
    void schemaRegistrationAcceptsPrdSingleTokenPastTenseEventNames() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "commission.paid", "commission", "server", "L1 BI", "commission_id", "id",
                false, true, "100%", "v6", "register canonical commission event");
        EventGovernanceMapper.EventSchemaRecord inserted = new EventGovernanceMapper.EventSchemaRecord(
                103L, "commission.paid", "commission", "monetization", "server", "L1 BI", true,
                "100%", 7);
        when(governanceMapper.findSchema("commission.paid")).thenReturn(null, inserted);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);

        ApiResult<EventCenterOverview> result = service.registerSchema("idem-a4-schema-single-action", request);

        assertThat(result.getCode()).isZero();
        verify(governanceMapper).insertProperty(103L, "commission_id", "id", 7);
    }

    @Test
    void extendingSchemaCarriesEveryExistingAllowlistedPropertyIntoTheNewRevision() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "L1 BI", "device_id", "id",
                false, false, "10%", "v6", "extend session event without dropping session_id");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                106L, "app.session_started", "app", "acquisition", "client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 6);
        when(governanceMapper.findSchema("app.session_started")).thenReturn(existing);
        when(governanceMapper.countProperty(106L, "device_id")).thenReturn(0);
        when(governanceMapper.countLiveProperties(106L)).thenReturn(1);
        when(governanceMapper.countActiveProperties(106L, 6)).thenReturn(1);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);
        when(governanceMapper.carryForwardProperties(106L, 6, 7)).thenReturn(1);
        when(governanceMapper.updateSchemaRevision(
                106L, 7, "authenticated-admin", "extend session event without dropping session_id"))
                .thenReturn(1);

        ApiResult<EventCenterOverview> result = service.registerSchema("idem-a4-schema-extension", request);

        assertThat(result.getCode()).isZero();
        org.mockito.InOrder writes = org.mockito.Mockito.inOrder(governanceMapper);
        writes.verify(governanceMapper).advanceRevision(6, 7);
        writes.verify(governanceMapper).carryForwardProperties(106L, 6, 7);
        writes.verify(governanceMapper).updateSchemaRevision(
                106L, 7, "authenticated-admin", "extend session event without dropping session_id");
        writes.verify(governanceMapper).insertProperty(106L, "device_id", "id", 7);
    }

    @Test
    void schemaExtensionUpdateGuardStillThrowsSoTheTransactionalRegistrationRollsBack() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "L1 BI", "device_id", "id",
                false, false, "10%", "v6", "reject stale schema update after carry forward");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                107L, "app.session_started", "app", "acquisition", "client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 6);
        when(governanceMapper.findSchema("app.session_started")).thenReturn(existing);
        when(governanceMapper.countProperty(107L, "device_id")).thenReturn(0);
        when(governanceMapper.countLiveProperties(107L)).thenReturn(1);
        when(governanceMapper.countActiveProperties(107L, 6)).thenReturn(1);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);
        when(governanceMapper.carryForwardProperties(107L, 6, 7)).thenReturn(1);
        when(governanceMapper.updateSchemaRevision(
                107L, 7, "authenticated-admin", "reject stale schema update after carry forward"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.registerSchema("idem-a4-schema-extension-stale", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A4_SCHEMA_UPDATE_FAILED");

        verify(governanceMapper).carryForwardProperties(107L, 6, 7);
        verify(governanceMapper, never()).insertProperty(107L, "device_id", "id", 7);
        verify(auditLogService, never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void existingSchemaPropertyExtensionUsesMigrationOwnedMetadataWithoutResubmittingIt() {
        ExistingSchemaPropertyRequest request = new ExistingSchemaPropertyRequest(
                "quest.completed", "instance_key", "string", "v104",
                "add an instance key to the governed quest event");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                108L, "quest.completed", "quest", "engagement", "QuestCompletionFactConsumer", "H3",
                true, "100%", 104);
        when(governanceMapper.lockCurrentRevision()).thenReturn(104);
        when(governanceMapper.findSchema("quest.completed")).thenReturn(existing);
        when(governanceMapper.countProperty(108L, "instance_key")).thenReturn(0);
        when(governanceMapper.countLiveProperties(108L)).thenReturn(2);
        when(governanceMapper.countActiveProperties(108L, 104)).thenReturn(2);
        when(governanceMapper.advanceRevision(104, 105)).thenReturn(1);
        when(governanceMapper.carryForwardProperties(108L, 104, 105)).thenReturn(2);
        when(governanceMapper.updateSchemaRevision(
                108L, 105, "authenticated-admin", "add an instance key to the governed quest event"))
                .thenReturn(1);

        ApiResult<EventCenterOverview> result = service.addSchemaProperty("idem-a4-quest-instance", request);

        assertThat(result.getCode()).isZero();
        org.mockito.InOrder writes = org.mockito.Mockito.inOrder(governanceMapper);
        writes.verify(governanceMapper).advanceRevision(104, 105);
        writes.verify(governanceMapper).carryForwardProperties(108L, 104, 105);
        writes.verify(governanceMapper).updateSchemaRevision(
                108L, 105, "authenticated-admin", "add an instance key to the governed quest event");
        writes.verify(governanceMapper).insertProperty(108L, "instance_key", "string", 105);
    }

    @Test
    void existingSchemaPropertyExtensionFailsClosedWhenNotEveryActiveFieldCarriesForward() {
        ExistingSchemaPropertyRequest request = new ExistingSchemaPropertyRequest(
                "quest.completed", "instance_key", "string", "v104",
                "reject an incomplete governed schema snapshot");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                109L, "quest.completed", "quest", "engagement", "QuestCompletionFactConsumer", "H3",
                true, "100%", 104);
        when(governanceMapper.lockCurrentRevision()).thenReturn(104);
        when(governanceMapper.findSchema("quest.completed")).thenReturn(existing);
        when(governanceMapper.countProperty(109L, "instance_key")).thenReturn(0);
        when(governanceMapper.countLiveProperties(109L)).thenReturn(2);
        when(governanceMapper.countActiveProperties(109L, 104)).thenReturn(2);
        when(governanceMapper.advanceRevision(104, 105)).thenReturn(1);
        when(governanceMapper.carryForwardProperties(109L, 104, 105)).thenReturn(1);

        assertThatThrownBy(() -> service.addSchemaProperty("idem-a4-quest-incomplete", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_CARRY_FORWARD_FAILED");

        verify(governanceMapper, never()).updateSchemaRevision(
                109L, 105, "authenticated-admin", "reject an incomplete governed schema snapshot");
        verify(governanceMapper, never()).insertProperty(109L, "instance_key", "string", 105);
        verify(auditLogService, never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void zeroFieldSchemaCanReceiveItsFirstCustomField() {
        ExistingSchemaPropertyRequest request = new ExistingSchemaPropertyRequest(
                "app.dau", "session_id", "id", "v285", "add the first custom field to a shared envelope event");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                111L, "app.dau", "app", "acquisition", "client", "L1 BI", false, "10%", 285);
        when(governanceMapper.lockCurrentRevision()).thenReturn(285);
        when(governanceMapper.findSchema("app.dau")).thenReturn(existing);
        when(governanceMapper.countProperty(111L, "session_id")).thenReturn(0);
        when(governanceMapper.countLiveProperties(111L)).thenReturn(0);
        when(governanceMapper.countActiveProperties(111L, 285)).thenReturn(0);
        when(governanceMapper.advanceRevision(285, 286)).thenReturn(1);
        when(governanceMapper.carryForwardProperties(111L, 285, 286)).thenReturn(0);
        when(governanceMapper.updateSchemaRevision(
                111L, 286, "authenticated-admin", "add the first custom field to a shared envelope event"))
                .thenReturn(1);

        ApiResult<EventCenterOverview> result = service.addSchemaProperty("idem-a4-first-field", request);

        assertThat(result.getCode()).isZero();
        verify(governanceMapper).insertProperty(111L, "session_id", "id", 286);
    }

    @Test
    void legacyPropertiesAtAnotherRevisionFailClosedInsteadOfBeingDropped() {
        ExistingSchemaPropertyRequest request = new ExistingSchemaPropertyRequest(
                "store.viewed", "session_id", "id", "v285", "reject a legacy property revision drift");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                112L, "store.viewed", "store", "conversion", "client", "L1 BI", false, "10%", 285);
        when(governanceMapper.lockCurrentRevision()).thenReturn(285);
        when(governanceMapper.findSchema("store.viewed")).thenReturn(existing);
        when(governanceMapper.countProperty(112L, "session_id")).thenReturn(0);
        when(governanceMapper.countLiveProperties(112L)).thenReturn(2);
        when(governanceMapper.countActiveProperties(112L, 285)).thenReturn(0);
        when(governanceMapper.advanceRevision(285, 286)).thenReturn(1);

        assertThatThrownBy(() -> service.addSchemaProperty("idem-a4-revision-drift", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_REVISION_DRIFT");

        verify(governanceMapper, never()).carryForwardProperties(112L, 285, 286);
        verify(governanceMapper, never()).insertProperty(112L, "session_id", "id", 286);
        verify(auditLogService, never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void genericSchemaRegistrationRejectsExistingConsumerMismatch() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "new consumer", "device_id", "id",
                false, false, "10%", "v6", "reject consumer mutation through property registration");
        EventGovernanceMapper.EventSchemaRecord existing = new EventGovernanceMapper.EventSchemaRecord(
                110L, "app.session_started", "app", "acquisition", "client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 6);
        when(governanceMapper.findSchema("app.session_started")).thenReturn(existing);
        when(governanceMapper.countProperty(110L, "device_id")).thenReturn(0);

        ApiResult<EventCenterOverview> result = service.registerSchema("idem-a4-schema-consumer-conflict", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("A4_SCHEMA_METADATA_CONFLICT");
        verify(governanceMapper, never()).advanceRevision(6, 7);
    }

    @Test
    void schemaPreviewReadsAnExactActiveSchemaOutsideTheOverviewLimit() {
        EventCenterOverview.EventSchemaRegistration schema = new EventCenterOverview.EventSchemaRegistration(
                "quest.completed", "quest", "engagement", "QuestCompletionFactConsumer", "H3",
                "quest_id:id, instance_key:string", true, "100%", "v104", "2026-09-07 17:00:00", "full", 3);
        when(governanceMapper.findSchemaRegistration("quest.completed")).thenReturn(schema);

        ApiResult<EventCenterOverview.EventSchemaRegistration> result = service.schemaRegistration(" Quest.Completed ");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(schema);
        verify(governanceMapper).findSchemaRegistration("quest.completed");
    }

    @Test
    void schemaPreviewRejectsMalformedNamesAndReturnsControlledNotFound() {
        ApiResult<EventCenterOverview.EventSchemaRegistration> malformed = service.schemaRegistration("quest/anything");
        ApiResult<EventCenterOverview.EventSchemaRegistration> missing = service.schemaRegistration("quest.completed");

        assertThat(malformed.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(malformed.getMessage()).isEqualTo("A4_SCHEMA_EVENT_NAME_INVALID");
        assertThat(missing.getCode()).isEqualTo(404);
        assertThat(missing.getMessage()).isEqualTo("A4_EVENT_SCHEMA_NOT_FOUND");
    }

    @Test
    void registeredDomainExtensionCanBeClosedBySchemaRegistration() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "conversation.session_started", "conversation", "server", "L1 BI", "session_id", "id",
                false, false, "10%", "v6", "close registered domain extension");
        EventGovernanceMapper.EventSchemaRecord inserted = new EventGovernanceMapper.EventSchemaRecord(
                104L, "conversation.session_started", "conversation", "acquisition", "server", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 7);
        when(governanceMapper.countRegistrableDomainExtension(
                "conversation", "conversation.session_started")).thenReturn(1);
        when(governanceMapper.findSchema("conversation.session_started")).thenReturn(null, inserted);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);
        when(governanceMapper.completeDomainExtension(
                "conversation", "conversation.session_started")).thenReturn(1);

        ApiResult<EventCenterOverview> result = service.registerSchema("idem-a4-schema-domain-close", request);

        assertThat(result.getCode()).isZero();
        verify(governanceMapper).completeDomainExtension("conversation", "conversation.session_started");
    }

    @Test
    void concurrentDuplicateFailsTheIdempotentActionSoTheTransactionRollsBackAllSchemaWrites() {
        EventSchemaRegistrationRequest request = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "L1 BI", "session_id", "id",
                false, false, "10%", "v6", "register concurrent schema property");
        EventGovernanceMapper.EventSchemaRecord inserted = new EventGovernanceMapper.EventSchemaRecord(
                105L, "app.session_started", "app", "acquisition", "client", "L1 BI", false,
                "浏览/会话 10% · 资金/风控/转化 100%", 7);
        when(governanceMapper.findSchema("app.session_started")).thenReturn(null, inserted);
        when(governanceMapper.advanceRevision(6, 7)).thenReturn(1);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("concurrent duplicate"))
                .when(governanceMapper).insertProperty(105L, "session_id", "id", 7);

        assertThatThrownBy(() -> service.registerSchema("idem-a4-schema-race", request))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("A4_SCHEMA_DUPLICATE");

        verify(auditLogService, never()).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void domainExtensionRegistrationRejectsSunsetDomains() {
        EventDomainExtensionRequest sunset = new EventDomainExtensionRequest(
                "premium", "premium.checkout_restored", "server", "L1 BI", "restore sunset capability");
        EventDomainExtensionRequest ok = new EventDomainExtensionRequest(
                "conversation", "conversation.session_started", "M3 service", "L1 BI", "register conversation events");
        when(governanceMapper.countDomainExtension("conversation", "conversation.session_started")).thenReturn(0);

        ApiResult<EventCenterOverview.EventDomainExtensionBatch> rejected =
                service.registerDomainExtension("idem-a4-batch-1", sunset);
        ApiResult<EventCenterOverview.EventDomainExtensionBatch> accepted =
                service.registerDomainExtension("idem-a4-batch-2", ok);

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(rejected.getMessage()).isEqualTo("SUNSET_CAPABILITY_READONLY");
        assertThat(accepted.getCode()).isZero();
        verify(governanceMapper).insertDomainExtension(
                "conversation", "conversation.session_started", "M3 service", "L1 BI",
                "authenticated-admin", "register conversation events");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static List<EventGovernanceMapper.DomainExtensionRecord> i3CompletedExtensions() {
        return List.of(
                new EventGovernanceMapper.DomainExtensionRecord(
                        301L, "notification", "notification.delivered",
                        "I3DispatchExecutor", "A4/I3/B3/L", "DONE"),
                new EventGovernanceMapper.DomainExtensionRecord(
                        302L, "notification", "notification.read",
                        "AppNotificationService", "A4/I3/L", "DONE"),
                new EventGovernanceMapper.DomainExtensionRecord(
                        303L, "notification", "notification.swipe_action_taken",
                        "AppNotificationService", "A4/I3/B3/L", "DONE"));
    }

    private static final class FakeConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, String> groups = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
            groups.put(configKey, configGroup);
        }

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (configGroup.equals(groups.get(key))) {
                    result.put(key, value);
                }
            });
            return result;
        }
    }
}
