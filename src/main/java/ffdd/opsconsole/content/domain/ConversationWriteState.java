package ffdd.opsconsole.content.domain;

/**
 * Read-only distinction between a conversation's stored owner, the customer's
 * current assignment, and the assignment's current writable conversation.
 * It does not grant write authority; the existing peer/current-thread checks
 * remain authoritative on the write path.
 */
public record ConversationWriteState(
        String threadOwnerAgentId,
        String threadOwnerAgentName,
        String currentAssignmentAgentId,
        String currentAssignmentAgentName,
        String writableConversationNo,
        boolean canReply) implements ContentConversationDetail.WriteState {
}
