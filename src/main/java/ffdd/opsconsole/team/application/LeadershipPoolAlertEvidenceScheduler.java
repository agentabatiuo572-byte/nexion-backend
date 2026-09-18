package ffdd.opsconsole.team.application;

import ffdd.opsconsole.team.mapper.LeadershipPoolAlertEvidenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only persists configuration-alert delivery proof; settlement remains blocked and is never retried here. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadershipPoolAlertEvidenceScheduler {
    private final LeadershipPoolAlertEvidenceMapper mapper;
    private final LeadershipPoolAlertEvidenceService evidence;

    @Scheduled(fixedDelayString="${nexion.outbox.dispatch-delay-ms:1000}",
               initialDelayString="${nexion.outbox.dispatch-initial-delay-ms:1000}")
    public void dispatchPending() {
        for(Long id:mapper.eligible(100)) {
            try { evidence.consume(id); }
            catch(LeadershipPoolAlertEvidenceService.EvidenceRejected ex) {
                mapper.defer(id,ex.getMessage());
                log.warn("F4 configuration alert evidence unresolved outboxId={} code={}",id,ex.getMessage());
            } catch(RuntimeException ex) {
                // Do not expose producer values or turn absent evidence / infrastructure failures into DEAD.
                mapper.defer(id,"F4_ALERT_VERIFICATION_UNAVAILABLE");
                log.warn("F4 configuration alert evidence verification unavailable outboxId={}",id);
            }
        }
    }
}
