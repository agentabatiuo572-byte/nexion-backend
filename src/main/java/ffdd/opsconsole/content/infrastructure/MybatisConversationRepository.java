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
        return counters;
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
        long total = mapper.countConversations(status, type, ownerAgentId, userId, keyword, unreadOnly);
        List<ContentConversationView> records = total == 0
                ? List.of()
                : mapper.pageConversations(status, type, ownerAgentId, keyword, userId, unreadOnly, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<ContentConversationView> findByConversationNo(String conversationNo) {
        return Optional.ofNullable(mapper.findByConversationNo(conversationNo));
    }

    @Override
    public List<ContentConversationMessageView> messages(String conversationNo) {
        return messageMapper.listByConversationNo(conversationNo);
    }

    @Override
    public List<ContentConversationView> overdueTransferredConversations(LocalDateTime cutoff, int limit) {
        return mapper.overdueTransferredConversations(cutoff, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public void transferToPending(ContentConversationView conversation, String targetType, String targetId, String targetName, String reason, String operator, LocalDateTime now) {
        mapper.markTransferred(conversation.conversationNo(), targetId, targetName, now);
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
    }

    @Override
    public void acceptTransfer(ContentConversationView conversation, String operator, LocalDateTime now) {
        mapper.acceptConversation(conversation.conversationNo(), operator, now);
        mapper.markTransferAccepted(conversation.conversationNo(), operator, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " 已接收转入会话", now);
    }

    @Override
    public void returnTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        mapper.returnConversation(conversation.conversationNo(), conversation.transferFromAgentId(), conversation.transferFromAgentName(), now);
        mapper.markTransferReturned(conversation.conversationNo(), reason, operator, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                "转入会话已退回: " + reason, now);
    }

    @Override
    public void waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        String message = "转入会话继续等待: " + reason;
        mapper.markTransferWait(conversation.conversationNo(), message, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " " + message, now);
    }

    @Override
    public void reply(ContentConversationView conversation, String body, String operator, LocalDateTime now) {
        mapper.replyConversation(conversation.conversationNo(), body, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "agent", operator, body, now);
    }

    @Override
    public void updateStatus(ContentConversationView conversation, String status, String operator, LocalDateTime now) {
        mapper.updateConversationStatus(conversation.conversationNo(), status, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " 将会话状态更新为 " + status, now);
    }

    @Override
    public void archive(ContentConversationView conversation, boolean archived, String operator, LocalDateTime now) {
        String status = archived ? "CLOSED" : "RESOLVED";
        mapper.updateConversationStatus(conversation.conversationNo(), status, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + (archived ? " 已归档会话" : " 已撤销归档会话"), now);
    }

    @Override
    public boolean fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        String targetId = "standby-pool";
        String targetName = "Standby pool";
        int claimed = mapper.markTransferFallback(conversation.conversationNo(), targetId, targetName, reason, operator, now);
        if (claimed == 0) {
            return false;
        }
        int updated = mapper.fallbackConversation(conversation.conversationNo(), targetId, targetName, now);
        if (updated == 0) {
            throw new IllegalStateException("CONVERSATION_FALLBACK_HEADER_UPDATE_FAILED");
        }
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                "转入待处理超时回落 " + targetName + ": " + reason, now);
        return true;
    }

    @Override
    public void markConvertedToTicket(ContentConversationView conversation, String ticketNo, String operator, LocalDateTime now) {
        String message = "会话已转工单 " + ticketNo;
        mapper.markConvertedToTicket(conversation.conversationNo(), message, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                operator + " " + message, now);
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
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        mapper.insert(entity);
        insertMessage(entity.getId(), conversationNo, userId, "agent", ownerAgentName, openingText, now);
        return findByConversationNo(conversationNo)
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
    }

    private void insertMessage(
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
