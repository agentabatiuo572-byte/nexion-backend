package ffdd.opsconsole.content;

import ffdd.opsconsole.content.application.SupportOwnershipService;
import static org.mockito.Mockito.*;

/** Existing domain tests isolate authorization; dedicated binding tests exercise the real guard. */
public final class SupportTestDependencies {
    private SupportTestDependencies() {}
    /** Legacy service unit tests isolate projection SQL; AppConversationUserClearTest covers the real projection. */
    public static ffdd.opsconsole.content.application.AppConversationVisibilityService visibility(
            ffdd.opsconsole.content.domain.ConversationRepository repository) {
        var service = mock(ffdd.opsconsole.content.application.AppConversationVisibilityService.class);
        when(service.project(any(), anyBoolean())).thenAnswer(call -> {
            ffdd.opsconsole.content.domain.ContentConversationView row = call.getArgument(0);
            int unread = repository.unreadUserVisibleAgentMessageCount(row.conversationNo());
            return new ffdd.opsconsole.content.domain.ContentConversationView(row.id(), row.conversationNo(), row.userId(),
                    row.conversationType(), row.status(), row.ownerAgentId(), row.ownerAgentName(), unread,
                    row.lastMessage(), row.lastMessageAt(), row.transferFromAgentId(), row.transferFromAgentName(),
                    row.transferToType(), row.transferToId(), row.transferToName(), row.transferReason(), row.transferredAt(),
                    row.updatedAt(), row.version(), row.lastPublicMessageId(), row.lastMessageKind(), row.archived());
        });
        when(service.page(any(), any(), anyLong(), anyLong(), nullable(Long.class), anyBoolean())).thenAnswer(call -> {
            var query = new ffdd.opsconsole.content.dto.ConversationQueryRequest(call.getArgument(1), null, null,
                    call.getArgument(0), null, false, call.getArgument(2), call.getArgument(3));
            var page = Boolean.TRUE.equals(call.getArgument(5)) ? repository.pageConversationsBeforeId(query, call.getArgument(4))
                    : repository.pageConversations(query);
            return new ffdd.opsconsole.shared.api.PageResult<>(page.getTotal(), page.getPageNum(), page.getPageSize(),
                    page.getRecords().stream().map(row -> service.project(row, false)).toList());
        });
        return service;
    }
    public static ffdd.opsconsole.content.application.SupportHumanMessageService humanMessages() {
        var service=mock(ffdd.opsconsole.content.application.SupportHumanMessageService.class);
        when(service.prepare(any(),any(),any(),any(),any(),any(),nullable(String.class),nullable(String.class),nullable(String.class),nullable(String.class),nullable(Long.class)))
            .thenReturn(new ffdd.opsconsole.content.application.SupportHumanMessageService.Prepared(42L,"ADMIN",1L,"test-message","TEXT","SERVICE",null,null,"hash",null,null,null,null,null));
        return service;
    }
    public static SupportOwnershipService ownership() {
        var guard=mock(SupportOwnershipService.class);
        when(guard.actorId()).thenReturn(1L);
        when(guard.actorId(any())).thenReturn(1L);
        when(guard.supervisor(any())).thenReturn(true);
        when(guard.canRead(any(),any())).thenReturn(true);
        when(guard.canReadConversation(any(),any())).thenReturn(true);
        return guard;
    }
}
