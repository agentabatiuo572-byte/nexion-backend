package ffdd.opsconsole.bi.domain;

public record BiReportCreateCommand(
        String reportId,
        String name,
        String type,
        String cycle,
        String format,
        String scope,
        String fields,
        Long rowCount,
        Boolean containsPii,
        String maskingPolicy,
        String status,
        String note) {
}
