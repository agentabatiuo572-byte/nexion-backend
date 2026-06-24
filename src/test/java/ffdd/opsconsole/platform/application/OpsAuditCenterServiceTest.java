package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.mapper.AuditConfirmCategoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationHistoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsAuditCenterServiceTest {
    private final InMemoryPlatformConfigRepository repository = new InMemoryPlatformConfigRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AuditOperationTicketMapper ticketMapper = mock(AuditOperationTicketMapper.class);
    private final AuditOperationHistoryMapper historyMapper = mock(AuditOperationHistoryMapper.class);
    private final AuditConfirmCategoryMapper confirmCategoryMapper = mock(AuditConfirmCategoryMapper.class);
    private final OpsAuditCenterService service =
            new OpsAuditCenterService(repository, auditLogService, ticketMapper, historyMapper, confirmCategoryMapper);

    @BeforeEach
    void setUp() {
        AuditStatsSummaryResponse summary = new AuditStatsSummaryResponse();
        summary.setTotal(3482L);
        summary.setByResult(List.of());
        summary.setByRiskLevel(List.of());
        when(auditLogService.summary(any(AuditStatsQueryRequest.class))).thenReturn(summary);
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(new AuditLogRecord()));
        when(auditLogService.topActions(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("A2_OPERATION_APPROVED", 2L)));
    }

    @Test
    void overviewReturnsServerCanonicalA2QueueAndStats() {
        ApiResult<AuditCenterOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().pendingTickets()).isEqualTo(14);
        assertThat(result.getData().stats().fundTickets()).isEqualTo(5);
        assertThat(result.getData().stats().sosTickets()).isEqualTo(1);
        assertThat(result.getData().stats().todayAuditEvents()).isEqualTo(3482L);
        assertThat(result.getData().operationQueue()).extracting(AuditCenterOverview.AuditOperationTicket::id)
                .contains("WO-8852", "WO-8838");
        assertThat(result.getData().mechanismParams()).extracting(AuditCenterOverview.AuditMechanismParam::key)
                .contains("ttl", "retention", "schema");
        assertThat(result.getData().recentLogs()).hasSize(1);
        assertThat(result.getData().topActions()).hasSize(1);
    }

    @Test
    void approveRequiresIdempotencyKeyAndReason() {
        AuditOperationDecisionRequest request = new AuditOperationDecisionRequest("verified", "superadmin");

        ApiResult<AuditCenterOverview.AuditOperationTicket> noKey = service.approve(" ", "WO-8852", request);
        ApiResult<AuditCenterOverview.AuditOperationTicket> noReason =
                service.approve("idem-1", "WO-8852", new AuditOperationDecisionRequest(" ", "superadmin"));

        assertThat(noKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(noReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void approvePersistsTerminalStatusAndWritesA2Audit() {
        AuditOperationDecisionRequest request = new AuditOperationDecisionRequest("verified by treasury", "superadmin");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.approve("idem-a2-1", "WO-8852", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("approved");
        assertThat(repository.items.get("admin.a2.operation.WO-8852.status").configValue()).isEqualTo("approved");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_OPERATION_APPROVED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A2_OPERATION");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("operationId", "WO-8852")
                .containsEntry("idempotencyKey", "idem-a2-1");
    }

    @Test
    void terminalOperationCannotBeDecidedTwice() {
        AuditOperationDecisionRequest request = new AuditOperationDecisionRequest("verified", "superadmin");

        service.reject("idem-a2-1", "WO-8851", request);
        ApiResult<AuditCenterOverview.AuditOperationTicket> result =
                service.approve("idem-a2-2", "WO-8851", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("A2_OPERATION_ALREADY_TERMINAL");
    }

    @Test
    void mechanismParamUpdatePersistsConfigAndWritesAudit() {
        AuditMechanismParamUpdateRequest request =
                new AuditMechanismParamUpdateRequest("12 字", "tighten confirmation reason", "superadmin");

        ApiResult<AuditCenterOverview.AuditMechanismParam> result =
                service.updateMechanismParam("idem-param-1", "ttl", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("12 字");
        assertThat(repository.items.get("admin.a2.reason_min_chars").configValue()).isEqualTo("12 字");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_MECHANISM_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("paramKey", "ttl");
    }

    @Test
    void mechanismParamRejectsOutOfRangeValue() {
        AuditMechanismParamUpdateRequest request =
                new AuditMechanismParamUpdateRequest("6 字", "too short", "superadmin");

        ApiResult<AuditCenterOverview.AuditMechanismParam> result =
                service.updateMechanismParam("idem-param-1", "ttl", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static final class InMemoryPlatformConfigRepository implements PlatformConfigRepository {
        private final Map<String, PlatformConfigItem> items = new LinkedHashMap<>();
        private long sequence = 1L;

        @Override
        public Optional<PlatformConfigItem> findActiveByKey(String configKey) {
            return Optional.ofNullable(items.get(configKey));
        }

        @Override
        public List<PlatformConfigItem> findActiveByGroups(Collection<String> configGroups) {
            return items.values().stream()
                    .filter(item -> configGroups.contains(item.configGroup()))
                    .toList();
        }

        @Override
        public PlatformConfigItem save(PlatformConfigItem item) {
            PlatformConfigItem saved = item.id() == null
                    ? new PlatformConfigItem(
                            sequence++,
                            item.configKey(),
                            item.configValue(),
                            item.valueType(),
                            item.configGroup(),
                            item.visibility(),
                            item.remark(),
                            item.status(),
                            LocalDateTime.now(),
                            LocalDateTime.now())
                    : item;
            items.put(saved.configKey(), saved);
            return saved;
        }
    }
}
