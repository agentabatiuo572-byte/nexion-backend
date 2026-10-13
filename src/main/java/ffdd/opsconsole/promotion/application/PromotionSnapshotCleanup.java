package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static ffdd.opsconsole.promotion.domain.PromotionValues.timestamp;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionSnapshotCleanup {
    private final PromotionMapper db;

    @Scheduled(fixedDelayString="${nexion.promotion.list-snapshot-cleanup-delay-ms:60000}")
    public void cleanupExpired(){
        int deleted=db.write("DELETE FROM nx_promotion_list_snapshot WHERE expires_at<=? ORDER BY expires_at,snapshot_id LIMIT 100",timestamp(Instant.now()));
        if(deleted>0)log.info("Removed {} expired promotion list snapshots",deleted);
        int reports=db.write("DELETE FROM nx_promotion_report_snapshot WHERE expires_at<=? AND NOT EXISTS (SELECT 1 FROM nx_promotion_export_job j WHERE j.snapshot_id=nx_promotion_report_snapshot.snapshot_id) ORDER BY expires_at,snapshot_id LIMIT 100",timestamp(Instant.now()));
        if(reports>0)log.info("Removed {} expired unexported promotion report snapshots",reports);
    }
}
