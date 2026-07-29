package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportSlaBaseline;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SupportSlaBaselineInitializer implements ApplicationRunner {
    private final SupportKnowledgeRepository knowledgeRepository;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (SupportSlaBaseline.Rule rule : SupportSlaBaseline.RULES) {
            knowledgeRepository.insertSlaIfMissing(
                    rule.category(),
                    new SupportSlaUpdateRequest(
                            rule.firstResponseMins(),
                            rule.resolutionHours(),
                            rule.queue(),
                            rule.escalation(),
                            "system",
                            "bootstrap complete support SLA matrix"),
                    now);
        }
    }
}
