package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsEventCenterServiceTest {
    private final FakeConfigFacade configFacade = new FakeConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsEventCenterService service = new OpsEventCenterService(
            configFacade,
            auditLogService,
            OpsReadTimeSeedPolicy.enabledForDirectConstruction());

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
    }

    @Test
    void overviewIsServerCanonicalAndDoesNotExposeSunsetDomainsAsActive() {
        ApiResult<EventCenterOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        EventCenterOverview overview = result.getData();
        assertThat(overview.stats().todayEvents()).isEqualTo("4.2M");
        assertThat(overview.registeredDomains()).contains("app", "admin", "nex");
        assertThat(overview.pendingDomains()).contains("content", "notification", "disclosure", "learn");
        assertThat(overview.pendingDomains()).doesNotContain("premium", "points", "nexv2");
        assertThat(overview.sunsetDomains()).contains("premium", "points", "nexv2");
        assertThat(overview.domainExtensions()).flatExtracting(EventCenterOverview.EventDomainExtensionBatch::newDomains)
                .extracting(EventCenterOverview.EventDomainItem::name)
                .doesNotContain("premium", "points", "nexv2");
        assertThat(overview.recentLogs()).hasSize(1);
        assertThat(overview.topActions()).hasSize(1);
    }

    @Test
    void paramUpdateRequiresIdempotencyKeyReasonAndOperator() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("120 秒", "adjust launch KPI", "superadmin");

        ApiResult<EventCenterOverview.EventDimensionParam> noKey = service.updateParam(" ", "day0", request);
        ApiResult<EventCenterOverview.EventDimensionParam> noReason =
                service.updateParam("idem-a4", "day0", new EventCenterMutationRequest("120 秒", " ", "superadmin"));
        ApiResult<EventCenterOverview.EventDimensionParam> noOperator =
                service.updateParam("idem-a4", "day0", new EventCenterMutationRequest("120 秒", "reason", " "));

        assertThat(noKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(noReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(noOperator.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void paramUpdatePersistsConfigAndWritesAudit() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("120 秒", "phase launch change", "superadmin");

        ApiResult<EventCenterOverview.EventDimensionParam> result =
                service.updateParam("idem-a4-param", "day0", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("120 秒");
        assertThat(configFacade.values).containsEntry("admin.a4.event.kpi.day0", "120 秒");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A4_EVENT_PARAM_CHANGED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A4_EVENT_CENTER_PARAM");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("paramKey", "day0")
                .containsEntry("idempotencyKey", "idem-a4-param");
    }

    @Test
    void lockedRetentionAndRawJsonValuesAreRejected() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("D1/D7", "try change", "superadmin");
        EventCenterMutationRequest rawJson = new EventCenterMutationRequest("{\"window\":90}", "manual json", "superadmin");

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
        EventCenterMutationRequest pii = new EventCenterMutationRequest("user.phone_plaintext", "bad schema", "superadmin");
        EventCenterMutationRequest ok = new EventCenterMutationRequest("schema v4", "new event batch", "superadmin");

        ApiResult<EventCenterOverview> rejected = service.registerSchema("idem-a4-schema-1", pii);
        ApiResult<EventCenterOverview> accepted = service.registerSchema("idem-a4-schema-2", ok);

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(rejected.getMessage()).isEqualTo("A4_SCHEMA_PII_REJECTED");
        assertThat(accepted.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("admin.a4.event.schema_version", "schema v4");
    }

    @Test
    void domainExtensionRegistrationRejectsSunsetDomains() {
        EventCenterMutationRequest sunset = new EventCenterMutationRequest("premium.checkout_restored", "restore", "superadmin");
        EventCenterMutationRequest ok = new EventCenterMutationRequest("content.experiment_locked", "new content event", "superadmin");

        ApiResult<EventCenterOverview.EventDomainExtensionBatch> rejected =
                service.registerDomainExtension("idem-a4-batch-1", sunset);
        ApiResult<EventCenterOverview.EventDomainExtensionBatch> accepted =
                service.registerDomainExtension("idem-a4-batch-2", ok);

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(rejected.getMessage()).isEqualTo("SUNSET_CAPABILITY_READONLY");
        assertThat(accepted.getCode()).isZero();
        assertThat(configFacade.values).containsEntry(
                "admin.a4.event.batch.content_experiment_locked.status",
                "registered");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
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
