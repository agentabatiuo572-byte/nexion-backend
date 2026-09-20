package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
/** Archives only post-policy audit rows carrying an explicit expire_at; legacy rows remain untouched. */
public class AuditRetentionService {
    static final int ARCHIVE_BATCH_SIZE = 200;
    static final int MAX_BATCHES_PER_RUN = 10;
    private final AuditLogMapper mapper;
    private final AuditLogService auditLogService;
    private final A2RuntimePolicy policy;

    @Scheduled(cron = "${nexion.audit.retention.cron:0 15 3 * * *}")
    @Transactional(rollbackFor = Exception.class)
    public void scheduledCleanup() {
        RetentionRun run = runNow();
        log.info("A2 audit archive completed months={} archivedRows={} removedHotRows={} evaluatedAt={}",
                run.retentionMonths(), run.archivedRows(), run.affectedRows(), run.cutoff());
    }

    @Transactional(rollbackFor = Exception.class)
    public RetentionRun runNow() {
        int months = policy.retentionMonths();
        LocalDateTime now = LocalDateTime.now();
        if (!Integer.valueOf(1).equals(mapper.tryAcquireRetentionLock())) {
            return new RetentionRun(months, now, 0, 0, false);
        }
        int archived = 0;
        int removed = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                List<Long> ids = mapper.lockExpiredForArchive(now, ARCHIVE_BATCH_SIZE);
                List<Long> locked = ids == null ? List.of() : ids;
                for (Long id : locked) {
                    archived += mapper.insertArchiveFromHot(id);
                    removed += mapper.deleteHotAfterArchive(id);
                }
                if (locked.size() < ARCHIVE_BATCH_SIZE) break;
            }
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("A2_AUDIT_RETENTION_EXECUTED")
                    .resourceType("A2_AUDIT_RETENTION")
                    .resourceId(now.toLocalDate().toString())
                    .actorType("SYSTEM").actorUsername("system")
                    .result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("retentionMonths", months, "evaluatedAt", now.toString(),
                            "batchLimit", ARCHIVE_BATCH_SIZE, "maxBatches", MAX_BATCHES_PER_RUN,
                            "archivedRows", archived,
                            "removedHotRows", removed, "legacyRowsEligible", false, "idempotent", true))
                    .build());
            return new RetentionRun(months, now, removed, archived, true);
        } finally {
            mapper.releaseRetentionLock();
        }
    }

    public record RetentionRun(
            int retentionMonths, LocalDateTime cutoff, int affectedRows, int archivedRows, boolean lockAcquired) {}

    /**
     * 一次清理的操作前范围。与 {@link RetentionRun} 同处 shared 层:platform 的 web 层
     * 依赖 shared,反向依赖会形成环,所以这里只带事实,HTTP 形状由控制器负责。
     */
    public record RetentionPreview(
            int retentionMonths,
            long eligibleRows,
            LocalDateTime earliestExpireAt,
            long notYetExpiredRows,
            long legacyRowsWithoutExpireAt,
            boolean archiveRequired,
            String approvalAuthority) {}

    /**
     * 「立即清理」的操作前预览。
     *
     * 计数与最早日期走的是 {@link AuditLogMapper#lockExpiredForArchive} 的同一组谓词(见 mapper 注释),
     * 因此预览数字与随后真实删除量不会分叉。执行该动作需要 {@code platform_a2_write},由控制器上的
     * {@code @PreAuthorize} 强制,这里只把它作为可见信息回给页面。
     */
    public RetentionPreview preview() {
        LocalDateTime now = LocalDateTime.now();
        return new RetentionPreview(
                policy.retentionMonths(),
                mapper.countEligibleForArchive(now),
                mapper.earliestEligibleExpireAt(now),
                mapper.countNotYetExpired(now),
                mapper.countLegacyWithoutExpireAt(),
                true,
                "platform_a2_write");
    }
}
