package ffdd.opsconsole.content.dto;

public record SessionScriptQueryRequest(
        String status,
        String keyword,
        Long pageNum,
        Long pageSize) {
}
