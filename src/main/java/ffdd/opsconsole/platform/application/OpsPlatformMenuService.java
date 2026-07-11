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
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AuditLogService auditLogService;

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
    public ApiResult<MenuNodeView> createNode(String idempotencyKey, PlatformMenuNodeCreateRequest request) {
        ApiResult<Void> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(), actor(request == null ? null : request.operator()));
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        String menuCode = request.menuCode() == null ? "" : request.menuCode().trim();
        if (!StringUtils.hasText(menuCode)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "MENU_CODE_REQUIRED");
        }
        if (menuExists(menuCode)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "MENU_CODE_DUPLICATE");
        }
        Long parentId = null;
        if (StringUtils.hasText(request.parentCode())) {
            AdminMenuEntity parent = menuMapper.selectOne(new LambdaQueryWrapper<AdminMenuEntity>()
                    .eq(AdminMenuEntity::getMenuCode, request.parentCode().trim())
                    .eq(AdminMenuEntity::getIsDeleted, 0));
            if (parent == null) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PARENT_MENU_NOT_FOUND");
            }
            parentId = parent.getId();
        }
        AdminMenuEntity entity = new AdminMenuEntity();
        entity.setMenuCode(menuCode);
        entity.setMenuName(request.menuName());
        entity.setMenuNameZh(StringUtils.hasText(request.menuNameZh()) ? request.menuNameZh() : request.menuName());
        entity.setParentId(parentId);
        entity.setRoutePath(request.routePath());
        entity.setIcon(request.icon());
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        entity.setStatus(1);
        entity.setIsDeleted(0);
        menuMapper.insert(entity);
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
        AdminMenuEntity entity = loadActive(menuId);
        if (entity == null) {
            return ApiResult.fail(404, "MENU_NODE_NOT_FOUND");
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
        AdminMenuEntity entity = loadActive(menuId);
        if (entity == null) {
            return ApiResult.fail(404, "MENU_NODE_NOT_FOUND");
        }
        if (!menuMapper.selectChildren(menuId).isEmpty()) {
            return ApiResult.fail(409, "MENU_NODE_HAS_CHILDREN");
        }
        entity.setIsDeleted(1);
        menuMapper.updateById(entity);
        int affectedBindings = roleMenuMapper.softDeleteByMenuId(menuId);
        audit("A7_MENU_NODE_DELETED", menuId.toString(), request.reason(), request.operator(), idempotencyKey,
                Map.of("menuCode", entity.getMenuCode(), "roleMenuBindingsRevoked", affectedBindings));
        return ApiResult.ok(null);
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
        auditLogService.record(AuditLogWriteRequest.builder()
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

    private String actor(String fallback) {
        return AdminActorResolver.resolve(fallback);
    }
}
