package ffdd.opsconsole.common.boundary;

/** Read-only ownership check for an existing support conversation. Does not send a message. */
public interface PhoneReplacementSupportContract extends DomainFacade {
    void requireConversationOwner(long userId, String conversationNo);
}
