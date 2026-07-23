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
    private final ffdd.opsconsole.shared.idempotency.AdminIdempotencyService idempotencyService =
            mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyService.class);
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
                    objectMapper,
                    idempotencyService);
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
        when(auditLogService.aggregate(any(AuditLogQueryRequest.class))).thenReturn(List.of());
        when(replayBusinessPermissionGuard.validateProposal(
                org.mockito.ArgumentMatchers.nullable(AuditReplayCommand.class)))
                .thenReturn(ApiResult.ok());
        when(replayBusinessPermissionGuard.validateProposalContext(any(AuditOperationProposalRequest.class)))
                .thenReturn(ApiResult.ok(null));

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
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(4);
            return action.get();
        });
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_OPERATION_APPROVED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A2_OPERATION");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("operationId", "WO-8852")
                .containsEntry("idempotencyKey", "idem-a2-1");
    }

    @Test
    void overviewUsesExactAggregateInsteadOfCappedRecentLogList() {
        when(auditLogService.aggregate(any(AuditLogQueryRequest.class))).thenReturn(List.of(
                new ffdd.opsconsole.shared.audit.A2AuditAggregate("SUCCESS", "HIGH", "C2_FREEZE", 701L)));

        ApiResult<AuditCenterOverview> result = service.overview();

        assertThat(result.getData().stats().todayAuditEvents()).isEqualTo(701L);
        assertThat(result.getData().topActions()).containsExactly(new ffdd.opsconsole.shared.audit.AuditStatsBucket("C2_FREEZE", 701L));
    }

    @Test
    void overviewInfersLaterDomainsLAndMForLegacyTickets() {
        putTicket("WO-L", "L2 funnel report", "pending", "param", false, false);
        putTicket("WO-M", "M3 campaign report", "pending", "param", false, false);
        AuditLogQueryRequest l = new AuditLogQueryRequest(); l.setDomain("L");
        AuditLogQueryRequest m = new AuditLogQueryRequest(); m.setDomain("M");
        assertThat(service.overview(l).getData().operationQueue()).extracting(AuditCenterOverview.AuditOperationTicket::id)
                .containsExactly("WO-L");
        assertThat(service.overview(m).getData().operationQueue()).extracting(AuditCenterOverview.AuditOperationTicket::id)
                .containsExactly("WO-M");
    }

    @Test
    void approveRechecksBusinessPermissionBeforeReplay() {
        putTicket("WO-PERMISSION-REVOKED", "J1 恢复提现闸", "pending", "emergency", true, false);
        AuditReplayCommand command = new AuditReplayCommand("D", "d2_withdraw_approve", Map.of());
        doReturn(ApiResult.fail(403, "A2_BUSINESS_PERMISSION_DENIED:finance_d2_large_approve"))
                .when(replayBusinessPermissionGuard).validateProposal(command);

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.approve(
                "idem-revoked", "WO-PERMISSION-REVOKED",
                new AuditOperationDecisionRequest("permission changed after proposal", "treasury.approver"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(ticketRows.get("WO-PERMISSION-REVOKED").getStatus()).isEqualTo("pending");
        verify(replayDispatcher, never()).dispatch(any(), any());
        verify(ticketMapper, never()).updateById(ticketRows.get("WO-PERMISSION-REVOKED"));
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
    void proposerCannotApproveOwnProposalEvenWhenClientSpoofsAnotherOperator() {
        putTicket("WO-SELF", "A6 role grants", "pending", "HIGH", true, false);
        ticketRows.get("WO-SELF").setOperatorName("alice.admin");
        ticketRows.get("WO-SELF").setRoleGate("TWO_PERSON");
        authenticate("41", "alice.admin");

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = service.approve(
                "idem-self", "WO-SELF", new AuditOperationDecisionRequest("looks good", "bob.admin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("A2_MAKER_CHECKER_REQUIRED");
        assertThat(ticketRows.get("WO-SELF").getStatus()).isEqualTo("pending");
        verify(replayDispatcher, never()).dispatch(any(), any());
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
        verify(auditLogService).recordRequired(audit.capture());
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
        verify(auditLogService).recordRequired(captor.capture());
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
    void delegatedProposalRejectsMissingCommandBeforeCreatingTicketOrObjectLock() {
        doReturn(ApiResult.fail(403, "A2_BUSINESS_COMMAND_REQUIRED"))
                .when(replayBusinessPermissionGuard).validateProposal(null);
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "冻结账户", "U00000052", "正常", "冻结", "risk-user", "风控", "acct",
                false, false, "风控 / 超管", "高风险账户处置验证原因", "C2", null,
                new ffdd.opsconsole.platform.domain.AuditLockTarget("C", "ACCOUNT", "U00000052"), null);

        var result = service.createProposal("idem-null-command", request);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("A2_BUSINESS_COMMAND_REQUIRED");
        verify(ticketMapper, never()).insert(any(AuditOperationTicketEntity.class));
        verify(lockMapper, never()).insert(any(ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity.class));
    }

    @Test
    void delegatedProposalPersistsCanonicalServerDescriptionInsteadOfClientCopy() {
        AuditReplayCommand command = new AuditReplayCommand(
                "K", "k1_cluster_release", Map.of("clusterId", "CL-ACTIVE"));
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "批准安全操作", "CL-ACTIVE", "正常", "保持正常", "risk-user", "client-role", "param",
                false, true, "client-gate", "解除误判并恢复账户正常使用", "K1", command,
                new ffdd.opsconsole.platform.domain.AuditLockTarget("K", "cluster", "CL-ACTIVE"), null);
        var descriptor = new AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor(
                "解除误判 · CL-ACTIVE", "CL-ACTIVE", "以服务器执行时状态为准", "released",
                "K1", "acct", true,
                new ffdd.opsconsole.platform.domain.AuditLockTarget("K", "cluster", "CL-ACTIVE"));
        doReturn(ApiResult.ok(descriptor)).when(replayBusinessPermissionGuard).validateProposalContext(request);
        doReturn(true).when(replayBusinessPermissionGuard).delegatedProposal();

        var result = service.createProposal("idem-canonical-context", request);
        AuditOperationTicketEntity stored = ticketRows.get(result.getData().id());

        assertThat(result.getCode()).isZero();
        assertThat(stored.getAction()).isEqualTo("解除误判 · CL-ACTIVE");
        assertThat(stored.getObjectText()).isEqualTo("CL-ACTIVE");
        assertThat(stored.getBeforeValue()).isEqualTo("以服务器执行时状态为准");
        assertThat(stored.getAfterValue()).isEqualTo("released");
        assertThat(stored.getOperationType()).isEqualTo("acct");
        assertThat(stored.getAmplifies()).isEqualTo(1);
        assertThat(stored.getSos()).isZero();
        assertThat(stored.getOperatorRole()).isEqualTo("风控");
        assertThat(stored.getRoleGate()).isEqualTo("门槛者");
    }

    @Test
    void fullWriterCanonicalProposalKeepsAuthenticatedOperatorRole() {
        AuditReplayCommand command = new AuditReplayCommand(
                "C", "c2_account_freeze", Map.of("userId", "52", "status", "frozen"));
        AuditOperationProposalRequest request = new AuditOperationProposalRequest(
                "冻结账户", "52", "正常", "冻结", "superadmin", "超管", "acct",
                true, false, "超管", "命中高风险规则需立即冻结", "C2", command,
                new ffdd.opsconsole.platform.domain.AuditLockTarget("C", "ACCOUNT", "52"), null);
        var descriptor = new AuditReplayBusinessPermissionGuard.DelegatedProposalDescriptor(
                "冻结账户 · 52", "52", "以服务器执行时状态为准", "frozen",
                "C2", "acct", true,
                new ffdd.opsconsole.platform.domain.AuditLockTarget("C", "ACCOUNT", "52"));
        doReturn(ApiResult.ok(descriptor)).when(replayBusinessPermissionGuard).validateProposalContext(request);
        doReturn(false).when(replayBusinessPermissionGuard).delegatedProposal();

        var result = service.createProposal("idem-full-writer-role", request);
        AuditOperationTicketEntity stored = ticketRows.get(result.getData().id());

        assertThat(result.getCode()).isZero();
        assertThat(stored.getOperatorRole()).isEqualTo("超管");
        assertThat(stored.getAction()).isEqualTo("冻结账户 · 52");
        assertThat(stored.getObjectText()).isEqualTo("52");
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
        verify(auditLogService).recordRequired(captor.capture());
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
                new AuditMechanismParamUpdateRequest("12", "tighten confirmation reason", "superadmin");

        ApiResult<AuditCenterOverview.AuditMechanismParam> result =
                service.updateMechanismParam("idem-param-1", "ttl", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("12 字");
        assertThat(repository.items.get("admin.a2.reason_min_chars").configValue()).isEqualTo("12 字");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_MECHANISM_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("paramKey", "ttl")
                .containsEntry("before", "8 字")
                .containsEntry("after", "12 字");
        verify(idempotencyService).execute(org.mockito.ArgumentMatchers.eq("A2_COMMAND"),
                org.mockito.ArgumentMatchers.eq("idem-param-1"), any(),
                org.mockito.ArgumentMatchers.eq(AuditCenterOverview.AuditMechanismParam.class), any());
    }

    @Test
    void mechanismParamRejectsReasonShorterThanCurrentConfiguredMinimum() {
        repository.save(new PlatformConfigItem(null, "admin.a2.reason_min_chars", "12 字", "STRING",
                "admin_a2", "INTERNAL", "test", 1, null, null));

        ApiResult<AuditCenterOverview.AuditMechanismParam> result = service.updateMechanismParam(
                "idem-short", "retention", new AuditMechanismParamUpdateRequest("13", "only eight", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(repository.items).doesNotContainKey("admin.a2.retention_months");
    }

    @Test
    void mechanismParamReplayDoesNotRepeatWriteOrAuditAndRejectsCrossOperationKeyReuse() {
        java.util.concurrent.atomic.AtomicReference<String> storedHash = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> storedResult = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            String hash = invocation.getArgument(2);
            if (storedHash.get() == null) {
                storedHash.set(hash);
                Object result = ((java.util.function.Supplier<?>) invocation.getArgument(4)).get();
                storedResult.set(result);
                return result;
            }
            if (!storedHash.get().equals(hash)) {
                throw new ffdd.opsconsole.shared.exception.BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            }
            return storedResult.get();
        }).when(idempotencyService).execute(org.mockito.ArgumentMatchers.eq("A2_COMMAND"),
                org.mockito.ArgumentMatchers.eq("idem-replay"), any(), any(), any());
        AuditMechanismParamUpdateRequest request =
                new AuditMechanismParamUpdateRequest("12", "tighten confirmation reason", "superadmin");

        assertThat(service.updateMechanismParam("idem-replay", "ttl", request).getCode()).isZero();
        assertThat(service.updateMechanismParam("idem-replay", "ttl", request).getCode()).isZero();
        ApiResult<AuditCenterOverview.AuditMechanismParam> crossOperation = service.updateMechanismParam(
                "idem-replay", "retention",
                new AuditMechanismParamUpdateRequest("13", "retention evidence reason", "superadmin"));
        authenticate("41", "alice.admin");
        ApiResult<AuditCenterOverview.AuditMechanismParam> crossActor =
                service.updateMechanismParam("idem-replay", "ttl", request);

        assertThat(crossOperation.getCode()).isEqualTo(409);
        assertThat(crossActor.getCode()).isEqualTo(409);
        verify(auditLogService, org.mockito.Mockito.times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void allA2MutationsRejectReasonLongerThanTwoHundredCharacters() {
        ApiResult<AuditCenterOverview.AuditMechanismParam> result = service.updateMechanismParam(
                "idem-long", "ttl", new AuditMechanismParamUpdateRequest("12", "x".repeat(201), "superadmin"));
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void mechanismParamRejectsOutOfRangeValue() {
        AuditMechanismParamUpdateRequest request =
                new AuditMechanismParamUpdateRequest("6 字", "too short", "superadmin");

        ApiResult<AuditCenterOverview.AuditMechanismParam> result =
                service.updateMechanismParam("idem-param-1", "ttl", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void schemaVersionUsesTheSameThirtyTwoCharacterContractAsTheFrontend() {
        ApiResult<AuditCenterOverview.AuditMechanismParam> valid = service.updateMechanismParam(
                "idem-schema-valid", "schema",
                new AuditMechanismParamUpdateRequest("schema2026", "publish audited schema version", "superadmin"));
        ApiResult<AuditCenterOverview.AuditMechanismParam> invalidChars = service.updateMechanismParam(
                "idem-schema-chars", "schema",
                new AuditMechanismParamUpdateRequest("v4 unsafe", "reject malformed schema version", "superadmin"));
        ApiResult<AuditCenterOverview.AuditMechanismParam> tooLong = service.updateMechanismParam(
                "idem-schema-long", "schema",
                new AuditMechanismParamUpdateRequest("v".repeat(33), "reject oversized schema version", "superadmin"));

        assertThat(valid.getCode()).isZero();
        assertThat(valid.getData().value()).isEqualTo("统一 schema · schema2026");
        assertThat(invalidChars.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(tooLong.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void numericMechanismInputsRejectEmbeddedNonNumericCharacters() {
        ApiResult<AuditCenterOverview.AuditMechanismParam> ttl = service.updateMechanismParam(
                "idem-dirty-ttl", "ttl",
                new AuditMechanismParamUpdateRequest("abc8xyz", "reject dirty numeric value", "superadmin"));
        ApiResult<AuditCenterOverview.AuditMechanismParam> retention = service.updateMechanismParam(
                "idem-dirty-retention", "retention",
                new AuditMechanismParamUpdateRequest("1e3", "reject dirty retention value", "superadmin"));

        assertThat(ttl.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(retention.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
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
