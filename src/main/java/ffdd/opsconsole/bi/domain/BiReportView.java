package ffdd.opsconsole.bi.domain;

import java.time.LocalDateTime;

public record BiReportView(
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
        String note,
        String lastAction,
        LocalDateTime lastActionAt,
        String reason,
        Boolean snapshotAvailable) {
    public BiReportView(
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
            String note,
            String lastAction,
            LocalDateTime lastActionAt,
            String reason) {
        this(reportId, name, type, cycle, format, scope, fields, rowCount, containsPii, maskingPolicy,
                status, note, lastAction, lastActionAt, reason, false);
    }
}
