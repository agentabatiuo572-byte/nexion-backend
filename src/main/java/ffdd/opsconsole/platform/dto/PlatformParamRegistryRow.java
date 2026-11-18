package ffdd.opsconsole.platform.dto;

public record PlatformParamRegistryRow(
        String canonicalKey,
        String displayName,
        String description,
        String domain,
        String domainLabel,
        String ownerCode,
        String ownerLabel,
        String ownerRoute,
        String currentValue,
        String valueType,
        String unit,
        String source,
        String sourceStatus,
        String updatedAt,
        boolean operationConfirm,
        boolean serverCanonical,
        /**
         * 该值是否为**实时权威事实**(而非存量配置快照)。
         *
         * A3 的系统健康指标(nx_event_outbox 等)由实时 provider 现算,A5 此前却把
         * nx_config_item 里 2026-06-24 种下的 admin.health.* 死行当普通参数展示,
         * 于是同一个事件管道在 A3 显示「严重积压」、在 A5 显示「正常 · 延迟 1.2s」。
         */
        boolean live,
        /** 实时采样的观测时刻;非实时行为空串。 */
        String observedAt,
        /** 该值已过期/无法实时读取 —— 页面必须标「历史/已过期」,不得称「当前服务端值」。 */
        boolean stale) {
    @com.fasterxml.jackson.annotation.JsonProperty(value = "usageDescriptionAvailable", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean usageDescriptionAvailable() {
        String status = usageStatus();
        return !"UNCONFIRMED".equals(status) && !"REDACTED".equals(status);
    }

    @com.fasterxml.jackson.annotation.JsonProperty(value = "usageStatus", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String usageStatus() {
        if ("SECRET".equals(valueType)) return "REDACTED";
        if (description == null || description.isBlank() || description.startsWith("未确认")
                || description.endsWith("这里只显示当前值。")) return "UNCONFIRMED";
        if (description.startsWith("已退役")) return "RETIRED";
        if (description.startsWith("仅管理") || description.startsWith("内部执行状态")) return "READ_OR_METADATA_ONLY";
        return "VERIFIED_USAGE";
    }
}
