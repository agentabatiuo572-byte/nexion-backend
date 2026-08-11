package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.shared.api.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SupportTicketRepository {
    void ensureSeedData(LocalDateTime now);

    Map<String, Object> counters();

    PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request);

    Optional<SupportTicketView> findByTicketNo(String ticketNo);

    List<SupportTicketMessageView> messages(String ticketNo);

    /** Public App projection: excludes internal notes and operational system traces. */
    default List<SupportTicketMessageView> userVisibleMessages(String ticketNo) {
        return messages(ticketNo).stream()
                .filter(message -> "user".equalsIgnoreCase(message.senderType())
                        || "agent".equalsIgnoreCase(message.senderType()))
                .toList();
    }

    SupportTicketView createTicket(
            String ticketNo,
            Long userId,
            String category,
            String priority,
            String title,
            String body,
            Long assignedAdminId,
            String assignedAdminName,
            String operator,
            LocalDateTime now);

    void appendReply(SupportTicketView ticket, String body, String operator, LocalDateTime now);

    default boolean appendReplyCas(SupportTicketView ticket, String body, String operator, LocalDateTime now) {
        appendReply(ticket, body, operator, now);
        return true;
    }

    /** App user reply: reopens a non-closed ticket and increments the ops unread counter under CAS. */
    default boolean appendUserReplyCas(SupportTicketView ticket, String body, LocalDateTime now) {
        throw new UnsupportedOperationException("APP_SUPPORT_TICKET_REPLY_NOT_IMPLEMENTED");
    }

    void updateStatus(SupportTicketView ticket, String status, String operator, LocalDateTime now);

    default boolean updateStatusCas(SupportTicketView ticket, String status, String operator, LocalDateTime now) {
        updateStatus(ticket, status, operator, now);
        return true;
    }

    void updatePriority(SupportTicketView ticket, String priority, LocalDateTime now);

    default boolean updatePriorityCas(SupportTicketView ticket, String priority, LocalDateTime now) {
        updatePriority(ticket, priority, now);
        return true;
    }

    void assign(SupportTicketView ticket, Long assignedAdminId, String assignedAdminName, LocalDateTime now);

    default boolean assignCas(
            SupportTicketView ticket,
            Long assignedAdminId,
            String assignedAdminName,
            LocalDateTime now) {
        assign(ticket, assignedAdminId, assignedAdminName, now);
        return true;
    }

    void archive(SupportTicketView ticket, boolean archived, String operator, LocalDateTime now);

    default boolean archiveCas(SupportTicketView ticket, boolean archived, String operator, LocalDateTime now) {
        archive(ticket, archived, operator, now);
        return true;
    }

    void appendSystemTrace(SupportTicketView ticket, String body, LocalDateTime now);

    default boolean appendSystemTraceCas(SupportTicketView ticket, String body, LocalDateTime now) {
        appendSystemTrace(ticket, body, now);
        return true;
    }

    default boolean appendInternalNoteCas(
            SupportTicketView ticket,
            String body,
            String operator,
            LocalDateTime now) {
        appendSystemTrace(ticket, "内部备注 · " + operator + "：" + body, now);
        return true;
    }
}
