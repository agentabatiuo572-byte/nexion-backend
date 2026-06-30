package ffdd.opsconsole.platform.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
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
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import ffdd.opsconsole.platform.mapper.OpsOptionsMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsAdminAccountService {
    private static final String GROUP_ACCOUNT = "admin_a1_account";
    private static final String GROUP_ROLE = "admin_a1_role";
    private static final String GROUP_SECURITY = "admin_a1_security";
    private static final String GROUP_RBAC = "admin_a1_rbac";
    private static final Set<String> ACTIVE_GROUPS = Set.of(GROUP_ACCOUNT, GROUP_ROLE, GROUP_SECURITY, GROUP_RBAC);
    private static final List<String> GRANT_OPTIONS = List.of("-", "R", "M", "C");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Pattern ACTION_CODE_PATTERN = Pattern.compile("\\(([A-Za-z][A-Za-z0-9_-]*)\\)");
    private static final Pattern ROLE_REGISTRATION_PATTERN =
            Pattern.compile("^a1\\.role\\.([a-z][a-z0-9_-]*)\\.registered$");
    private static final Pattern SECURITY_BASELINE_REGISTRATION_PATTERN =
            Pattern.compile("^a1\\.security\\.baseline\\.([a-z][a-z0-9_-]*)\\.registered$");
    private static final String RBAC_ACTION_PREFIX = "a1.rbac.action.";
    private static final Set<String> SESSION_REVOKE_ROLES = Set.of("super", "risk");
    private static final Map<String, String> ROLE_CODE_TO_KEY = Map.of(
            "SUPER_ADMIN", "super",
            "CONFIG_ADMIN", "config",
            "FINANCE", "finance",
            "RISK", "risk",
            "CONTENT", "content",
            "GROWTH", "growth",
            "SUPPORT", "support",
            "AUDITOR", "audit");
    private static final Map<String, String> ROLE_KEY_TO_CODE = Map.of(
            "super", "SUPER_ADMIN",
            "config", "CONFIG_ADMIN",
            "finance", "FINANCE",
            "risk", "RISK",
            "content", "CONTENT",
            "growth", "GROWTH",
            "support", "SUPPORT",
            "audit", "AUDITOR");
    private final PlatformConfigRepository configRepository;
    private final AuditLogService auditLogService;
    private final AdminMapper adminMapper;
    private final AdminRoleRelationMapper roleRelationMapper;
    private final OpsOptionsMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminSessionRegistry adminSessionRegistry;
    private final OpsAuditCenterService auditCenterService;

    public ApiResult<AdminAccountOverview> overview() {
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        List<AdminAccountOverview.OperatorRecord> operators = operators(configs);
        int active = (int) operators.stream().filter(operator -> "enabled".equals(operator.status())).count();
        int activeSessions = operators.stream().mapToInt(AdminAccountOverview.OperatorRecord::sessions).sum();
        AdminAccountOverview.AdminAccountStats stats = new AdminAccountOverview.AdminAccountStats(
                operators.size(),
                active,
                operators.size() - active,
                activeSessions,
                effectiveSupers(operators),
                pendingA1OperationTickets());

        return ApiResult.ok(new AdminAccountOverview(
                stats,
                roleDefinitions(configs),
                operators,
                rbacActions(configs),
                securityBaselines(configs)));
    }

    @Transactional
    public ApiResult<AdminAccountOverview.OperatorRecord> createAccount(
            String idempotencyKey, AdminAccountCreateRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        String displayName = normalizeText(request.displayName(), "DISPLAY_NAME_REQUIRED");
        String email = normalizeText(request.email(), "EMAIL_REQUIRED").toLowerCase(Locale.ROOT);
        if (!email.contains("@") || !email.endsWith("@nexion.io")) {
            return ApiResult.fail(422, "WORK_EMAIL_REQUIRED");
        }
        if (adminByEmail(email).isPresent()) {
            return ApiResult.fail(409, "ADMIN_EMAIL_EXISTS");
        }
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        String role = normalizeRole(request.role(), configs);
        if (role == null) {
            return ApiResult.fail(422, "ROLE_INVALID");
        }
        String deliver = normalizeDeliver(request.deliver());
        if (deliver == null) {
            return ApiResult.fail(422, "CREDENTIAL_DELIVERY_INVALID");
        }
        if (StringUtils.hasText(request.initialPassword()) && !"handoff".equals(deliver)) {
            return ApiResult.fail(422, "INITIAL_PASSWORD_HANDOFF_ONLY");
        }
        if (StringUtils.hasText(request.initialPassword()) && !strongInitialPassword(request.initialPassword())) {
            return ApiResult.fail(422, "INITIAL_PASSWORD_WEAK");
        }

        String initialPassword = normalizeInitialPassword(request.initialPassword(), deliver);
        AdminEntity admin = new AdminEntity();
        admin.setUsername(uniqueUsername(email));
        admin.setPasswordHash(passwordEncoder.encode(
                StringUtils.hasText(initialPassword) ? initialPassword : UUID.randomUUID().toString()));
        admin.setNickname(displayName);
        admin.setEmail(email);
        admin.setSuperAdmin("super".equals(role) ? 1 : 0);
        admin.setStatus(1);
        admin.setIsDeleted(0);
        adminMapper.insert(admin);
        Long adminId = admin.getId();
        if (adminId == null) {
            adminId = adminByUsername(admin.getUsername()).map(AdminEntity::getId).orElse(null);
        }
        if (adminId == null) {
            return ApiResult.fail(500, "ADMIN_ID_MISSING");
        }
        syncPrimaryRoleRelation(adminId, role);

        String accountId = String.valueOf(adminId);
        String prefix = "a1.account." + accountId;
        String credentialStatus = "mail".equals(deliver) ? "MAIL_DISPATCHED" : "HANDOFF_PENDING";
        save(prefix + ".tfa", "true", GROUP_ACCOUNT, "A1 account 2FA");
        save(prefix + ".lastLogin", "", GROUP_ACCOUNT, "A1 account last login");
        save(prefix + ".credentialDeliveryStatus", credentialStatus, GROUP_ACCOUNT, "A1 credential delivery");
        save(prefix + ".createdAt", LocalDateTime.now().format(ISO), GROUP_ACCOUNT, "A1 account created at");

        audit("A1_OPERATOR_CREATED", "A1_ADMIN_ACCOUNT", accountId, request.operator(), request.reason(), idempotencyKey,
                Map.of("role", role, "credentialDeliveryStatus", credentialStatus,
                        "initialPasswordProvided", StringUtils.hasText(initialPassword)));
        AdminAccountOverview.OperatorRecord created = requireOperator(accountId);
        linkA2Proposal(idempotencyKey, "运营账号创建(A1)", operatorLabel(created), "—",
                role + " / " + credentialStatus, request.operator(), currentOperatorRole(configs), "acct",
                false, false, "超管", request.reason());
        return ApiResult.ok(created);
    }

    @Transactional
    public ApiResult<AdminAccountOverview.OperatorRecord> changeRole(
            String idempotencyKey, String accountId, AdminAccountRoleUpdateRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        AdminAccountOverview.OperatorRecord current = findOperator(accountId).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "ACCOUNT_NOT_FOUND");
        }
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        String nextRole = normalizeRole(request.role(), configs);
        if (nextRole == null) {
            return ApiResult.fail(422, "ROLE_INVALID");
        }
        if ("super".equals(current.role())
                && "enabled".equals(current.status())
                && !"super".equals(nextRole)
                && effectiveSupers(operators(configs)) - 1 < minEffectiveSupers(configs)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "MIN_EFFECTIVE_SUPER_REQUIRED");
        }

        Long adminId = parseAccountId(current.id()).orElseThrow();
        AdminEntity patch = new AdminEntity();
        patch.setId(adminId);
        patch.setSuperAdmin("super".equals(nextRole) ? 1 : 0);
        adminMapper.updateById(patch);
        syncPrimaryRoleRelation(adminId, nextRole);

        audit("A1_OPERATOR_ROLE_CHANGED", "A1_ADMIN_ACCOUNT", current.id(), request.operator(), request.reason(), idempotencyKey,
                Map.of("fromRole", current.role(), "toRole", nextRole));
        linkA2Proposal(idempotencyKey, "运营账号改角色(A1)", operatorLabel(current), current.role(), nextRole,
                request.operator(), currentOperatorRole(configs), "acct", false, false, "超管", request.reason());
        return ApiResult.ok(requireOperator(current.id()));
    }

    @Transactional
    public ApiResult<AdminAccountOverview.OperatorRecord> updateStatus(
            String idempotencyKey, String accountId, AdminAccountStatusUpdateRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        String normalizedAccountId = StringUtils.hasText(accountId) ? accountId.trim() : accountId;
        AdminAccountOverview.OperatorRecord current = operators(configs).stream()
                .filter(operator -> operator.id().equals(normalizedAccountId))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "ACCOUNT_NOT_FOUND");
        }
        String nextStatus = normalizeStatus(request.status());
        if (nextStatus == null) {
            return ApiResult.fail(422, "STATUS_INVALID");
        }
        if ("disabled".equals(nextStatus)
                && "super".equals(current.role())
                && "enabled".equals(current.status())
                && effectiveSupers(operators(configs)) - 1 < minEffectiveSupers(configs)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "MIN_EFFECTIVE_SUPER_REQUIRED");
        }

        Long adminId = parseAccountId(current.id()).orElseThrow();
        AdminEntity patch = new AdminEntity();
        patch.setId(adminId);
        patch.setStatus("enabled".equals(nextStatus) ? 1 : 0);
        adminMapper.updateById(patch);

        String prefix = "a1.account." + current.id();
        if ("disabled".equals(nextStatus)) {
            adminSessionRegistry.revokeSessions(adminId);
            save(prefix + ".killedAt", LocalDateTime.now().format(ISO), GROUP_ACCOUNT, "A1 account sessions revoked");
        }
        audit("enabled".equals(nextStatus) ? "A1_OPERATOR_ENABLED" : "A1_OPERATOR_DISABLED",
                "A1_ADMIN_ACCOUNT", current.id(), request.operator(), request.reason(), idempotencyKey,
                Map.of("fromStatus", current.status(), "toStatus", nextStatus));
        linkA2Proposal(idempotencyKey, "enabled".equals(nextStatus) ? "运营账号启用(A1)" : "运营账号禁用(A1)",
                operatorLabel(current), current.status(), nextStatus, request.operator(), currentOperatorRole(configs),
                "acct", false, false, "超管", request.reason());
        return ApiResult.ok(requireOperator(current.id()));
    }

    @Transactional
    public ApiResult<AdminAccountOverview.OperatorRecord> reset2fa(
            String idempotencyKey, String accountId, AdminAccountActionRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        AdminAccountOverview.OperatorRecord current = findOperator(accountId).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "ACCOUNT_NOT_FOUND");
        }
        save("a1.account." + current.id() + ".tfaResetAt", LocalDateTime.now().format(ISO), GROUP_ACCOUNT, "A1 2FA reset");
        audit("A1_OPERATOR_2FA_RESET", "A1_ADMIN_ACCOUNT", current.id(), request.operator(), request.reason(), idempotencyKey,
                Map.of("tfaRequired", true));
        linkA2Proposal(idempotencyKey, "运营账号重置双因子(A1)", operatorLabel(current), "已绑定", "待重新绑定",
                request.operator(), currentOperatorRole(loadConfigMap(ACTIVE_GROUPS)), "acct", false, false, "超管",
                request.reason());
        return ApiResult.ok(requireOperator(current.id()));
    }

    @Transactional
    public ApiResult<AdminAccountOverview.OperatorRecord> revokeSessions(
            String idempotencyKey, String accountId, AdminAccountActionRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        List<AdminAccountOverview.OperatorRecord> operators = operators(configs);
        String normalizedAccountId = StringUtils.hasText(accountId) ? accountId.trim() : accountId;
        AdminAccountOverview.OperatorRecord current = operators.stream()
                .filter(operator -> operator.id().equals(normalizedAccountId))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "ACCOUNT_NOT_FOUND");
        }
        AdminAccountOverview.OperatorRecord actor = authenticatedOperator(operators).orElse(null);
        ApiResult<Void> authorization = requireSessionRevokeAuthorization(actor, current);
        if (authorization != null) {
            return failLike(authorization);
        }
        String now = LocalDateTime.now().format(ISO);
        Long adminId = parseAccountId(current.id()).orElseThrow();
        int revoked = adminSessionRegistry.revokeSessions(adminId);
        save("a1.account." + current.id() + ".killedAt", now, GROUP_ACCOUNT, "A1 sessions revoked");
        audit("A1_OPERATOR_SESSION_REVOKED", "A1_ADMIN_ACCOUNT", current.id(), request.operator(), request.reason(), idempotencyKey,
                Map.of("killedAt", now, "revokedSessions", revoked));
        linkA2Proposal(idempotencyKey, "运营账号强制登出(A1)", operatorLabel(current),
                current.sessions() + " active sessions", "0 active sessions", request.operator(),
                actor == null ? "super" : actor.role(), "acct", false, false, "超管 / 风控", request.reason());
        return ApiResult.ok(requireOperator(current.id()));
    }

    @Transactional
    public ApiResult<AdminAccountOverview.SecurityBaseline> updateSecurityBaseline(
            String idempotencyKey, String baselineKey, AdminAccountSecurityBaselineUpdateRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        String key = normalizeText(baselineKey, "BASELINE_KEY_REQUIRED").toLowerCase(Locale.ROOT);
        String value = normalizeText(request.value(), "BASELINE_VALUE_REQUIRED");
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        AdminAccountOverview.SecurityBaseline baseline = securityBaseline(key, configs).orElse(null);
        if (baseline == null) {
            return ApiResult.fail(404, "SECURITY_BASELINE_NOT_FOUND");
        }
        if (baseline.locked()) {
            return ApiResult.fail(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(), "SECURITY_BASELINE_LOCKED");
        }
        if ("session".equals(key)) {
            Matcher matcher = Pattern.compile("(\\d+)\\s*min?\\s*/\\s*(\\d+)\\s*h?", Pattern.CASE_INSENSITIVE)
                    .matcher(value);
            if (!matcher.find()) {
                return ApiResult.fail(422, "SESSION_LIMIT_FORMAT_INVALID");
            }
            int idle = Integer.parseInt(matcher.group(1));
            int abs = Integer.parseInt(matcher.group(2));
            if (idle < 15 || idle > 60 || abs < 4 || abs > 12) {
                return ApiResult.fail(422, "SESSION_LIMIT_OUT_OF_RANGE");
            }
            save("a1.security.sessionIdle", String.valueOf(idle), GROUP_SECURITY, "A1 session idle");
            save("a1.security.sessionAbs", String.valueOf(abs), GROUP_SECURITY, "A1 session absolute");
        } else if ("lock".equals(key)) {
            Matcher matcher = Pattern.compile("(\\d+)\\s*次?\\s*/\\s*(\\d+)\\s*min?", Pattern.CASE_INSENSITIVE)
                    .matcher(value);
            if (!matcher.find()) {
                return ApiResult.fail(422, "LOCK_LIMIT_FORMAT_INVALID");
            }
            int count = Integer.parseInt(matcher.group(1));
            int minutes = Integer.parseInt(matcher.group(2));
            if (count < 3 || count > 10 || minutes < 5 || minutes > 60) {
                return ApiResult.fail(422, "LOCK_LIMIT_OUT_OF_RANGE");
            }
            save("a1.security.lockShortCnt", String.valueOf(count), GROUP_SECURITY, "A1 lock short count");
            save("a1.security.lockShortMin", String.valueOf(minutes), GROUP_SECURITY, "A1 lock short minutes");
        }
        save("a1.security.baseline." + key + ".value", value, GROUP_SECURITY, "A1 security baseline value");
        audit("A1_SECURITY_BASELINE_CHANGED", "A1_SECURITY_BASELINE", key, request.operator(), request.reason(), idempotencyKey,
                Map.of("value", value));
        linkA2Proposal(idempotencyKey, "安全基线调整(A1)", baseline.name(), baseline.value(), value,
                request.operator(), currentOperatorRole(configs), "param", false, false, "超管", request.reason());
        return ApiResult.ok(securityBaseline(key, loadConfigMap(ACTIVE_GROUPS)).orElseThrow());
    }

    @Transactional
    public ApiResult<AdminAccountOverview.RbacAction> updateRbacGrants(
            String idempotencyKey, String actionId, AdminRbacGrantUpdateRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        AdminAccountOverview.RbacAction action = rbacActions(configs).stream()
                .filter(item -> item.id().equals(actionId))
                .findFirst()
                .orElse(null);
        if (action == null) {
            return ApiResult.fail(404, "RBAC_ACTION_NOT_FOUND");
        }
        List<String> grants = request.grants() == null ? List.of() : request.grants().stream()
                .map(grant -> grant == null ? "" : grant.trim().toUpperCase(Locale.ROOT))
                .toList();
        List<String> roleKeys = roleKeys(configs);
        if (grants.size() != roleKeys.size() || grants.stream().anyMatch(grant -> !GRANT_OPTIONS.contains(grant))) {
            return ApiResult.fail(422, "RBAC_GRANT_INVALID");
        }
        int auditIndex = roleKeys.indexOf("audit");
        if (auditIndex >= 0 && !"audit_export".equals(action.id()) && List.of("M", "C").contains(grants.get(auditIndex))) {
            return ApiResult.fail(422, "AUDIT_ROLE_WRITE_FORBIDDEN");
        }
        int superIndex = roleKeys.indexOf("super");
        if ("operator_governance".equals(action.id()) && (superIndex < 0 || !"M".equals(grants.get(superIndex)))) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "OPERATOR_GOVERNANCE_SUPER_GRANT_REQUIRED");
        }
        if ("资金".equals(action.domainGroup()) && writes(grants, roleKeys, "growth", "content", "support")) {
            return ApiResult.fail(422, "FUND_DOMAIN_WRITE_FORBIDDEN");
        }
        if ("用户/风控".equals(action.domainGroup()) && writes(grants, roleKeys, "finance", "growth", "content")) {
            return ApiResult.fail(422, "RISK_DOMAIN_WRITE_FORBIDDEN");
        }

        for (int index = 0; index < roleKeys.size(); index++) {
            save("a1.rbac." + roleKeys.get(index) + "." + action.id(), grants.get(index), GROUP_RBAC, "A1 RBAC grant changed");
        }
        audit("A1_RBAC_GRANT_CHANGED", "A1_RBAC_ACTION", action.id(), request.operator(), request.reason(), idempotencyKey,
                Map.of("grants", grants));
        linkA2Proposal(idempotencyKey, "RBAC 授权调整(A1)", action.action(), String.join("/", action.grants()),
                String.join("/", grants), request.operator(), currentOperatorRole(configs), "param", false, false,
                "超管", request.reason());
        return ApiResult.ok(rbacAction(action.id(), loadConfigMap(ACTIVE_GROUPS)).orElseThrow());
    }

    @Transactional
    public ApiResult<AdminAccountOverview.RbacAction> registerRbacAction(
            String idempotencyKey, AdminRbacActionCreateRequest request) {
        ApiResult<Void> mutation = requireMutation(
                idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (mutation != null) {
            return failLike(mutation);
        }
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        String action = normalizeText(request.action(), "RBAC_ACTION_REQUIRED");
        String domainGroup = normalizeDomainGroup(request.domainGroup());
        String actionId = uniqueActionId(slugFor(action), configs);
        save(RBAC_ACTION_PREFIX + actionId, action, GROUP_RBAC, "A1 RBAC action registered");
        save(RBAC_ACTION_PREFIX + actionId + ".domainGroup", domainGroup, GROUP_RBAC, "A1 RBAC action domain group");
        save(RBAC_ACTION_PREFIX + actionId + ".sort", String.valueOf((rbacActions(configs).size() + 1) * 10), GROUP_RBAC,
                "A1 RBAC action sort");
        for (String role : roleKeys(configs)) {
            save("a1.rbac." + role + "." + actionId, defaultGrant(role), GROUP_RBAC, "A1 RBAC default grant");
        }
        audit("A1_RBAC_ACTION_REGISTERED", "A1_RBAC_ACTION", actionId, request.operator(), request.reason(), idempotencyKey,
                Map.of("action", action, "domainGroup", domainGroup));
        AdminAccountOverview.RbacAction created = rbacAction(actionId, loadConfigMap(ACTIVE_GROUPS)).orElseThrow();
        linkA2Proposal(idempotencyKey, "RBAC 动作登记(A1)", created.action(), "—", domainGroup,
                request.operator(), currentOperatorRole(configs), "param", false, false, "超管", request.reason());
        return ApiResult.ok(created);
    }

    private int pendingA1OperationTickets() {
        return auditCenterService.pendingOperationCountByActionMarker("(A1)");
    }

    private void linkA2Proposal(
            String idempotencyKey,
            String action,
            String objectText,
            String beforeValue,
            String afterValue,
            String operator,
            String operatorRole,
            String type,
            boolean amplifies,
            boolean sos,
            String roleGate,
            String reason) {
        ApiResult<AuditCenterOverview.AuditOperationTicket> result = auditCenterService.createProposal(
                idempotencyKey.trim() + "-a2",
                new AuditOperationProposalRequest(
                        action,
                        objectText,
                        beforeValue,
                        afterValue,
                        operator,
                        operatorRole,
                        type,
                        amplifies,
                        sos,
                        roleGate,
                        reason,
                        "A1"));
        if (result == null || result.getCode() != 0) {
            String message = result == null ? "A2_OPERATION_PROPOSAL_FAILED" : result.getMessage();
            throw new IllegalStateException("A2_OPERATION_PROPOSAL_FAILED:" + message);
        }
    }

    private String currentOperatorRole(Map<String, PlatformConfigItem> configs) {
        return authenticatedOperator(operators(configs))
                .map(AdminAccountOverview.OperatorRecord::role)
                .orElse("super");
    }

    private String operatorLabel(AdminAccountOverview.OperatorRecord operator) {
        String name = firstText(operator.name(), operator.email(), "管理员");
        if (StringUtils.hasText(operator.email()) && !operator.email().equals(name)) {
            return name + " · " + operator.email();
        }
        return name;
    }

    private List<AdminAccountOverview.RoleDefinition> roleDefinitions(Map<String, PlatformConfigItem> configs) {
        return roleRows().stream()
                .map(role -> roleDefinition(role, configs))
                .toList();
    }

    private AdminAccountOverview.RoleDefinition roleDefinition(
            AdminRoleOptionEntity row,
            Map<String, PlatformConfigItem> configs) {
        String role = roleKey(row.getRoleCode());
        String prefix = "a1.role." + role + ".";
        return new AdminAccountOverview.RoleDefinition(
                role,
                text(configs, prefix + "name", firstText(row.getRoleName(), role)),
                text(configs, prefix + "avatar", defaultRoleAvatar(role)),
                text(configs, prefix + "color", defaultRoleColor(role)),
                text(configs, prefix + "description", defaultRoleDescription(role)),
                text(configs, prefix + "scope", defaultRoleScope(role)));
    }

    private List<AdminRoleOptionEntity> roleRows() {
        List<AdminRoleOptionEntity> rows = roleMapper.selectList(new LambdaQueryWrapper<AdminRoleOptionEntity>()
                .eq(AdminRoleOptionEntity::getStatus, 1)
                .eq(AdminRoleOptionEntity::getIsDeleted, 0)
                .orderByAsc(AdminRoleOptionEntity::getId));
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row != null && StringUtils.hasText(row.getRoleCode()))
                .toList();
    }

    private List<AdminAccountOverview.OperatorRecord> operators(Map<String, PlatformConfigItem> configs) {
        List<AdminEntity> admins = adminRows();
        return admins.stream()
                .map(admin -> operatorRecord(admin, configs))
                .sorted(Comparator.comparing(record -> parseAccountId(record.id()).orElse(Long.MAX_VALUE)))
                .toList();
    }

    private AdminAccountOverview.OperatorRecord operatorRecord(AdminEntity admin, Map<String, PlatformConfigItem> configs) {
        String id = String.valueOf(admin.getId());
        String prefix = "a1.account." + id + ".";
        boolean enabled = Integer.valueOf(1).equals(admin.getStatus());
        String role = roleFromRelation(admin.getId()).orElseGet(() -> defaultRole(admin, configs));
        int sessions = enabled ? adminSessionRegistry.countActiveSessions(admin.getId()) : 0;
        return new AdminAccountOverview.OperatorRecord(
                id,
                firstText(admin.getNickname(), admin.getUsername(), id),
                StringUtils.hasText(admin.getEmail()) ? admin.getEmail().trim() : "",
                role,
                Boolean.parseBoolean(text(configs, prefix + "tfa", "true")),
                enabled ? "enabled" : "disabled",
                text(configs, prefix + "lastLogin", admin.getUpdatedAt() == null ? "" : admin.getUpdatedAt().format(ISO)),
                sessions,
                text(configs, prefix + "tfaResetAt", null),
                text(configs, prefix + "credentialDeliveryStatus", "ACTIVE"));
    }

    private List<AdminAccountOverview.RbacAction> rbacActions(Map<String, PlatformConfigItem> configs) {
        Map<String, AdminAccountOverview.RbacAction> actions = new LinkedHashMap<>();
        configs.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(RBAC_ACTION_PREFIX))
                .filter(entry -> !entry.getKey().substring(RBAC_ACTION_PREFIX.length()).contains("."))
                .sorted(Comparator.comparingInt(entry -> {
                    String id = entry.getKey().substring(RBAC_ACTION_PREFIX.length());
                    return number(configs, RBAC_ACTION_PREFIX + id + ".sort", 9999);
                }))
                .map(entry -> {
                    String id = entry.getKey().substring(RBAC_ACTION_PREFIX.length());
                    return withGrantOverrides(new AdminAccountOverview.RbacAction(
                            id,
                            entry.getValue().configValue(),
                            text(configs, RBAC_ACTION_PREFIX + id + ".domainGroup", ""),
                            roleKeys(configs).stream().map(this::defaultGrant).toList()), configs);
                })
                .forEach(action -> actions.put(action.id(), action));
        return new ArrayList<>(actions.values());
    }

    private Optional<AdminAccountOverview.RbacAction> rbacAction(String actionId, Map<String, PlatformConfigItem> configs) {
        return rbacActions(configs).stream().filter(action -> action.id().equals(actionId)).findFirst();
    }

    private AdminAccountOverview.RbacAction withGrantOverrides(
            AdminAccountOverview.RbacAction action, Map<String, PlatformConfigItem> configs) {
        List<String> grants = new ArrayList<>();
        List<String> roleKeys = roleKeys(configs);
        for (int index = 0; index < roleKeys.size(); index++) {
            String role = roleKeys.get(index);
            String fallback = index < action.grants().size() ? action.grants().get(index) : defaultGrant(role);
            String grant = text(configs, "a1.rbac." + role + "." + action.id(), fallback);
            grants.add(GRANT_OPTIONS.contains(grant) ? grant : fallback);
        }
        return new AdminAccountOverview.RbacAction(action.id(), action.action(), action.domainGroup(), grants);
    }

    private List<AdminAccountOverview.SecurityBaseline> securityBaselines(Map<String, PlatformConfigItem> configs) {
        Map<String, AdminAccountOverview.SecurityBaseline> baselines = new LinkedHashMap<>();
        configs.keySet().stream()
                .map(SECURITY_BASELINE_REGISTRATION_PATTERN::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .map(key -> securityBaseline(key, configs))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(item -> number(
                        configs,
                        "a1.security.baseline." + item.key() + ".sort",
                        9999)))
                .forEach(baseline -> baselines.put(baseline.key(), baseline));
        return new ArrayList<>(baselines.values());
    }

    private Optional<AdminAccountOverview.SecurityBaseline> securityBaseline(String key, Map<String, PlatformConfigItem> configs) {
        String prefix = "a1.security.baseline." + key + ".";
        if (!"true".equalsIgnoreCase(text(configs, prefix + "registered", "false"))) {
            return Optional.empty();
        }
        String configuredValue = text(configs, prefix + "value", "");
        return Optional.of(withSecurityValue(new AdminAccountOverview.SecurityBaseline(
                key,
                text(configs, prefix + "label", key),
                text(configs, prefix + "description", ""),
                configuredValue,
                Boolean.parseBoolean(text(configs, prefix + "locked", "false"))), configs));
    }

    private AdminAccountOverview.SecurityBaseline withSecurityValue(
            AdminAccountOverview.SecurityBaseline baseline, Map<String, PlatformConfigItem> configs) {
        String value = switch (baseline.key()) {
            case "session" -> sessionValue(configs, baseline.value());
            case "lock" -> lockValue(configs, baseline.value());
            default -> baseline.value();
        };
        return new AdminAccountOverview.SecurityBaseline(
                baseline.key(),
                baseline.name(),
                baseline.sub(),
                value,
                baseline.locked());
    }

    private String sessionValue(Map<String, PlatformConfigItem> configs, String configuredValue) {
        if (configs.containsKey("a1.security.sessionIdle") || configs.containsKey("a1.security.sessionAbs")) {
            return text(configs, "a1.security.sessionIdle", "30") + "min / " + text(configs, "a1.security.sessionAbs", "8") + "h";
        }
        return configuredValue;
    }

    private String lockValue(Map<String, PlatformConfigItem> configs, String configuredValue) {
        if (configs.containsKey("a1.security.lockShortCnt") || configs.containsKey("a1.security.lockShortMin")) {
            return text(configs, "a1.security.lockShortCnt", "5") + " 次/"
                    + text(configs, "a1.security.lockShortMin", "15") + "min · 15 次/24h";
        }
        return configuredValue;
    }

    private Map<String, PlatformConfigItem> loadConfigMap(Collection<String> groups) {
        return configRepository.findActiveByGroups(groups).stream()
                .collect(Collectors.toMap(
                        PlatformConfigItem::configKey,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private AdminAccountOverview.OperatorRecord requireOperator(String accountId) {
        return findOperator(accountId).orElseThrow();
    }

    private Optional<AdminAccountOverview.OperatorRecord> findOperator(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            return Optional.empty();
        }
        String normalized = accountId.trim();
        if (parseAccountId(normalized).isEmpty()) {
            return Optional.empty();
        }
        return operators(loadConfigMap(ACTIVE_GROUPS)).stream()
                .filter(operator -> operator.id().equals(normalized))
                .findFirst();
    }

    private Optional<AdminAccountOverview.OperatorRecord> authenticatedOperator(
            List<AdminAccountOverview.OperatorRecord> operators) {
        return authenticatedAdminId()
                .flatMap(adminId -> operators.stream()
                        .filter(operator -> operator.id().equals(String.valueOf(adminId)))
                        .findFirst());
    }

    private Optional<Long> authenticatedAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long adminId) {
            return Optional.of(adminId);
        }
        return parseAccountId(String.valueOf(principal));
    }

    private ApiResult<Void> requireSessionRevokeAuthorization(
            AdminAccountOverview.OperatorRecord actor,
            AdminAccountOverview.OperatorRecord target) {
        if (actor == null) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "FORCE_LOGOUT_ROLE_FORBIDDEN");
        }
        if (actor.id().equals(target.id())) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "FORCE_LOGOUT_SELF_FORBIDDEN");
        }
        if (!SESSION_REVOKE_ROLES.contains(actor.role())) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "FORCE_LOGOUT_ROLE_FORBIDDEN");
        }
        if ("super".equals(target.role()) && !disabledE2eShiftSuper(target)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "FORCE_LOGOUT_SUPER_TARGET_FORBIDDEN");
        }
        return null;
    }

    private boolean disabledE2eShiftSuper(AdminAccountOverview.OperatorRecord target) {
        return "disabled".equals(target.status())
                && StringUtils.hasText(target.email())
                && target.email().startsWith("e2e_")
                && target.email().endsWith("@nexion.io");
    }

    private void save(String key, String value, String group, String remark) {
        PlatformConfigItem existing = configRepository.findActiveByKey(key).orElseGet(() ->
                new PlatformConfigItem(null, key, value, "STRING", group, "ADMIN", remark, 1, null, null));
        configRepository.save(existing.withValue(value, group, remark, 1));
    }

    private void audit(
            String action,
            String resourceType,
            String resourceId,
            String operator,
            String reason,
            String idempotencyKey,
            Map<String, Object> extraDetail) {
        Map<String, Object> detail = new LinkedHashMap<>(extraDetail);
        detail.put("reason", reason.trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator.trim())
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private ApiResult<Void> requireMutation(String idempotencyKey, String reason, String operator) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(operator)) {
            return ApiResult.fail(422, "OPERATOR_REQUIRED");
        }
        return null;
    }

    private <T> ApiResult<T> failLike(ApiResult<Void> result) {
        return ApiResult.fail(result.getCode(), result.getMessage());
    }

    private List<AdminEntity> adminRows() {
        List<AdminEntity> rows = adminMapper.selectList(new LambdaQueryWrapper<AdminEntity>()
                .eq(AdminEntity::getIsDeleted, 0)
                .orderByAsc(AdminEntity::getId));
        return rows == null ? List.of() : rows;
    }

    private Optional<AdminEntity> adminByEmail(String email) {
        return Optional.ofNullable(adminMapper.selectOne(new LambdaQueryWrapper<AdminEntity>()
                .eq(AdminEntity::getEmail, email)
                .eq(AdminEntity::getIsDeleted, 0)
                .last("LIMIT 1")));
    }

    private Optional<AdminEntity> adminByUsername(String username) {
        return Optional.ofNullable(adminMapper.selectOne(new LambdaQueryWrapper<AdminEntity>()
                .eq(AdminEntity::getUsername, username)
                .eq(AdminEntity::getIsDeleted, 0)
                .last("LIMIT 1")));
    }

    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf("@"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (!StringUtils.hasText(base)) {
            base = "admin";
        }
        String candidate = base;
        int index = 2;
        while (adminByUsername(candidate).isPresent()) {
            candidate = base + "_" + index++;
        }
        return candidate;
    }

    private void syncPrimaryRoleRelation(Long adminId, String role) {
        String roleCode = roleCode(role);
        roleRelationMapper.disableOtherPrimaryRoles(adminId, roleCode);
        roleRelationMapper.ensurePrimaryRole(adminId, roleCode);
    }

    private Optional<Long> parseAccountId(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(accountId.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private List<String> roleKeys(Map<String, PlatformConfigItem> configs) {
        return roleDefinitions(configs).stream()
                .map(AdminAccountOverview.RoleDefinition::key)
                .toList();
    }

    private Optional<String> roleFromRelation(Long adminId) {
        if (adminId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(roleRelationMapper.activeRoleCode(adminId))
                .map(this::roleKey)
                .filter(role -> roleKeys(loadConfigMap(ACTIVE_GROUPS)).contains(role));
    }

    private String defaultRole(AdminEntity admin, Map<String, PlatformConfigItem> configs) {
        List<String> keys = roleKeys(configs);
        if (Integer.valueOf(1).equals(admin.getSuperAdmin())) {
            return keys.contains("super") || keys.isEmpty() ? "super" : keys.get(0);
        }
        if (keys.contains("audit")) {
            return "audit";
        }
        return keys.stream()
                .filter(role -> !"super".equals(role))
                .findFirst()
                .orElse("audit");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeRole(String role, Map<String, PlatformConfigItem> configs) {
        if (!StringUtils.hasText(role)) {
            return null;
        }
        String normalized = roleKey(role);
        return roleKeys(configs).contains(normalized) ? normalized : null;
    }

    private String roleKey(String roleOrCode) {
        if (!StringUtils.hasText(roleOrCode)) {
            return "";
        }
        String normalized = roleOrCode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        String mapped = ROLE_CODE_TO_KEY.get(normalized);
        if (StringUtils.hasText(mapped)) {
            return mapped;
        }
        return roleOrCode.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String roleCode(String roleKey) {
        String normalized = roleKey(roleKey);
        return ROLE_KEY_TO_CODE.getOrDefault(normalized, normalized.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private String defaultRoleAvatar(String role) {
        return switch (role) {
            case "super" -> "SA";
            case "config" -> "OA";
            case "finance" -> "FI";
            case "risk" -> "RK";
            case "content" -> "CT";
            case "growth" -> "GR";
            case "support" -> "CS";
            case "audit" -> "AU";
            default -> role.length() <= 2 ? role.toUpperCase(Locale.ROOT) : role.substring(0, 2).toUpperCase(Locale.ROOT);
        };
    }

    private String defaultRoleColor(String role) {
        return switch (role) {
            case "super" -> "red";
            case "config" -> "blue";
            case "finance" -> "green";
            case "risk" -> "orange";
            case "content" -> "purple";
            case "growth" -> "cyan";
            case "support" -> "indigo";
            case "audit" -> "gray";
            default -> "";
        };
    }

    private String defaultRoleDescription(String role) {
        return switch (role) {
            case "super" -> "平台 Owner，保留所有危急操作。";
            case "config" -> "运营配置、平台参数与跨域配置变更。";
            case "finance" -> "资金、账务、提现与覆盖率。";
            case "risk" -> "风控模型、KYC、账户限制与熔断。";
            case "content" -> "文案、课程、风险披露与公告。";
            case "growth" -> "活动、节奏、权益与触达。";
            case "support" -> "用户查询、工单协同与客服处置。";
            case "audit" -> "审计与合规观察，禁止写操作。";
            default -> "";
        };
    }

    private String defaultRoleScope(String role) {
        return switch (role) {
            case "super" -> "全域：资金、风控、内容、配置、审计";
            case "config" -> "A/C/E/F/G/H/I/J/M 配置";
            case "finance" -> "B/D/C 资金域";
            case "risk" -> "C/K/J 风控域";
            case "content" -> "I/公告/课程";
            case "growth" -> "E/F/G/H 增长与收益";
            case "support" -> "M/C/D/K 客服协同";
            case "audit" -> "A2/L 报表与审计";
            default -> "";
        };
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return List.of("enabled", "disabled").contains(normalized) ? normalized : null;
    }

    private String normalizeDeliver(String deliver) {
        String normalized = StringUtils.hasText(deliver) ? deliver.trim().toLowerCase(Locale.ROOT) : "mail";
        return List.of("mail", "handoff").contains(normalized) ? normalized : null;
    }

    private String normalizeInitialPassword(String initialPassword, String deliver) {
        if (!"handoff".equals(deliver) || !StringUtils.hasText(initialPassword)) {
            return null;
        }
        return initialPassword.trim();
    }

    private boolean strongInitialPassword(String initialPassword) {
        String normalized = initialPassword.trim();
        return normalized.length() >= 12
                && Pattern.compile("[A-Z]").matcher(normalized).find()
                && Pattern.compile("[a-z]").matcher(normalized).find()
                && Pattern.compile("\\d").matcher(normalized).find();
    }

    private String normalizeDomainGroup(String domainGroup) {
        if (!StringUtils.hasText(domainGroup)) {
            return "基座/应急";
        }
        String normalized = domainGroup.trim();
        return List.of("资金", "用户/风控", "增长/内容", "基座/应急").contains(normalized) ? normalized : "基座/应急";
    }

    private int effectiveSupers(List<AdminAccountOverview.OperatorRecord> operators) {
        return (int) operators.stream()
                .filter(operator -> "super".equals(operator.role()) && "enabled".equals(operator.status()))
                .count();
    }

    private int minEffectiveSupers(Map<String, PlatformConfigItem> configs) {
        return number(configs, "a1.security.minEffectiveSupers", 2);
    }

    private boolean writes(List<String> grants, List<String> roleKeys, String... roles) {
        for (String role : roles) {
            int index = roleKeys.indexOf(role);
            if (index >= 0 && List.of("M", "C").contains(grants.get(index))) {
                return true;
            }
        }
        return false;
    }

    private String defaultGrant(String role) {
        return "audit".equals(role) ? "R" : "-";
    }

    private String uniqueActionId(String base, Map<String, PlatformConfigItem> configs) {
        Set<String> existing = rbacActions(configs).stream().map(AdminAccountOverview.RbacAction::id).collect(Collectors.toSet());
        if (!existing.contains(base)) {
            return base;
        }
        int index = 2;
        while (existing.contains(base + "_" + index)) {
            index++;
        }
        return base + "_" + index;
    }

    private String slugFor(String action) {
        Matcher code = ACTION_CODE_PATTERN.matcher(action);
        if (code.find()) {
            return code.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        }
        String slug = action.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return StringUtils.hasText(slug) ? slug : "action_" + System.currentTimeMillis();
    }

    private String text(Map<String, PlatformConfigItem> configs, String key, String fallback) {
        PlatformConfigItem item = configs.get(key);
        return item == null || !StringUtils.hasText(item.configValue()) ? fallback : item.configValue();
    }

    private int number(Map<String, PlatformConfigItem> configs, String key, int fallback) {
        try {
            return Integer.parseInt(text(configs, key, String.valueOf(fallback)).replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
