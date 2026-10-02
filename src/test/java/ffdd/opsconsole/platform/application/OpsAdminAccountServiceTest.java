package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportAdminAvatarService;
import ffdd.opsconsole.content.application.SupportAttachmentPolicy;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.mapper.SupportAdminAvatarMapper;
import ffdd.opsconsole.content.mapper.SupportAttachmentMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportBulkMapper;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.AdminAccountCreateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.platform.dto.AdminAccountPasswordResetResponse;
import ffdd.opsconsole.platform.dto.AdminAccountProfileUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountSecurityBaselineUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacActionCreateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacGrantUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.infrastructure.AdminRbacActionEntity;
import ffdd.opsconsole.platform.infrastructure.AdminRbacGrantEntity;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import ffdd.opsconsole.platform.infrastructure.AdminSecurityBaselineEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.platform.mapper.AdminRbacActionMapper;
import ffdd.opsconsole.platform.mapper.AdminRbacGrantMapper;
import ffdd.opsconsole.platform.mapper.AdminSecurityBaselineMapper;
import ffdd.opsconsole.platform.mapper.AuditConfirmCategoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationHistoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.platform.mapper.OpsOptionsMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

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
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final OpsAuditCenterService auditCenterService = mock(OpsAuditCenterService.class);
    private final OpsPlatformRoleService platformRoleService = mock(OpsPlatformRoleService.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade configFacade =
            new ffdd.opsconsole.platform.application.PlatformConfigFacadeAdapter(repository);
    private final List<AdminEntity> admins = new ArrayList<>();
    private final Map<Long, String> roleRelations = new LinkedHashMap<>();
    private final Map<Long, AdminAccountStateEntity> accountStates = new LinkedHashMap<>();
    private final Map<String, AdminRbacActionEntity> rbacActionRows = new LinkedHashMap<>();
    private final Map<String, Map<String, AdminRbacGrantEntity>> rbacGrantRows = new LinkedHashMap<>();
    private final Map<String, AdminSecurityBaselineEntity> securityBaselineRows = new LinkedHashMap<>();
    private final OpsAdminAccountService service =
            new OpsAdminAccountService(auditLogService, adminMapper, roleRelationMapper, roleMapper,
                    accountStateMapper, rbacActionMapper, rbacGrantMapper, securityBaselineMapper, passwordEncoder,
                    adminSessionRegistry, permissionCache, auditCenterService, lockMapper, platformRoleService,
                    configFacade,mock(ffdd.opsconsole.content.application.SupportAdminAvatarService.class));

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        authenticateAs(1L);
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
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
        for (long adminId : List.of(1L, 2L, 3L)) {
            upsertAccountState(adminId, state -> {
                state.setTfaRequired(1);
                state.setTfaSecretEncrypted("encrypted-test-secret");
                state.setTfaBoundAt(LocalDateTime.now());
                state.setCredentialDeliveryStatus("ACTIVE");
            });
        }

        when(roleMapper.selectList(any())).thenReturn(testRoleRows());
        when(roleRelationMapper.activeRoleCode(any(Long.class)))
                .thenAnswer(invocation -> roleRelations.get(invocation.getArgument(0)));
        when(roleRelationMapper.disableOtherPrimaryRoles(any(Long.class), any(String.class))).thenReturn(1);
        when(roleRelationMapper.lockActiveRoleIdByCode(any(String.class))).thenReturn(1L);
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
        when(accountStateMapper.upsertCredentialStatus(any(Long.class), any(String.class))).thenAnswer(invocation -> {
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
                state.setTfaSecretEncrypted(null);
                state.setTfaBoundAt(null);
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
        when(adminMapper.selectList(any())).thenAnswer(invocation -> admins.stream()
                .filter(admin -> !Integer.valueOf(1).equals(admin.getIsDeleted()))
                .toList());
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
                            "pending", null));
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
                        if (patch.getPasswordHash() != null) {
                            admin.setPasswordHash(patch.getPasswordHash());
                        }
                        if (patch.getUsername() != null) {
                            admin.setUsername(patch.getUsername());
                        }
                        if (patch.getNickname() != null) {
                            admin.setNickname(patch.getNickname());
                        }
                        if (patch.getEmail() != null) {
                            admin.setEmail(patch.getEmail());
                        }
                        if (patch.getIsDeleted() != null) {
                            admin.setIsDeleted(patch.getIsDeleted());
                        }
                        admin.setUpdatedAt(LocalDateTime.now());
                    });
            return 1;
        });
        when(adminMapper.updateStatusIfVersion(anyLong(), anyLong(), anyInt())).thenAnswer(invocation -> {
            long adminId = invocation.getArgument(0);
            long candidateVersion = invocation.getArgument(1);
            int nextStatus = invocation.getArgument(2);
            AdminEntity target = admins.stream()
                    .filter(admin -> admin.getId().equals(adminId))
                    .findFirst()
                    .orElse(null);
            if (target == null || !Long.valueOf(candidateVersion).equals(target.getVersion())) return 0;
            target.setStatus(nextStatus);
            target.setVersion(target.getVersion() + 1);
            target.setUpdatedAt(LocalDateTime.now());
            return 1;
        });
        when(adminMapper.updateProfileIfVersion(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            AdminEntity target = activeVersionMatch(invocation.getArgument(0), invocation.getArgument(1));
            if (target == null) return 0;
            target.setUsername(invocation.getArgument(2));
            target.setNickname(invocation.getArgument(3));
            target.setEmail(invocation.getArgument(4));
            target.setVersion(target.getVersion() + 1);
            target.setUpdatedAt(LocalDateTime.now());
            return 1;
        });
        when(adminMapper.updateRoleIfVersion(anyLong(), anyLong(), anyInt())).thenAnswer(invocation -> {
            AdminEntity target = activeVersionMatch(invocation.getArgument(0), invocation.getArgument(1));
            if (target == null) return 0;
            target.setSuperAdmin(invocation.getArgument(2));
            target.setVersion(target.getVersion() + 1);
            target.setUpdatedAt(LocalDateTime.now());
            return 1;
        });
        when(adminMapper.updatePasswordIfVersion(anyLong(), anyLong(), anyString())).thenAnswer(invocation -> {
            AdminEntity target = activeVersionMatch(invocation.getArgument(0), invocation.getArgument(1));
            if (target == null) return 0;
            target.setPasswordHash(invocation.getArgument(2));
            target.setVersion(target.getVersion() + 1);
            target.setUpdatedAt(LocalDateTime.now());
            return 1;
        });
        when(adminMapper.incrementVersionIfVersion(anyLong(), anyLong())).thenAnswer(invocation -> {
            AdminEntity target = activeVersionMatch(invocation.getArgument(0), invocation.getArgument(1));
            if (target == null) return 0;
            target.setVersion(target.getVersion() + 1);
            target.setUpdatedAt(LocalDateTime.now());
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        A2ReplayContext.exitReplay();
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
        assertThat(result.getData().operators()).extracting(AdminAccountOverview.OperatorRecord::username)
                .contains("superadmin", "risk.lead");
        assertThat(result.getData().rbacMatrix()).extracting(AdminAccountOverview.RbacAction::id)
                .contains("operator_governance", "audit_export")
                .doesNotContain("premium", "nex-v2", "points");
    }

    @Test
    void overviewIncludesAuditableRoleHistoryBaseline() {
        ApiResult<AdminAccountOverview> result = service.overview();

        AdminAccountOverview.OperatorRecord superadmin = result.getData().operators().stream()
                .filter(operator -> "1".equals(operator.id()))
                .findFirst()
                .orElseThrow();
        assertThat(superadmin.roleHistory()).singleElement().satisfies(history -> {
            assertThat(history.toRole()).isEqualTo("super");
            assertThat(history.source()).isEqualTo("CURRENT_ASSIGNMENT");
        });
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

    /**
     * A1 锁定基线必须与 C6 登录风控(auth.risk.*)同源 —— 此前 A1 读存量固定串
     * "5次 / 15min",C6 与真实登录拦截读 auth.risk.*,同一条策略两个真相。
     * 值格式必须保持前端 a1-accounts.tsx 四个正则可解析。
     */
    @Test
    void lockBaselineIsDerivedFromTheC6AuthRiskThresholds() {
        repository.put("auth.risk.login_lock_threshold", "6", "auth");
        repository.put("auth.risk.lock_duration_minutes", "30", "auth");
        repository.put("auth.risk.login_long_lock_threshold", "12", "auth");
        repository.put("auth.risk.long_lock_duration_hours", "36", "auth");
        registerTestSecurity("lock", "登录锁定基线", "旧文案", "5次 / 15min", true, 20);

        ApiResult<AdminAccountOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        AdminAccountOverview.SecurityBaseline lock = result.getData().securityBaselines().stream()
                .filter(baseline -> "lock".equals(baseline.key()))
                .findFirst()
                .orElseThrow();
        assertThat(lock.value()).isEqualTo("6次 / 30min + 12次 / 36h");
        assertThat(lock.sub())
                .contains("6 次").contains("30 分钟").contains("12 次").contains("36 小时");
        // 派生行恒只读:改阈值只能去 C6。
        assertThat(lock.locked()).isTrue();
    }

    /** 未配置时回落到与登录拦截相同的默认阈值,而不是旧的硬编码串。 */
    @Test
    void lockBaselineFallsBackToTheSameDefaultsTheLoginGuardUses() {
        registerTestSecurity("lock", "登录锁定基线", "旧文案", "5次 / 15min", true, 20);

        ApiResult<AdminAccountOverview> result = service.overview();

        AdminAccountOverview.SecurityBaseline lock = result.getData().securityBaselines().stream()
                .filter(baseline -> "lock".equals(baseline.key()))
                .findFirst()
                .orElseThrow();
        assertThat(lock.value()).isEqualTo("5次 / 15min + 10次 / 24h");
    }

    /** 存量行会被收敛到 auth.risk.* 当前值,不再保留会漂移的旧串。 */
    @Test
    void lockBaselineRewritesAStaleStoredRowOnRead() {
        repository.put("auth.risk.login_lock_threshold", "7", "auth");
        registerTestSecurity("lock", "登录锁定基线", "旧文案", "5次 / 15min", true, 20);
        when(securityBaselineMapper.upsertBaseline(
                any(String.class), any(String.class), any(String.class), any(String.class), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    AdminSecurityBaselineEntity row = securityBaselineRows.get(invocation.getArgument(0));
                    row.setLabel(invocation.getArgument(1));
                    row.setDescription(invocation.getArgument(2));
                    row.setBaselineValue(invocation.getArgument(3));
                    row.setLocked(invocation.getArgument(4));
                    return 1;
                });

        service.overview();

        assertThat(securityBaselineRows.get("lock").getBaselineValue())
                .isEqualTo("7次 / 15min + 10次 / 24h");
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
                new AdminAccountStatusUpdateRequest(
                        "disabled", "offboarding", "superadmin", versionOf("1"));

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.updateStatus("idem-a1-1", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("MIN_EFFECTIVE_SUPER_REQUIRED");
        assertThat(admins.get(0).getStatus()).isEqualTo(1);
    }

    @Test
    void statusCasLetsOnlyOneDifferentIdempotencyKeyWinAndRevokesSessionsOnce() {
        String expectedVersion = versionOf("4");
        doAnswer(invocation -> {
            AdminEntity target = admins.stream()
                    .filter(admin -> admin.getId().equals(4L))
                    .findFirst()
                    .orElseThrow();
            if (!Long.valueOf(expectedVersion).equals(target.getVersion())) return 0;
            target.setStatus(0);
            target.setVersion(target.getVersion() + 1);
            return 1;
        }).when(adminMapper).updateStatusIfVersion(4L, Long.parseLong(expectedVersion), 0);
        AdminAccountStatusUpdateRequest request = new AdminAccountStatusUpdateRequest(
                "disabled", "fixture recovery removes compromised operator", "superadmin", expectedVersion);

        ApiResult<AdminAccountOverview.OperatorRecord> winner =
                service.updateStatus("status-race-left", "4", request);
        ApiResult<AdminAccountOverview.OperatorRecord> loser =
                service.updateStatus("status-race-right", "4", request);

        assertThat(winner.getCode()).isZero();
        assertThat(loser.getCode()).isEqualTo(409);
        assertThat(loser.getMessage()).isEqualTo("ACCOUNT_VERSION_STALE");
        verify(adminSessionRegistry).revokeSessions(4L);
    }

    @Test
    void statusCasZeroRowsFailsClosedBeforeAnySessionRevokeOrAudit() {
        String expectedVersion = versionOf("4");
        doReturn(0).when(adminMapper).updateStatusIfVersion(4L, Long.parseLong(expectedVersion), 0);
        AdminAccountStatusUpdateRequest request = new AdminAccountStatusUpdateRequest(
                "disabled", "concurrent account state changed", "superadmin", expectedVersion);

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.updateStatus("status-race-lost", "4", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ACCOUNT_VERSION_STALE");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
        verify(auditLogService, never()).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void profileRoleAndPasswordCasLosersLeaveTheirDependentSideEffectsUntouched() {
        String expectedVersion = versionOf("4");
        doReturn(0).when(adminMapper).updateProfileIfVersion(eq(4L), eq(Long.parseLong(expectedVersion)), anyString(), anyString(), anyString());
        doReturn(0).when(adminMapper).updateRoleIfVersion(4L, Long.parseLong(expectedVersion), 0);
        doReturn(0).when(adminMapper).updatePasswordIfVersion(eq(4L), eq(Long.parseLong(expectedVersion)), anyString());

        ApiResult<AdminAccountOverview.OperatorRecord> profile = service.updateProfile("profile-race", "4",
                new AdminAccountProfileUpdateRequest("risk.shift", "风控值班长", "", "concurrent profile", "superadmin", expectedVersion));
        ApiResult<AdminAccountOverview.OperatorRecord> role = service.changeRole("role-race", "4",
                new AdminAccountRoleUpdateRequest("unassigned", "concurrent role", "superadmin", expectedVersion));
        ApiResult<AdminAccountPasswordResetResponse> password = service.resetPassword("password-race", "4",
                new AdminAccountActionRequest("concurrent credential reset", "superadmin", expectedVersion));

        assertThat(profile.getCode()).isEqualTo(409);
        assertThat(role.getCode()).isEqualTo(409);
        assertThat(password.getCode()).isEqualTo(409);
        verify(roleRelationMapper, never()).disableAllPrimaryRoles(4L);
        verify(permissionCache, never()).evict(4L);
        verify(accountStateMapper, never()).upsertCredentialStatus(4L, "PASSWORD_CHANGE_REQUIRED");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
    }

    @Test
    void refreshedVersionAllowsTheNextSensitiveAccountMutation() {
        String before = versionOf("4");
        ApiResult<AdminAccountOverview.OperatorRecord> disabled = service.updateStatus("status-winner", "4",
                new AdminAccountStatusUpdateRequest("disabled", "first operator wins", "superadmin", before));
        String refreshed = versionOf("4");

        ApiResult<AdminAccountOverview.OperatorRecord> profile = service.updateProfile("profile-refreshed", "4",
                new AdminAccountProfileUpdateRequest("risk.shift", "风控值班长", "", "fresh version follows status", "superadmin", refreshed));

        assertThat(disabled.getCode()).isZero();
        assertThat(refreshed).isNotEqualTo(before);
        assertThat(profile.getCode()).isZero();
    }

    @Test
    void missingSingleSessionFailsBeforeCasAuditOrAccountStateWrites() {
        String expectedVersion = versionOf("4");
        AdminAccountActionRequest request = new AdminAccountActionRequest(
                "target session no longer exists", "superadmin", expectedVersion);

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.revokeSession("single-session-missing", "4", "missing-session", request);

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("ADMIN_SESSION_NOT_FOUND");
        verify(adminMapper, never()).incrementVersionIfVersion(anyLong(), anyLong());
        verify(accountStateMapper, never()).upsertSessionsRevokedAt(eq(4L), any(LocalDateTime.class));
        verify(auditLogService, never()).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void createAccountPersistsRealAdminWithOneTimeServerCredentialAndWritesAudit() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "risk.new",
                "新风控成员",
                null,
                "risk",
                null,
                "new employee onboarding",
                "superadmin",
                "RiskNew@12345678");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("5");
        assertThat(result.getData().name()).isEqualTo("新风控成员");
        assertThat(result.getData().username()).isEqualTo("risk.new");
        assertThat(result.getData().email()).isEmpty();
        assertThat(result.getData().credentialDeliveryStatus()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(result.getData().role()).isEqualTo("risk");
        assertThat(repository.items).doesNotContainKey("a1.account.5.role");
        assertThat(repository.items).doesNotContainKey("a1.account.5.registered");
        assertThat(repository.items).doesNotContainKey("a1.account.5.tfa");
        assertThat(repository.items).doesNotContainKey("a1.account.5.credentialDeliveryStatus");
        assertThat(repository.items).doesNotContainKey("a1.account.5.createdAt");
        assertThat(accountStates.get(5L).getTfaRequired()).isEqualTo(1);
        assertThat(accountStates.get(5L).getCredentialDeliveryStatus()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(admins).extracting(AdminEntity::getUsername).contains("risk.new");
        AdminEntity created = admins.stream()
                .filter(admin -> "risk.new".equals(admin.getUsername()))
                .findFirst()
                .orElseThrow();
        assertThat(result.getData().temporaryPassword()).hasSize(20);
        assertThat(passwordEncoder.matches(result.getData().temporaryPassword(), created.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("RiskNew@12345678", created.getPasswordHash())).isFalse();
        assertThat(result.getData().temporaryPassword()).isNotEqualTo("RiskNew@12345678");
        verify(roleRelationMapper).ensurePrimaryRole(5L, "RISK");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A1_OPERATOR_CREATED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A1_ADMIN_ACCOUNT");
        // Task2 移除 createAccount 的 linkA2Proposal 调用(不再留痕 A2 recordExecuted),
        // 故不再 verify auditCenterService.recordExecuted; 仅保留 auditLogService 审计断言。
    }

    @Test
    void createAccountAcceptsOptionalValidEmail() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "content.shift",
                "内容值班",
                "content-shift@example.com",
                "content",
                null,
                "new employee onboarding",
                "superadmin",
                "ContentShift@123");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-valid-email", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().username()).isEqualTo("content.shift");
        assertThat(result.getData().email()).isEqualTo("content-shift@example.com");
        assertThat(admins).extracting(AdminEntity::getEmail).contains("content-shift@example.com");
        verify(roleRelationMapper).ensurePrimaryRole(5L, "CONTENT");
    }

    @Test
    void updateProfileWritesRealAdminColumnsAndRevokesSessionsWhenLoginNameChanges() {
        AdminAccountProfileUpdateRequest request = new AdminAccountProfileUpdateRequest(
                "risk.shift",
                "风控值班长",
                "",
                "operator profile correction",
                "superadmin",
                versionOf("4"));

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.updateProfile("idem-profile-1", "4", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().username()).isEqualTo("risk.shift");
        assertThat(result.getData().name()).isEqualTo("风控值班长");
        assertThat(result.getData().email()).isEmpty();
        AdminEntity target = admins.stream()
                .filter(admin -> admin.getId().equals(4L))
                .findFirst()
                .orElseThrow();
        assertThat(target.getUsername()).isEqualTo("risk.shift");
        assertThat(target.getNickname()).isEqualTo("风控值班长");
        assertThat(target.getEmail()).isEmpty();
        verify(adminSessionRegistry).revokeSessions(4L);
        assertThat(accountStates.get(4L).getSessionsRevokedAt()).isNotNull();
        assertThat(repository.items).doesNotContainKey("a1.account.4.profile");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A1_OPERATOR_PROFILE_UPDATED");
        // Task2 移除 updateProfile 的 linkA2Proposal 调用,不再 verify auditCenterService.recordExecuted。
    }

    @Test
    void deleteAccountIsDisabledAndLeavesAdminUntouched() {
        AdminAccountActionRequest request = new AdminAccountActionRequest("operator left company", "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.deleteAccount("idem-delete-1", "4", request);

        assertThat(result.getCode()).isEqualTo(405);
        assertThat(result.getMessage()).isEqualTo("ACCOUNT_DELETE_DISABLED_USE_DISABLE");
        AdminEntity target = admins.stream()
                .filter(admin -> admin.getId().equals(4L))
                .findFirst()
                .orElseThrow();
        assertThat(target.getStatus()).isEqualTo(1);
        assertThat(target.getIsDeleted()).isZero();
        verify(adminSessionRegistry, never()).revokeSessions(4L);
    }

    @Test
    void deleteAccountIsDisabledForSelfAndSuperTargetsToo() {
        AdminAccountActionRequest request = new AdminAccountActionRequest("bad delete", "superadmin");

        ApiResult<AdminAccountOverview.OperatorRecord> selfResult =
                service.deleteAccount("idem-delete-self", "1", request);

        assertThat(selfResult.getCode()).isEqualTo(405);
        assertThat(selfResult.getMessage()).isEqualTo("ACCOUNT_DELETE_DISABLED_USE_DISABLE");

        admins.get(2).setStatus(0);
        ApiResult<AdminAccountOverview.OperatorRecord> minSuperResult =
                service.deleteAccount("idem-delete-min-super", "2", request);

        assertThat(minSuperResult.getCode()).isEqualTo(405);
        assertThat(minSuperResult.getMessage()).isEqualTo("ACCOUNT_DELETE_DISABLED_USE_DISABLE");
        assertThat(admins.get(1).getIsDeleted()).isZero();
        verify(adminSessionRegistry, never()).revokeSessions(1L);
        verify(adminSessionRegistry, never()).revokeSessions(2L);
    }

    @Test
    void changeRoleAcceptsExplicitUnassignedAndRemovesThePrimaryRole() {
        AdminAccountRoleUpdateRequest request =
                new AdminAccountRoleUpdateRequest(
                        "unassigned", "remove obsolete access", "superadmin", versionOf("4"));

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.changeRole("idem-unassign-1", "4", request);

        assertThat(result.getCode()).isZero();
        verify(roleRelationMapper).disableAllPrimaryRoles(4L);
        verify(roleRelationMapper, never()).ensurePrimaryRole(eq(4L), anyString());
    }

    @Test
    void reset2faRejectsEffectiveSuperFloor() {
        admins.get(2).setStatus(0);
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("security recovery", "superadmin", versionOf("1"));

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.reset2fa("idem-reset-super-floor", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("MIN_EFFECTIVE_SUPER_REQUIRED");
        verify(accountStateMapper, never()).upsertTfaResetAt(eq(1L), any(LocalDateTime.class));
        verify(adminSessionRegistry, never()).revokeSessions(1L);
    }

    @Test
    void reset2faRevokesExistingSessionsAndClearsBinding() {
        upsertAccountState(4L, state -> {
            state.setTfaRequired(1);
            state.setTfaSecretEncrypted("encrypted-risk-secret");
            state.setTfaBoundAt(LocalDateTime.now());
        });
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("verified recovery", "superadmin", versionOf("4"));

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.reset2fa("idem-reset-risk", "4", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().tfa()).isFalse();
        verify(adminSessionRegistry).revokeSessions(4L);
        verify(accountStateMapper).upsertSessionsRevokedAt(eq(4L), any(LocalDateTime.class));
    }

    @Test
    void createAccountRejectsInvalidEmailFormat() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "external.ops",
                "外部人员",
                "external.example.com",
                "content",
                null,
                "invalid email should be rejected",
                "superadmin",
                "ExternalOps@123");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-invalid-email", request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("EMAIL_FORMAT_INVALID");
    }

    @Test
    void createAccountGeneratesServerSideTemporaryPasswordAndIgnoresClientCredential() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "finance.shift",
                "资金测试值班",
                null,
                "finance",
                null,
                "local e2e shift bootstrap",
                "superadmin",
                "");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-shift", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().temporaryPassword())
                .hasSize(20)
                .containsPattern("[a-z]")
                .containsPattern("[A-Z]")
                .containsPattern("\\d")
                .containsPattern("[^A-Za-z0-9]");
        AdminEntity created = admins.stream()
                .filter(admin -> "finance.shift".equals(admin.getUsername()))
                .findFirst()
                .orElseThrow();
        assertThat(passwordEncoder.matches(result.getData().temporaryPassword(), created.getPasswordHash())).isTrue();
        assertThat(result.getData().temporaryPassword()).isNotEqualTo(request.initialPassword());
    }

    @Test
    void createAccountRejectsInvalidOrDuplicateUsername() {
        AdminAccountCreateRequest request = new AdminAccountCreateRequest(
                "bad name",
                "资金测试值班",
                null,
                "finance",
                null,
                "local e2e shift bootstrap",
                "superadmin",
                "E2eShift@12345");

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.createAccount("idem-create-shift", request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USERNAME_INVALID");

        when(adminMapper.selectOne(any())).thenReturn(admins.get(3));
        ApiResult<AdminAccountOverview.OperatorRecord> duplicate = service.createAccount(
                "idem-create-duplicate",
                new AdminAccountCreateRequest(
                        "risk.lead",
                        "重复账号",
                        null,
                        "risk",
                        null,
                        "duplicate username",
                        "superadmin",
                        "RiskLead@12345"));

        assertThat(duplicate.getCode()).isEqualTo(409);
        assertThat(duplicate.getMessage()).isEqualTo("ADMIN_USERNAME_EXISTS");
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
                "9999",
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
                        "risk.lead",
                        "专属客服新账号",
                        "risk@nexion.io",
                        "support",
                        null,
                        "客服主管不能创建后台账号",
                        "support.manager",
                        "SupportNew@123"));
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
                new AdminAccountSecurityBaselineUpdateRequest(
                        "45min / 10h", "shorten console sessions", "superadmin", "30min / 8h");

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
    void legacyA1RbacGrantMatrixIsRejectedBecauseA6IsAuthoritative() {
        AdminRbacGrantUpdateRequest auditWriteRequest = new AdminRbacGrantUpdateRequest(
                List.of("C", "C", "-", "-", "-", "-", "M", "M"),
                "bad audit grant",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> auditResult =
                service.updateRbacGrants("idem-rbac-1", "balance_adjust", auditWriteRequest);

        assertThat(auditResult.getCode()).isEqualTo(409);
        assertThat(auditResult.getMessage()).isEqualTo("RBAC_AUTHORITY_MOVED_TO_A6");

        AdminRbacGrantUpdateRequest superRemovedRequest = new AdminRbacGrantUpdateRequest(
                List.of("-", "-", "-", "-", "-", "-", "-", "R"),
                "remove super",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> superResult =
                service.updateRbacGrants("idem-rbac-2", "operator_governance", superRemovedRequest);

        assertThat(superResult.getCode()).isEqualTo(409);
        assertThat(superResult.getMessage()).isEqualTo("RBAC_AUTHORITY_MOVED_TO_A6");
    }

    @Test
    void legacyA1RbacGrantEndpointDoesNotWriteAnyGrantTable() {
        AdminRbacGrantUpdateRequest request = new AdminRbacGrantUpdateRequest(
                List.of("C", "R", "-", "M", "-", "-", "R", "R"),
                "tighten risk grants",
                "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> result =
                service.updateRbacGrants("idem-rbac-write", "balance_adjust", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("RBAC_AUTHORITY_MOVED_TO_A6");
        assertThat(repository.items).doesNotContainKey("a1.rbac.risk.balance_adjust");
        assertThat(repository.items).doesNotContainKey("a1.rbac.config.balance_adjust");
    }

    @Test
    void resetPasswordReturnsTemporaryPasswordOnceAndMarksChangeRequired() {
        authenticateAs(1L);
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("operator forgot password", "superadmin", versionOf("4"));

        ApiResult<AdminAccountPasswordResetResponse> result =
                service.resetPassword("idem-password-reset", "4", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().account().id()).isEqualTo("4");
        assertThat(result.getData().temporaryPassword())
                .hasSize(20)
                .matches("Aa1![23456789abcdefghjkmnpqrstuvwxyz]{16}");
        AdminEntity target = admins.stream()
                .filter(admin -> admin.getId().equals(4L))
                .findFirst()
                .orElseThrow();
        assertThat(passwordEncoder.matches(result.getData().temporaryPassword(), target.getPasswordHash())).isTrue();
        assertThat(accountStates.get(4L).getCredentialDeliveryStatus()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        verify(adminSessionRegistry).revokeSessions(4L);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A1_OPERATOR_PASSWORD_RESET");
        assertThat(captor.getValue().toString()).doesNotContain(result.getData().temporaryPassword());
    }

    @Test
    void revokeSessionsDeletesRedisBackedAdminSessionsAndAudits() {
        authenticateAs(1L);
        when(adminSessionRegistry.revokeSessions(4L)).thenReturn(2);
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("suspected account takeover", "superadmin", versionOf("4"));

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
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("suspected support console misuse", "risk.lead", versionOf("5"));

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-risk", "5", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(5L);
    }

    @Test
    void revokeSessionsRejectsDisabledSuperTarget() {
        admins.add(admin(6L, "ops.shift", "平台审计值班", "ops.shift@nexion.io", 1, 0));
        authenticateAs(1L);
        AdminAccountActionRequest request = new AdminAccountActionRequest(
                "super cleanup requires account-disable flow", "superadmin", versionOf("6"));

        ApiResult<AdminAccountOverview.OperatorRecord> result =
                service.revokeSessions("idem-session-e2e-cleanup", "6", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_SUPER_TARGET_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(6L);
    }

    @Test
    void revokeSessionsRejectsSelfTarget() {
        authenticateAs(4L);
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("self test should be blocked", "risk.lead", versionOf("4"));

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
        AdminAccountActionRequest request =
                new AdminAccountActionRequest("finance should not force logout", "finance.ops", versionOf("4"));

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-role", "4", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
    }

    @Test
    void revokeSessionsRejectsRiskOperatorBeforeSuperTargetCheck() {
        authenticateAs(4L);
        AdminAccountActionRequest request = new AdminAccountActionRequest(
                "super admin target should be protected", "risk.lead", versionOf("1"));

        ApiResult<AdminAccountOverview.OperatorRecord> result = service.revokeSessions("idem-session-super-target", "1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("FORCE_LOGOUT_ROLE_FORBIDDEN");
        verify(adminSessionRegistry, never()).revokeSessions(1L);
    }

    @Test
    void legacyA1ActionRegistrationIsRejectedBecauseA8DictionaryIsAuthoritative() {
        AdminRbacActionCreateRequest request =
                new AdminRbacActionCreateRequest("批量补发收益(E3)", "增长/内容", "new sensitive action", "superadmin");

        ApiResult<AdminAccountOverview.RbacAction> result = service.registerRbacAction("idem-rbac-create", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("RBAC_AUTHORITY_MOVED_TO_A6");
        assertThat(rbacActionRows).doesNotContainKey("e3");
    }

    @Test
    void replayA1AccountStatusUpdateInvokesUpdateStatusAndSucceeds() {
        AuditReplayCommand cmd = new AuditReplayCommand("A", "a1_account_status_update", Map.of(
                "accountId", "4",
                "status", "disabled",
                "expectedVersion", versionOf("4")));
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay disable risk operator", "idem-replay-status");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isZero();
        AdminEntity target = admins.stream()
                .filter(admin -> admin.getId().equals(4L))
                .findFirst()
                .orElseThrow();
        assertThat(target.getStatus()).isZero();
    }

    @Test
    void replayA6RoleGrantProposalDelegatesToAuthoritativeRoleService() {
        AuditReplayCommand cmd = new AuditReplayCommand("A", "a6_role_grants_update", Map.of(
                "roleId", 9L,
                "permissionCodes", List.of("user_c1_read"),
                "menuIds", List.of(3L)));
        AuditReplayContext ctx = new AuditReplayContext("approver", "approved role grant", "idem-a6-replay");
        when(platformRoleService.updateRoleGrants(eq(9L), eq("idem-a6-replay"), any()))
                .thenReturn(ApiResult.ok(null));

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isZero();
        verify(platformRoleService).updateRoleGrants(eq(9L), eq("idem-a6-replay"), any());
    }

    @Test
    void replayA1AccountCreateInvokesCreateAccountAndSucceeds() {
        AuditReplayCommand cmd = new AuditReplayCommand("A", "a1_account_create", Map.of(
                "username", "risk.replay",
                "displayName", "回放风控",
                "role", "risk",
                "initialPassword", "ReplayRisk@12345"));
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay create risk operator", "idem-replay-create");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isZero();
        assertThat(admins).extracting(AdminEntity::getUsername).contains("risk.replay");
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        AuditReplayCommand cmd = new AuditReplayCommand("A", "a1_unknown_op", Map.of());
        AuditReplayContext ctx = new AuditReplayContext("superadmin", "replay unknown op", "idem-replay-unknown");

        ApiResult<?> result = service.replay(cmd, ctx);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:a1_unknown_op");
    }

    @Test
    void a2ApprovalCreatesAccountWithMakersAvatarAsDifferentChecker() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        AuditReplayCommand command = new AuditReplayCommand("A", "a1_account_create", Map.of(
                "username", "avatar.new", "displayName", "New Support", "email", "avatar-new@example.test",
                "role", "support", "avatarAssetId", fixture.assetId));
        String operationId = fixture.propose(command);

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isZero();
        assertThat(result.getData().status()).isEqualTo("approved");
        AdminEntity created = admins.stream().filter(admin -> "avatar.new".equals(admin.getUsername()))
                .findFirst().orElseThrow();
        fixture.assertAttached(created.getId());
        assertThat(fixture.accounts.overview().getData().operators())
                .filteredOn(account -> String.valueOf(created.getId()).equals(account.id()))
                .singleElement().satisfies(account -> assertThat(account.avatarAssetId()).isEqualTo(fixture.assetId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Avatar.New", " avatar.new "})
    void a2AvatarCreateKeepsOriginalUsernameNormalization(String username) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        AuditReplayCommand command = new AuditReplayCommand("A", "a1_account_create", Map.of(
                "username", username, "displayName", "New Support", "email", "avatar-new@example.test",
                "role", "support", "avatarAssetId", fixture.assetId));
        String operationId = fixture.propose(command);
        assertThat(fixture.ticketRows.get(operationId).getObjectText()).isEqualTo("avatar.new");
        assertThat(fixture.activeLocks.get(operationId).getTargetId()).isEqualTo("avatar.new");

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isZero();
        AdminEntity created = admins.stream().filter(admin -> "avatar.new".equals(admin.getUsername()))
                .findFirst().orElseThrow();
        fixture.assertAttached(created.getId());
    }

    @Test
    void a2ApprovalUpdatesAccountWithMakersAvatarAsDifferentChecker() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        AuditReplayCommand command = new AuditReplayCommand("A", "a1_account_update_profile", Map.of(
                "accountId", "4", "username", "risk.lead", "displayName", "Updated Risk",
                "email", "risk@nexion.io", "expectedVersion", "0", "avatarAssetId", fixture.assetId));
        String operationId = fixture.propose(command);

        ApiResult<AuditCenterOverview.AuditOperationTicket> result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isZero();
        assertThat(result.getData().status()).isEqualTo("approved");
        fixture.assertAttached(4L);
        assertThat(admins.get(3).getVersion()).isEqualTo(1L);
        assertThat(fixture.accounts.overview().getData().operators()).filteredOn(account -> "4".equals(account.id()))
                .singleElement().satisfies(account -> {
                    assertThat(account.avatarAssetId()).isEqualTo(fixture.assetId);
                    assertThat(account.name()).isEqualTo("Updated Risk");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a2ApprovalRejectsMakerAndRenamedMakerSelfReview(boolean renamed) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        if (renamed) admins.get(0).setUsername("renamed.maker");
        fixture.authenticate(1L);

        var result = fixture.approveAuthenticated(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
        assertThat(fixture.ticketRows.get(operationId).getStatus()).isEqualTo("pending");
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform_a1_write", "platform_a2_operation_approve"})
    void a2ApprovalRejectsCheckerWithoutOriginalPermissions(String missingAuthority) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        fixture.authenticate(2L, AvatarApprovalFixture.AUTHORITIES.stream()
                .filter(authority -> !authority.equals(missingAuthority)).toList());

        var result = fixture.approveAuthenticated(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    void a2ApprovalRejectsNonSuperCheckerEvenWithA1AndA2Grants() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        fixture.authenticate(4L);

        var result = fixture.approveAuthenticated(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
    }

    @ParameterizedTest
    @ValueSource(longs = {2L, 3L})
    void a2ApprovalRejectsCheckerAndThirdPartyAvatar(long uploader) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.replaceAsset(uploader, "READY", null, false);
        String operationId = fixture.propose(fixture.updateCommand());

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(404);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(fixture.ticketRows.get(operationId).getStatus()).isEqualTo("pending");
    }

    @Test
    void a2ProposalIgnoresForgedMakerFieldsInParams() throws Exception {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        Map<String, Object> params = new LinkedHashMap<>(fixture.updateCommand().params());
        params.put("_avatarMakerAdminId", 3L);
        params.put("avatarMakerAdminId", 2L);
        String operationId = fixture.propose(new AuditReplayCommand("A", "a1_account_update_profile", params));
        var stored = new ObjectMapper().readTree(fixture.ticketRows.get(operationId).getCommandJson());
        assertThat(stored.path("avatarMakerAdminId").asLong()).isEqualTo(1L);

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isZero();
        fixture.assertAttached(4L);
    }

    @Test
    void a2ApprovalRejectsLegacyAvatarTicketWithoutServerMaker() throws Exception {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        var stored = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper()
                .readTree(fixture.ticketRows.get(operationId).getCommandJson());
        stored.remove("avatarMakerAdminId");
        fixture.ticketRows.get(operationId).setCommandJson(stored.toString());

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isIn(403, 422);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"READY,true", "CANCELLED,false", "ATTACHED,false"})
    void a2ApprovalPreservesAvatarExpiryAndStateChecks(String state, boolean expired) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.replaceAsset(1L, state, "ATTACHED".equals(state) ? 4L : null, expired);
        if ("ATTACHED".equals(state)) upsertAccountState(4L, account -> {
            account.setAvatarAssetId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            account.setAvatarVersion(2L);
        });
        String operationId = fixture.propose(fixture.updateCommand());

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(409);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(fixture.assets.get(fixture.assetId).state()).isEqualTo(state);
    }

    @Test
    void a2ApprovalCasConflictDoesNotReadOrAttachAvatar() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        when(adminMapper.updateProfileIfVersion(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(409);
        verify(fixture.avatarMapper, never()).lock(anyString());
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
        assertThat(fixture.ticketRows.get(operationId).getStatus()).isEqualTo("pending");
    }

    @Test
    void a2ApprovalStorageFailureDoesNotAttachOrLeakContext() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        when(fixture.storage.exists(anyString())).thenReturn(false);

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(503);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("2");
        assertThat(fixture.ticketRows.get(operationId).getStatus()).isEqualTo("pending");
    }

    @Test
    void a2ApprovalUncheckedExceptionDoesNotLeakContext() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        when(fixture.storage.exists(anyString())).thenThrow(new IllegalStateException("injected storage failure"));

        assertThatThrownBy(() -> fixture.approve(operationId))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected storage failure");

        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("2");
    }

    @Test
    void a2ApprovalIdempotentRetryDoesNotAttachOrIncrementTwice() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());

        var first = fixture.approve(operationId);
        var retry = fixture.approve(operationId);

        assertThat(first.getCode()).as(first.getMessage()).isZero();
        assertThat(retry.getCode()).as(retry.getMessage()).isZero();
        assertThat(retry.getData().id()).isEqualTo(first.getData().id());
        fixture.assertAttached(4L);
        assertThat(admins.get(3).getVersion()).isEqualTo(1L);
        verify(fixture.avatarMapper, times(1)).attach(fixture.assetId, 4L);
        verify(fixture.avatarMapper, times(1)).accountAvatar(4L, fixture.assetId);
    }

    @Test
    void a2ApprovalDoesNotGiveCheckerMakerPreviewOrCancellationRights() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        assertThat(fixture.approve(operationId).getCode()).isZero();

        assertThatThrownBy(() -> fixture.avatars.preview(fixture.assetId))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> fixture.avatars.cancel(fixture.assetId, "cancel-idem"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        fixture.assertAttached(4L);
        verify(fixture.avatarMapper, never()).cancel(anyString());
        verify(fixture.storage, never()).get(anyString());
    }

    @ParameterizedTest
    @CsvSource({"accountId,3", "displayName,Injected Name", "avatarAssetId,bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"})
    void a2ApprovalRejectsChangesToFrozenCommand(String field, String changedValue) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture(accounts -> new AuditReplayable() {
            public String domain() { return accounts.domain(); }
            public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
                Map<String, Object> changed = new LinkedHashMap<>(command.params());
                changed.put(field, changedValue);
                return accounts.replay(new AuditReplayCommand(command.domain(), command.op(), changed), context);
            }
        });
        String operationId = fixture.propose(fixture.updateCommand());

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
        verify(adminMapper, never()).updateProfileIfVersion(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a2ApprovalRejectsChangesToFrozenDomainOrOperation(boolean changeDomain) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture(accounts -> new AuditReplayable() {
            public String domain() { return accounts.domain(); }
            public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
                return accounts.replay(new AuditReplayCommand(changeDomain ? "B" : command.domain(),
                        changeDomain ? command.op() : "a1_account_create", command.params()), context);
            }
        });
        String operationId = fixture.propose(fixture.updateCommand());

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins).hasSize(4);
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    void a2ApprovalRejectsReplayWithAnotherTicketId() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture(accounts -> new AuditReplayable() {
            public String domain() { return accounts.domain(); }
            public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
                A2ReplayContext.enterReplay("WO-ANOTHER-TICKET");
                return accounts.replay(command, context);
            }
        });
        String operationId = fixture.propose(fixture.updateCommand());

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    void a2ApprovalCannotAttachAnotherTargetDuringItsRealAvatarRead() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        fixture.avatarReadProbe = () -> assertThatThrownBy(() -> fixture.avatars.attach(3L, fixture.assetId))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isZero();
        fixture.assertAttached(4L);
        verify(fixture.avatarMapper, never()).attach(anyString(), eq(3L));
        assertThat(accountStates.get(3L).getAvatarAssetId()).isNull();
    }

    @Test
    void a2ApprovalCannotAttachAnotherMakerAssetDuringItsRealAvatarRead() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String other = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        SupportAvatarAsset original = fixture.assets.get(fixture.assetId);
        fixture.assets.put(other, new SupportAvatarAsset(other, 1L, "other-upload", "other-upload-idem",
                original.requestHash(), original.mime(), original.byteCount(), "private/admin-avatar/other",
                "READY", null, original.expiresAt()));
        String operationId = fixture.propose(fixture.updateCommand());
        fixture.avatarReadProbe = () -> assertThatThrownBy(() -> fixture.avatars.attach(4L, other))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));

        var result = fixture.approve(operationId);

        assertThat(result.getCode()).as(result.getMessage()).isZero();
        fixture.assertAttached(4L);
        assertThat(fixture.assets.get(other).state()).isEqualTo("READY");
        verify(fixture.avatarMapper, never()).attach(eq(other), anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L})
    void plainReplayFlagDoesNotAuthorizeMakerOrCheckerAvatar(long uploader) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.replaceAsset(uploader, "READY", null, false);
        fixture.authenticate(2L);
        A2ReplayContext.enterReplay("WO-ORDINARY-REPLAY");
        try {
            assertThatThrownBy(() -> fixture.avatars.attach(4L, fixture.assetId))
                    .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
            fixture.assertNoAvatarWrites();
        } finally {
            A2ReplayContext.exitReplay();
        }
        fixture.assertContextCleared();
    }

    @Test
    void directMakerAttachAndSameTargetRetryKeepOriginalOwnershipGuard() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.authenticate(1L);

        fixture.avatars.attach(4L, fixture.assetId);
        fixture.avatars.attach(4L, fixture.assetId);

        assertThat(fixture.assets.get(fixture.assetId).state()).isEqualTo("ATTACHED");
        assertThat(fixture.assets.get(fixture.assetId).attachedAdminId()).isEqualTo(4L);
        assertThat(accountStates.get(4L).getAvatarAssetId()).isEqualTo(fixture.assetId);
        assertThat(accountStates.get(4L).getAvatarVersion()).isEqualTo(1L);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("1");
        verify(fixture.avatarMapper, times(1)).attach(fixture.assetId, 4L);
        fixture.assertContextCleared();
    }

    @Test
    void a2ApprovalUsesCheckerIdWhenOldMakerUsernameIsReassigned() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        String oldName = admins.get(0).getUsername();
        admins.get(0).setUsername("renamed.maker");
        admins.get(1).setUsername(oldName);

        assertThat(fixture.approve(operationId).getCode()).isZero();
        fixture.assertAttached(4L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "1.5", "0", "-1", "9007199254740992", "9223372036854775808", "null"})
    void a2ApprovalRejectsInvalidServerMakerId(String makerJson) throws Exception {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        var mapper = new ObjectMapper();
        var stored = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fixture.ticketRows.get(operationId).getCommandJson());
        stored.set("avatarMakerAdminId", mapper.readTree(makerJson));
        fixture.ticketRows.get(operationId).setCommandJson(stored.toString());

        assertThat(fixture.approve(operationId).getCode()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    void a2ApprovalRejectsUserSubjectEvenWhenNumericIdAndGrantsOverlap() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        fixture.authenticate(2L);
        ((UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
                .setDetails(Map.of("subjectType", "USER", "username", "customer"));

        assertThat(fixture.approveAuthenticated(operationId).getCode()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatarAssetId", "expectedVersion"})
    void a2ApprovalRejectsRemovingAnyFrozenField(String field) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture(accounts -> new AuditReplayable() {
            public String domain() { return accounts.domain(); }
            public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
                command.params().remove(field);
                return accounts.replay(command, context);
            }
        });
        String operationId = fixture.propose(fixture.updateCommand());

        assertThat(fixture.approve(operationId).getCode()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a2ApprovalRejectsInPlaceMutationOfNestedFrozenPayload() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture(accounts -> new AuditReplayable() {
            public String domain() { return accounts.domain(); }
            public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
                ((Map<String, Object>) command.params().get("extra")).put("nested", List.of("changed"));
                return accounts.replay(command, context);
            }
        });
        var params = new LinkedHashMap<>(fixture.updateCommand().params());
        params.put("extra", Map.of("nested", List.of("original")));
        String operationId = fixture.propose(new AuditReplayCommand("A", "a1_account_update_profile", params));

        assertThat(fixture.approve(operationId).getCode()).isEqualTo(403);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    void a2AvatarCreateDoesNotFallBackToAnExistingAccountWhenGeneratedIdIsMissing() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(new AuditReplayCommand("A", "a1_account_create", Map.of(
                "username", "avatar.new", "displayName", "New Support", "email", "avatar-new@example.test",
                "role", "support", "avatarAssetId", fixture.assetId)));
        when(adminMapper.insert(any(AdminEntity.class))).thenReturn(1);

        assertThat(fixture.approve(operationId).getCode()).isEqualTo(500);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins).hasSize(4);
        assertThat(fixture.ticketRows.get(operationId).getStatus()).isEqualTo("pending");
    }

    @Test
    void directDispatcherDoesNotMintApprovedAvatarOwnership() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.authenticate(1L);

        assertThatThrownBy(() -> new AuditReplayDispatcher(List.of(fixture.accounts)).dispatch(
                fixture.updateCommand(), new AuditReplayContext("superadmin", "Direct dispatch is not approval", "direct-dispatch")))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
    }

    @Test
    void approvedAvatarContextIsNotInheritedByAnotherThread() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        fixture.avatarReadProbe = () -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            assertThat(A2ReplayContext.hasAvatarApproval()).isFalse();
            assertThat(A2ReplayContext.isReplaying()).isFalse();
            assertThatThrownBy(() -> A2ReplayContext.approvedAvatarUploader(2L, 4L, fixture.assetId))
                    .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
            A2ReplayContext.exitReplay();
        }).join();

        assertThat(fixture.approve(operationId).getCode()).isZero();
        fixture.assertAttached(4L);
    }

    @Test
    void a2AvatarProposalRejectsAnObjectLockForAnotherAccount() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.authenticate(1L);
        var result = fixture.audit.createProposal("mismatched-target", new AuditOperationProposalRequest(
                "Account avatar", "3", "before", "after", "request-actor", "Super", "acct", false, false,
                "Super", "Avatar request must use the actual account", "A", fixture.updateCommand(),
                new AuditLockTarget("A", "account", "3"), null));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(fixture.ticketRows).isEmpty();
        verify(lockMapper, never()).insert(any(ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity.class));
        fixture.assertNoAvatarWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "domain", "type", "extra"})
    void a2AvatarProposalRequiresOneExactAccountTarget(String changed) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.authenticate(1L);
        AuditLockTarget target = switch (changed) {
            case "missing" -> null;
            case "domain" -> new AuditLockTarget("B", "account", "4");
            case "type" -> new AuditLockTarget("A", "role", "4");
            default -> new AuditLockTarget("A", "account", "4");
        };
        var result = fixture.audit.createProposal("mismatched-target", new AuditOperationProposalRequest(
                "Account avatar", "4", "before", "after", "request-actor", "Super", "acct", false, false,
                "Super", "Avatar request must use one account", "A", fixture.updateCommand(), target,
                "extra".equals(changed) ? List.of(new AuditLockTarget("A", "account", "3")) : null));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(fixture.ticketRows).isEmpty();
        verify(lockMapper, never()).insert(any(ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity.class));
        fixture.assertNoAvatarWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"object", "source", "lock", "ticket", "missing", "extra"})
    void a2AvatarApprovalRejectsPersistedTicketTargetMismatch(String changed) {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        String operationId = fixture.propose(fixture.updateCommand());
        var ticket = fixture.ticketRows.get(operationId);
        var lock = fixture.activeLocks.get(operationId);
        switch (changed) {
            case "object" -> ticket.setObjectText("3");
            case "source" -> ticket.setSourceDomain("B");
            case "lock" -> lock.setTargetId("3");
            case "ticket" -> lock.setTicketId("WO-ANOTHER-TICKET");
            case "missing" -> fixture.activeLocks.clear();
            case "extra" -> when(lockMapper.selectActiveByTicketId(operationId)).thenReturn(List.of(lock, lock));
        }

        assertThat(fixture.approve(operationId).getCode()).isIn(403, 422);
        fixture.assertNoAvatarWrites();
        fixture.assertContextCleared();
        assertThat(admins.get(3).getVersion()).isZero();
        assertThat(ticket.getStatus()).isEqualTo("pending");
    }

    @Test
    void a2AvatarProposalUsesActualTargetDomainInTicketAndAudit() {
        AvatarApprovalFixture fixture = new AvatarApprovalFixture();
        fixture.authenticate(1L);
        var result = fixture.audit.createProposal("forged-source", new AuditOperationProposalRequest(
                "Account avatar", "Misleading display", "before", "after", "request-actor", "Super", "acct", false, false,
                "Super", "Avatar request must use its actual audit domain", "D", fixture.updateCommand(),
                new AuditLockTarget("A", "account", "4"), null));

        assertThat(result.getCode()).isZero();
        var ticket = fixture.ticketRows.get(result.getData().id());
        assertThat(ticket.getSourceDomain()).isEqualTo("A");
        assertThat(ticket.getObjectText()).isEqualTo("4");
        var capture = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(capture.capture());
        assertThat(capture.getValue().getAction()).isEqualTo("A2_OPERATION_PROPOSED");
        assertThat(((Map<?, ?>) capture.getValue().getDetail()).get("sourceDomain")).isEqualTo("A");
        fixture.assertNoAvatarWrites();
    }

    /** Reuses the A1 rows above; only persistence/storage are doubles in the approval and avatar path. */
    private final class AvatarApprovalFixture {
        static final List<String> AUTHORITIES = List.of("platform_a1_read", "platform_a1_write", "platform_a2_write",
                "platform_a2_proposal_create", "platform_a2_operation_approve");
        final String assetId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        final SupportAdminAvatarMapper avatarMapper = mock(SupportAdminAvatarMapper.class);
        final ObjectStorageService storage = mock(ObjectStorageService.class);
        final AuditOperationTicketMapper tickets = mock(AuditOperationTicketMapper.class);
        final Map<String, AuditOperationTicketEntity> ticketRows = new LinkedHashMap<>();
        final Map<String, ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity> activeLocks = new LinkedHashMap<>();
        final Map<String, SupportAvatarAsset> assets = new LinkedHashMap<>();
        final SupportAdminAvatarService avatars;
        final OpsAdminAccountService accounts;
        final OpsAuditCenterService audit;
        String attachActor;
        Runnable avatarReadProbe;

        AvatarApprovalFixture() {
            this(accounts -> accounts);
        }

        AvatarApprovalFixture(Function<OpsAdminAccountService, AuditReplayable> replayTarget) {
            repository.put(A2RuntimePolicy.REASON_MIN_KEY, "8 字", "admin_a2");
            when(adminMapper.selectById(anyLong())).thenAnswer(invocation -> admins.stream()
                    .filter(admin -> admin.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
            SupportBindingMapper bindings = mock(SupportBindingMapper.class);
            when(bindings.rolesSnapshot(anyLong())).thenAnswer(invocation ->
                    List.of(roleRelations.get(invocation.getArgument(0))));
            SupportOwnershipService ownership = new SupportOwnershipService(bindings);
            SupportAttachmentPolicy policy = mock(SupportAttachmentPolicy.class);
            SupportAttachmentService attachments = new SupportAttachmentService(
                    mock(SupportAttachmentMapper.class), bindings, ownership, storage, policy,
                    mock(ProductionSupportPathGuard.class), mock(PlatformTransactionManager.class),
                    mock(SupportBulkMapper.class), new ObjectMapper());
            avatars = new SupportAdminAvatarService(avatarMapper, roleRelationMapper, ownership,
                    attachments, policy, storage);
            accounts = new OpsAdminAccountService(auditLogService, adminMapper, roleRelationMapper, roleMapper,
                    accountStateMapper, rbacActionMapper, rbacGrantMapper, securityBaselineMapper, passwordEncoder,
                    adminSessionRegistry, permissionCache, auditCenterService, lockMapper, platformRoleService,
                    configFacade, avatars);
            AdminOperatorRoleResolver roleResolver = new AdminOperatorRoleResolver(adminMapper, roleRelationMapper);
            AuditReplayBusinessPermissionGuard permissions = new AuditReplayBusinessPermissionGuard(
                    mock(TrustDisclosureRepository.class), roleResolver, mock(EmergencyControlRepository.class));
            AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
            Map<String, String> hashes = new LinkedHashMap<>();
            Map<String, Object> responses = new LinkedHashMap<>();
            when(idempotency.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0) + ":" + invocation.getArgument(1);
                String hash = invocation.getArgument(2);
                if (hashes.containsKey(key)) {
                    if (!hashes.get(key).equals(hash)) throw new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                    return responses.get(key);
                }
                java.util.function.Supplier<?> action = invocation.getArgument(4);
                Object response = action.get();
                hashes.put(key, hash);
                responses.put(key, response);
                return response;
            });
            audit = new OpsAuditCenterService(repository, auditLogService,
                    OpsReadTimeSeedPolicy.disabledForDirectConstruction(), tickets,
                    mock(AuditOperationHistoryMapper.class), mock(AuditConfirmCategoryMapper.class), lockMapper,
                    permissions, new AuditReplayDispatcher(List.of(replayTarget.apply(accounts))), new A2AccessPolicy(roleResolver, tickets),
                    new ObjectMapper(), idempotency);
            when(tickets.insert(any(AuditOperationTicketEntity.class))).thenAnswer(invocation -> {
                AuditOperationTicketEntity row = invocation.getArgument(0);
                row.setId((long) ticketRows.size() + 1);
                ticketRows.put(row.getOperationId(), row);
                return 1;
            });
            when(tickets.selectActiveByOperationIdForUpdate(anyString()))
                    .thenAnswer(invocation -> ticketRows.get(invocation.getArgument(0)));
            when(tickets.updateById(any(AuditOperationTicketEntity.class))).thenReturn(1);
            when(lockMapper.insert(any(ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity.class))).thenAnswer(invocation -> {
                ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity row = invocation.getArgument(0);
                activeLocks.put(row.getTicketId(), row);
                return 1;
            });
            when(lockMapper.selectActiveByTicketId(anyString())).thenAnswer(invocation -> {
                var row = activeLocks.get(invocation.getArgument(0));
                return row == null ? List.of() : List.of(row);
            });
            assets.put(assetId, new SupportAvatarAsset(assetId, 1L, "upload-maker", "upload-idem",
                    "image-hash", "image/png", 1L, "private/admin-avatar/test", "READY", null,
                    LocalDateTime.now(ZoneOffset.UTC).plusHours(1)));
            when(avatarMapper.lock(anyString())).thenAnswer(invocation -> {
                Runnable probe = avatarReadProbe;
                avatarReadProbe = null;
                if (probe != null) probe.run();
                attachActor = SecurityContextHolder.getContext().getAuthentication().getName();
                return assets.get(invocation.getArgument(0));
            });
            when(avatarMapper.reference(anyLong())).thenAnswer(invocation -> {
                AdminAccountStateEntity state = accountStates.get(invocation.getArgument(0));
                return state == null || state.getAvatarAssetId() == null ? null
                        : Map.of("assetId", state.getAvatarAssetId(), "version", state.getAvatarVersion());
            });
            when(storage.exists(anyString())).thenReturn(true);
            when(avatarMapper.attach(anyString(), anyLong())).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                SupportAvatarAsset row = assets.get(id);
                if (row == null || !"READY".equals(row.state())
                        || !row.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) return 0;
                assets.put(id, new SupportAvatarAsset(row.id(), row.uploaderId(), row.clientUploadId(),
                        row.idempotencyKey(), row.requestHash(), row.mime(), row.byteCount(), row.objectKey(),
                        "ATTACHED", invocation.getArgument(1), row.expiresAt()));
                return 1;
            });
            when(avatarMapper.accountAvatar(anyLong(), anyString())).thenAnswer(invocation -> {
                upsertAccountState(invocation.getArgument(0), state -> {
                    state.setAvatarAssetId(invocation.getArgument(1));
                    state.setAvatarVersion(state.getAvatarVersion() == null ? 1L : state.getAvatarVersion() + 1);
                });
                return 1;
            });
        }

        String propose(AuditReplayCommand command) {
            authenticate(1L);
            String target = String.valueOf(command.params().get(command.op().equals("a1_account_create")
                    ? "username" : "accountId"));
            var result = audit.createProposal("propose-avatar", new AuditOperationProposalRequest(
                    "账号头像修改(A1)", target, "before", "after", "ignored-request-actor", "超管", "acct",
                    false, false, "超管", "Maker requests account avatar", "A", command,
                    new AuditLockTarget("A", "account", target), null));
            assertThat(result.getCode()).as(result.getMessage()).isZero();
            return result.getData().id();
        }

        ApiResult<AuditCenterOverview.AuditOperationTicket> approve(String operationId) {
            authenticate(2L);
            return approveAuthenticated(operationId);
        }

        ApiResult<AuditCenterOverview.AuditOperationTicket> approveAuthenticated(String operationId) {
            return audit.approve("approve-avatar", operationId,
                    new AuditOperationDecisionRequest("Checker verified account avatar", "ignored-request-actor"));
        }

        void authenticate(long adminId) {
            authenticate(adminId, AUTHORITIES);
        }

        void authenticate(long adminId, List<String> authorities) {
            var authentication = new UsernamePasswordAuthenticationToken(String.valueOf(adminId), null,
                    authorities.stream()
                            .map(SimpleGrantedAuthority::new).toList());
            authentication.setDetails(Map.of("subjectType", "ADMIN", "username", admins.stream()
                    .filter(admin -> admin.getId().equals(adminId)).findFirst().orElseThrow().getUsername()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        void assertAttached(long adminId) {
            assertThat(assets.get(assetId).state()).isEqualTo("ATTACHED");
            assertThat(assets.get(assetId).attachedAdminId()).isEqualTo(adminId);
            assertThat(accountStates.get(adminId).getAvatarAssetId()).isEqualTo(assetId);
            assertThat(accountStates.get(adminId).getAvatarVersion()).isEqualTo(1L);
            assertThat(attachActor).isEqualTo("2");
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("2");
            assertContextCleared();
        }

        AuditReplayCommand updateCommand() {
            return new AuditReplayCommand("A", "a1_account_update_profile", Map.of(
                    "accountId", "4", "username", "risk.lead", "displayName", "Updated Risk",
                    "email", "risk@nexion.io", "expectedVersion", "0", "avatarAssetId", assetId));
        }

        void replaceAsset(long uploader, String state, Long attachedAdmin, boolean expired) {
            SupportAvatarAsset row = assets.get(assetId);
            assets.put(assetId, new SupportAvatarAsset(row.id(), uploader, row.clientUploadId(), row.idempotencyKey(),
                    row.requestHash(), row.mime(), row.byteCount(), row.objectKey(), state, attachedAdmin,
                    LocalDateTime.now(ZoneOffset.UTC).plusHours(expired ? -1 : 1)));
        }

        void assertNoAvatarWrites() {
            verify(avatarMapper, never()).attach(anyString(), anyLong());
            verify(avatarMapper, never()).accountAvatar(anyLong(), anyString());
        }

        void assertContextCleared() {
            assertThat(A2ReplayContext.isReplaying()).isFalse();
            assertThat(A2ReplayContext.operationId()).isNull();
            assertThat(A2ReplayContext.hasAvatarApproval()).isFalse();
        }
    }

    private void registerTestRbacActions() {
        registerTestAction("balance_adjust", "余额/资产调整(C3)", "用户/风控", 10,
                List.of("C", "-", "C", "-", "-", "-", "M", "R"));
        registerTestAction("operator_governance", "运营账号治理(A1)", "基座/应急", 20,
                List.of("M", "-", "-", "-", "-", "-", "-", "R"));
        registerTestAction("audit_export", "审计全量导出(A2)", "基座/应急", 30,
                List.of("M", "-", "-", "-", "-", "-", "-", "M"));
    }

    private String versionOf(String accountId) {
        return service.overview().getData().operators().stream()
                .filter(operator -> accountId.equals(operator.id()))
                .findFirst()
                .orElseThrow()
                .version();
    }

    private AdminEntity activeVersionMatch(long adminId, long expectedVersion) {
        return admins.stream()
                .filter(admin -> admin.getId().equals(adminId))
                .filter(admin -> Long.valueOf(expectedVersion).equals(admin.getVersion()))
                .findFirst()
                .orElse(null);
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
        entity.setVersion(0L);
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
