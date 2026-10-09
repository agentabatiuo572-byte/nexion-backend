package ffdd.opsconsole.content.dto;

public record SupportAgentQueryRequest(
        Long pageNum,
        Long pageSize,
        Long groupId) {
    public SupportAgentQueryRequest(Long pageNum,Long pageSize) { this(pageNum,pageSize,null); }
}
