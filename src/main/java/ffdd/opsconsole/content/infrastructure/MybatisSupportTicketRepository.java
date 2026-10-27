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
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSupportTicketRepository implements SupportTicketRepository {
    @Override public Map<String,Object> counters(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        return ticketMapper.scopedCounters(java.util.Objects.requireNonNull(scope,"Current scope required"));
    }
    @Override public PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        return scopedPage(request,null,false,scope);
    }
    @Override public PageResult<SupportTicketView> pageTicketsBeforeId(SupportTicketQueryRequest request,Long beforeId,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        return scopedPage(request,beforeId,true,scope);
    }
    private PageResult<SupportTicketView> scopedPage(SupportTicketQueryRequest r,Long before,boolean stable,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        java.util.Objects.requireNonNull(scope,"Current scope required");
        long page=stable?1:normalizePage(r==null?null:r.pageNum()),size=normalizeSize(r==null?null:r.pageSize());
        String tab=normalizeScope(r==null?null:r.scope()),status=r==null?null:trim(r.status()),category=r==null?null:trim(r.category()),priority=r==null?null:trim(r.priority()),keyword=r==null?null:trim(r.keyword());
        Long assigned=r==null?null:r.assignedAdminId(),user=r==null?null:r.userId();
        var reader=visibility();
        long total=ticketMapper.countTicketsScoped(tab,status,category,priority,assigned,user,keyword,reader,scope);
        var rows=total==0?List.<SupportTicketView>of():ticketMapper.pageTicketsScoped(tab,status,category,priority,assigned,user,keyword,before,stable,size,(page-1)*size,reader,scope);
        return new PageResult<>(total,page,size,rows);
    }
    private final ffdd.opsconsole.content.application.SupportOwnershipService ownership;
    private static final int LAST_MESSAGE_MAX_CODE_POINTS = 512;
    private final SupportTicketMapper ticketMapper;
    private final SupportTicketMessageMapper messageMapper;
    private final ffdd.opsconsole.content.application.SupportTicketCreationPolicyService creationPolicy;
    private final ffdd.opsconsole.content.application.SupportTicketOwnerService ticketOwners;

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
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
        long total = ticketMapper.countTickets(scope, status, category, priority, assignedAdminId, userId, keyword,visibility());
        List<SupportTicketView> records =
                ticketMapper.pageTickets(scope, status, category, priority, assignedAdminId, userId, keyword,
                        null, false, pageSize, (pageNum - 1) * pageSize,visibility());
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public PageResult<SupportTicketView> pageTicketsBeforeId(
            SupportTicketQueryRequest request, Long beforeId) {
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        String scope = normalizeScope(request == null ? null : request.scope());
        String status = request == null ? null : trim(request.status());
        String category = request == null ? null : trim(request.category());
        String priority = request == null ? null : trim(request.priority());
        Long assignedAdminId = request == null ? null : request.assignedAdminId();
        Long userId = request == null ? null : request.userId();
        String keyword = request == null ? null : trim(request.keyword());
        long total = ticketMapper.countTickets(scope, status, category, priority, assignedAdminId, userId, keyword,visibility());
        List<SupportTicketView> records = ticketMapper.pageTickets(
                scope, status, category, priority, assignedAdminId, userId, keyword,
                beforeId, true, pageSize, 0,visibility());
        return new PageResult<>(total, 1, pageSize, records);
    }

    @Override
    public Optional<SupportTicketView> findByTicketNo(String ticketNo) {
        var reader=visibility();
        var ticket=ticketMapper.findByTicketNo(ticketNo,reader);
        if(ticket!=null && reader.privateRead() && !reader.supervisor()) {
            // canRead uses a locking/current assignment read, not the enclosing command's RR snapshot.
            boolean allowed=ownership.canRead(reader.adminId(),ticket.userId());
            ticket=ticketMapper.findByTicketNo(ticketNo,new SupportTicketMapper.Visibility(reader.adminId(),reader.customerId(),allowed,allowed));
        }
        return Optional.ofNullable(ticket);
    }

    @Override
    public List<SupportTicketMessageView> messages(String ticketNo) {
        var ticket=findByTicketNo(ticketNo).orElse(null);
        var messages=messageMapper.listByTicketNo(ticketNo);
        return ticket!=null && ticket.contentRestricted()
                ? messages.stream().map(m -> Set.of("internal","system").contains(m.senderType()) ? m :
                    new SupportTicketMessageView(m.id(),m.ticketId(),m.ticketNo(),m.senderId(),m.senderType(),m.senderName(),SupportTicketView.RESTRICTED_TEXT,m.createdAt())).toList()
                : messages;
    }

    @Override
    public List<SupportTicketMessageView> userVisibleMessages(String ticketNo) {
        return messageMapper.listUserVisibleByTicketNo(ticketNo);
    }

    @Override
    public List<SupportTicketMessageView> recentUserVisibleMessages(String ticketNo, int limit) {
        return messageMapper.listRecentUserVisibleByTicketNo(ticketNo, limit);
    }

    @Override
    public List<SupportTicketMessageView> recentUserVisibleMessagesBefore(
            String ticketNo, Long beforeMessageId, int limit) {
        return messageMapper.listRecentUserVisibleByTicketNoBefore(ticketNo, beforeMessageId, limit);
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
        var owner = ticketOwners.resolveForCreate(userId, assignedAdminId);
        LocalDateTime admittedAt = creationPolicy.requireAllowed(userId, category, title, body);
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean admin=auth!=null && auth.getDetails() instanceof java.util.Map<?,?> details && "ADMIN".equals(details.get("subjectType"));
        SupportTicketEntity entity = new SupportTicketEntity();
        entity.setTicketNo(ticketNo);
        entity.setSourceConversationNo(SupportTicketView.DIRECT_SOURCE);
        entity.setUserId(userId == null ? 0L : userId);
        entity.setCategory(category);
        entity.setPriority(priority);
        entity.setStatus("OPEN");
        entity.setTitle(title);
        entity.setLastMessage(headerSummary(body));
        entity.setAssignedAdminId(owner.adminId());
        entity.setAssignedAdminName(owner.name());
        entity.setUserUnreadCount(admin?1:0);
        entity.setOpsUnreadCount(admin?0:1);
        entity.setMessageCount(1);
        entity.setLastMessageAt(admittedAt);
        entity.setArchived(false);
        entity.setArchivedAt(null);
        entity.setVersion(0L);
        entity.setCreatedAt(admittedAt);
        entity.setUpdatedAt(admittedAt);
        entity.setIsDeleted(0);
        ticketMapper.insert(entity);
        insertMessage(entity.getId(), ticketNo, admin?ownership.actorId():userId, admin?"agent":"user",
                admin?ffdd.opsconsole.shared.security.AdminActorResolver.resolve("system"):"用户",body,admittedAt);
        return findByTicketNo(ticketNo).orElseGet(() -> new SupportTicketView(
                entity.getId(), ticketNo, entity.getUserId(), category, priority, "OPEN", title, headerSummary(body),
                owner.adminId(), owner.name(), admin?1:0, admin?0:1, 1, admittedAt, null, admittedAt, admittedAt, false, null,
                0L, false));
    }

    @Override
    public void appendReply(SupportTicketView ticket, String body, String operator, LocalDateTime now) {
        ticketMapper.appendReplyHeader(ticket.ticketNo(), headerSummary(body), ticket.status(), safeVersion(ticket), now);
        insertMessage(ticket.id(), ticket.ticketNo(), ownership.actorId(), "agent", operator, body, now);
    }

    @Override
    public boolean appendReplyCas(SupportTicketView ticket, String body, String operator, LocalDateTime now) {
        if (ticketMapper.appendReplyHeader(
                ticket.ticketNo(), headerSummary(body), ticket.status(), safeVersion(ticket), now) != 1) {
            return false;
        }
        insertMessage(ticket.id(), ticket.ticketNo(), ownership.actorId(), "agent", operator, body, now);
        return true;
    }

    @Override
    public boolean appendUserReplyCas(SupportTicketView ticket, String body, LocalDateTime now) {
        if (ticketMapper.appendUserReplyHeader(
                ticket.ticketNo(), ticket.userId(), headerSummary(body), ticket.status(), safeVersion(ticket), now) != 1) {
            return false;
        }
        insertMessage(ticket.id(), ticket.ticketNo(), ticket.userId(), "user", "用户", body, now);
        return true;
    }

    @Override
    public boolean markUserReadCas(SupportTicketView ticket, LocalDateTime now) {
        return ticketMapper.markUserRead(ticket.ticketNo(), ticket.userId(), ticket.status(), safeVersion(ticket), now) == 1;
    }

    @Override
    public void updateStatus(SupportTicketView ticket, String status, String operator, LocalDateTime now) {
        ticketMapper.updateStatus(ticket.ticketNo(), status, ticket.status(), safeVersion(ticket), now);
    }

    @Override
    public boolean updateStatusCas(SupportTicketView ticket, String status, String operator, LocalDateTime now) {
        return ticketMapper.updateStatus(
                ticket.ticketNo(), status, ticket.status(), safeVersion(ticket), now) == 1;
    }

    @Override
    public void updatePriority(SupportTicketView ticket, String priority, LocalDateTime now) {
        ticketMapper.updatePriority(ticket.ticketNo(), priority, ticket.status(), safeVersion(ticket), now);
    }

    @Override
    public boolean updatePriorityCas(SupportTicketView ticket, String priority, LocalDateTime now) {
        return ticketMapper.updatePriority(
                ticket.ticketNo(), priority, ticket.status(), safeVersion(ticket), now) == 1;
    }

    @Override
    public void assign(SupportTicketView ticket, Long assignedAdminId, String assignedAdminName, LocalDateTime now) {
        throw new ffdd.opsconsole.shared.exception.BizException(409, "SUPPORT_TICKET_OWNER_MANAGED_BY_BINDING");
    }

    @Override
    public boolean assignCas(
            SupportTicketView ticket,
            Long assignedAdminId,
            String assignedAdminName,
            LocalDateTime now) {
        throw new ffdd.opsconsole.shared.exception.BizException(409, "SUPPORT_TICKET_OWNER_MANAGED_BY_BINDING");
    }

    @Override
    public void archive(SupportTicketView ticket, boolean archived, String operator, LocalDateTime now) {
        ticketMapper.archive(ticket.ticketNo(), archived, ticket.status(), safeVersion(ticket), now);
    }

    @Override
    public boolean archiveCas(SupportTicketView ticket, boolean archived, String operator, LocalDateTime now) {
        return ticketMapper.archive(
                ticket.ticketNo(), archived, ticket.status(), safeVersion(ticket), now) == 1;
    }

    @Override
    public void appendSystemTrace(SupportTicketView ticket, String body, LocalDateTime now) {
        ticketMapper.appendSystemTraceHeader(ticket.ticketNo(), headerSummary(body), now);
        insertMessage(ticket.id(), ticket.ticketNo(), null, "system", "系统", body, now);
    }

    @Override
    public boolean appendSystemTraceCas(SupportTicketView ticket, String body, LocalDateTime now) {
        if (ticketMapper.appendSystemTraceHeaderCas(
                ticket.ticketNo(), headerSummary(body), ticket.status(), safeVersion(ticket), now) != 1) {
            return false;
        }
        insertMessage(ticket.id(), ticket.ticketNo(), null, "system", "系统", body, now);
        return true;
    }

    @Override
    public boolean appendInternalNoteCas(
            SupportTicketView ticket,
            String body,
            String operator,
            LocalDateTime now) {
        if (ticketMapper.appendInternalNoteHeader(
                ticket.ticketNo(), ticket.status(), safeVersion(ticket), now) != 1) {
            return false;
        }
        insertMessage(ticket.id(), ticket.ticketNo(), null, "internal", operator, body, now);
        return true;
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

    @Override
    public void markConversationSource(String ticketNo,String conversationNo) {
        if(ticketMapper.markConversationSource(ticketNo,conversationNo)!=1) throw new IllegalStateException("SUPPORT_TICKET_SOURCE_CONFLICT");
    }

    private SupportTicketMapper.Visibility visibility() {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details))
            return new SupportTicketMapper.Visibility(null,null,false,false);
        Long actor=ownership.actorId();
        if("USER".equals(details.get("subjectType"))) return new SupportTicketMapper.Visibility(null,actor,false,false);
        boolean privateRead=ffdd.opsconsole.content.application.SupportOwnershipService.hasAuthority("service_m3_read");
        boolean supervisor=privateRead && ownership.supervisor(actor);
        boolean eligible=supervisor;
        if(privateRead && !supervisor) {
            try {ownership.requireEligibleAgent();eligible=true;} catch(ffdd.opsconsole.shared.exception.BizException ex) {if(ex.getCode()!=403)throw ex;}
        }
        return new SupportTicketMapper.Visibility(actor,null,privateRead && eligible,supervisor);
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

    private String headerSummary(String body) {
        if (body == null) {
            return null;
        }
        int codePoints = body.codePointCount(0, body.length());
        if (codePoints <= LAST_MESSAGE_MAX_CODE_POINTS) {
            return body;
        }
        int end = body.offsetByCodePoints(0, LAST_MESSAGE_MAX_CODE_POINTS - 1);
        return body.substring(0, end) + "…";
    }

    private String normalizeScope(String value) {
        String scope = trim(value);
        return scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope) ? null : scope.toLowerCase();
    }

    private long safeVersion(SupportTicketView ticket) {
        return ticket.version() == null ? 0L : ticket.version();
    }

}
