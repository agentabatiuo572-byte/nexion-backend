package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** These two L6 families already have facts; no general event bus or financial replay is involved. */
@Slf4j
@Component
@RequiredArgsConstructor
public class L6BehaviorEvidenceScheduler {
    private final L6BehaviorEvidenceMapper mapper;
    private final L6BehaviorEvidenceService evidence;

    @Scheduled(fixedDelayString="${nexion.outbox.dispatch-delay-ms:1000}",
               initialDelayString="${nexion.outbox.dispatch-initial-delay-ms:1000}")
    public void dispatchPending() {
        for(Long id:mapper.eligible(100)) {
            try { evidence.consume(id); }
            catch(L6BehaviorEvidenceService.EvidenceRejected ex) {
                mapper.defer(id,ex.getMessage());
                log.warn("L6 evidence unresolved outboxId={} code={}",id,ex.getMessage());
            } catch(RuntimeException ex) {
                // Do not expose producer values or turn absent evidence / infrastructure failures into DEAD.
                mapper.defer(id,"L6_EVIDENCE_VERIFICATION_UNAVAILABLE");
                log.warn("L6 evidence verification unavailable outboxId={}",id);
            }
        }
    }
}
