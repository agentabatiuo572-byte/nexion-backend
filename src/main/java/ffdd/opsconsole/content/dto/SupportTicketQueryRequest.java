package ffdd.opsconsole.content.dto;

public record SupportTicketQueryRequest(
        String scope,
        String status,
        String category,
        String priority,
        Long assignedAdminId,
        Long userId,
        String keyword,
        Long pageNum,
        Long pageSize,
        ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode readMode,
        Long groupId) {
    public SupportTicketQueryRequest(String scope,String status,String category,String priority,Long assignedAdminId,Long userId,String keyword,Long pageNum,Long pageSize) {
        this(scope,status,category,priority,assignedAdminId,userId,keyword,pageNum,pageSize,null,null);
    }
}
