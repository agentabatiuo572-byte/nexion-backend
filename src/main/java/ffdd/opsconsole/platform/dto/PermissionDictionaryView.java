package ffdd.opsconsole.platform.dto;

/** A8 权限字典展示视图（只读）。 */
public record PermissionDictionaryView(
        String permissionCode,
        String permissionName,
        String permType,
        Long menuId,
        String menuCodePath,
        Integer amplifies,
        Integer boundRoleCount,
        String resourcePath) {
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String description() {
        String documented = switch (permissionCode == null ? "" : permissionCode) {
            case "platform_a2_operation_approve" -> "允许批准通过范围校验的审批单；仍需满足该业务的批准权限，不能批准本人提交的操作。";
            case "finprod_g2_write" -> "允许通过兑换管理入口发起排队处理或修改排队参数；不授予费率和额度的专用修改权限。";
            case "finprod_g2_cap_user_write" -> "允许修改单用户日兑换额度；提高额度仍需按风控规则审批。";
            case "finprod_g2_cap_platform_write" -> "允许修改平台日兑换额度；提高额度仍需按风控规则审批。";
            case "finprod_g2_fee_rate_write" -> "允许调整兑换费率和最低手续费；降费仍需按风险规则审批。";
            case "service_m3_read" -> "允许读取本人数据范围内的专属会话和命令结果，不允许发送或代客户改派。";
            default -> null;
        };
        if (documented != null) return documented;
        String scope = menuCodePath == null || menuCodePath.isBlank() ? "未归类页面" : menuCodePath;
        String action = permissionName == null || permissionName.isBlank() ? "该操作" : permissionName;
        return switch (permType == null ? "" : permType) {
            case "READ" -> "可在「" + scope + "」查看：" + action + "。此权限不授予写入能力。";
            case "WRITE" -> "可在「" + scope + "」发起：" + action + "。仍需满足业务条件。";
            default -> "可在「" + scope + "」发起敏感操作：" + action + "。仍需满足实际审批和业务条件。";
        };
    }
}
