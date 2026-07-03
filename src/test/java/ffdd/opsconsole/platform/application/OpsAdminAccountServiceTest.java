package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.infrastructure.AdminRbacActionEntity;
import ffdd.opsconsole.platform.infrastructure.AdminRbacGrantEntity;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import ffdd.opsconsole.platform.infrastructure.AdminSecurityBaselineEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.platform.mapper.AdminRbacActionMapper;
import ffdd.opsconsole.platform.mapper.AdminRbacGrantMapper;
import ffdd.opsconsole.platform.mapper.AdminSecurityBaselineMapper;
import ffdd.opsconsole.platform.mapper.OpsOptionsMapper;
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
import java.util.function.Consumer;
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
    private final OpsOptionsMapper roleMapper = mock(OpsOptionsMapper.class);
    private final AdminAccountStateMapper accountStateMapper = mock(AdminAccountStateMapper.class);
    private final AdminRbacActionMapper rbacActionMapper = mock(AdminRbacActionMapper.class);
    private final AdminRbacGrantMapper rbacGrantMapper = mock(AdminRbacGrantMapper.class);
    private final AdminSecurityBaselineMapper securityBaselineMapper = mock(AdminSecurityBaselineMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AdminSessionRegistry adminSessionRegistry = mock(AdminSessionRegistry.class);
    private final OpsAuditCenterService auditCenterService = mock(OpsAuditCenterService.class);
    private final List<AdminEntity> admins = new ArrayList<>();
    private final Map<Long, String> roleRelations = new LinkedHashMap<>();
    private final Map<Long, AdminAccountStateEntity> accountStates = new LinkedHashMap<>();
    private final Map<String, AdminRbacActionEntity> rbacActionRows = new LinkedHashMap<>();
    private final Map<String, Map<String, AdminRbacGrantEntity>> rbacGrantRows = new LinkedHashMap<>();
    private final Map<String, AdminSecurityBaselineEntity> securityBaselineRows = new LinkedHashMap<>();
    private final OpsAdminAccountService service =
            new OpsAdminAccountService(auditLogService, adminMapper, roleRelationMapper, roleMapper,
                    accountStateMapper, rbacActionMapper, rbacGrantMapper, securityBaselineMapper, passwordEncoder,
                    adminSessionRegistry, auditCenterService);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        authenticateAs(1L);
        repository.clear();
        accountStates.clear();
        rbacActionRows.clear();
        rbacGrantRows.clear();
        securityBaselineRows.clear();
        registerTestRbacActions();
        registerTestSecurityBaselines();

        admins.clear();
        admins.add(admin(1L, "superadmin", "Super Admin", "admin@nexion.ai", 1, 1));
        admins.add(admin(2L, "finance.lead", "财务主管", "finance@nexion.io", 1, 1));
        admins.add(admin(3L, "ops.owner", "运营负责人", "ops-owner@nexion.io", 1, 1));
        admins.add(admin(4L, "risk.lead", "风控主管", "risk@nexion.io", 0, 1));
        roleRelations.clear();
        roleRelations.put(1L, "SUPER_ADMIN");
        roleRelations.put(2L, "SUPER_ADMIN");
        roleRelations.put(3L, "SUPER_ADMIN");
        roleRelations.put(4L, "RISK");

        when(roleMapper.selectList(any())).thenReturn(testRoleRows());
        when(roleRelationMapper.activeRoleCode(any(Long.class)))
                .thenAnswer(invocation -> roleRelations.get(invocation.getArgument(0)));
        when(roleRelationMapper.disableOtherPrimaryRoles(any(Long.class), any(String.class))).thenReturn(1);
        when(roleRelationMapper.ensurePrimaryRole(any(Long.class), any(String.class))).thenAnswer(invocation -> {
            roleRelations.put(invocation.getArgument(0), invocation.getArgument(1));
            return 1;
        });
        when(accountStateMapper.selectActiveByAdminId(any(Long.class)))
                .thenAnswer(invocation -> accountStates.get(invocation.getArgument(0)));
        when(accountStateMapper.upsertCreatedState(any(Long.class), any(String.class))).thenAnswer(invocation -> {
            upsertAccountState(invocation.getArgument(0), state -> {
                state.setTfaRequired(1);
                state.setCredentialDeliveryStatus(invocation.getArgument(1));
            });
            return 1;
        });
        when(accountStateMapper.upsertTfaResetAt(any(Long.class), any(LocalDateTime.class))).thenAnswer(invocation -> {
            upsertAccountState(invocation.getArgument(0), state -> {
                state.setTfaRequired(1);
                state.setTfaResetAt(invocation.getArgument(1));
            });
            return 1;
        });
        when(accountStateMapper.upsertSessionsRevokedAt(any(Long.class), any(LocalDateTime.class))).thenAnswer(invocation -> {
            upsertAccountState(invocation.getArgument(0), state -> state.setSessionsRevokedAt(invocation.getArgument(1)));
            return 1;
        });
        when(rbacActionMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(rbacActionRows.values()));
        when(rbacActionMapper.upsertAction(any(String.class), any(String.class), any(String.class), anyInt()))
                .thenAnswer(invocation -> {
                    putRbacAction(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3));
                    return 1;
                });
        when(rbacGrantMapper.selectList(any())).thenAnswer(invocation -> rbacGrantRows.values().stream()
                .flatMap(row -> row.values().stream())
                .toList());
        when(rbacGrantMapper.upsertGrant(any(String.class), any(String.class), any(String.class))).thenAnswer(invocation -> {
            putRbacGrant(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return 1;
        });
        when(securityBaselineMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(securityBaselineRows.values()));
        when(securityBaselineMapper.selectActiveByKey(any(String.class)))
                .thenAnswer(invocation -> securityBaselineRows.get(invocation.getArgument(0)));
        when(securityBaselineMapper.upsertValue(any(String.class), any(String.class))).thenAnswer(invocation -> {
            AdminSecurityBaselineEntity row = securityBaselineRows.get(invocation.getArgument(0));
            if (row != null) {
                row.setBaselineValue(invocation.getArgument(1));
                row.setUpdatedAt(LocalDateTime.now());
                return 1;
            }
            return 0;
        });
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
    void overviewReturnsAdminTableAndBusinessTableBackedA1Data() {
        ApiResult<AdminAccountOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().totalAccounts()).isEqualTo(4);
        assertThat(result.getData().stats().effectiveSupers()).isEqualTo(3);
        assertThat(result.getData().roles()).hasSize(8);
        assertThat(result.getData().operators()).extracting(AdminAccountOverview.OperatorRecord::id)
                .contains("1", "4");
        assertThat(result.getData().operators()).extracting(AdminAccountOverview.OperatorRecord::email)
                .contains("admin@nexion.ai", "risk@nexion.io");
        assertThat(result.getData().rbacMatrix()).extracting(AdminAccountOverview.RbacAction::id)
                .contains("operator_governance", "audit_export")
                .doesNotContain("premium", "nex-v2", "points");
    }

    @Test
    void overviewDoesNotInventA1RoleRbacOrSecurityConfigWhenDbHasNoConfigRows() {
        repository.clear();
        rbacActionRows.clear();
        rbacGrantRows.clear();
        securityBaselineRows.clear();

        ApiResult<AdminAccountOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().roles()).extracting(AdminAccountOverview.RoleDefinition::key)
                .containsExactly("super", "config", "finance", "risk", "content", "growth",
                        "support", "audit");
        assertThat(result.getData().rbacMatrix()).isEmpty();
        assertThat(result.getData().securityBaselines()).isEmpty();
        assertThat(result.getData().operators()).filteredOn(operator -> "1".equals(operator.id()))
                .extracting(AdminAccountOverview.OperatorRecord::role)
                .containsExactly("super");
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
        assertThat(repository.items).doesNotContainKey("a1.account.5.role");
        assertThat(repository.items).doesNotContainKey("a1.account.5.registered");
        assertThat(repository.items).doesNotContainKey("a1.account.5.tfa");
        assertThat(repository.items).doesNotContainKey("a1.account.5.credentialDeliveryStatus");
        assertThat(repository.items).doesNotContainKey("a1.account.5.createdAt");
        assertThat(accountStates.get(5L).getTfaRequired()).isEqualTo(1);
        assertThat(accountStates.get(5L).getCredentialDeliveryStatus()).isEqualTo("MAIL_DISPATCHED");
        assertThat(admins).extracting(AdminEntity::getEmail).contains("risk-new@nexion.io");
        assertThat(result.getData().toString()).doesNotContain("NX-");
        verify(roleRelationMapper).ensurePrimaryRole(5L, "RISK");

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
    void createAccountAllowsHandoffInitialPasswordWithoutReturningPlaintextCredential() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "资金测试值班",
                "finance-shift@nexion.io",
                "finance",
                "handoff",
                "local e2e shift bootstrap",
                "superadmin",
                "E2eShift@12345");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-shift", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().credentialDeliveryStatus()).isEqualTo("HANDOFF_PENDING");
        assertThat(accountStates.get(5L).getCredentialDeliveryStatus()).isEqualTo("HANDOFF_PENDING");
        AdminEntity created = admins.stream()
                .filter(admin -> "finance-shift@nexion.io".equals(admin.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(passwordEncoder.matches("E2eShift@12345", created.getPasswordHash())).isTrue();
        assertThat(result.getData().toString()).doesNotContain("E2eShift@12345");
    }

    @Test
    void createAccountRejectsInitialPasswordOutsideHandoffDelivery() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "资金测试值班",
                "finance-shift@nexion.io",
                "finance",
                "mail",
                "local e2e shift bootstrap",
                "superadmin",
                "E2eShift@12345");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-shift", request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("INITIAL_PASSWORD_HANDOFF_ONLY");
    }

    @Test
    void supportOperatorCannotChangeA1Roles() {
        admins.add(admin(5L, "support.manager", "客服主管", "support-manager@nexion.io", 0, 1));
        admins.add(admin(6L, "support.legacy", "旧客服", "support-legacy@nexion.io", 0, 1));
        roleRelations.put(5L, "SUPPORT");
        roleRelations.put(6L, "SUPPORT");
        authenticateAs(5L);

        ApiResult<AdminAccountOverview.OperatorRecord> rejected = service.changeRole(
                "idem-support-role-1",
                "6",
                new AdminAccountRoleUpdateRequest("risk", "客服不能改 A1 全局角色", "support.manager"));

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(rejected.getMessage()).isEqualTo("ROLE_ASSIGNMENT_FORBIDDEN");
        assertThat(roleRelations.get(6L)).isEqualTo("SUPPORT");
    }

    @Test
    void supportOperatorCannotCreateAccountsOrMutateA1SecuritySettings() {
        admins.add(admin(5L, "support.manager", "客服主管", "support-manager@nexion.io", 0, 1));
        roleRelations.put(5L, "SUPPORT");
        authenticateAs(5L);

        ApiResult<AdminAccountOverview.OperatorRecord> createResult = service.createAccount(
                "idem-support-create",
                new AdminAccountCreateRequest(
                        "专属客服新账号",
                        "dedicated-new@nexion.io",
                        "support",
                        "mail",
                        "客服主管不能创建后台账号",
                        "support.manager"));
        ApiResult<AdminAccountOverview.SecurityBaseline> securityResult = service.updateSecurityBaseline(
                "idem-support-security",
                "session",
                new AdminAccountSecurityBaselineUpdateRequest("45min / 10h", "客服主管不能改安全基线", "support.manager"));

        assertThat(createResult.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(createResult.getMessage()).isEqualTo("ROLE_ASSIGNMENT_FORBIDDEN");
        assertThat(securityResult.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(securityResult.getMessage()).isEqualTo("SECURITY_BASELINE_FORBIDDEN");
    }

    @Test
    void updateSecurityBaselineParsesSessionLimitAndAudits() {
        AdminAccountSecurityBaselineUpdateRequest request =
                new AdminAccountSecurityBaselineUpdateRequest("45min / 10h", "shorten console sessions", "superadmin");

        ApiResult<AdminAccountOverview.SecurityBaseline> result =
                service.updateSecurityBaseline("idem-sec-1", "session", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().value()).isEqualTo("45min / 10h");
        assertThat(securityBaselineRows.get("session").getBaselineValue()).isEqualTo("45min / 10h");
        assertThat(repository.items).doesNotContainKey("a1.security.sessionIdle");
        assertThat(repository.items).doesNotContainKey("a1.security.sessionAbs");
        assertThat(repository.items).doesNotContainKey("a1.security.baseline.session.value");
        verify(auditLogService).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void updateRbacRejectsAuditWriteAndProtectsOperatorGovernanceSuperGrant() {
        AdminRbacGrantUpdateRequest auditWriteRequest = new AdminRbacGrantUpdateRequest(
                List.of("C", "C", "-", "-", "-", "-", "M", "M"),
                "bad audit grant",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> auditResult =
                service.updateRbacGrants("idem-rbac-1", "balance_adjust", auditWriteRequest);

        assertThat(auditResult.getCode()).isEqualTo(422);
        assertThat(auditResult.getMessage()).isEqualTo("AUDIT_ROLE_WRITE_FORBIDDEN");

        AdminRbacGrantUpdateRequest superRemovedRequest = new AdminRbacGrantUpdateRequest(
                List.of("-", "-", "-", "-", "-", "-", "-", "R"),
                "remove super",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> superResult =
                service.updateRbacGrants("idem-rbac-2", "operator_governance", superRemovedRequest);

        assertThat(superResult.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(superResult.getMessage()).isEqualTo("OPERATOR_GOVERNANCE_SUPER_GRANT_REQUIRED");
    }

    @Test
    void updateRbacGrantsWritesBusinessGrantTableNotNxConfigItem() {
        AdminRbacGrantUpdateRequest request = new AdminRbacGrantUpdateRequest(
                List.of("C", "R", "-", "M", "-", "-", "R", "R"),
                "tighten risk grants",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> result =
                service.updateRbacGrants("idem-rbac-write", "balance_adjust", request);

        assertThat(result.getCode()).isZero();
        assertThat(rbacGrantRows.get("balance_adjust").get("risk").getGrantValue()).isEqualTo("M");
        assertThat(rbacGrantRows.get("balance_adjust").get("config").getGrantValue()).isEqualTo("R");
        assertThat(repository.items).doesNotContainKey("a1.rbac.risk.balance_adjust");
        assertThat(repository.items).doesNotContainKey("a1.rbac.config.balance_adjust");
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
    void revokeSessionsRejectsRiskOperatorForNonSuperTarget() {
        admins.add(admin(5L, "support.ops", "客服专员", "support@nexion.io", 0, 1));
        roleRelations.put(5L, "SUPPORT");
        authenticateAs(4L);
        when(adminSessionRegistry.revokeSessions(5L)).thenReturn(1);
        AdminAccountActionRequest request = new AdminAccountActionRequest("suspected support console misuse", "risk.lead");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-risk", "5", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(5L);
    }

    @Test
    void revokeSessionsRejectsDisabledSuperTarget() {
        admins.add(admin(6L, "ops.shift", "平台审计值班", "ops.shift@nexion.io", 1, 0));
        authenticateAs(1L);
        AdminAccountActionRequest request = new AdminAccountActionRequest("super cleanup requires account-disable flow", "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.revokeSessions("idem-session-e2e-cleanup", "6", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_SUPER_TARGET_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(6L);
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
        roleRelations.put(5L, "FINANCE");
        authenticateAs(5L);
        AdminAccountActionRequest request = new AdminAccountActionRequest("finance should not force logout", "finance.ops");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-role", "4", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
    }

    @Test
    void revokeSessionsRejectsRiskOperatorBeforeSuperTargetCheck() {
        authenticateAs(4L);
        AdminAccountActionRequest request = new AdminAccountActionRequest("super admin target should be protected", "risk.lead");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-super-target", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(1L);
    }

    @Test
    void registerRbacActionDefaultsToNoWriteAndAuditReadOnly() {
        AdminRbacActionCreateRequest request =
                new AdminRbacActionCreateRequest("批量补发收益(E3)", "增长/内容", "new sensitive action", "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> result = service.registerRbacAction("idem-rbac-create", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("e3");
        assertThat(result.getData().grants()).containsExactly("-", "-", "-", "-", "-", "-", "-", "R");
        assertThat(rbacActionRows).containsKey("e3");
        assertThat(rbacGrantRows.get("e3")).hasSize(8);
        assertThat(repository.items).doesNotContainKey("a1.rbac.action.e3");
        verify(auditLogService).record(any(AuditLogWriteRequest.class));
    }

    private void registerTestRbacActions() {
        registerTestAction("balance_adjust", "余额/资产调整(C3)", "用户/风控", 10,
                List.of("C", "-", "C", "-", "-", "-", "M", "R"));
        registerTestAction("operator_governance", "运营账号治理(A1)", "基座/应急", 20,
                List.of("M", "-", "-", "-", "-", "-", "-", "R"));
        registerTestAction("audit_export", "审计全量导出(A2)", "基座/应急", 30,
                List.of("M", "-", "-", "-", "-", "-", "-", "M"));
    }

    private void registerTestAction(String id, String action, String domainGroup, int sort, List<String> grants) {
        putRbacAction(id, action, domainGroup, sort);
        List<String> roles = List.of("super", "config", "finance", "risk", "content", "growth",
                "support", "audit");
        for (int i = 0; i < roles.size(); i++) {
            putRbacGrant(id, roles.get(i), grants.get(i));
        }
    }

    private void upsertAccountState(Long adminId, Consumer<AdminAccountStateEntity> mutator) {
        AdminAccountStateEntity state = accountStates.computeIfAbsent(adminId, id -> {
            AdminAccountStateEntity created = new AdminAccountStateEntity();
            created.setId(id);
            created.setAdminId(id);
            created.setTfaRequired(1);
            created.setCredentialDeliveryStatus("ACTIVE");
            created.setIsDeleted(0);
            return created;
        });
        mutator.accept(state);
        state.setUpdatedAt(LocalDateTime.now());
    }

    private void putRbacAction(String id, String action, String domainGroup, int sort) {
        AdminRbacActionEntity row = new AdminRbacActionEntity();
        row.setId((long) rbacActionRows.size() + 1);
        row.setActionId(id);
        row.setActionName(action);
        row.setDomainGroup(domainGroup);
        row.setSortOrder(sort);
        row.setStatus(1);
        row.setIsDeleted(0);
        rbacActionRows.put(id, row);
    }

    private void putRbacGrant(String actionId, String roleKey, String grantValue) {
        Map<String, AdminRbacGrantEntity> grants =
                rbacGrantRows.computeIfAbsent(actionId, ignored -> new LinkedHashMap<>());
        AdminRbacGrantEntity row = grants.computeIfAbsent(roleKey, role -> {
            AdminRbacGrantEntity created = new AdminRbacGrantEntity();
            created.setId((long) grants.size() + 1);
            created.setActionId(actionId);
            created.setRoleKey(role);
            created.setStatus(1);
            created.setIsDeleted(0);
            return created;
        });
        row.setGrantValue(grantValue);
        row.setUpdatedAt(LocalDateTime.now());
    }

    private List<AdminRoleOptionEntity> testRoleRows() {
        return List.of(
                roleRow(1L, "SUPER_ADMIN", "Super Administrator"),
                roleRow(2L, "CONFIG_ADMIN", "Operations Administrator"),
                roleRow(4L, "FINANCE", "财务"),
                roleRow(5L, "RISK", "风控"),
                roleRow(6L, "CONTENT", "内容"),
                roleRow(7L, "GROWTH", "增长"),
                roleRow(8L, "SUPPORT", "客服"),
                roleRow(12L, "AUDITOR", "只读审计"));
    }

    private AdminRoleOptionEntity roleRow(Long id, String roleCode, String roleName) {
        AdminRoleOptionEntity row = new AdminRoleOptionEntity();
        row.setId(id);
        row.setRoleCode(roleCode);
        row.setRoleName(roleName);
        row.setStatus(1);
        row.setIsDeleted(0);
        return row;
    }

    private void registerTestSecurityBaselines() {
        registerTestSecurity("session", "会话上限", "后台闲置 30min、绝对 8h。", "30min / 8h", false, 10);
    }

    private void registerTestSecurity(String key, String label, String description, String value, boolean locked, int sort) {
        AdminSecurityBaselineEntity row = new AdminSecurityBaselineEntity();
        row.setId((long) securityBaselineRows.size() + 1);
        row.setBaselineKey(key);
        row.setLabel(label);
        row.setDescription(description);
        row.setBaselineValue(value);
        row.setLocked(locked ? 1 : 0);
        row.setSortOrder(sort);
        row.setStatus(1);
        row.setIsDeleted(0);
        securityBaselineRows.put(key, row);
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
