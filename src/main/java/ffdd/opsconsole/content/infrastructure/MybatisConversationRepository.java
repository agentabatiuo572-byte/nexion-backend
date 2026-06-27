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
        if (mapper.countConversations(null, null, null, null, null, null) > 0) {
            return;
        }
        seedConversation(
                "CV-M-SEED-001",
                88421L,
                "advisor",
                "mia",
                "Mia",
                "OPEN",
                0,
                now.minusHours(3),
                List.of(
                        seedMessage("agent", "Mia", "你好,我是你的专属顾问 Mia, 有明显的机会第一时间提醒你。", now.minusHours(3)),
                        seedMessage("agent", "Mia", "你的设备近期有不少时段闲置,升级到 NexionBox Pro 可以减少空窗。", now.minusMinutes(40)),
                        seedMessage("agent", "Mia", "180 天锁仓有更优档位,要不要我帮你看下额度?", now.minusMinutes(12))),
                null);
        seedConversation(
                "CV-M-SEED-002",
                39922L,
                "support",
                "support",
                "Support queue",
                "OPEN",
                1,
                now.minusHours(2),
                List.of(seedMessage("user", "用户", "你好,我的提现显示待处理超过一天了,能帮我查下状态吗?", now.minusHours(2))),
                null);
        seedConversation(
                "CV-M-SEED-003",
                77810L,
                "support",
                "tomas",
                "Tomas R.",
                "RESOLVED",
                0,
                now.minusHours(6),
                List.of(
                        seedMessage("user", "用户", "KYC 一直没过,能看下原因吗?", now.minusHours(6)),
                        seedMessage("agent", "Tomas R.", "已核对,证件照模糊导致;重新上传清晰照即可。", now.minusHours(5))),
                null);
        seedConversation(
                "CV-M-SEED-004",
                43391L,
                "support",
                "aisha",
                "Aisha O.",
                "OPEN",
                0,
                now.minusMinutes(50),
                List.of(
                        seedMessage("system", "系统", "由普通客服 Aisha O. 主动发起 · 目标:提现频繁用户主动关怀", now.minusMinutes(50)),
                        seedMessage("agent", "Aisha O.", "您好,注意到您近期提现较频繁;如果有任何疑问,我可以帮您逐笔核对。", now.minusMinutes(45))),
                null);
        seedConversation(
                "CV-M-SEED-005",
                66120L,
                "support",
                "tomas",
                "Tomas R.",
                "TRANSFERRED",
                1,
                now.minusMinutes(70),
                List.of(
                        seedMessage("user", "用户", "我刚充值了一笔大额,想提一部分出来,但提现一直没动静。", now.minusMinutes(70)),
                        seedMessage("agent", "Sarah K.", "你的实名资料还差一项待补充,我转给合规同事跟进。", now.minusMinutes(52)),
                        seedMessage("system", "系统", "Sarah K. 转交给 Tomas R. · 原因:新户首充大额加 KYC 待补。", now.minusMinutes(38))),
                new SeedTransfer("sarah", "Sarah K.", "agent", "tomas", "Tomas R.", "新户首充大额加 KYC 待补,转给合规客服跟进。", now.minusMinutes(38)));
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
    public void fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
        String targetId = "standby-pool";
        String targetName = "Standby pool";
        mapper.fallbackConversation(conversation.conversationNo(), targetId, targetName, now);
        mapper.markTransferFallback(conversation.conversationNo(), targetId, targetName, reason, operator, now);
        insertMessage(conversation.id(), conversation.conversationNo(), null, "system", "系统",
                "转入待处理超时回落 " + targetName + ": " + reason, now);
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

    private void seedConversation(
            String conversationNo,
            Long userId,
            String conversationType,
            String ownerAgentId,
            String ownerAgentName,
            String status,
            int unreadCount,
            LocalDateTime createdAt,
            List<SeedMessage> messages,
            SeedTransfer transfer) {
        SeedMessage last = messages.get(messages.size() - 1);
        ConversationEntity entity = new ConversationEntity();
        entity.setConversationNo(conversationNo);
        entity.setUserId(userId);
        entity.setConversationType(conversationType);
        entity.setStatus(status);
        entity.setOwnerAgentId(ownerAgentId);
        entity.setOwnerAgentName(ownerAgentName);
        entity.setUnreadCount(unreadCount);
        entity.setLastMessage(last.content());
        entity.setLastMessageAt(last.createdAt());
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(last.createdAt());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        for (SeedMessage message : messages) {
            insertMessage(
                    entity.getId(),
                    conversationNo,
                    "user".equals(message.senderType()) ? userId : null,
                    message.senderType(),
                    message.senderName(),
                    message.content(),
                    message.createdAt());
        }
        if (transfer != null) {
            mapper.insertTransfer(
                    conversationNo,
                    transfer.fromAgentId(),
                    transfer.fromAgentName(),
                    transfer.targetType(),
                    transfer.targetId(),
                    transfer.targetName(),
                    transfer.reason(),
                    "system",
                    transfer.transferredAt());
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

    private record SeedMessage(String senderType, String senderName, String content, LocalDateTime createdAt) {
    }

    private record SeedTransfer(
            String fromAgentId,
            String fromAgentName,
            String targetType,
            String targetId,
            String targetName,
            String reason,
            LocalDateTime transferredAt) {
    }
}
