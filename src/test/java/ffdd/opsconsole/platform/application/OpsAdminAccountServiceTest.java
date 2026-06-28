package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.AdminAccountCreateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.platform.dto.AdminAccountRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountSecurityBaselineUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacActionCreateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacGrantUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class OpsAdminAccountServiceTest {
    private final InMemoryPlatformConfigRepository repository = new InMemoryPlatformConfigRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AdminSessionRegistry adminSessionRegistry = mock(AdminSessionRegistry.class);
    private final OpsAuditCenterService auditCenterService = mock(OpsAuditCenterService.class);
    private final List<AdminEntity> admins = new ArrayList<>();
    private final OpsAdminAccountService service =
            new OpsAdminAccountService(repository, auditLogService, adminMapper, roleRelationMapper, passwordEncoder,
                    adminSessionRegistry, auditCenterService);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository.clear();
        registerTestRoles();
        registerTestRbacActions();
        registerTestSecurityBaselines();

        admins.clear();
        admins.add(admin(1L, "superadmin", "Super Admin", "admin@nexion.ai", 1, 1));
        admins.add(admin(2L, "finance.lead", "财务主管", "finance@nexion.io", 1, 1));
        admins.add(admin(3L, "ops.owner", "运营负责人", "ops-owner@nexion.io", 1, 1));
        admins.add(admin(4L, "risk.lead", "风控主管", "risk@nexion.io", 0, 1));
        repository.put("a1.account.4.role", "risk", "admin_a1_account");

        when(adminMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(admins));
        when(adminMapper.selectOne(any())).thenReturn(null);
        when(adminSessionRegistry.countActiveSessions(any(Long.class))).thenReturn(0);
        when(auditCenterService.pendingOperationCountByActionMarker("(A1)")).thenReturn(0);
        when(auditCenterService.createProposal(any(String.class), any(AuditOperationProposalRequest.class)))
                .thenAnswer(invocation -> {
                    AuditOperationProposalRequest proposal = invocation.getArgument(1);
                    return ApiResult.ok(new AuditCenterOverview.AuditOperationTicket(
                            "WO-A1-TEST",
                            proposal.action(),
                            proposal.obj(),
                            proposal.beforeValue(),
                            proposal.afterValue(),
                            proposal.operator(),
                            proposal.operatorRole(),
                            proposal.type(),
                            Boolean.TRUE.equals(proposal.amplifies()),
                            Boolean.TRUE.equals(proposal.sos()),
                            "刚刚",
                            false,
                            proposal.roleGate(),
                            proposal.reason(),
                            "pending"));
                });
        when(adminMapper.insert(any(AdminEntity.class))).thenAnswer(invocation -> {
            AdminEntity entity = invocation.getArgument(0);
            entity.setId(nextAdminId());
            entity.setUpdatedAt(LocalDateTime.now());
            admins.add(entity);
            return 1;
        });
        when(adminMapper.updateById(any(AdminEntity.class))).thenAnswer(invocation -> {
            AdminEntity patch = invocation.getArgument(0);
            admins.stream()
                    .filter(admin -> admin.getId().equals(patch.getId()))
                    .findFirst()
                    .ifPresent(admin -> {
                        if (patch.getSuperAdmin() != null) {
                            admin.setSuperAdmin(patch.getSuperAdmin());
                        }
                        if (patch.getStatus() != null) {
                            admin.setStatus(patch.getStatus());
                        }
                        admin.setUpdatedAt(LocalDateTime.now());
                    });
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewReturnsAdminTableAndConfigBackedA1Data() {
        ApiResult<AdminAccountOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().totalAccounts()).isEqualTo(4);
        assertThat(result.getData().stats().effectiveSupers()).isEqualTo(3);
        assertThat(result.getData().roles()).hasSize(7);
        assertThat(result.getData().operators()).extracting(AdminAccountOverview.OperatorRecord::id)
                .contains("1", "4");
        assertThat(result.getData().operators()).extracting(AdminAccountOverview.OperatorRecord::email)
                .contains("admin@nexion.ai", "risk@nexion.io");
        assertThat(result.getData().rbacMatrix()).extracting(AdminAccountOverview.RbacAction::id)
                .contains("operator_governance", "audit_export")
                .doesNotContain("premium", "nex-v2", "points");
    }

    @Test
    void overviewCountsActiveAdminSessionsFromRedisRegistry() {
        when(adminSessionRegistry.countActiveSessions(4L)).thenReturn(2);

        ApiResult<AdminAccountOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().activeSessions()).isEqualTo(2);
        assertThat(result.getData().operators()).filteredOn(operator -> "4".equals(operator.id()))
                .extracting(AdminAccountOverview.OperatorRecord::sessions)
                .containsExactly(2);
    }

    @Test
    void changeRoleRequiresIdempotencyKey() {
        AdminAccountRoleUpdateRequest request =
                new AdminAccountRoleUpdateRequest("risk", "change duty", "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.changeRole(" ", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void disablingLastTwoSuperBoundaryIsRejected() {
        admins.get(2).setStatus(0);
        AdminAccountStatusUpdateRequest request =
                new AdminAccountStatusUpdateRequest("disabled", "offboarding", "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.updateStatus("idem-a1-1", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("MIN_EFFECTIVE_SUPER_REQUIRED");
        assertThat(admins.get(0).getStatus()).isEqualTo(1);
    }

    @Test
    void createAccountPersistsRealAdminWithoutReturningPlaintextCredentialAndWritesAudit() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "新风控成员",
                "risk-new@nexion.io",
                "risk",
                "mail",
                "new employee onboarding",
                "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("5");
        assertThat(result.getData().name()).isEqualTo("新风控成员");
        assertThat(result.getData().credentialDeliveryStatus()).isEqualTo("MAIL_DISPATCHED");
        assertThat(result.getData().role()).isEqualTo("risk");
        assertThat(repository.items).containsKey("a1.account.5.role");
        assertThat(repository.items).doesNotContainKey("a1.account.5.registered");
        assertThat(admins).extracting(AdminEntity::getEmail).contains("risk-new@nexion.io");
        assertThat(result.getData().toString()).doesNotContain("NX-");
        verify(roleRelationMapper).ensurePrimaryRole(5L, "OPS_ADMIN");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A1_OPERATOR_CREATED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A1_ADMIN_ACCOUNT");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AuditOperationProposalRequest> proposalCaptor =
                ArgumentCaptor.forClass(AuditOperationProposalRequest.class);
        verify(auditCenterService).createProposal(keyCaptor.capture(), proposalCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("idem-create-1-a2");
        assertThat(proposalCaptor.getValue().action()).isEqualTo("运营账号创建(A1)");
        assertThat(proposalCaptor.getValue().obj()).contains("新风控成员", "risk-new@nexion.io");
        assertThat(proposalCaptor.getValue().obj()).doesNotContain("5");
        assertThat(proposalCaptor.getValue().sourceDomain()).isEqualTo("A1");
    }

    @Test
    void updateSecurityBaselineParsesSessionLimitAndAudits() {
        AdminAccountSecurityBaselineUpdateRequest request =
                new AdminAccountSecurityBaselineUpdateRequest("45min / 10h", "shorten console sessions", "superadmin");

        ApiResult<AdminAccountOverview.SecurityBaseline> result =
                service.updateSecurityBaseline("idem-sec-1", "session", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("45min / 10h");
        assertThat(repository.items.get("a1.security.sessionIdle").configValue()).isEqualTo("45");
        assertThat(repository.items.get("a1.security.sessionAbs").configValue()).isEqualTo("10");
        verify(auditLogService).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void updateRbacRejectsAuditWriteAndProtectsOperatorGovernanceSuperGrant() {
        AdminRbacGrantUpdateRequest auditWriteRequest = new AdminRbacGrantUpdateRequest(
                List.of("C", "C", "-", "-", "-", "M", "M"),
                "bad audit grant",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> auditResult =
                service.updateRbacGrants("idem-rbac-1", "balance_adjust", auditWriteRequest);

        assertThat(auditResult.getCode()).isEqualTo(422);
        assertThat(auditResult.getMessage()).isEqualTo("AUDIT_ROLE_WRITE_FORBIDDEN");

        AdminRbacGrantUpdateRequest superRemovedRequest = new AdminRbacGrantUpdateRequest(
                List.of("-", "-", "-", "-", "-", "-", "R"),
                "remove super",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> superResult =
                service.updateRbacGrants("idem-rbac-2", "operator_governance", superRemovedRequest);

        assertThat(superResult.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(superResult.getMessage()).isEqualTo("OPERATOR_GOVERNANCE_SUPER_GRANT_REQUIRED");
    }

    @Test
    void revokeSessionsDeletesRedisBackedAdminSessionsAndAudits() {
        authenticateAs(1L);
        when(adminSessionRegistry.revokeSessions(4L)).thenReturn(2);
        AdminAccountActionRequest request = new AdminAccountActionRequest("suspected account takeover", "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-1", "4", request);

        assertThat(result.getCode()).isZero();
        verify(adminSessionRegistry).revokeSessions(4L);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A1_OPERATOR_SESSION_REVOKED");
        Map<?, ?> detail = (Map<?, ?>) captor.getValue().getDetail();
        assertThat(detail.get("revokedSessions")).isEqualTo(2);
    }

    @Test
    void revokeSessionsAllowsRiskOperatorForNonSuperTarget() {
        admins.add(admin(5L, "support.ops", "客服专员", "support@nexion.io", 0, 1));
        repository.put("a1.account.5.role", "support", "admin_a1_account");
        authenticateAs(4L);
        when(adminSessionRegistry.revokeSessions(5L)).thenReturn(1);
        AdminAccountActionRequest request = new AdminAccountActionRequest("suspected support console misuse", "risk.lead");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-risk", "5", request);

        assertThat(result.getCode()).isZero();
        verify(adminSessionRegistry).revokeSessions(5L);
    }

    @Test
    void revokeSessionsRejectsSelfTarget() {
        authenticateAs(4L);
        AdminAccountActionRequest request = new AdminAccountActionRequest("self test should be blocked", "risk.lead");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-self", "4", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_SELF_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
    }

    @Test
    void revokeSessionsRejectsNonSuperAndNonRiskOperator() {
        admins.add(admin(5L, "finance.ops", "财务专员", "finance-ops@nexion.io", 0, 1));
        repository.put("a1.account.5.role", "finance", "admin_a1_account");
        authenticateAs(5L);
        AdminAccountActionRequest request = new AdminAccountActionRequest("finance should not force logout", "finance.ops");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-role", "4", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
    }

    @Test
    void revokeSessionsRejectsSuperTarget() {
        authenticateAs(4L);
        AdminAccountActionRequest request = new AdminAccountActionRequest("super admin target should be protected", "risk.lead");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-super-target", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_SUPER_TARGET_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(1L);
    }

    @Test
    void registerRbacActionDefaultsToNoWriteAndAuditReadOnly() {
        AdminRbacActionCreateRequest request =
                new AdminRbacActionCreateRequest("批量补发收益(E3)", "增长/内容", "new sensitive action", "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> result = service.registerRbacAction("idem-rbac-create", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("e3");
        assertThat(result.getData().grants()).containsExactly("-", "-", "-", "-", "-", "-", "R");
        assertThat(repository.items).containsKey("a1.rbac.action.e3");
        verify(auditLogService).record(any(AuditLogWriteRequest.class));
    }

    private void registerTestRoles() {
        registerTestRole("super", "超级管理员", "SA", "red", "平台 Owner，保留所有危急操作。", "全域：资金、风控、内容、配置、审计", 10);
        registerTestRole("finance", "资金运营", "FI", "green", "资金、账务、提现与覆盖率。", "D/C/B 资金域", 20);
        registerTestRole("risk", "风控运营", "RK", "orange", "风控模型、KYC、账户限制与熔断。", "C/K/J 风控域", 30);
        registerTestRole("growth", "增长运营", "GR", "blue", "活动、Phase dial、权益与触达。", "H/权益/触达", 40);
        registerTestRole("content", "内容运营", "CT", "purple", "文案、课程、风险披露与公告。", "I/公告/课程", 50);
        registerTestRole("support", "客服运营", "CS", "cyan", "用户查询、工单协同与只读辅助。", "C/D 只读与协助", 60);
        registerTestRole("audit", "审计只读", "AU", "gray", "审计与合规观察，禁止写操作。", "A2/审计/报表", 70);
    }

    private void registerTestRole(
            String key, String name, String avatar, String color, String description, String scope, int sort) {
        String prefix = "a1.role." + key + ".";
        repository.put(prefix + "registered", "true", "admin_a1_role");
        repository.put(prefix + "name", name, "admin_a1_role");
        repository.put(prefix + "avatar", avatar, "admin_a1_role");
        repository.put(prefix + "color", color, "admin_a1_role");
        repository.put(prefix + "description", description, "admin_a1_role");
        repository.put(prefix + "scope", scope, "admin_a1_role");
        repository.put(prefix + "sort", String.valueOf(sort), "admin_a1_role");
    }

    private void registerTestRbacActions() {
        registerTestAction("balance_adjust", "余额/资产调整(C3)", "用户/风控", 10, List.of("C", "C", "-", "-", "-", "M", "R"));
        registerTestAction("operator_governance", "运营账号治理(A1)", "基座/应急", 20, List.of("M", "-", "-", "-", "-", "-", "R"));
        registerTestAction("audit_export", "审计全量导出(A2)", "基座/应急", 30, List.of("M", "-", "-", "-", "-", "-", "M"));
    }

    private void registerTestAction(String id, String action, String domainGroup, int sort, List<String> grants) {
        repository.put("a1.rbac.action." + id, action, "admin_a1_rbac");
        repository.put("a1.rbac.action." + id + ".domainGroup", domainGroup, "admin_a1_rbac");
        repository.put("a1.rbac.action." + id + ".sort", String.valueOf(sort), "admin_a1_rbac");
        List<String> roles = List.of("super", "finance", "risk", "growth", "content", "support", "audit");
        for (int i = 0; i < roles.size(); i++) {
            repository.put("a1.rbac." + roles.get(i) + "." + id, grants.get(i), "admin_a1_rbac");
        }
    }

    private void registerTestSecurityBaselines() {
        registerTestSecurity("session", "会话上限", "后台闲置 30min、绝对 8h。", "30min / 8h", false, 10);
    }

    private void registerTestSecurity(String key, String label, String description, String value, boolean locked, int sort) {
        String prefix = "a1.security.baseline." + key + ".";
        repository.put(prefix + "registered", "true", "admin_a1_security");
        repository.put(prefix + "label", label, "admin_a1_security");
        repository.put(prefix + "description", description, "admin_a1_security");
        repository.put(prefix + "value", value, "admin_a1_security");
        repository.put(prefix + "locked", String.valueOf(locked), "admin_a1_security");
        repository.put(prefix + "sort", String.valueOf(sort), "admin_a1_security");
    }

    private AdminEntity admin(Long id, String username, String nickname, String email, int superAdmin, int status) {
        AdminEntity entity = new AdminEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setNickname(nickname);
        entity.setEmail(email);
        entity.setPasswordHash("encoded");
        entity.setSuperAdmin(superAdmin);
        entity.setStatus(status);
        entity.setIsDeleted(0);
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private Long nextAdminId() {
        return admins.stream().mapToLong(AdminEntity::getId).max().orElse(0L) + 1;
    }

    private void authenticateAs(Long adminId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(adminId), null, List.of()));
    }

    private static final class InMemoryPlatformConfigRepository implements PlatformConfigRepository {
        private final Map<String, PlatformConfigItem> items = new LinkedHashMap<>();
        private long sequence = 1L;

        void clear() {
            items.clear();
            sequence = 1L;
        }

        void put(String key, String value, String group) {
            save(new PlatformConfigItem(null, key, value, "STRING", group, "ADMIN", "test", 1, null, null));
        }

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
