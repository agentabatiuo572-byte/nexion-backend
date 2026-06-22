package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMessageMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSupportTicketRepository implements SupportTicketRepository {
    private final SupportTicketMapper ticketMapper;
    private final SupportTicketMessageMapper messageMapper;

    @Override
    public Map<String, Object> counters() {
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("active", ticketMapper.countActive());
        counters.put("pendingUser", ticketMapper.countPendingUser());
        counters.put("opsUnread", ticketMapper.countOpsUnread());
        counters.put("highPriorityActive", ticketMapper.countHighPriorityActive());
        counters.put("archived", ticketMapper.countArchived());
        return counters;
    }

    @Override
    public PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request) {
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        String scope = normalizeScope(request == null ? null : request.scope());
        String status = request == null ? null : trim(request.status());
        String category = request == null ? null : trim(request.category());
        String priority = request == null ? null : trim(request.priority());
        Long assignedAdminId = request == null ? null : request.assignedAdminId();
        Long userId = request == null ? null : request.userId();
        String keyword = request == null ? null : trim(request.keyword());
        long total = ticketMapper.countTickets(scope, status, category, priority, assignedAdminId, userId, keyword);
        List<SupportTicketView> records =
                ticketMapper.pageTickets(scope, status, category, priority, assignedAdminId, userId, keyword, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<SupportTicketView> findByTicketNo(String ticketNo) {
        return Optional.ofNullable(ticketMapper.findByTicketNo(ticketNo));
    }

    @Override
    public List<SupportTicketMessageView> messages(String ticketNo) {
        return messageMapper.listByTicketNo(ticketNo);
    }

    @Override
    public SupportTicketView createTicket(
            String ticketNo,
            Long userId,
            String category,
            String priority,
            String title,
            String body,
            Long assignedAdminId,
            String assignedAdminName,
            String operator,
            LocalDateTime now) {
        SupportTicketEntity entity = new SupportTicketEntity();
        entity.setTicketNo(ticketNo);
        entity.setUserId(userId == null ? 0L : userId);
        entity.setCategory(category);
        entity.setPriority(priority);
        entity.setStatus("OPEN");
        entity.setTitle(title);
        entity.setLastMessage(body);
        entity.setAssignedAdminId(assignedAdminId);
        entity.setAssignedAdminName(assignedAdminName);
        entity.setUserUnreadCount(0);
        entity.setOpsUnreadCount(1);
        entity.setMessageCount(1);
        entity.setLastMessageAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        ticketMapper.insert(entity);
        insertMessage(entity.getId(), ticketNo, userId, "user", "用户", body, now);
        return findByTicketNo(ticketNo).orElseGet(() -> new SupportTicketView(
                entity.getId(), ticketNo, entity.getUserId(), category, priority, "OPEN", title, body,
                assignedAdminId, assignedAdminName, 0, 1, 1, now, null, now, now));
    }

    @Override
    public void appendReply(SupportTicketView ticket, String body, String operator, LocalDateTime now) {
        ticketMapper.appendReplyHeader(ticket.ticketNo(), body, now);
        insertMessage(ticket.id(), ticket.ticketNo(), null, "agent", operator, body, now);
    }

    @Override
    public void updateStatus(SupportTicketView ticket, String status, String operator, LocalDateTime now) {
        ticketMapper.updateStatus(ticket.ticketNo(), status, now);
    }

    @Override
    public void updatePriority(SupportTicketView ticket, String priority, LocalDateTime now) {
        ticketMapper.updatePriority(ticket.ticketNo(), priority, now);
    }

    @Override
    public void assign(SupportTicketView ticket, Long assignedAdminId, String assignedAdminName, LocalDateTime now) {
        ticketMapper.assign(ticket.ticketNo(), assignedAdminId, assignedAdminName, now);
    }

    private void insertMessage(Long ticketId, String ticketNo, Long senderId, String senderType, String senderName, String content, LocalDateTime now) {
        SupportTicketMessageEntity message = new SupportTicketMessageEntity();
        message.setTicketId(ticketId);
        message.setTicketNo(ticketNo);
        message.setSenderId(senderId);
        message.setSenderType(senderType);
        message.setSenderName(senderName);
        message.setContent(content);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        message.setIsDeleted(0);
        messageMapper.insert(message);
    }

    private long normalizePage(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizeSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeScope(String value) {
        String scope = trim(value);
        return scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope) ? null : scope.toLowerCase();
    }
}
