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

    void updateStatus(SupportTicketView ticket, String status, String operator, LocalDateTime now);

    void updatePriority(SupportTicketView ticket, String priority, LocalDateTime now);

    void assign(SupportTicketView ticket, Long assignedAdminId, String assignedAdminName, LocalDateTime now);
}
