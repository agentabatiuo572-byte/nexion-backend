package ffdd.opsconsole.bi.dto;

public record BiReportCreateRequest(
        String reason,
        String operator,
        String exportType,
        String timeRange,
        String fields,
        String piiLevel,
        String maskPolicy,
        String recipient,
        String ticket,
        String cohort,
        String phase,
        String locale,
        String ref) {

    public BiReportCreateRequest(
            String reason,
            String operator,
            String exportType,
            String timeRange,
            String fields,
            String piiLevel,
            String maskPolicy,
            String recipient,
            String ticket) {
        this(reason, operator, exportType, timeRange, fields, piiLevel, maskPolicy, recipient, ticket,
                null, null, null, null);
    }
}
