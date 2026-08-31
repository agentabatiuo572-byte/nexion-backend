package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.application.BinarySettlementPolicyProvider.BinarySettlementPolicy;
import ffdd.opsconsole.team.application.BinarySettlementPolicyProvider.PolicyBlocked;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.BinaryCommissionEventRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.BinaryLegAssignmentRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.BinarySettlementRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.LegVolumeSnapshot;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.PaidOrderVolumeCandidate;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Minimal F3 money closure. No leg is inferred: every owner L1 root must have one immutable A/B assignment.
 */
@Service
@RequiredArgsConstructor
public class BinaryCommissionSettlementService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;
    /** F5 coolingDays 配置 key(PRD line231 默认30;读 commission/cooling-days)。 */
    private static final String CONFIG_KEY_COOLING_DAYS = "commission/cooling-days";
    private static final int DEFAULT_COOLING_DAYS = 30;

    private final BinaryCommissionSettlementMapper mapper;
    private final BinarySettlementPolicyProvider policyProvider;
    private final TreasuryLedgerPostingFacade ledgerFacade;
    private final AuditLogService auditLogService;
    private final PlatformConfigFacade configFacade;
    private final EventOutboxService eventOutboxService;
    private final AdminIdempotencyService idempotencyService;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AssignmentResult assignLeg(
            Long ownerUserId, Long memberUserId, String rawLeg,
            Long actorAdminId, String actorUsername) {
        requirePositive(ownerUserId, "BINARY_OWNER_REQUIRED");
        requirePositive(memberUserId, "BINARY_MEMBER_REQUIRED");
        requirePositive(actorAdminId, "BINARY_ADMIN_ACTOR_REQUIRED");
        if (Objects.equals(ownerUserId, memberUserId)) throw new IllegalArgumentException("BINARY_MEMBER_NOT_OWNER_L1");
        String leg = normalizeLeg(rawLeg);
        String operator = text(actorUsername, "BINARY_ASSIGNER_REQUIRED");
        if (mapper.lockActiveOwner(ownerUserId) == null
                || mapper.lockDirectMember(ownerUserId, memberUserId) == null) {
            throw new IllegalArgumentException("BINARY_MEMBER_NOT_OWNER_L1");
        }
        BinaryLegAssignmentRow existing = mapper.findAssignmentForUpdate(ownerUserId, memberUserId);
        if (existing != null) {
            if (!leg.equals(existing.leg())) throw new BizException(409, "BINARY_LEG_ASSIGNMENT_IMMUTABLE");
            return new AssignmentResult(ownerUserId, memberUserId, leg, true);
        }
        if (mapper.insertAssignment(ownerUserId, memberUserId, leg, actorAdminId, operator) != 1) {
            existing = mapper.findAssignmentForUpdate(ownerUserId, memberUserId);
            if (existing == null || !leg.equals(existing.leg())) {
                throw new BizException(409, "BINARY_LEG_ASSIGNMENT_IMMUTABLE");
            }
            return new AssignmentResult(ownerUserId, memberUserId, leg, true);
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F3_BINARY_LEG_ASSIGNED").resourceType("BINARY_LEG_ASSIGNMENT")
                .resourceId(ownerUserId + ":" + memberUserId).bizNo(ownerUserId + ":" + memberUserId)
                .userId(ownerUserId).actorId(actorAdminId).actorType("ADMIN").actorUsername(operator)
                .result("SUCCESS").riskLevel("HIGH")
                .detail(linked("ownerUserId", ownerUserId, "memberUserId", memberUserId, "leg", leg))
                .build());
        return new AssignmentResult(ownerUserId, memberUserId, leg, false);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SettlementResult settle(Long ownerUserId, LocalDate settlementDate) {
        return settleTrusted(ownerUserId, settlementDate, null, "SYSTEM", "SYSTEM", "系统周期结算");
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SettlementResult settleAsAdmin(
            Long ownerUserId, LocalDate settlementDate, String reason,
            Long actorAdminId, String actorUsername, String idempotencyKey) {
        requirePositive(ownerUserId, "BINARY_OWNER_REQUIRED");
        if (settlementDate == null) throw new IllegalArgumentException("BINARY_SETTLEMENT_DATE_REQUIRED");
        requirePositive(actorAdminId, "BINARY_ADMIN_ACTOR_REQUIRED");
        String operator = text(actorUsername, "BINARY_ADMIN_ACTOR_REQUIRED");
        String normalizedReason = reason(reason);
        String hash = requestHash(ownerUserId, settlementDate, normalizedReason);
        return idempotencyService.execute(
                "F3_BINARY_SETTLEMENT",
                idempotencyKey,
                hash,
                SettlementResult.class,
                () -> settleTrusted(
                        ownerUserId, settlementDate, actorAdminId,
                        operator, "ADMIN", normalizedReason));
    }

    private String requestHash(Long ownerUserId, LocalDate settlementDate, String reason) {
        String canonical = ownerUserId + "\u001f" + settlementDate + "\u001f" + reason;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_NOT_AVAILABLE", ex);
        }
    }

    private SettlementResult settleTrusted(
            Long ownerUserId, LocalDate settlementDate, Long actorId, String actorUsername,
            String actorType, String reason) {
        requirePositive(ownerUserId, "BINARY_OWNER_REQUIRED");
        if (settlementDate == null) throw new IllegalArgumentException("BINARY_SETTLEMENT_DATE_REQUIRED");
        mapper.ensureSettlementMutex(ownerUserId, settlementDate);
        if (mapper.lockSettlementMutex(ownerUserId, settlementDate) == null) {
            throw new IllegalStateException("BINARY_SETTLEMENT_MUTEX_UNAVAILABLE");
        }
        if (mapper.lockActiveOwner(ownerUserId) == null) {
            return blockedWithoutRow(ownerUserId, settlementDate, "BINARY_OWNER_NOT_ACTIVE");
        }
        reconcileRefundedVolumes(ownerUserId);
        if (mapper.countReversalRequiredVolumes(ownerUserId) > 0) {
            return blockedWithoutRow(ownerUserId, settlementDate, "BINARY_REFUND_REVERSAL_REQUIRED");
        }
        BinarySettlementRow existing = mapper.findSettlementForUpdate(ownerUserId, settlementDate);
        if (existing != null && !"BLOCKED".equalsIgnoreCase(existing.status())) return fromExisting(existing);
        if (existing != null && mapper.deleteBlockedSettlement(ownerUserId, settlementDate) != 1) {
            throw new IllegalStateException("BINARY_BLOCKED_SETTLEMENT_RETRY_CONFLICT");
        }

        BinarySettlementPolicy policy;
        try {
            policy = policyProvider.lockPolicy();
        } catch (PolicyBlocked ex) {
            return blockedWithoutRow(ownerUserId, settlementDate, ex.getMessage());
        }
        List<BinaryLegAssignmentRow> assignments = mapper.listAssignmentsForUpdate(ownerUserId);
        int directMembers = mapper.countDirectMembers(ownerUserId);
        if (policy.spilloverEnabled() && directMembers >= 2
                && (assignments == null || assignments.size() != directMembers)) {
            autoPlaceMissing(ownerUserId, assignments, actorId, actorUsername, actorType);
            assignments = mapper.listAssignmentsForUpdate(ownerUserId);
        }
        if (directMembers < 2 || assignments == null || assignments.size() != directMembers) {
            return blockedWithoutRow(ownerUserId, settlementDate, "BINARY_LEG_ASSIGNMENT_INCOMPLETE");
        }
        Long leftRoot = anchor(assignments, "A");
        Long rightRoot = anchor(assignments, "B");
        if (leftRoot == null || rightRoot == null) {
            return blockedWithoutRow(ownerUserId, settlementDate, "BINARY_BOTH_LEGS_REQUIRED");
        }

        LocalDate month = settlementDate.withDayOfMonth(1);
        LocalDateTime monthStart = month.atStartOfDay();
        LocalDateTime settlementWindowEnd = settlementDate.plusDays(1).atStartOfDay();
        try {
            capturePaidOrders(ownerUserId, monthStart, settlementWindowEnd);
        } catch (SettlementBlocked ex) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    ZERO, ZERO, ZERO, ex.getMessage());
        }
        VolumeWindow volumeWindow = volumeWindow(
                ownerUserId, policy.residualPolicy(), monthStart, settlementWindowEnd, settlementDate);
        BigDecimal consumedBefore = volumeWindow.consumedBefore();
        BigDecimal left = volumeWindow.left();
        BigDecimal right = volumeWindow.right();

        if (policy.paused()) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    left, right, policy.dailyCap(), "F3_BINARY_PAUSED");
        }
        if (!isSettlementDue(settlementDate, policy.settlePeriod())) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    left, right, policy.dailyCap(), "F3_SETTLEMENT_NOT_DUE");
        }
        if (left.compareTo(policy.threshold()) < 0 || right.compareTo(policy.threshold()) < 0) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    left, right, policy.dailyCap(), "BINARY_THRESHOLD_NOT_MET");
        }

        BigDecimal rawMatched = left.min(right);
        BigDecimal availableMatched = rawMatched.subtract(consumedBefore).max(BigDecimal.ZERO);
        if (availableMatched.signum() <= 0) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    left, right, policy.dailyCap(), "BINARY_NO_AVAILABLE_MATCHED_VOLUME");
        }
        BigDecimal settlementCap = policy.dailyCap()
                .multiply(BigDecimal.valueOf(periodDays(settlementDate, policy.settlePeriod())))
                .setScale(6, RoundingMode.DOWN);
        BigDecimal settledToday = money(mapper.settledAmountOnDate(ownerUserId, settlementDate));
        BigDecimal capRemaining = settlementCap.subtract(settledToday).max(BigDecimal.ZERO);
        if (capRemaining.signum() <= 0) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    left, right, settlementCap, "H1_BINARY_DAILY_CAP_EXHAUSTED");
        }
        BigDecimal capMatched = capRemaining.divide(policy.matchRate(), 6, RoundingMode.DOWN);
        BigDecimal consumedMatched = availableMatched.min(capMatched).setScale(6, RoundingMode.DOWN);
        BigDecimal amount = consumedMatched.multiply(policy.matchRate()).setScale(6, RoundingMode.DOWN);
        if (consumedMatched.signum() <= 0 || amount.signum() <= 0) {
            return blocked(ownerUserId, settlementDate, leftRoot, rightRoot,
                    left, right, settlementCap, "BINARY_PAYABLE_AMOUNT_ZERO");
        }
        BinarySettlementRow pending = new BinarySettlementRow(
                null, ownerUserId, settlementDate, leftRoot, rightRoot,
                left, right, consumedMatched, amount, settlementCap, null, "PENDING");
        if (mapper.insertSettlement(pending) != 1) throw new IllegalStateException("BINARY_SETTLEMENT_INSERT_FAILED");
        if (volumeWindow.pairReset()
                && mapper.insertPairResetCursorCas(
                        ownerUserId, settlementDate,
                        volumeWindow.fromVolumeId(), volumeWindow.throughVolumeId(),
                        left, right, consumedMatched) != 1) {
            throw new BizException(409, "F3_PAIR_RESET_CURSOR_CAS_CONFLICT");
        }

        String settlementNo = settlementNo(ownerUserId, settlementDate);
        String remark = "F3 binary settlement | rawMatched=" + rawMatched.toPlainString()
                + " | consumedBefore=" + consumedBefore.toPlainString()
                + " | rate=" + policy.matchRate().toPlainString();
        if (mapper.insertBinaryCommissionEvent(new BinaryCommissionEventRow(
                ownerUserId, settlementNo, consumedMatched, amount, remark, resolveCoolingDays())) != 1) {
            throw new IllegalStateException("BINARY_COMMISSION_EVENT_INSERT_FAILED");
        }
        Long eventId = mapper.selectLastInsertId();
        if (eventId == null || eventId <= 0
                || mapper.linkSettlementEvent(ownerUserId, settlementDate, eventId) != 1) {
            throw new IllegalStateException("BINARY_SETTLEMENT_EVENT_LINK_FAILED");
        }
        String billId = billId(ownerUserId, settlementDate);
        ledgerFacade.postLedgerEntry(
                billId, ownerUserId, "TEAM_COMMISSION", "USDT", "IN", amount,
                "PENDING", "F3 binary commission cooling payout");
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F3_BINARY_SETTLED").resourceType("BINARY_SETTLEMENT")
                .resourceId(settlementNo).bizNo(billId).userId(ownerUserId).actorId(actorId)
                .actorType(actorType).actorUsername(actorUsername)
                .result("SUCCESS").riskLevel("HIGH")
                .detail(linked(
                        "leftVolume", left, "rightVolume", right,
                        "rawMatched", rawMatched, "consumedBefore", consumedBefore,
                        "consumedMatched", consumedMatched, "amountUsdt", amount,
                        "settlementCap", settlementCap,
                        "settlePeriod", policy.settlePeriod(),
                        "residualPolicy", policy.residualPolicy(),
                        "commissionEventId", eventId,
                        "reason", reason))
                .build());
        eventOutboxService.publish(
                "BINARY_COMMISSION",
                settlementNo,
                "commission.paid",
                linked(
                        "userId", ownerUserId,
                        "kind", "binary",
                        "currency", "USDT",
                        "amount", amount,
                        "smallerTrackGv", rawMatched,
                        "matchRate", policy.matchRate(),
                        "dailyCapApplied", amount.compareTo(settlementCap) >= 0,
                        "commissionEventId", eventId));
        return new SettlementResult(
                ownerUserId, settlementDate, "PENDING", "", left, right,
                consumedMatched, amount, settlementCap, eventId, false);
    }

    private VolumeWindow volumeWindow(
            Long ownerUserId,
            String residualPolicy,
            LocalDateTime monthStart,
            LocalDateTime settlementWindowEnd,
            LocalDate settlementDate) {
        if ("每次对碰清零".equals(residualPolicy)) {
            long fromVolumeId = positiveOrZero(mapper.latestPairResetThroughVolumeId(ownerUserId));
            long throughVolumeId = positiveOrZero(mapper.latestActiveVolumeIdInWindow(
                    ownerUserId, monthStart, settlementWindowEnd));
            if (throughVolumeId <= fromVolumeId) {
                return new VolumeWindow(ZERO, ZERO, ZERO, true, fromVolumeId, throughVolumeId);
            }
            LegVolumeSnapshot snapshot = mapper.pairResetLegVolumes(
                    ownerUserId, fromVolumeId, throughVolumeId, monthStart, settlementWindowEnd);
            return new VolumeWindow(
                    money(snapshot == null ? null : snapshot.leftVolume()),
                    money(snapshot == null ? null : snapshot.rightVolume()),
                    ZERO, true, fromVolumeId, throughVolumeId);
        }
        if ("转结".equals(residualPolicy)) {
            BigDecimal consumed = money(mapper.consumedMatchedBefore(ownerUserId, settlementDate.plusDays(1)));
            LegVolumeSnapshot snapshot = mapper.carriedLegVolumes(ownerUserId, settlementWindowEnd);
            BigDecimal left = money(snapshot == null ? null : snapshot.leftVolume())
                    .subtract(consumed).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
            BigDecimal right = money(snapshot == null ? null : snapshot.rightVolume())
                    .subtract(consumed).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
            return new VolumeWindow(left, right, ZERO, false, 0L, 0L);
        }
        BigDecimal consumed = money(mapper.consumedMatchedInMonth(
                ownerUserId, settlementDate.withDayOfMonth(1)));
        LegVolumeSnapshot snapshot = mapper.monthlyLegVolumes(ownerUserId, monthStart, settlementWindowEnd);
        return new VolumeWindow(
                money(snapshot == null ? null : snapshot.leftVolume()),
                money(snapshot == null ? null : snapshot.rightVolume()),
                consumed, false, 0L, 0L);
    }

    private long positiveOrZero(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    private void reconcileRefundedVolumes(Long ownerUserId) {
        int invalidVolumes = mapper.countInvalidPaidOrderVolumes(ownerUserId);
        if (invalidVolumes <= 0) return;
        int reversalRequired = mapper.markInvalidPaidOrderVolumesReversalRequired(ownerUserId);
        int voided = mapper.voidInvalidUnconsumedPaidOrderVolumes(ownerUserId);
        if (reversalRequired + voided != invalidVolumes) {
            throw new IllegalStateException("BINARY_REFUND_RECONCILIATION_FAILED");
        }
    }

    private void capturePaidOrders(Long ownerUserId, LocalDateTime monthStart, LocalDateTime monthEnd) {
        List<PaidOrderVolumeCandidate> candidates = mapper.listPaidOrderCandidates(ownerUserId, monthStart, monthEnd);
        if (candidates == null) throw new SettlementBlocked("BINARY_PAID_ORDER_SOURCE_UNAVAILABLE");
        for (PaidOrderVolumeCandidate candidate : candidates) {
            if (candidate == null || candidate.mappedRootCount() != 1
                    || !List.of("A", "B").contains(candidate.leg())
                    || candidate.amountUsdt() == null || candidate.amountUsdt().signum() <= 0
                    || candidate.paidAt() == null || !StringUtils.hasText(candidate.orderNo())) {
                throw new SettlementBlocked("BINARY_ORDER_MAPPING_AMBIGUOUS");
            }
            if (mapper.insertPaidOrderVolume(ownerUserId, candidate) == 1) continue;
            PaidOrderVolumeCandidate stored = mapper.findPaidOrderVolumeForUpdate(ownerUserId, candidate.orderNo());
            if (!sameVolume(stored, candidate)) throw new SettlementBlocked("BINARY_ORDER_VOLUME_CONFLICT");
        }
    }

    private boolean sameVolume(PaidOrderVolumeCandidate stored, PaidOrderVolumeCandidate candidate) {
        return stored != null
                && Objects.equals(stored.orderUserId(), candidate.orderUserId())
                && Objects.equals(stored.rootMemberUserId(), candidate.rootMemberUserId())
                && Objects.equals(stored.leg(), candidate.leg())
                && money(stored.amountUsdt()).compareTo(money(candidate.amountUsdt())) == 0
                && Objects.equals(stored.paidAt(), candidate.paidAt());
    }

    private SettlementResult blocked(
            Long ownerUserId, LocalDate date, Long leftRoot, Long rightRoot,
            BigDecimal left, BigDecimal right, BigDecimal cap, String reason) {
        // BLOCKED is a retriable decision, not a durable payout. Do not consume the owner/date
        // idempotency key: assignment/config/B1/threshold repairs may safely retry the same day.
        return new SettlementResult(ownerUserId, date, "BLOCKED", reason,
                money(left), money(right), ZERO, ZERO, money(cap), null, false);
    }

    private SettlementResult blockedWithoutRow(Long ownerUserId, LocalDate date, String reason) {
        return new SettlementResult(ownerUserId, date, "BLOCKED", reason,
                ZERO, ZERO, ZERO, ZERO, ZERO, null, false);
    }

    private SettlementResult fromExisting(BinarySettlementRow row) {
        return new SettlementResult(
                row.userId(), row.settlementDate(), row.status(), "IDEMPOTENT_REPLAY",
                money(row.leftVolume()), money(row.rightVolume()), money(row.matchedVolume()),
                money(row.amountUsdt()), money(row.dailyCapUsdt()), row.commissionEventId(), true);
    }

    private Long anchor(List<BinaryLegAssignmentRow> assignments, String leg) {
        return assignments.stream().filter(row -> leg.equals(row.leg()))
                .map(BinaryLegAssignmentRow::memberUserId).min(Long::compareTo).orElse(null);
    }

    private void autoPlaceMissing(
            Long ownerUserId,
            List<BinaryLegAssignmentRow> current,
            Long actorId,
            String actorUsername,
            String actorType) {
        List<BinaryLegAssignmentRow> safeCurrent = current == null ? List.of() : current;
        int aCount = (int) safeCurrent.stream().filter(row -> "A".equals(row.leg())).count();
        int bCount = (int) safeCurrent.stream().filter(row -> "B".equals(row.leg())).count();
        List<Long> missing = mapper.listUnassignedDirectMembersForUpdate(ownerUserId);
        if (missing == null) throw new IllegalStateException("BINARY_AUTO_PLACEMENT_SOURCE_UNAVAILABLE");
        for (Long memberUserId : missing) {
            String leg = aCount <= bCount ? "A" : "B";
            if (mapper.insertAssignment(ownerUserId, memberUserId, leg,
                    actorId == null ? 0L : actorId,
                    "SYSTEM".equals(actorType) ? "SYSTEM_AUTO_PLACEMENT" : actorUsername) != 1) {
                BinaryLegAssignmentRow replay = mapper.findAssignmentForUpdate(ownerUserId, memberUserId);
                if (replay == null || !leg.equals(replay.leg())) {
                    throw new BizException(409, "BINARY_AUTO_PLACEMENT_CONFLICT");
                }
            } else {
                auditLogService.recordRequired(AuditLogWriteRequest.builder()
                        .action("F3_BINARY_AUTO_PLACED").resourceType("BINARY_LEG_ASSIGNMENT")
                        .resourceId(ownerUserId + ":" + memberUserId)
                        .bizNo(ownerUserId + ":" + memberUserId).userId(ownerUserId)
                        .actorId(actorId).actorType(actorType).actorUsername(actorUsername)
                        .result("SUCCESS").riskLevel("HIGH")
                        .detail(linked("ownerUserId", ownerUserId, "memberUserId", memberUserId, "leg", leg))
                        .build());
            }
            if ("A".equals(leg)) aCount++; else bCount++;
        }
    }

    private boolean isSettlementDue(LocalDate date, String settlePeriod) {
        return switch (settlePeriod) {
            case "每日" -> true;
            case "每周" -> date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            case "每月" -> date.getDayOfMonth() == date.lengthOfMonth();
            default -> false;
        };
    }

    private int periodDays(LocalDate date, String settlePeriod) {
        return switch (settlePeriod) {
            case "每日" -> 1;
            case "每周" -> 7;
            case "每月" -> date.lengthOfMonth();
            default -> 0;
        };
    }

    private String normalizeLeg(String value) {
        String leg = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("A", "B").contains(leg)) throw new IllegalArgumentException("BINARY_LEG_INVALID");
        return leg;
    }

    /** F5 coolingDays(读 commission/cooling-days,默认30;PRD line231)。 */
    private int resolveCoolingDays() {
        return configFacade.activeValue(CONFIG_KEY_COOLING_DAYS)
                .map(v -> {
                    try { return Integer.parseInt(v.trim()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(d -> d >= 0)
                .orElse(DEFAULT_COOLING_DAYS);
    }

    private String text(String value, String code) {
        if (!StringUtils.hasText(value) || value.trim().length() > 64) throw new IllegalArgumentException(code);
        return value.trim();
    }

    private String reason(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("REASON_REQUIRED");
        }
        String reason = value.trim();
        if (reason.length() < 8 || reason.length() > 200) {
            throw new IllegalArgumentException("REASON_REQUIRED");
        }
        return reason;
    }

    private void requirePositive(Long value, String code) {
        if (value == null || value <= 0) throw new IllegalArgumentException(code);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.DOWN);
    }

    private String settlementNo(Long ownerUserId, LocalDate date) {
        return "BINARY-" + ownerUserId + "-" + DAY_KEY.format(date);
    }

    private String billId(Long ownerUserId, LocalDate date) {
        return "F3-BINARY-" + ownerUserId + "-" + DAY_KEY.format(date);
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static final class SettlementBlocked extends RuntimeException {
        private SettlementBlocked(String code) {
            super(code);
        }
    }

    private record VolumeWindow(
            BigDecimal left,
            BigDecimal right,
            BigDecimal consumedBefore,
            boolean pairReset,
            long fromVolumeId,
            long throughVolumeId) { }

    public record AssignmentResult(Long ownerUserId, Long memberUserId, String leg, boolean replayed) { }

    public record SettlementResult(
            Long ownerUserId,
            LocalDate settlementDate,
            String status,
            String reason,
            BigDecimal leftVolume,
            BigDecimal rightVolume,
            BigDecimal matchedVolume,
            BigDecimal amountUsdt,
            BigDecimal dailyCapUsdt,
            Long commissionEventId,
            boolean replayed) { }
}
