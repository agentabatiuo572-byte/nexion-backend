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
        Long pageSize) {
}
