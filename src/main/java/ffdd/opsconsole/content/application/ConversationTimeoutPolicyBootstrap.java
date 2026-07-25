package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConversationTimeoutPolicyBootstrap {
    private final ConversationTimeoutPolicyMapper mapper;

    @PostConstruct
    public void initialize() {
        mapper.ensurePolicyTable();
        mapper.ensureEventTable();
        mapper.insertDefaultPolicy();
    }
}
