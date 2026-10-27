package ffdd.opsconsole.content.domain;

import java.util.List;

/** The authenticated user's visibility boundary, not physical deletion. */
public record ConversationUserClear(String scope, boolean userScopeCleared, String peerKey,
        long throughMessageId, List<String> conversationNos) {
    public ConversationUserClear(String peerKey, long throughMessageId, List<String> conversationNos) {
        this("USER_VIEW", true, peerKey, throughMessageId, List.copyOf(conversationNos));
    }
}
