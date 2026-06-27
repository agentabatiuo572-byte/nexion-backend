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
    public void ensureSeedData(LocalDateTime now) {
        if (ticketMapper.countTickets(null, null, null, null, null, null, null) > 0) {
            return;
        }
        seedTicket(
                "TK-M-SEED-1024",
                88421L,
                "withdrawal",
                "HIGH",
                "Withdrawal pending more than 24 hours",
                "OPEN",
                1L,
                "Marina K.",
                now.minusDays(2),
                List.of(
                        seedMessage("user", "用户", "Hi, I requested a USDT withdrawal yesterday and it is still pending.", now.minusDays(2)),
                        seedMessage("agent", "Marina K.", "I have escalated this to the payment desk and will keep the thread updated.", now.minusDays(2).plusHours(1)),
                        seedMessage("agent", "Marina K.", "Payment desk found a TRC20 confirmation delay. ETA 8-12h.", now.minusHours(4))));
        seedTicket(
                "TK-M-SEED-1023",
                90233L,
                "kyc",
                "NORMAL",
                "KYC documents rejected - what is wrong?",
                "PENDING_USER",
                2L,
                "Tomas R.",
                now.minusDays(2).plusHours(2),
                List.of(
                        seedMessage("user", "用户", "My passport was rejected but I cannot see the reason.", now.minusDays(2).plusHours(2)),
                        seedMessage("agent", "Tomas R.", "The rejection reason was blurry MRZ. Please re-upload with better lighting.", now.minusHours(9))));
        seedTicket(
                "TK-M-SEED-1019",
                39922L,
                "hardware",
                "HIGH",
                "NexionBox Pro disconnected after firmware v3.4",
                "IN_PROGRESS",
                3L,
                "Hiro T.",
                now.minusDays(3),
                List.of(
                        seedMessage("user", "用户", "After firmware v3.4 my NexionBox Pro went offline and will not reconnect.", now.minusDays(3)),
                        seedMessage("agent", "Hiro T.", "Amber-amber-red means WiFi auth failure after update. Please re-pair via app.", now.minusDays(2).plusHours(1)),
                        seedMessage("user", "用户", "Reset worked but earning is still lower than usual.", now.minusDays(1))));
        seedTicket(
                "TK-M-SEED-1011",
                66120L,
                "account",
                "NORMAL",
                "Cannot login from my new phone",
                "RESOLVED",
                4L,
                "Aisha O.",
                now.minusDays(7),
                List.of(
                        seedMessage("user", "用户", "I changed phone and cannot pass 2FA.", now.minusDays(7)),
                        seedMessage("agent", "Aisha O.", "I started account recovery and sent the verification email.", now.minusDays(6).plusHours(1)),
                        seedMessage("user", "用户", "Recovered, thanks.", now.minusDays(5))));
        seedTicket(
                "TK-M-SEED-1007",
                43391L,
                "genesis",
                "URGENT",
                "Genesis Node #4192 not received",
                "CLOSED",
                1L,
                "Marina K.",
                now.minusDays(12),
                List.of(
                        seedMessage("user", "用户", "Purchased Genesis Node #4192 two days ago, tx confirmed but NFT not in wallet.", now.minusDays(12)),
                        seedMessage("agent", "Marina K.", "Mint queue had a backlog; this purchase is now priority.", now.minusDays(11).plusHours(2)),
                        seedMessage("user", "用户", "Received, all good.", now.minusDays(10))));
    }

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

    private void seedTicket(
            String ticketNo,
            Long userId,
            String category,
            String priority,
            String title,
            String status,
            Long assignedAdminId,
            String assignedAdminName,
            LocalDateTime createdAt,
            List<SeedMessage> messages) {
        SeedMessage last = messages.get(messages.size() - 1);
        SupportTicketEntity entity = new SupportTicketEntity();
        entity.setTicketNo(ticketNo);
        entity.setUserId(userId);
        entity.setCategory(category);
        entity.setPriority(priority);
        entity.setStatus(status);
        entity.setTitle(title);
        entity.setLastMessage(last.content());
        entity.setAssignedAdminId(assignedAdminId);
        entity.setAssignedAdminName(assignedAdminName);
        entity.setUserUnreadCount(0);
        entity.setOpsUnreadCount(("OPEN".equals(status) || "PENDING_USER".equals(status)) ? 1 : 0);
        entity.setMessageCount(messages.size());
        entity.setLastMessageAt(last.createdAt());
        entity.setClosedAt(("RESOLVED".equals(status) || "CLOSED".equals(status)) ? last.createdAt() : null);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(last.createdAt());
        entity.setIsDeleted(0);
        ticketMapper.insert(entity);
        for (SeedMessage message : messages) {
            insertMessage(
                    entity.getId(),
                    ticketNo,
                    "user".equals(message.senderType()) ? userId : null,
                    message.senderType(),
                    message.senderName(),
                    message.content(),
                    message.createdAt());
        }
    }

    private SeedMessage seedMessage(String senderType, String senderName, String content, LocalDateTime createdAt) {
        return new SeedMessage(senderType, senderName, content, createdAt);
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

    private record SeedMessage(String senderType, String senderName, String content, LocalDateTime createdAt) {
    }
}
