package ffdd.opsconsole.platform.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.infrastructure.AdminRoleRelationEntity;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleCreateRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleDetail;
import ffdd.opsconsole.platform.dto.PlatformRoleGrantsUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformRoleOverview;
import ffdd.opsconsole.platform.dto.PlatformRoleOverview.RoleSummary;
import ffdd.opsconsole.platform.dto.PlatformRoleUpdateRequest;
import ffdd.opsconsole.platform.infrastructure.AdminRoleEntity;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.mapper.AdminRoleMapper;
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** A6 角色管理（核心）。角色 CRUD + 权限/菜单双绑定白名单同步 + 改绑定精准 evict 角色下 admin。 */
@ApplicationService
@RequiredArgsConstructor
public class OpsPlatformRoleService {
    private static final String RESOURCE_TYPE = "A6_PLATFORM_ROLE";
    private static final Set<String> BUILTIN_ROLE_CODES = Set.of(
            "SUPER_ADMIN", "CONFIG_ADMIN", "FINANCE", "RISK", "CONTENT", "GROWTH", "SUPPORT", "AUDITOR");

    private final AdminRoleMapper roleMapper;
    private final AdminRoleMenuMapper roleMenuMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminRoleRelationMapper roleRelationMapper;
    private final AdminPermissionCache permissionCache;
    private final AuditLogService auditLogService;
    private final OpsAuditCenterService auditCenterService;

    public ApiResult<PlatformRoleOverview> overview() {
        List<AdminRoleEntity> roles = roleMapper.selectList(new LambdaQueryWrapper<AdminRoleEntity>()
                .eq(AdminRoleEntity::getIsDeleted, 0)
                .orderByAsc(AdminRoleEntity::getId));
        List<RoleSummary> summaries = new ArrayList<>(roles.size());
        for (AdminRoleEntity role : roles) {
            summaries.add(new RoleSummary(role.getId(), role.getRoleCode(), role.getRoleName(),
                    role.getRemark(), role.getStatus(),
                    BUILTIN_ROLE_CODES.contains(role.getRoleCode()), countAdminsByRole(role.getId())));
        }
        return ApiResult.ok(new PlatformRoleOverview(summaries, summaries.size()));
    }

    public ApiResult<PlatformRoleDetail> detail(Long roleId) {
        AdminRoleEntity role = loadActive(roleId);
        if (role == null) {
            return ApiResult.fail(404, "ROLE_NOT_FOUND");
        }
        List<String> permissionCodes = rolePermissionMapper.selectActivePermissionCodesByRole(roleId);
        List<Long> menuIds = roleMenuMapper.selectActiveMenuIdsByRole(roleId);
        return ApiResult.ok(new PlatformRoleDetail(role.getId(), role.getRoleCode(), role.getRoleName(),
                role.getRemark(), role.getStatus(), BUILTIN_ROLE_CODES.contains(role.getRoleCode()),
                permissionCodes, menuIds));
    }

    @Transactional
    public ApiResult<PlatformRoleDetail> createRole(String idempotencyKey, PlatformRoleCreateRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        String roleCode = request.roleCode() == null ? "" : request.roleCode().trim();
        if (!StringUtils.hasText(roleCode)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ROLE_CODE_REQUIRED");
        }
        if (BUILTIN_ROLE_CODES.contains(roleCode)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BUILTIN_CODE_RESERVED");
        }
        if (roleMapper.existsByRoleCode(roleCode)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ROLE_CODE_DUPLICATE");
        }
        AdminRoleEntity entity = new AdminRoleEntity();
        entity.setRoleCode(roleCode);
        entity.setRoleName(request.roleName());
        entity.setRemark(request.remark());
        entity.setStatus(request.status() == null ? 1 : request.status());
        entity.setIsDeleted(0);
        roleMapper.insert(entity);
        audit("A6_ROLE_CREATED", entity.getId().toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("roleCode", roleCode, "roleName", String.valueOf(entity.getRoleName())), "HIGH");
        return detail(entity.getId());
    }

    @Transactional
    public ApiResult<?> updateRole(Long roleId, String idempotencyKey, PlatformRoleUpdateRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        AdminRoleEntity entity = loadActive(roleId);
        if (entity == null) {
            return ApiResult.fail(404, "ROLE_NOT_FOUND");
        }
        Integer requestedStatus = request.status();
        boolean statusChanges = requestedStatus != null && !requestedStatus.equals(entity.getStatus());
        if (requestedStatus != null && requestedStatus != 0 && requestedStatus != 1) {
            return ApiResult.fail(422, "ROLE_STATUS_INVALID");
        }
        if (statusChanges && (StringUtils.hasText(request.roleName()) || request.remark() != null)) {
            return ApiResult.fail(422, "ROLE_STATUS_UPDATE_MUST_BE_SEPARATE");
        }
        if (statusChanges && "SUPER_ADMIN".equals(entity.getRoleCode()) && requestedStatus == 0) {
            return ApiResult.fail(403, "SUPER_ADMIN_ROLE_IMMUTABLE");
        }
        if (statusChanges && !A2ReplayContext.isReplaying()) {
            Map<String, Object> params = Map.of("roleId", roleId, "status", requestedStatus);
            String action = requestedStatus == 1 ? "A6_ROLE_ENABLED" : "A6_ROLE_DISABLED";
            return auditCenterService.createProposal(idempotencyKey, new AuditOperationProposalRequest(
                    action, entity.getRoleCode(), String.valueOf(entity.getStatus()), String.valueOf(requestedStatus),
                    actor(request.operator()), "ADMIN", "acct", true, false, "TWO_PERSON", request.reason(), "A",
                    new AuditReplayCommand("A", "a6_role_status_update", params),
                    new AuditLockTarget("A", "a6_role", roleId.toString()), List.of()));
        }
        if (StringUtils.hasText(request.roleName())) {
            entity.setRoleName(request.roleName());
        }
        if (request.remark() != null) {
            entity.setRemark(request.remark());
        }
        if (requestedStatus != null) {
            entity.setStatus(requestedStatus);
        }
        List<Long> adminIds = roleRelationMapper.selectAdminIdsByRole(roleId);
        roleMapper.updateById(entity);
        evictAdmins(adminIds);
        audit("A6_ROLE_UPDATED", roleId.toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("roleCode", entity.getRoleCode()), "MEDIUM");
        return detail(roleId);
    }

    @Transactional
    public ApiResult<?> deleteRole(Long roleId, String idempotencyKey, AdminAccountActionRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        AdminRoleEntity entity = loadActive(roleId);
        if (entity == null) {
            return ApiResult.fail(404, "ROLE_NOT_FOUND");
        }
        if (BUILTIN_ROLE_CODES.contains(entity.getRoleCode())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BUILTIN_ROLE_NOT_DELETABLE");
        }
        if (!A2ReplayContext.isReplaying()) {
            return auditCenterService.createProposal(idempotencyKey, new AuditOperationProposalRequest(
                    "A6_ROLE_DELETED", entity.getRoleCode(), "active", "deleted",
                    actor(request.operator()), "ADMIN", "acct", true, false, "TWO_PERSON", request.reason(), "A",
                    new AuditReplayCommand("A", "a6_role_delete", Map.of("roleId", roleId)),
                    new AuditLockTarget("A", "a6_role", roleId.toString()), List.of()));
        }
        List<Long> adminIds = roleRelationMapper.selectAdminIdsByRole(roleId);
        roleRelationMapper.disableRelationsByRole(roleId);
        roleMenuMapper.disableAllRoleMenus(roleId);
        rolePermissionMapper.disableAllRolePermissions(entity.getRoleCode());
        entity.setIsDeleted(1);
        roleMapper.updateById(entity);
        evictAdmins(adminIds);
        audit("A6_ROLE_DELETED", roleId.toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("roleCode", entity.getRoleCode(), "affectedAdmins", adminIds.size()), "HIGH");
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<?> updateRoleGrants(Long roleId, String idempotencyKey,
                                         PlatformRoleGrantsUpdateRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        AdminRoleEntity entity = loadActive(roleId);
        if (entity == null) {
            return ApiResult.fail(404, "ROLE_NOT_FOUND");
        }
        if ("SUPER_ADMIN".equals(entity.getRoleCode())) {
            return ApiResult.fail(403, "SUPER_ADMIN_ROLE_IMMUTABLE");
        }
        String roleCode = entity.getRoleCode();
        List<String> desiredPerms = request.permissionCodes() == null ? List.of() : request.permissionCodes();
        List<Long> desiredMenus = request.menuIds() == null ? List.of() : request.menuIds();

        if (!A2ReplayContext.isReplaying()) {
            String authenticatedOperator = actor(request.operator());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("roleId", roleId);
            params.put("permissionCodes", desiredPerms);
            params.put("menuIds", desiredMenus);
            params.put("reason", request.reason());
            params.put("operator", authenticatedOperator);
            AuditOperationProposalRequest proposal = new AuditOperationProposalRequest(
                    "A6_ROLE_GRANTS_CHANGED",
                    roleCode,
                    String.valueOf(Map.of(
                            "permissionCodes", rolePermissionMapper.selectActivePermissionCodesByRole(roleId),
                            "menuIds", roleMenuMapper.selectActiveMenuIdsByRole(roleId))),
                    String.valueOf(Map.of("permissionCodes", desiredPerms, "menuIds", desiredMenus)),
                    authenticatedOperator,
                    "ADMIN",
                    "acct",
                    true,
                    false,
                    "TWO_PERSON",
                    request.reason(),
                    "A",
                    new AuditReplayCommand("A", "a6_role_grants_update", params),
                    new AuditLockTarget("A", "a6_role", roleId.toString()),
                    List.of());
            return auditCenterService.createProposal(idempotencyKey, proposal);
        }

        int permsBefore = rolePermissionMapper.selectActivePermissionCodesByRole(roleId).size();
        if (desiredPerms.isEmpty()) {
            rolePermissionMapper.disableAllRolePermissions(roleCode);
        } else {
            rolePermissionMapper.disableRolePermissionsExcept(roleCode, desiredPerms);
            rolePermissionMapper.restoreRolePermissions(roleCode, desiredPerms);
            rolePermissionMapper.insertMissingRolePermissions(roleCode, desiredPerms);
        }
        int menusBefore = roleMenuMapper.selectActiveMenuIdsByRole(roleId).size();
        if (desiredMenus.isEmpty()) {
            roleMenuMapper.disableAllRoleMenus(roleId);
        } else {
            roleMenuMapper.disableRoleMenusExcept(roleId, desiredMenus);
            roleMenuMapper.restoreRoleMenus(roleId, desiredMenus);
            roleMenuMapper.insertMissingRoleMenus(roleId, desiredMenus);
        }
        List<Long> adminIds = roleRelationMapper.selectAdminIdsByRole(roleId);
        evictAdmins(adminIds);
        audit("A6_ROLE_GRANTS_CHANGED", roleId.toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("roleCode", roleCode,
                        "permsBefore", permsBefore, "permsAfter", desiredPerms.size(),
                        "menusBefore", menusBefore, "menusAfter", desiredMenus.size(),
                        "affectedAdmins", adminIds.size()),
                "HIGH");
        return detail(roleId);
    }

    private long countAdminsByRole(Long roleId) {
        Long count = roleRelationMapper.selectCount(new LambdaQueryWrapper<AdminRoleRelationEntity>()
                .eq(AdminRoleRelationEntity::getRoleId, roleId)
                .eq(AdminRoleRelationEntity::getIsDeleted, 0));
        return count == null ? 0 : count;
    }

    private AdminRoleEntity loadActive(Long roleId) {
        AdminRoleEntity entity = roleMapper.selectById(roleId);
        if (entity == null || (entity.getIsDeleted() != null && entity.getIsDeleted() == 1)) {
            return null;
        }
        return entity;
    }

    private void evictAdmins(List<Long> adminIds) {
        if (adminIds == null) {
            return;
        }
        for (Long adminId : adminIds) {
            permissionCache.evict(adminId);
        }
    }

    private ApiResult<Void> requireMutation(String idempotencyKey, String reason, String operator) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(),
                    OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(operator)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATOR_REQUIRED");
        }
        return null;
    }

    private void audit(String action, String resourceId, String reason, String operator,
                       String idempotencyKey, Map<String, Object> detail, String riskLevel) {
        Map<String, Object> fullDetail = new LinkedHashMap<>(detail);
        fullDetail.put("reason", reason == null ? "" : reason.trim());
        fullDetail.put("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim());
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(RESOURCE_TYPE)
                .resourceId(resourceId)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel(riskLevel)
                .detail(fullDetail)
                .build());
    }

    private String actor(String fallback) {
        return AdminActorResolver.resolve(fallback);
    }
}
