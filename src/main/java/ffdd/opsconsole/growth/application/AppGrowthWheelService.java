package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.SpinTicket;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelEvent;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelTier;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** H4 server RNG, UTC daily tickets, payout guards, reward ledger, audit and A4 event. */
@Service
@RequiredArgsConstructor
public class AppGrowthWheelService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.0000");
    private static final Set<String> REWARD_KINDS = Set.of("nex", "points", "usdt", "coupon");

    private final AppGrowthWheelMapper mapper;
    private final VoucherGrantFacade voucherGrantFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;
    private final Clock clock = Clock.systemUTC();

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> spin(Long userId, String eventCode, String idempotencyKey) {
        if (userId == null || userId <= 0 || mapper.lockActiveUser(userId) == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
        String code = reference(eventCode, "EVENT_CODE_REQUIRED");
        LocalDate spinDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return executeOnce(userId, idempotencyKey, code + "|" + spinDate,
                () -> executeSpin(userId, code, spinDate));
    }

    private ApiResult<Map<String, Object>> executeSpin(Long userId, String code, LocalDate spinDate) {
        if (!"H4_WHEEL_PAYOUT".equals(mapper.lockWheelPayoutMutex())) {
            throw new BizException(409, "WHEEL_PAYOUT_MUTEX_UNAVAILABLE");
        }
        WheelEvent event = mapper.lockOpenWheelEvent(code);
        if (event == null) return ApiResult.fail(409, "WHEEL_EVENT_NOT_OPEN");

        String sourceType;
        String sourceId;
        SpinTicket ticket = null;
        if ("evt-spring-spin".equals(event.eventCode())
                && mapper.countDailySpin(event.eventId(), userId, spinDate) == 0) {
            sourceType = "DAILY";
            sourceId = spinDate.toString();
        } else {
            ticket = mapper.lockAvailableTicket(userId);
            if (ticket == null) return ApiResult.fail(409, "WHEEL_DAILY_LIMIT_REACHED");
            sourceType = "BONUS";
            sourceId = ticket.ticketId();
        }

        List<WheelTier> tiers = mapper.lockActiveTiers();
        validateTiers(tiers);
        WheelTier selected = draw(tiers);
        boolean downgraded = false;
        String downgradeReason = "NONE";
        String guardFailure = stockGuardFailure(selected, spinDate);
        if (guardFailure == null && Boolean.TRUE.equals(selected.realOutflow())) {
            guardFailure = realPrizeGuardFailure(selected, spinDate);
        }
        if (guardFailure != null) {
            selected = comfortTier(tiers);
            downgraded = true;
            downgradeReason = guardFailure;
        }

        String spinNo = "SPIN-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (mapper.insertSpin(spinNo, event, userId, spinDate, sourceType, sourceId,
                selected, downgraded, downgradeReason) != 1) {
            throw new BizException(409, "WHEEL_DAILY_LIMIT_REACHED");
        }
        if (ticket != null && mapper.consumeTicket(ticket.ticketId(), userId, code, spinDate) != 1) {
            throw new BizException(409, "WHEEL_SPIN_TICKET_CONFLICT");
        }
        award(userId, spinNo, selected);

        Map<String, Object> detail = linked(
                "campaignId", code, "tierId", selected.tierName(),
                "rewardType", selected.rewardKind().toUpperCase(Locale.ROOT),
                "rewardAmount", selected.rewardAmount(), "downgraded", downgraded,
                "sourceType", sourceType);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("H4_WHEEL_SPIN_AWARDED").resourceType("WHEEL_SPIN")
                .resourceId(spinNo).bizNo(spinNo).userId(userId).actorId(userId)
                .actorType("USER").actorUsername("user:" + userId).result("SUCCESS")
                .riskLevel(Boolean.TRUE.equals(selected.realOutflow()) ? "HIGH" : "MEDIUM")
                .detail(linked("eventCode", code, "spinDate", spinDate.toString(),
                        "sourceType", sourceType, "sourceId", sourceId,
                        "tierName", selected.tierName(), "downgradeReason", downgradeReason))
                .build());
        Attribution attribution = mapper.attribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null
                || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        outboxService.publishUserEvent(
                "WHEEL_SPIN", spinNo, "event.spin_awarded", userId,
                normalizePhase(attribution.phase()), attribution.accountAgeMonths(), attribution.cohort(), detail);
        return ApiResult.ok(linked(
                "spinId", spinNo, "eventId", code, "spinDate", spinDate.toString(),
                "sourceType", sourceType, "tierId", selected.tierName(),
                "rewardType", selected.rewardKind().toUpperCase(Locale.ROOT),
                "rewardAmount", selected.rewardAmount(), "rewardName", selected.rewardName(),
                "downgraded", downgraded, "downgradeReason", downgradeReason));
    }

    private void validateTiers(List<WheelTier> tiers) {
        if (tiers == null || tiers.size() < 2 || tiers.size() > 12) {
            throw new BizException(409, "WHEEL_TIER_COUNT_INVALID");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (WheelTier tier : tiers) {
            if (tier == null || tier.tierId() == null || !StringUtils.hasText(tier.tierName())
                    || !REWARD_KINDS.contains(tier.rewardKind()) || tier.rewardAmount() == null
                    || tier.rewardAmount().signum() <= 0 || tier.probabilityPct() == null
                    || tier.probabilityPct().signum() < 0) {
                throw new BizException(409, "WHEEL_TIER_CONFIGURATION_INVALID");
            }
            if ("coupon".equals(tier.rewardKind()) && !StringUtils.hasText(tier.voucherId())) {
                throw new BizException(409, "WHEEL_VOUCHER_NOT_CONFIGURED");
            }
            if ("usdt".equals(tier.rewardKind()) != Boolean.TRUE.equals(tier.realOutflow())) {
                throw new BizException(409, "WHEEL_REAL_PRIZE_CLASSIFICATION_INVALID");
            }
            total = total.add(tier.probabilityPct());
        }
        if (total.compareTo(ONE_HUNDRED) != 0) {
            throw new BizException(409, "WHEEL_PROBABILITY_SUM_INVALID");
        }
        if (tiers.stream().noneMatch(row -> !Boolean.TRUE.equals(row.realOutflow())
                && "nex".equals(row.rewardKind()))) {
            throw new BizException(409, "WHEEL_COMFORT_TIER_MISSING");
        }
    }

    private WheelTier draw(List<WheelTier> tiers) {
        BigDecimal draw = BigDecimal.valueOf(RANDOM.nextDouble(100d));
        BigDecimal cumulative = BigDecimal.ZERO;
        for (WheelTier tier : tiers) {
            cumulative = cumulative.add(tier.probabilityPct());
            if (draw.compareTo(cumulative) < 0) return tier;
        }
        return tiers.get(tiers.size() - 1);
    }

    private WheelTier comfortTier(List<WheelTier> tiers) {
        return tiers.stream()
                .filter(row -> !Boolean.TRUE.equals(row.realOutflow()))
                .filter(row -> "nex".equals(row.rewardKind()))
                .findFirst()
                .or(() -> tiers.stream().filter(row -> !Boolean.TRUE.equals(row.realOutflow())).findFirst())
                .orElseThrow(() -> new BizException(409, "WHEEL_COMFORT_TIER_MISSING"));
    }

    private String realPrizeGuardFailure(WheelTier tier, LocalDate spinDate) {
        if (!realPrizeEnabled(mapper.lockGuardValue("kill"))) return "REAL_PRIZE_DISABLED";
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage == null || !coverage.reliable() || coverage.coverageRatio() == null
                || coverage.redlinePct() == null || coverage.coverageRatio().signum() <= 0
                || coverage.redlinePct().signum() <= 0
                || coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return "B1_COVERAGE_REDLINE";
        }
        BigDecimal budget = money(mapper.lockGuardValue("budget"));
        BigDecimal paid = mapper.currentDailyRealPayout(spinDate);
        if (budget.signum() <= 0 || (paid == null ? BigDecimal.ZERO : paid)
                .add(tier.rewardAmount()).compareTo(budget) > 0) {
            return "DAILY_BUDGET_EXHAUSTED";
        }
        return null;
    }

    private String stockGuardFailure(WheelTier tier, LocalDate spinDate) {
        if (tier.dailyStock() != null && tier.dailyStock() > 0
                && mapper.currentTierDailyAwards(spinDate, tier.tierId()) >= tier.dailyStock()) {
            return "TIER_DAILY_STOCK_EXHAUSTED";
        }
        return null;
    }

    private boolean realPrizeEnabled(String value) {
        if (!StringUtils.hasText(value)) return false;
        return Set.of("1", "true", "on", "enabled", "open", "开")
                .contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private BigDecimal money(String value) {
        if (!StringUtils.hasText(value)) return BigDecimal.ZERO;
        String normalized = value.replaceAll("[^0-9.]", "");
        try {
            return StringUtils.hasText(normalized) ? new BigDecimal(normalized) : BigDecimal.ZERO;
        } catch (NumberFormatException ex) {
            throw new BizException(409, "WHEEL_DAILY_BUDGET_INVALID");
        }
    }

    private void award(Long userId, String spinNo, WheelTier tier) {
        BigDecimal amount = tier.rewardAmount();
        switch (tier.rewardKind()) {
            case "nex" -> creditWallet(userId, spinNo, "NEX", amount);
            case "usdt" -> creditWallet(userId, spinNo, "USDT", amount);
            case "points" -> {
                int points = wholeNumber(amount);
                Integer before = mapper.currentPointsBalance(userId);
                int balance = before == null ? 0 : before;
                if (mapper.insertPointsLedger(userId, spinNo, points, Math.addExact(balance, points)) != 1) {
                    throw new BizException(409, "WHEEL_REWARD_LEDGER_CONFLICT");
                }
            }
            case "coupon" -> voucherGrantFacade.grant(new VoucherGrantCommand(
                    userId, tier.voucherId(), "wheel:" + spinNo,
                    "WHEEL_SPIN", spinNo, "system:H4", "H4 Lucky Spin voucher award"));
            default -> throw new BizException(409, "WHEEL_REWARD_KIND_UNSUPPORTED");
        }
    }

    private void creditWallet(Long userId, String spinNo, String asset, BigDecimal amount) {
        BigDecimal before = "USDT".equals(asset) ? mapper.lockWalletUsdt(userId) : mapper.lockWalletNex(userId);
        if (before == null) throw new BizException(409, "USER_WALLET_NOT_FOUND");
        int changed = "USDT".equals(asset)
                ? mapper.creditWalletUsdt(userId, amount) : mapper.creditWalletNex(userId, amount);
        if (changed != 1 || mapper.insertWalletLedger(userId, spinNo, asset, amount, before.add(amount)) != 1) {
            throw new BizException(409, "WHEEL_REWARD_LEDGER_CONFLICT");
        }
    }

    private int wholeNumber(BigDecimal amount) {
        try {
            BigDecimal normalized = amount.stripTrailingZeros();
            if (normalized.scale() > 0) throw new ArithmeticException();
            return normalized.intValueExact();
        } catch (ArithmeticException ex) {
            throw new BizException(409, "WHEEL_POINTS_REWARD_INVALID");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeOnce(
            Long userId, String idempotencyKey, Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "APP:WHEEL_SPIN:USER:" + userId, idempotencyKey, sha256(String.valueOf(request)),
                ApiResult.class, (Supplier) action);
    }

    private String reference(String value, String error) {
        if (!StringUtils.hasText(value) || value.length() > 64 || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new BizException(422, error);
        }
        return value.trim();
    }

    private String normalizePhase(String value) {
        String phase = value == null ? "P1" : value.trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        return phase.matches("P[1-6]") ? phase : "P1";
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
