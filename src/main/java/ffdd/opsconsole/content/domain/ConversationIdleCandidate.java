package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record ConversationIdleCandidate(
        Long id,
        String conversationNo,
        String status,
        LocalDateTime lastActivityAt,
        Long version,
        Long policyVersion,
        Integer warnMinutes,
        Integer closeMinutes) {

    public boolean hasValidPolicy() {
        return policyVersion != null && policyVersion > 0
                && warnMinutes != null && warnMinutes >= 1 && warnMinutes <= 30
                && closeMinutes != null && closeMinutes > warnMinutes && closeMinutes <= 120;
    }
}
