package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import ffdd.opsconsole.content.mapper.ConversationMapper;
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
public class MybatisConversationRepository implements ConversationRepository {
    private final ConversationMapper mapper;
    private final ConversationMessageMapper messageMapper;
    private final ffdd.opsconsole.content.application.SupportOwnershipService ownership;

    @Override public Map<String,Object> counters(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        java.util.Objects.requireNonNull(scope,"Current scope required");
        var counts=new LinkedHashMap<String,Object>();
        for(String status:List.of("OPEN","RESOLVED","CLOSED")) counts.put(status.toLowerCase(java.util.Locale.ROOT),mapper.countConversationsScoped(status,null,null,null,null,null,null,scope));
        counts.put("incomingPending",mapper.countConversationsScoped("TRANSFERRED",null,null,null,null,null,false,scope));
        counts.put("unread",mapper.countConversationsScoped(null,null,null,null,null,true,false,scope));
        counts.put("archived",mapper.countConversationsScoped(null,null,null,null,null,null,true,scope));return counts;
    }
    @Override public PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        return scopedPage(request,null,false,scope);
    }
    @Override public PageResult<ContentConversationView> pageConversationsBeforeId(ConversationQueryRequest request,Long beforeId,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        return scopedPage(request,beforeId,true,scope);
    }
    private PageResult<ContentConversationView> scopedPage(ConversationQueryRequest r,Long before,boolean stable,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        java.util.Objects.requireNonNull(scope,"Current scope required");
        long page=stable?1:normalizePage(r==null?null:r.pageNum()),size=normalizeSize(r==null?null:r.pageSize());
        String status=r==null?null:trim(r.status()),type=r==null?null:trim(r.type()),keyword=r==null?null:trim(r.keyword());
        Long user=r==null?null:r.userId();Boolean unread=r==null?null:r.unreadOnly(),archived=r==null?null:r.archived();
        // Agent filtering is already in ReadScope; the old profile-enabled filter would hide handover assets.
        long total=mapper.countConversationsScoped(status,type,null,user,keyword,unread,archived,scope);
        var rows=total==0?List.<ContentConversationView>of():mapper.pageConversationsScoped(status,type,null,keyword,user,unread,before,stable,size,(page-1)*size,archived,scope);
        return new PageResult<>(total,page,size,rows);
    }

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

    @Override
    public Map<String, Object> counters() {
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("open", mapper.countOpen());
        counters.put("incomingPending", mapper.countIncomingPending());
        counters.put("unread", mapper.countUnread());
        counters.put("resolved", mapper.countResolved());
        counters.put("closed", mapper.countClosed());
        counters.put("archived", mapper.countArchived());
        return counters;
    }
    @Override public Map<String,Object> counters(Long agent) {
        if(agent==null)return counters();String scope=String.valueOf(agent);var counts=new LinkedHashMap<String,Object>();
        for(String status:List.of("OPEN","RESOLVED","CLOSED"))counts.put(status.toLowerCase(java.util.Locale.ROOT),mapper.countConversations(status,null,scope,null,null,null,null));
        counts.put("incomingPending",mapper.countConversations("TRANSFERRED",null,scope,null,null,null,false));
        counts.put("unread",mapper.countConversations(null,null,scope,null,null,true,false));
        counts.put("archived",mapper.countConversations(null,null,scope,null,null,null,true));return counts;
    }

    @Override
    public PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request) {
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        String status = request == null ? null : trim(request.status());
        String type = request == null ? null : trim(request.type());
        String ownerAgentId = request == null ? null : trim(request.ownerAgentId());
        Long userId = request == null ? null : request.userId();
        String keyword = request == null ? null : trim(request.keyword());
        Boolean unreadOnly = request == null ? null : request.unreadOnly();
        long total = mapper.countConversations(status, type, ownerAgentId, userId, keyword, unreadOnly, request==null?null:request.archived());
        List<ContentConversationView> records = total == 0
                ? List.of()
                : mapper.pageConversations(status, type, ownerAgentId, keyword, userId, unreadOnly,
                        null, false, pageSize, (pageNum - 1) * pageSize, request==null?null:request.archived());
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public PageResult<ContentConversationView> pageConversationsBeforeId(
            ConversationQueryRequest request, Long beforeId) {
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        String status = request == null ? null : trim(request.status());
        String type = request == null ? null : trim(request.type());
        String ownerAgentId = request == null ? null : trim(request.ownerAgentId());
        Long userId = request == null ? null : request.userId();
        String keyword = request == null ? null : trim(request.keyword());
        Boolean unreadOnly = request == null ? null : request.unreadOnly();
        long total = mapper.countConversations(status, type, ownerAgentId, userId, keyword, unreadOnly, request==null?null:request.archived());
        List<ContentConversationView> records = mapper.pageConversations(
                status, type, ownerAgentId, keyword, userId, unreadOnly, beforeId, true, pageSize, 0, request==null?null:request.archived());
        return new PageResult<>(total, 1, pageSize, records);
    }

    @Override
    public Optional<ContentConversationView> findByConversationNo(String conversationNo) {
        return Optional.ofNullable(mapper.findByConversationNo(conversationNo));
    }

    @Override
    public Optional<ContentConversationView> findByConversationNoForUpdate(String conversationNo) {
        if (mapper.lockConversationHeader(conversationNo) == null) {
            return Optional.empty();
        }
        // Global state-machine lock order: header first, active transfer second.
        mapper.lockPendingTransfers(conversationNo);
        return Optional.ofNullable(mapper.findCurrentByConversationNo(conversationNo));
    }

    @Override
    public List<ContentConversationMessageView> currentRecentUserVisibleMessages(String no,int limit) {
        return messageMapper.listCurrentRecentUserVisibleByConversationNo(no,limit);
    }
    @Override
    public int currentUnreadUserVisibleAgentMessageCount(String no) {
        return messageMapper.countCurrentUnreadUserVisibleAgentMessages(no);
    }

    @Override
    public List<ContentConversationMessageView> messages(String conversationNo) {
        return messageMapper.listByConversationNo(conversationNo);
    }

    @Override
    public List<ContentConversationMessageView> userVisibleMessages(String conversationNo) {
        return messageMapper.listUserVisibleByConversationNo(conversationNo);
    }

    @Override
    public List<ContentConversationMessageView> recentUserVisibleMessages(String conversationNo, int limit) {
        return messageMapper.listRecentUserVisibleByConversationNo(conversationNo, limit);
    }

    @Override
    public List<ContentConversationMessageView> recentUserVisibleMessagesBefore(
            String conversationNo, Long beforeMessageId, int limit) {
        return messageMapper.listRecentUserVisibleByConversationNoBefore(conversationNo, beforeMessageId, limit);
    }

    @Override
    public int unreadUserVisibleAgentMessageCount(String conversationNo) {
        return messageMapper.countUnreadUserVisibleAgentMessages(conversationNo);
    }

    @Override
    public boolean markAgentMessagesReadThrough(
            String conversationNo, Long lastSeenMessageId, String operator, LocalDateTime now,
            String expectedStatus, Long expectedVersion) {
        return messageMapper.markAgentMessagesReadThrough(
                conversationNo, lastSeenMessageId, operator, now, expectedStatus, expectedVersion) > 0;
    }

    @Override
    public boolean markUserMessagesReadThrough(ContentConversationView conversation, Long lastSeenMessageId,
            String operator, LocalDateTime now) {
        int changed = messageMapper.markUserMessagesReadThrough(conversation.conversationNo(), lastSeenMessageId, operator, now);
        if (changed > 0) mapper.refreshUserUnreadCount(conversation.conversationNo());
        return changed > 0;
    }

    @Override
    public List<ContentConversationView> overdueTransferredConversations(LocalDateTime cutoff, int limit) {
        return mapper.overdueTransferredConversations(cutoff, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public boolean transferToPending(ContentConversationView conversation, String targetType, String targetId, String targetName, String reason, String operator, LocalDateTime now) {
        if (mapper.markTransferred(conversation.conversationNo(), targetId, targetName, conversation.version(), now) == 0) {
            return false;
        }
        mapper.insertTransfer(
                conversation.conversationNo(),
                conversation.ownerAgentId(),
                conversation.ownerAgentName(),
                targetType,
                targetId,
                targetName,
                reason,
                operator,
                now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                "会话转交至 " + targetName + ": " + reason, now);
        return true;
    }

    @Override
    public boolean acceptTransfer(ContentConversationView conversation, String ownerAgentId, String ownerAgentName, String operator, LocalDateTime now) {
        int claimed = mapper.markTransferAccepted(conversation.conversationNo(), operator, now);
        if (claimed == 0) {
            return false;
        }
        if (claimed != 1) {
            throw new IllegalStateException("CONVERSATION_ACCEPT_TRANSFER_CARDINALITY_INVALID");
        }
        if (mapper.acceptConversation(conversation.conversationNo(), ownerAgentId, ownerAgentName, conversation.version(), now) == 0) {
            throw new IllegalStateException("CONVERSATION_ACCEPT_HEADER_UPDATE_FAILED");
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " 已接收转入会话", now);
        return true;
    }

    @Override
    public boolean returnTransfer(ContentConversationView conversation, String target, String reason, String operator, LocalDateTime now) {
        int claimed = mapper.markTransferReturned(conversation.conversationNo(), reason, operator, now);
        if (claimed == 0) {
            return false;
        }
        if (claimed != 1) {
            throw new IllegalStateException("CONVERSATION_RETURN_TRANSFER_CARDINALITY_INVALID");
        }
        String ownerId = "standby".equalsIgnoreCase(target) ? "standby-pool" : conversation.transferFromAgentId();
        String ownerName = "standby".equalsIgnoreCase(target) ? "备勤池" : conversation.transferFromAgentName();
        if (mapper.returnConversation(conversation.conversationNo(), ownerId, ownerName, conversation.version(), now) == 0) {
            throw new IllegalStateException("CONVERSATION_RETURN_HEADER_UPDATE_FAILED");
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                "转入会话已退回: " + reason, now);
        return true;
    }

    @Override
    public boolean waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        String message = "转入会话继续等待: " + reason;
        if (mapper.markTransferWait(conversation.conversationNo(), message, conversation.version(), now) == 0) {
            return false;
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " " + message, now);
        return true;
    }

    @Override
    public boolean reply(ContentConversationView conversation, String body, String operator, LocalDateTime now) {
        return replyAndReturnMessageId(conversation, body, operator, now) != null;
    }

    @Override
    public Long replyAndReturnMessageId(ContentConversationView conversation, String body, String operator, LocalDateTime now) {
        return replyAndReturnMessageId(conversation,body,ownership.actorId(),operator,now);
    }

    @Override
    public Long replyAndReturnMessageId(ContentConversationView conversation,String body,Long senderAdminId,String senderName,LocalDateTime now) {
        if(senderAdminId==null || senderAdminId<=0) throw new IllegalArgumentException("EXPLICIT_MESSAGE_ACTOR_REQUIRED");
        if (mapper.replyConversation(conversation.conversationNo(), body, conversation.status(), conversation.version(), now) == 0) {
            return null;
        }
        return insertMessage(conversation.id(), conversation.conversationNo(), senderAdminId, "agent", senderName, body, now);
    }

    @Override
    public boolean replyAsUser(ContentConversationView conversation, Long userId, String body, LocalDateTime now) {
        if (mapper.replyConversationAsUser(
                conversation.conversationNo(), userId, body, conversation.status(), conversation.version(), now) == 0) {
            return false;
        }
        insertMessage(conversation.id(), conversation.conversationNo(), userId, "user", "用户", body, now);
        return true;
    }

    @Override
    public boolean updateStatus(ContentConversationView conversation, String status, String operator, LocalDateTime now) {
        if (mapper.updateConversationStatus(conversation.conversationNo(), status, conversation.status(), conversation.version(), now) == 0) {
            return false;
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " 将会话状态更新为" + statusLabel(status), now);
        return true;
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "OPEN" -> "进行中";
            case "TRANSFERRED" -> "转入待处理";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            default -> "未知";
        };
    }

    @Override
    public boolean archive(ContentConversationView conversation, boolean archived, String operator, LocalDateTime now) {
        if (mapper.updateArchived(conversation.conversationNo(), archived, conversation.archived(), conversation.version(), now) == 0) {
            return false;
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + (archived ? " 已归档会话" : " 已撤销归档会话"), now);
        return true;
    }

    @Override
    public boolean fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        String targetId = "standby-pool";
        String targetName = "Standby pool";
        int claimed = mapper.markTransferFallback(conversation.conversationNo(), targetId, targetName, reason, operator, now);
        if (claimed == 0) {
            return false;
        }
        if (claimed != 1) {
            throw new IllegalStateException("CONVERSATION_FALLBACK_TRANSFER_CARDINALITY_INVALID");
        }
        int updated = mapper.fallbackConversation(conversation.conversationNo(), targetId, targetName, conversation.version(), now);
        if (updated == 0) {
            throw new IllegalStateException("CONVERSATION_FALLBACK_HEADER_UPDATE_FAILED");
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                "转入待处理超时回落 " + targetName + ": " + reason, now);
        return true;
    }

    @Override
    public boolean markConvertedToTicket(ContentConversationView conversation, String ticketNo, String operator, LocalDateTime now) {
        String message = "会话已转工单 " + ticketNo;
        int claimed = mapper.markConvertedToTicket(conversation.conversationNo(), message, conversation.version(), now);
        if (claimed == 0) {
            return false;
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " " + message, now);
        return true;
    }

    @Override
    public ContentConversationView createConversation(
            String conversationNo,
            Long userId,
            String conversationType,
            String ownerAgentId,
            String ownerAgentName,
            String openingText,
            LocalDateTime now) {
        return createConversationWithMessage(
                conversationNo, userId, conversationType, ownerAgentId, ownerAgentName, openingText, now).conversation();
    }

    @Override
    public PersistedConversation createConversationWithMessage(
            String conversationNo,
            Long userId,
            String conversationType,
            String ownerAgentId,
            String ownerAgentName,
            String openingText,
            LocalDateTime now) {
        return createConversationWithMessage(conversationNo,userId,conversationType,ownerAgentId,ownerAgentName,
                openingText,ownership.actorId(),ffdd.opsconsole.shared.security.AdminActorResolver.resolve("system"),now);
    }

    @Override
    public PersistedConversation createConversationWithMessage(
            String conversationNo,Long userId,String conversationType,String ownerAgentId,String ownerAgentName,
            String openingText,Long senderAdminId,String senderName,LocalDateTime now) {
        if(senderAdminId==null || senderAdminId<=0) throw new IllegalArgumentException("EXPLICIT_MESSAGE_ACTOR_REQUIRED");
        ConversationEntity entity = new ConversationEntity();
        entity.setConversationNo(conversationNo);
        entity.setUserId(userId);
        entity.setConversationType(conversationType);
        entity.setStatus("OPEN");
        entity.setOwnerAgentId(ownerAgentId);
        entity.setOwnerAgentName(ownerAgentName);
        entity.setUnreadCount(0);
        entity.setLastMessage(openingText);
        entity.setLastMessageAt(now);
        entity.setVersion(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        mapper.insert(entity);
        Long messageId = insertMessage(entity.getId(), conversationNo, senderAdminId, "agent", senderName, openingText, now);
        ContentConversationView conversation = findByConversationNo(conversationNo)
                .orElseGet(() -> new ContentConversationView(
                        entity.getId(),
                        conversationNo,
                        userId,
                        conversationType,
                        "OPEN",
                        ownerAgentId,
                        ownerAgentName,
                        0,
                        openingText,
                        now,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        now));
        return new PersistedConversation(conversation, messageId);
    }

    @Override
    public ContentConversationView createUserConversation(
            String conversationNo,
            Long userId,
            String conversationType,
            String openingText,
            LocalDateTime now) {
        return createUserConversation(conversationNo, userId, conversationType, openingText, null, "Unassigned", now);
    }

    @Override
    public ContentConversationView createUserConversation(
            String conversationNo, Long userId, String conversationType, String openingText,
            String ownerAgentId, String ownerAgentName, LocalDateTime now) {
        ConversationEntity entity = new ConversationEntity();
        entity.setConversationNo(conversationNo);
        entity.setUserId(userId);
        entity.setConversationType(conversationType);
        entity.setStatus("OPEN");
        entity.setOwnerAgentId(ownerAgentId);
        entity.setOwnerAgentName(org.springframework.util.StringUtils.hasText(ownerAgentName) ? ownerAgentName : "Unassigned");
        entity.setUnreadCount(1);
        entity.setLastMessage(openingText);
        entity.setLastMessageAt(now);
        entity.setVersion(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        mapper.insert(entity);
        insertMessage(entity.getId(), conversationNo, userId, "user", "用户", openingText, now);
        return findByConversationNo(conversationNo).orElseThrow();
    }

    private Long insertMessage(
            Long conversationId,
            String conversationNo,
            Long senderId,
            String senderType,
            String senderName,
            String content,
            LocalDateTime now) {
        ConversationMessageEntity message = new ConversationMessageEntity();
        message.setConversationId(conversationId);
        message.setConversationNo(conversationNo);
        message.setSenderId(senderId);
        message.setSenderType(senderType);
        message.setSenderName(senderName);
        message.setContent(content);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        message.setIsDeleted(0);
        messageMapper.insert(message);
        return message.getId();
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

}
