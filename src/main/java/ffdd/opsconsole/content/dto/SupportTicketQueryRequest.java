package ffdd.opsconsole.content.dto;

/** Filters for the read-only historical ticket search (admin global search). */
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
