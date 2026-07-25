package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record ConversationTimeoutPolicy(
        String policyKey,
        Integer warnMinutes,
        Integer closeMinutes,
        Long version,
        String updatedBy,
        String reason,
        LocalDateTime updatedAt) {
}
