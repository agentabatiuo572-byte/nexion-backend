package ffdd.opsconsole.bi.dto;

public record BiReportQueryRequest(
        String type,
        String status,
        Integer limit) {
}
