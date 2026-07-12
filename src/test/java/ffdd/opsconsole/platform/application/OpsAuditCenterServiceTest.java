package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.infrastructure.AuditConfirmCategoryEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationHistoryEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditConfirmCategoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationHistoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsAuditCenterServiceTest {
    private final InMemoryPlatformConfigRepository repository = new InMemoryPlatformConfigRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AuditOperationTicketMapper ticketMapper = mock(AuditOperationTicketMapper.class);
    private final AuditOperationHistoryMapper historyMapper = mock(AuditOperationHistoryMapper.class);
    private final AuditConfirmCategoryMapper confirmCategoryMapper = mock(AuditConfirmCategoryMapper.class);
    private final AuditReplayDispatcher replayDispatcher = mock(AuditReplayDispatcher.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final AuditReplayBusinessPermissionGuard replayBusinessPermissionGuard = mock(AuditReplayBusinessPermissionGuard.class);
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final OpsAuditCenterService service =
            new OpsAuditCenterService(
                    repository,
                    auditLogService,
                    ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                    ticketMapper,
                    historyMapper,
                    confirmCategoryMapper,
                    lockMapper,
                    replayBusinessPermissionGuard,
                    replayDispatcher,
                    objectMapper);
    private final Map<String, AuditOperationTicketEntity> ticketRows = new LinkedHashMap<>();
    private final List<AuditOperationHistoryEntity> historyRows = new ArrayList<>();
    private final List<AuditConfirmCategoryEntity> categoryRows = new ArrayList<>();
    private long entitySequence = 1L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository.items.clear();
        ticketRows.clear();
        historyRows.clear();
        categoryRows.clear();
        entitySequence = 1L;

        AuditStatsSummaryResponse summary = new AuditStatsSummaryResponse();
        summary.setTotal(0L);
        summary.setByResult(List.of());
        summary.setByRiskLevel(List.of());
        when(auditLogService.summary(any(AuditStatsQueryRequest.class))).thenReturn(summary);
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of());
        when(auditLogService.topActions(any(AuditStatsQueryRequest.class))).thenReturn(List.of());

        when(ticketMapper.selectCount(any())).thenAnswer(invocation -> ticketRows.values().stream().filter(OpsAuditCenterServiceTest::active).count());
        when(ticketMapper.insert(any(AuditOperationTicketEntity.class))).thenAnswer(invocation -> {
            AuditOperationTicketEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(entitySequence++);
            }
            ticketRows.put(row.getOperationId(), row);
            return 1;
        });
        when(ticketMapper.selectList(any())).thenAnswer(invocation -> {
            boolean terminalOnly = wrapperValues(invocation.getArgument(0)).stream()
                    .anyMatch(value -> value instanceof Collection<?> values && values.contains("approved"));
            return ticketRows.values().stream()
                    .filter(OpsAuditCenterServiceTest::active)
                    .filter(row -> !terminalOnly || TERMINAL_STATUSES.contains(status(row.getStatus())))
                    .sorted(Comparator.comparing(AuditOperationTicketEntity::getOperationId).reversed())
                    .toList();
        });
        when(ticketMapper.selectActiveByOperationIdForUpdate(any()))
                .thenAnswer(invocation -> ticketRows.get(invocation.getArgument(0)));
        when(ticketMapper.updateById(any(AuditOperationTicketEntity.class))).thenAnswer(invocation -> {
            AuditOperationTicketEntity row = invocation.getArgument(0);
            ticketRows.put(row.getOperationId(), row);
            return 1;
        });

        when(historyMapper.selectCount(any())).thenAnswer(invocation -> historyRows.stream().filter(OpsAuditCenterServiceTest::active).count());
        when(historyMapper.insert(any(AuditOperationHistoryEntity.class))).thenAnswer(invocation -> {
            AuditOperationHistoryEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(entitySequence++);
            }
            historyRows.add(row);
            return 1;
        });
        when(historyMapper.selectList(any())).thenAnswer(invocation -> historyRows.stream()
                .filter(OpsAuditCenterServiceTest::active)
                .sorted(Comparator.comparing(AuditOperationHistoryEntity::getId).reversed())
                .toList());

        when(confirmCategoryMapper.selectCount(any())).thenAnswer(invocation -> categoryRows.stream().filter(OpsAuditCenterServiceTest::active).count());
        when(confirmCategoryMapper.insert(any(AuditConfirmCategoryEntity.class))).thenAnswer(invocation -> {
            AuditConfirmCategoryEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(entitySequence++);
            }
            categoryRows.add(row);
            return 1;
        });
        when(confirmCategoryMapper.selectList(any())).thenAnswer(invocation -> categoryRows.stream()
                .filter(OpsAuditCenterServiceTest::active)
                .sorted(Comparator.comparing(AuditConfirmCategoryEntity::getSortOrder).thenComparing(AuditConfirmCategoryEntity::getId))
                .toList());

        doAnswer(invocation -> null).when(ticketMapper).createTicketTable();
        doAnswer(invocation -> null).when(historyMapper).createHistoryTable();
        doAnswer(invocation -> null).when(confirmCategoryMapper).createConfirmCategoryTable();
        // 批0: approve 回放目标域,dispatch 须返回成功(code=0)否则 NPE/fail
        doReturn(ApiResult.ok()).when(replayDispatcher).dispatch(any(), any());
        doReturn(ApiResult.ok()).when(replayBusinessPermissionGuard).validateProposal(any());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewDoesNotCreateA2BusinessRowsWhenDatabaseIsEmpty() {
        ApiResult<AuditCenterOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().pendingTickets()).isZero();
        assertThat(result.getData().stats().fundTickets()).isZero();
        assertThat(result.getData().stats().sosTickets()).isZero();
        assertThat(result.getData().stats().todayAuditEvents()).isZero();
        assertThat(result.getData().operationQueue()).isEmpty();
        assertThat(result.getData().mechanismParams())
                .extracting(AuditCenterOverview.AuditMechanismParam::key)
                .contains("reason_required", "ttl", "retention", "confirm_list", "schema");
        assertThat(result.getData().recentLogs()).isEmpty();
        assertThat(result.getData().topActions()).isEmpty();
        assertThat(ticketRows).isEmpty();
        assertThat(historyRows).isEmpty();
        assertThat(categoryRows).isEmpty();
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
        putTicket("WO-8852", "提现放行(大额操作确认)", "pending", "fund", true, false);
        AuditOperationDecisionRequest request = new AuditOperationDecisionRequest("verified by treasury", "treasury.approver");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.approve("idem-a2-1", "WO-8852", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("approved");
        assertThat(ticketRows.get("WO-8852").getStatus()).isEqualTo("approved");

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
    void authenticatedProposalCreatorOverridesSpoofedBodyOperator() {
        authenticate("41", "alice.admin");
        AuditOperationProposalRequest request = proposal("mallory");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.createProposal("idem-auth-proposal", request);

        assertThat(result.getCode()).isZero();
        assertThat(ticketRows.get(result.getData().id()).getOperatorName()).isEqualTo("alice.admin");
    }

    @Test
    void authenticatedAdminWithoutUsernameUsesStableAdminIdActor() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("41", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        ApiResult<AuditCenterOverview.AuditOperationTicket> result =
                service.createProposal("idem-admin-id", proposal("mallory"));

        assertThat(result.getCode()).isZero();
        assertThat(ticketRows.get(result.getData().id()).getOperatorName()).isEqualTo("admin:41");
    }

    @Test
    void twoPersonProposerCannotApproveOwnTicketEvenWithSpoofedOperator() {
        putTicket("WO-SELF", "A6 role grants", "pending", "HIGH", true, false);
        ticketRows.get("WO-SELF").setOperatorName("alice.admin");
        ticketRows.get("WO-SELF").setRoleGate("TWO_PERSON");
        authenticate("41", "alice.admin");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.approve(
                "idem-self", "WO-SELF", new AuditOperationDecisionRequest("looks good", "bob.admin"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("A2_TWO_PERSON_SELF_APPROVAL_FORBIDDEN");
        assertThat(ticketRows.get("WO-SELF").getStatus()).isEqualTo("pending");
        verify(replayDispatcher, never()).dispatch(any(), any());
        verify(lockMapper, never()).selectActiveByTicketId("WO-SELF");
        verify(ticketMapper, never()).updateById(ticketRows.get("WO-SELF"));
    }

    @Test
    void proposerMayRejectButAuditUsesAuthenticatedActor() {
        putTicket("WO-REJECT", "A6 role grants", "pending", "HIGH", true, false);
        ticketRows.get("WO-REJECT").setOperatorName("alice.admin");
        ticketRows.get("WO-REJECT").setRoleGate("TWO_PERSON");
        authenticate("41", "alice.admin");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.reject(
                "idem-reject", "WO-REJECT", new AuditOperationDecisionRequest("withdraw unsafe change", "mallory"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("alice.admin");
        verify(replayDispatcher, never()).dispatch(any(), any());
    }

    @Test
    void differentAuthenticatedAdminMayApproveAndIsTheOnlyReplayActor() {
        putTicket("WO-OTHER", "A6 role grants", "pending", "HIGH", true, false);
        ticketRows.get("WO-OTHER").setOperatorName("alice.admin");
        ticketRows.get("WO-OTHER").setRoleGate("TWO_PERSON");
        authenticate("52", "bob.admin");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.approve(
                "idem-other", "WO-OTHER", new AuditOperationDecisionRequest("independent review", "alice.admin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<ffdd.opsconsole.platform.domain.AuditReplayContext> context =
                ArgumentCaptor.forClass(ffdd.opsconsole.platform.domain.AuditReplayContext.class);
        verify(replayDispatcher).dispatch(any(), context.capture());
        assertThat(context.getValue().operator()).isEqualTo("bob.admin");
    }

    @Test
    void terminalOperationCannotBeDecidedTwice() {
        putTicket("WO-8851", "账单手工调整", "pending", "fund", false, false);
        AuditOperationDecisionRequest request = new AuditOperationDecisionRequest("verified", "superadmin");

        service.reject("idem-a2-1", "WO-8851", request);
        ApiResult<AuditCenterOverview.AuditOperationTicket> result =
                service.approve("idem-a2-2", "WO-8851", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("A2_OPERATION_ALREADY_TERMINAL");
    }

    @Test
    void createProposalPersistsBackendPendingTicketAndWritesAudit() {
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "Phase dial 调整",
                "H1 · 周任务倍率",
                "1.25x",
                "1.30x",
                "高翔",
                "增长",
                "param",
                true,
                false,
                "增长 / 超管",
                "本周 KPI 节奏校准",
                "H1",
                null,
                null,
                null);

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.createProposal("idem-proposal-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("pending");
        assertThat(result.getData().action()).isEqualTo("Phase dial 调整");
        assertThat(ticketRows).containsKey(result.getData().id());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_OPERATION_PROPOSED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("operationId", result.getData().id())
                .containsEntry("sourceDomain", "H1")
                .containsEntry("idempotencyKey", "idem-proposal-1");
    }

    @Test
    void createProposalRejectsWhenTargetDomainBusinessPermissionIsMissing() {
        AuditReplayCommand command = new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                "sectionKey", "financials", "action", "publish"));
        doReturn(ApiResult.fail(403, "A2_BUSINESS_PERMISSION_DENIED:content_i4_trust_section_manage"))
                .when(replayBusinessPermissionGuard).validateProposal(command);
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "发布财务信任版块", "financials", "v1", "v2", "proposer", "内容", "param",
                false, false, "合规 / 超管", "发布财务信任版块版本", "I4", command, null, null);

        var result = service.createProposal("idem-business-denied", request);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).contains("content_i4_trust_section_manage");
        verify(ticketMapper, never()).insert(any(AuditOperationTicketEntity.class));
    }

    @Test
    void accountProposalWritesHighRiskAudit() {
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "运营账号创建(A1)",
                "A1联动测试 · support@nexion.io",
                "—",
                "support / MAIL_DISPATCHED",
                "Super Admin",
                "超管",
                "acct",
                false,
                false,
                "超管",
                "新增客服账号入职",
                "A1",
                null,
                null,
                null);

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.createProposal("idem-a1-acct", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().type()).isEqualTo("acct");
        assertThat(result.getData().amplifies()).isFalse();
        assertThat(result.getData().sos()).isFalse();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_OPERATION_PROPOSED");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("operationId", result.getData().id())
                .containsEntry("sourceDomain", "A1")
                .containsEntry("type", "acct");
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

    private void putTicket(String operationId, String action, String status, String type, boolean amplifies, boolean sos) {
        AuditOperationTicketEntity ticket = new AuditOperationTicketEntity();
        ticket.setId(entitySequence++);
        ticket.setOperationId(operationId);
        ticket.setAction(action);
        ticket.setObjectText("explicit-test-row");
        ticket.setBeforeValue("-");
        ticket.setAfterValue("+1");
        ticket.setOperatorName("superadmin");
        ticket.setOperatorRole("超管");
        ticket.setOperationType(type);
        ticket.setAmplifies(amplifies ? 1 : 0);
        ticket.setSos(sos ? 1 : 0);
        ticket.setTimeLabel("刚刚");
        ticket.setMine(0);
        ticket.setRoleGate("超管");
        ticket.setReason("explicit test fixture");
        ticket.setStatus(status);
        ticket.setIsDeleted(0);
        // 批0: approve 要求 ticket 带 commandJson(否则 COMMAND_REQUIRED 422),fixture 补回放指令
        try {
            ticket.setCommandJson(objectMapper.writeValueAsString(
                    new AuditReplayCommand("D", "d2_withdraw_approve", Map.of())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
        ticketRows.put(operationId, ticket);
    }

    private AuditOperationProposalRequest proposal(String operator) {
        return new AuditOperationProposalRequest(
                "A6 role grants", "role:9", "read", "write", operator, "ADMIN", "param",
                true, false, "TWO_PERSON", "grant review", "A",
                new AuditReplayCommand("A", "a6_role_grants_update", Map.of()), null, null);
    }

    private void authenticate(String adminId, String username) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(adminId, null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", username));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static final Set<String> TERMINAL_STATUSES = Set.of("approved", "rejected", "withdrawn", "expired");

    private static String status(String value) {
        return value == null ? "pending" : value;
    }

    private static boolean active(AuditOperationTicketEntity row) {
        return row.getIsDeleted() == null || row.getIsDeleted() == 0;
    }

    private static boolean active(AuditOperationHistoryEntity row) {
        return row.getIsDeleted() == null || row.getIsDeleted() == 0;
    }

    private static boolean active(AuditConfirmCategoryEntity row) {
        return row.getIsDeleted() == null || row.getIsDeleted() == 0;
    }

    private static Collection<Object> wrapperValues(Object wrapper) {
        if (wrapper instanceof LambdaQueryWrapper<?> query) {
            return query.getParamNameValuePairs().values();
        }
        return List.of();
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
