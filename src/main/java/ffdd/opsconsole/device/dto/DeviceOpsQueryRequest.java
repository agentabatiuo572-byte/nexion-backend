package ffdd.opsconsole.device.dto;

public record DeviceOpsQueryRequest(
        String keyword, String simState, String source, String productCode, Long pageNum, Long pageSize,
        /** E5 按用户精确筛选(用户 ID);null 表示不按用户过滤,与关键词模糊匹配同时生效。 */
        Long userId) {
}