package ffdd.opsconsole.bi.dto;

public record BiReportQueryRequest(
        String type,
        String status,
        Integer pageNum,
        Integer pageSize,
        Integer limit) {
}
