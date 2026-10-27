package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.mapper.AppConversationClearMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;

/** APP projections only. Staff repositories continue to return the full original transcript. */
@ApplicationService
@RequiredArgsConstructor
public class AppConversationVisibilityService {
    private final AppConversationClearMapper mapper;
    private final ConversationRepository conversations;

    public PageResult<ContentConversationView> page(Long userId, String status, long pageNum,
            long pageSize, Long beforeId, boolean stable) {
        var rows = mapper.visiblePage(userId, status, beforeId, stable, pageSize, (pageNum - 1) * pageSize)
                .stream().map(no -> conversations.findByConversationNo(no).orElseThrow(
                        () -> new BizException(404, "CONVERSATION_NOT_FOUND")))
                .map(row -> project(row, false)).toList();
        return new PageResult<>(mapper.countVisible(userId, status), pageNum, pageSize, rows);
    }

    public ContentConversationView project(ContentConversationView row, boolean currentRead) {
        var peer = mapper.peer(row.userId(), row.conversationNo());
        if (peer == null) throw new BizException(404, "CONVERSATION_NOT_FOUND");
        int unread = currentRead ? conversations.currentUnreadUserVisibleAgentMessageCount(row.conversationNo())
                : conversations.unreadUserVisibleAgentMessageCount(row.conversationNo());
        String preview = row.lastMessage();
        var at = row.lastMessageAt();
        Long lastId = row.lastPublicMessageId();
        String kind = row.lastMessageKind();
        if (peer.userScopeCleared()) {
            var visible = currentRead ? conversations.currentRecentUserVisibleMessages(row.conversationNo(), 1)
                    : conversations.recentUserVisibleMessages(row.conversationNo(), 1);
            var last = visible.isEmpty() ? null : visible.get(0);
            preview = last == null ? null : last.content();
            at = last == null ? null : last.createdAt();
            lastId = last == null ? 0L : last.id();
            kind = last == null ? null : last.kind();
        }
        return new ContentConversationView(row.id(), row.conversationNo(), row.userId(), row.conversationType(),
                row.status(), peer.ownerAgentId(), peer.ownerAgentName(), unread, preview, at,
                row.transferFromAgentId(), row.transferFromAgentName(), row.transferToType(), row.transferToId(),
                row.transferToName(), row.transferReason(), row.transferredAt(), row.updatedAt(), row.version(),
                lastId, kind, row.archived(), null, null, peer.userScopeCleared() ? peer.throughMessageId() : null);
    }
}
