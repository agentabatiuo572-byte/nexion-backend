package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record ConversationTimeoutPolicy(
        String policyKey,
        Integer warnMinutes,
        Integer closeMinutes,
        Long version,
        String updatedBy,
        String reason,
        LocalDateTime updatedAt,
        Integer sessionEndMinutes) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public ConversationTimeoutPolicy { }
    public ConversationTimeoutPolicy(String policyKey,Integer warnMinutes,Integer closeMinutes,Long version,
            String updatedBy,String reason,LocalDateTime updatedAt) {
        this(policyKey,warnMinutes,closeMinutes,version,updatedBy,reason,updatedAt,10);
    }
}
