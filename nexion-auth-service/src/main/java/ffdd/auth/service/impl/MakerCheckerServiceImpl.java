package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.auth.client.SystemConfigClient;
import ffdd.auth.domain.Admin;
import ffdd.auth.domain.MakerCheckerTask;
import ffdd.auth.dto.AdminCreateRequest;
import ffdd.auth.dto.AdminTwoFactorResetRequest;
import ffdd.auth.dto.AdminUpdateRequest;
import ffdd.auth.dto.MakerCheckerCreateRequest;
import ffdd.auth.dto.MakerCheckerReviewRequest;
import ffdd.auth.mapper.MakerCheckerTaskMapper;
import ffdd.auth.service.AccessControlService;
import ffdd.auth.service.AdminService;
import ffdd.auth.service.MakerCheckerService;
import ffdd.common.exception.BizException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MakerCheckerServiceImpl implements MakerCheckerService {
    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final MakerCheckerTaskMapper taskMapper;
    private final AdminService adminService;
    private final AccessControlService accessControlService;
    private final SystemConfigClient systemConfigClient;
    private final ObjectMapper objectMapper;

    @Override
    public Page<MakerCheckerTask> page(long current, long size, String status, String resourceType) {
        LambdaQueryWrapper<MakerCheckerTask> wrapper = new LambdaQueryWrapper<MakerCheckerTask>()
                .eq(MakerCheckerTask::getIsDeleted, 0)
                .eq(StringUtils.hasText(status), MakerCheckerTask::getStatus, status)
                .eq(StringUtils.hasText(resourceType), MakerCheckerTask::getResourceType, resourceType)
                .orderByDesc(MakerCheckerTask::getId);
        return taskMapper.selectPage(Page.of(current, size), wrapper);
    }

    @Override
    public MakerCheckerTask create(MakerCheckerCreateRequest request) {
        MakerCheckerTask task = new MakerCheckerTask();
        task.setActionType(request.getActionType());
        task.setResourceType(request.getResourceType());
        task.setResourceId(request.getResourceId());
        task.setTitle(request.getTitle());
        task.setDetail(request.getDetail());
        task.setPayloadJson(request.getPayload() == null ? "{}" : request.getPayload().toString());
        task.setMaker(request.getMaker());
        task.setStatus(PENDING);
        task.setIsDeleted(0);
        taskMapper.insert(task);
        return task;
    }

    @Override
    public MakerCheckerTask approve(Long id, MakerCheckerReviewRequest request) {
        MakerCheckerTask task = pendingTask(id);
        if (task.getMaker() != null && task.getMaker().equalsIgnoreCase(request.getChecker())) {
            throw new BizException("Checker cannot be the maker");
        }
        execute(task, request);
        task.setChecker(request.getChecker());
        task.setReason(request.getReason());
        task.setStatus(APPROVED);
        task.setReviewedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return taskMapper.selectById(id);
    }

    @Override
    public MakerCheckerTask reject(Long id, MakerCheckerReviewRequest request) {
        MakerCheckerTask task = pendingTask(id);
        if (task.getMaker() != null && task.getMaker().equalsIgnoreCase(request.getChecker())) {
            throw new BizException("Checker cannot be the maker");
        }
        task.setChecker(request.getChecker());
        task.setReason(request.getReason());
        task.setStatus(REJECTED);
        task.setReviewedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return taskMapper.selectById(id);
    }

    private MakerCheckerTask pendingTask(Long id) {
        MakerCheckerTask task = taskMapper.selectById(id);
        if (task == null || Integer.valueOf(1).equals(task.getIsDeleted())) {
            throw new BizException("Maker-Checker task does not exist");
        }
        if (!PENDING.equals(task.getStatus())) {
            throw new BizException("Maker-Checker task is not pending");
        }
        return task;
    }

    private void execute(MakerCheckerTask task, MakerCheckerReviewRequest request) {
        try {
            JsonNode payload = objectMapper.readTree(task.getPayloadJson() == null ? "{}" : task.getPayloadJson());
            switch (task.getActionType()) {
                case "ADMIN_CREATE" -> executeAdminCreate(payload);
                case "ADMIN_UPDATE" -> executeAdminUpdate(payload);
                case "ADMIN_DELETE" -> adminService.delete(requiredLong(payload, "adminId"));
                case "ADMIN_RESET_2FA" -> adminService.resetTwoFactor(requiredLong(payload, "adminId"), twoFactorRequest(request));
                case "ROLE_ASSIGN_PERMISSIONS" -> executeRoleAssign(payload);
                case "SYSTEM_CONFIG_UPDATE" -> executeSystemConfigUpdate(payload);
                default -> throw new BizException("Unsupported Maker-Checker action: " + task.getActionType());
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("Maker-Checker execution failed: " + ex.getMessage());
        }
    }

    private void executeAdminCreate(JsonNode payload) throws Exception {
        AdminCreateRequest adminRequest = objectMapper.treeToValue(payload.get("admin"), AdminCreateRequest.class);
        Admin created = adminService.create(adminRequest);
        List<Long> roleIds = longs(payload.get("roleIds"));
        if (!roleIds.isEmpty()) {
            adminService.assignRoles(created.getId(), roleIds);
        }
    }

    private void executeAdminUpdate(JsonNode payload) throws Exception {
        Long adminId = requiredLong(payload, "adminId");
        if (payload.has("admin")) {
            AdminUpdateRequest updateRequest = objectMapper.treeToValue(payload.get("admin"), AdminUpdateRequest.class);
            adminService.update(adminId, updateRequest);
        }
        if (payload.has("roleIds")) {
            adminService.assignRoles(adminId, longs(payload.get("roleIds")));
        }
    }

    private void executeRoleAssign(JsonNode payload) {
        Long roleId = requiredLong(payload, "roleId");
        accessControlService.assignMenus(roleId, longs(payload.get("menuIds")));
        accessControlService.assignApiPermissions(roleId, longs(payload.get("permissionIds")));
    }

    private void executeSystemConfigUpdate(JsonNode payload) {
        String configKey = requiredText(payload, "configKey");
        String configValue = normalizeSystemConfigValue(configKey, requiredText(payload, "configValue"));
        systemConfigClient.upsert(new SystemConfigClient.ConfigItem(
                null,
                configKey,
                configValue,
                textOrDefault(payload, "valueType", "STRING"),
                textOrDefault(payload, "configGroup", "platform"),
                textOrDefault(payload, "visibility", "ADMIN"),
                textOrDefault(payload, "remark", ""),
                intOrDefault(payload, "status", 1)));
    }

    private String normalizeSystemConfigValue(String configKey, String configValue) {
        if (!configKey.startsWith("platform.events.")) {
            return configValue;
        }
        try {
            JsonNode node = objectMapper.readTree(configValue);
            if (node instanceof ObjectNode objectNode) {
                JsonNode stage = objectNode.get("stage");
                if (stage == null || "pending".equalsIgnoreCase(stage.asText())) {
                    objectNode.put("stage", "gray");
                    return objectMapper.writeValueAsString(objectNode);
                }
            }
        } catch (Exception ignored) {
            return configValue;
        }
        return configValue;
    }

    private AdminTwoFactorResetRequest twoFactorRequest(MakerCheckerReviewRequest request) {
        AdminTwoFactorResetRequest resetRequest = new AdminTwoFactorResetRequest();
        resetRequest.setOperator(request.getChecker());
        resetRequest.setReason(request.getReason());
        return resetRequest;
    }

    private Long requiredLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new BizException(field + " is required");
        }
        return value.asLong();
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !StringUtils.hasText(value.asText())) {
            throw new BizException(field + " is required");
        }
        return value.asText();
    }

    private String textOrDefault(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        return value == null || !StringUtils.hasText(value.asText()) ? defaultValue : value.asText();
    }

    private Integer intOrDefault(JsonNode payload, String field, Integer defaultValue) {
        JsonNode value = payload.get(field);
        return value == null || !value.canConvertToInt() ? defaultValue : value.asInt();
    }

    private List<Long> longs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        java.util.ArrayList<Long> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            if (item != null && item.canConvertToLong()) {
                values.add(item.asLong());
            }
        });
        return values;
    }
}
