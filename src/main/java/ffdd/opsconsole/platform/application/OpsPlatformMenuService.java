package ffdd.opsconsole.platform.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuNodeCreateRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuNodeUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformMenuTreeOverview;
import ffdd.opsconsole.platform.dto.PlatformMenuTreeOverview.MenuNodeView;
import ffdd.opsconsole.platform.dto.PlatformMenuTreeOverview.MenuTreeNode;
import ffdd.opsconsole.platform.infrastructure.AdminMenuEntity;
import ffdd.opsconsole.platform.mapper.AdminMenuMapper;
import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** A7 菜单管理。整树内存构树；节点元数据变更不 evict（不影响权限码缓存）。 */
@ApplicationService
@RequiredArgsConstructor
public class OpsPlatformMenuService {
    private static final String RESOURCE_TYPE = "A7_PLATFORM_MENU";

    private final AdminMenuMapper menuMapper;
    private final AdminRoleMenuMapper roleMenuMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;

    public ApiResult<PlatformMenuTreeOverview> overview() {
        List<AdminMenuEntity> all = menuMapper.selectAllActive();
        Map<Long, List<AdminMenuEntity>> byParent = all.stream()
                .collect(Collectors.groupingBy(e -> e.getParentId() == null ? 0L : e.getParentId()));
        List<MenuTreeNode> tree = buildChildren(byParent, 0L);
        int domainCount = (int) all.stream().filter(e -> e.getParentId() == null).count();
        int pageCount = all.size() - domainCount;
        int activeCount = (int) all.stream().filter(e -> e.getStatus() != null && e.getStatus() == 1).count();
        return ApiResult.ok(new PlatformMenuTreeOverview(tree, domainCount, pageCount, activeCount));
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public ApiResult<MenuNodeView> createNode(String idempotencyKey, PlatformMenuNodeCreateRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        String menuCode = request.menuCode() == null ? "" : request.menuCode().trim().toUpperCase(java.util.Locale.ROOT);
        return (ApiResult<MenuNodeView>) idempotent("A7_MENU_CREATE:" + menuCode, idempotencyKey, request,
                () -> createNodeOnce(idempotencyKey, request));
    }

    private ApiResult<MenuNodeView> createNodeOnce(String idempotencyKey, PlatformMenuNodeCreateRequest request) {
        String menuCode = request.menuCode() == null ? "" : request.menuCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!StringUtils.hasText(menuCode)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "MENU_CODE_REQUIRED");
        }
        AdminMenuEntity existing = menuMapper.selectByMenuCodeForUpdate(menuCode);
        if (existing != null && !Integer.valueOf(1).equals(existing.getIsDeleted())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "MENU_CODE_DUPLICATE");
        }
        Long parentId = null;
        if (StringUtils.hasText(request.parentCode())) {
            AdminMenuEntity parent = menuMapper.selectActiveByCodeForUpdate(request.parentCode().trim());
            if (parent == null) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PARENT_MENU_NOT_FOUND");
            }
            parentId = parent.getId();
        }
        AdminMenuEntity entity = existing == null ? new AdminMenuEntity() : existing;
        entity.setMenuCode(menuCode);
        entity.setMenuName(request.menuName());
        entity.setMenuNameZh(StringUtils.hasText(request.menuNameZh()) ? request.menuNameZh() : request.menuName());
        entity.setParentId(parentId);
        entity.setRoutePath(request.routePath());
        entity.setIcon(request.icon());
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        entity.setStatus(1);
        entity.setIsDeleted(0);
        if (existing == null) {
            menuMapper.insert(entity);
        } else {
            menuMapper.updateById(entity);
        }
        audit("A7_MENU_NODE_CREATED", entity.getId().toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("menuCode", menuCode, "menuName", String.valueOf(entity.getMenuName())));
        return ApiResult.ok(toView(entity));
    }

    @Transactional
    public ApiResult<MenuNodeView> updateNode(Long menuId, String idempotencyKey, PlatformMenuNodeUpdateRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        @SuppressWarnings("unchecked")
        ApiResult<MenuNodeView> result = (ApiResult<MenuNodeView>) idempotent(
                "A7_MENU_UPDATE:" + menuId, idempotencyKey, request,
                () -> updateNodeOnce(menuId, idempotencyKey, request));
        return result;
    }

    private ApiResult<MenuNodeView> updateNodeOnce(Long menuId, String idempotencyKey,
                                                   PlatformMenuNodeUpdateRequest request) {
        AdminMenuEntity entity = loadActiveForUpdate(menuId);
        if (entity == null) {
            return ApiResult.fail(404, "MENU_NODE_NOT_FOUND");
        }
        ApiResult<Void> statusGuard = validateStatusChange(entity, request.status());
        if (statusGuard != null) {
            return rejectMutation(entity, "UPDATE_STATUS", statusGuard.getCode(), statusGuard.getMessage(),
                    request.reason(), request.operator(), idempotencyKey,
                    Map.of("targetStatus", request.status()));
        }
        if (StringUtils.hasText(request.menuName())) {
            entity.setMenuName(request.menuName());
        }
        if (StringUtils.hasText(request.menuNameZh())) {
            entity.setMenuNameZh(request.menuNameZh());
        }
        if (request.routePath() != null) {
            entity.setRoutePath(request.routePath());
        }
        if (request.icon() != null) {
            entity.setIcon(request.icon());
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            if (request.status() != 0 && request.status() != 1) {
                return ApiResult.fail(422, "MENU_STATUS_INVALID");
            }
            entity.setStatus(request.status());
        }
        menuMapper.updateById(entity);
        audit("A7_MENU_NODE_UPDATED", menuId.toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("menuCode", entity.getMenuCode()));
        return ApiResult.ok(toView(entity));
    }

    @Transactional
    public ApiResult<Void> deleteNode(Long menuId, String idempotencyKey, AdminAccountActionRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        @SuppressWarnings("unchecked")
        ApiResult<Void> result = (ApiResult<Void>) idempotent(
                "A7_MENU_DELETE:" + menuId, idempotencyKey, request,
                () -> deleteNodeOnce(menuId, idempotencyKey, request));
        return result;
    }

    private ApiResult<Void> deleteNodeOnce(Long menuId, String idempotencyKey, AdminAccountActionRequest request) {
        AdminMenuEntity entity = loadActiveForUpdate(menuId);
        if (entity == null) {
            return ApiResult.fail(404, "MENU_NODE_NOT_FOUND");
        }
        if (!menuMapper.selectChildren(menuId).isEmpty()) {
            return rejectMutation(entity, "DELETE", 409, "MENU_NODE_HAS_CHILDREN",
                    request.reason(), request.operator(), idempotencyKey, Map.of());
        }
        if (permissionMapper.countActiveByMenuId(menuId) > 0) {
            return rejectMutation(entity, "DELETE", 409, "MENU_NODE_HAS_PERMISSIONS",
                    request.reason(), request.operator(), idempotencyKey, Map.of());
        }
        if (roleMenuMapper.countActiveByMenuId(menuId) > 0) {
            return rejectMutation(entity, "DELETE", 409, "MENU_NODE_HAS_ROLE_BINDINGS",
                    request.reason(), request.operator(), idempotencyKey, Map.of());
        }
        entity.setIsDeleted(1);
        menuMapper.updateById(entity);
        audit("A7_MENU_NODE_DELETED", menuId.toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("menuCode", entity.getMenuCode()));
        return ApiResult.ok(null);
    }

    private ApiResult<Void> validateStatusChange(AdminMenuEntity entity, Integer targetStatus) {
        if (targetStatus == null) {
            return null;
        }
        if (targetStatus == 1 && entity.getParentId() != null) {
            AdminMenuEntity parent = loadActiveForUpdate(entity.getParentId());
            if (parent == null || !Integer.valueOf(1).equals(parent.getStatus())) {
                return ApiResult.fail(409, "PARENT_MENU_NOT_ACTIVE");
            }
        }
        if (targetStatus == 0) {
            boolean hasActiveChildren = menuMapper.selectChildren(entity.getId()).stream()
                    .anyMatch(child -> Integer.valueOf(1).equals(child.getStatus()));
            if (hasActiveChildren) {
                return ApiResult.fail(409, "MENU_NODE_HAS_ACTIVE_CHILDREN");
            }
            if (permissionMapper.countActiveByMenuId(entity.getId()) > 0) {
                return ApiResult.fail(409, "MENU_NODE_HAS_PERMISSIONS");
            }
            if (roleMenuMapper.countActiveByMenuId(entity.getId()) > 0) {
                return ApiResult.fail(409, "MENU_NODE_HAS_ROLE_BINDINGS");
            }
        }
        return null;
    }

    private List<MenuTreeNode> buildChildren(Map<Long, List<AdminMenuEntity>> byParent, Long parentId) {
        List<AdminMenuEntity> children = byParent.getOrDefault(parentId, List.of());
        List<MenuTreeNode> nodes = new ArrayList<>(children.size());
        for (AdminMenuEntity e : children) {
            nodes.add(new MenuTreeNode(toView(e), buildChildren(byParent, e.getId())));
        }
        return nodes;
    }

    private AdminMenuEntity loadActive(Long menuId) {
        AdminMenuEntity entity = menuMapper.selectById(menuId);
        if (entity == null || (entity.getIsDeleted() != null && entity.getIsDeleted() == 1)) {
            return null;
        }
        return entity;
    }

    private AdminMenuEntity loadActiveForUpdate(Long menuId) {
        return menuId == null ? null : menuMapper.selectActiveForUpdate(menuId);
    }

    private boolean menuExists(String menuCode) {
        Long count = menuMapper.selectCount(new LambdaQueryWrapper<AdminMenuEntity>()
                .eq(AdminMenuEntity::getMenuCode, menuCode)
                .eq(AdminMenuEntity::getIsDeleted, 0));
        return count != null && count > 0;
    }

    private MenuNodeView toView(AdminMenuEntity e) {
        return new MenuNodeView(e.getId(), e.getMenuCode(), e.getMenuName(), e.getMenuNameZh(),
                e.getParentId(), e.getRoutePath(), e.getIcon(), e.getSortOrder(), e.getStatus());
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
        String normalizedReason = reason.trim();
        int reasonLength = normalizedReason.codePointCount(0, normalizedReason.length());
        if (reasonLength < 8 || reasonLength > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REASON_LENGTH_INVALID");
        }
        if (!StringUtils.hasText(operator)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATOR_REQUIRED");
        }
        return null;
    }

    private void audit(String action, String resourceId, String reason, String operator,
                       String idempotencyKey, Map<String, Object> detail) {
        Map<String, Object> fullDetail = new LinkedHashMap<>(detail);
        fullDetail.put("reason", reason == null ? "" : reason.trim());
        fullDetail.put("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(RESOURCE_TYPE)
                .resourceId(resourceId)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(fullDetail)
                .build());
    }

    private <T> ApiResult<T> rejectMutation(AdminMenuEntity entity, String operation, int httpStatus,
                                            String rejectionCode, String reason, String operator,
                                            String idempotencyKey, Map<String, Object> detail) {
        Map<String, Object> fullDetail = new LinkedHashMap<>(detail);
        fullDetail.put("menuCode", entity.getMenuCode());
        fullDetail.put("operation", operation);
        fullDetail.put("rejectionCode", rejectionCode);
        fullDetail.put("reason", reason.trim());
        fullDetail.put("idempotencyKey", idempotencyKey.trim());
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("A7_MENU_MUTATION_REJECTED")
                .resourceType(RESOURCE_TYPE)
                .resourceId(entity.getId().toString())
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(fullDetail)
                .build());
        return ApiResult.fail(httpStatus, rejectionCode);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<?> idempotent(String scope, String idempotencyKey, Object request,
                                    Supplier<? extends ApiResult<?>> action) {
        return (ApiResult<?>) idempotencyService.execute(
                scope, idempotencyKey.trim(), requestHash(scope, request), ApiResult.class, (Supplier) action);
    }

    private String requestHash(String scope, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String actor(String fallback) {
        return AdminActorResolver.resolve(fallback);
    }
}
