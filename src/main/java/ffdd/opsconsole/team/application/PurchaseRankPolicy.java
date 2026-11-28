package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseRankPolicy {
    private static final String CUTOVER = "team.purchase-rank.cutover-at";
    private static final String PENDING = "PENDING";
    private final PlatformConfigFacade config;
    private final TreasuryCoverageFacade coverage;
    private final AuditLogService audit;
    private final Clock clock;

    public static Instant cutover(PlatformConfigFacade config, boolean currentRead) {
        var value = (currentRead ? config.activeValueForUpdate(CUTOVER) : config.activeValue(CUTOVER)).orElse(PENDING);
        return PENDING.equals(value) ? null : Instant.parse(value);
    }

    public static boolean active(PlatformConfigFacade config) {
        return config != null && cutover(config, false) != null;
    }

    public static void requireCoverage(TreasuryCoverageSnapshot snapshot) {
        if (snapshot == null || !snapshot.reliable() || snapshot.coverageRatio() == null || snapshot.redlinePct() == null) {
            throw new BizException(503, "COVERAGE_SNAPSHOT_UNAVAILABLE");
        }
        if (snapshot.redlineBreached()) throw new BizException(409, "COVERAGE_BELOW_REDLINE");
    }

    @Scheduled(fixedDelayString = "${nexion.team.purchase-rank.coverage-delay-ms:30000}")
    @Transactional(rollbackFor = Exception.class)
    public void activateWhenCovered() {
        if (!PENDING.equals(config.activeValue(CUTOVER).orElse(null))) return;
        var snapshot = coverage.snapshot();
        if (snapshot == null || !snapshot.reliable() || snapshot.coverageRatio() == null
                || snapshot.redlinePct() == null || snapshot.redlineBreached()) return;
        if (!PENDING.equals(config.activeValueForUpdate(CUTOVER).orElse(null))) return;
        String at = clock.instant().truncatedTo(ChronoUnit.MICROS).toString();
        config.upsertAdminValue(CUTOVER, at, "STRING", "team", "B83 好友购买奖励按等级生效时刻");
        audit.recordRequired(AuditLogWriteRequest.builder().action("F_PURCHASE_RANK_ACTIVATED")
                .resourceType("TEAM_POLICY").resourceId(CUTOVER).actorUsername("SYSTEM").riskLevel("HIGH")
                .detail(Map.of("effectiveAt", at, "coverageRatio", snapshot.coverageRatio(),
                        "redlinePct", snapshot.redlinePct(), "reserveUsd", snapshot.ratioReserveUsd(),
                        "liabilityUsd", snapshot.ratioLiabilitiesUsd())).build());
    }
}
