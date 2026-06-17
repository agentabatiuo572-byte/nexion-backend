package ffdd.opsconsole.market.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsNexMarketService {
    private static final String CURRENT_PRICE_KEY = "wallet.exchange.nex_usdt_price";
    private static final String WEEKLY_CURVE_KEY = "wallet.nex_market.weekly_curve";
    private static final String PUMP_PROBABILITY_KEY = "wallet.nex_market.pump_probability";
    private static final String VOLATILITY_KEY = "wallet.nex_market.volatility_pct";

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final NexMarketRepository marketRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpsNexMarketService(
            PlatformConfigFacade configFacade,
            TreasuryCoverageFacade coverageFacade,
            NexMarketRepository marketRepository,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this(configFacade, coverageFacade, marketRepository, auditLogService, objectMapper, Clock.systemUTC());
    }

    OpsNexMarketService(
            PlatformConfigFacade configFacade,
            TreasuryCoverageFacade coverageFacade,
            NexMarketRepository marketRepository,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.configFacade = configFacade;
        this.coverageFacade = coverageFacade;
        this.marketRepository = marketRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ApiResult<Map<String, Object>> overview() {
        List<NexMarketCurveFrame> frames = loadCurve();
        int activeDay = activeDayIndex();
        NexMarketCurveFrame activeFrame = frameFor(frames, activeDay);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G3");
        response.put("asset", "NEX");
        response.put("currency", "USDT");
        response.put("currentPrice", currentPrice());
        response.put("activeDayIndex", activeDay);
        response.put("activeFrame", activeFrame);
        response.put("weekPeakPrice", weekPeak(frames));
        response.put("frames", frames);
        response.put("coverage", coverage());
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of("nx_config_item:" + WEEKLY_CURVE_KEY, "nx_price_index:NEX_USDT"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateWeeklyCurve(String idempotencyKey, NexMarketCurveUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        List<NexMarketCurveFrame> before = loadCurve();
        List<NexMarketCurveFrame> frames = normalizeFrames(request.frames());
        if (curveRaisesLiability(before, frames)) {
            TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
            if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
                return ApiResult.fail(
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
        }
        String curveJson = writeCurve(frames);
        configFacade.upsertAdminValue(WEEKLY_CURVE_KEY, curveJson, "JSON", "wallet", "G3 weekly NEX market curve");
        applyFrame(frameFor(frames, activeDayIndex()), frames);
        audit("G3_WEEKLY_CURVE_CHANGED", "NEX_MARKET_CURVE", WEEKLY_CURVE_KEY, request.operator(), Map.of(
                "oldPeakPrice", weekPeak(before),
                "newPeakPrice", weekPeak(frames),
                "activeDayIndex", activeDayIndex(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    public ApiResult<Map<String, Object>> advanceCurrentFrame(String idempotencyKey, NexMarketAdvanceRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        List<NexMarketCurveFrame> frames = loadCurve();
        NexMarketCurveFrame activeFrame = frameFor(frames, activeDayIndex());
        applyFrame(activeFrame, frames);
        audit("G3_DAILY_FRAME_ADVANCED", "NEX_MARKET_CURVE", WEEKLY_CURVE_KEY, request.operator(), Map.of(
                "activeDayIndex", activeFrame.dayIndex(),
                "targetPrice", activeFrame.targetPrice(),
                "pumpProbability", activeFrame.pumpProbability(),
                "volatilityPct", activeFrame.volatilityPct(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    void advanceScheduledFrame() {
        List<NexMarketCurveFrame> frames = loadCurve();
        applyFrame(frameFor(frames, activeDayIndex()), frames);
    }

    private void applyFrame(NexMarketCurveFrame frame, List<NexMarketCurveFrame> frames) {
        BigDecimal oldPrice = currentPrice();
        BigDecimal newPrice = frame.targetPrice().setScale(8, RoundingMode.HALF_UP);
        configFacade.upsertAdminValue(CURRENT_PRICE_KEY, newPrice.stripTrailingZeros().toPlainString(), "NUMBER", "wallet", "G3 active NEX price");
        configFacade.upsertAdminValue(PUMP_PROBABILITY_KEY, frame.pumpProbability().stripTrailingZeros().toPlainString(), "NUMBER", "wallet", "G3 active pump probability");
        configFacade.upsertAdminValue(VOLATILITY_KEY, frame.volatilityPct().stripTrailingZeros().toPlainString(), "NUMBER", "wallet", "G3 active volatility");
        marketRepository.publishNexUsdtPrice(
                newPrice,
                deltaPercent(oldPrice, newPrice),
                sparklineJson(frames),
                LocalDateTime.now(clock));
    }

    private List<NexMarketCurveFrame> normalizeFrames(List<NexMarketCurveFrame> frames) {
        if (frames == null || frames.size() != 7) {
            throw new IllegalArgumentException("G3 weekly curve must contain 7 frames");
        }
        List<NexMarketCurveFrame> normalized = new ArrayList<>();
        for (NexMarketCurveFrame frame : frames) {
            if (frame == null || frame.dayIndex() < 0 || frame.dayIndex() > 6) {
                throw new IllegalArgumentException("dayIndex must be 0-6");
            }
            BigDecimal price = requirePositive(frame.targetPrice(), "targetPrice").setScale(8, RoundingMode.HALF_UP);
            BigDecimal pump = normalizeProbability(frame.pumpProbability());
            BigDecimal volatility = requirePositive(frame.volatilityPct(), "volatilityPct").setScale(4, RoundingMode.HALF_UP);
            if (volatility.compareTo(BigDecimal.valueOf(20)) > 0) {
                throw new IllegalArgumentException("volatilityPct must be <= 20");
            }
            normalized.add(new NexMarketCurveFrame(frame.dayIndex(), price, pump, volatility));
        }
        List<Integer> indexes = normalized.stream().map(NexMarketCurveFrame::dayIndex).sorted().toList();
        if (!indexes.equals(List.of(0, 1, 2, 3, 4, 5, 6))) {
            throw new IllegalArgumentException("dayIndex must cover 0-6 exactly once");
        }
        return normalized.stream().sorted(Comparator.comparingInt(NexMarketCurveFrame::dayIndex)).toList();
    }

    private List<NexMarketCurveFrame> loadCurve() {
        return configFacade.activeValue(WEEKLY_CURVE_KEY)
                .filter(StringUtils::hasText)
                .map(this::readCurve)
                .orElseGet(() -> defaultCurve(currentPrice()));
    }

    private List<NexMarketCurveFrame> readCurve(String json) {
        try {
            return normalizeFrames(objectMapper.readValue(json, new TypeReference<List<NexMarketCurveFrame>>() {
            }));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return defaultCurve(currentPrice());
        }
    }

    private String writeCurve(List<NexMarketCurveFrame> frames) {
        try {
            return objectMapper.writeValueAsString(frames);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize G3 weekly curve", ex);
        }
    }

    private String sparklineJson(List<NexMarketCurveFrame> frames) {
        try {
            return objectMapper.writeValueAsString(frames.stream().map(NexMarketCurveFrame::targetPrice).toList());
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<NexMarketCurveFrame> defaultCurve(BigDecimal currentPrice) {
        List<NexMarketCurveFrame> frames = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            BigDecimal drift = BigDecimal.ONE.add(BigDecimal.valueOf(index - 3L).multiply(new BigDecimal("0.005")));
            frames.add(new NexMarketCurveFrame(
                    index,
                    currentPrice.multiply(drift).setScale(8, RoundingMode.HALF_UP),
                    new BigDecimal("0.08"),
                    new BigDecimal("3.00")));
        }
        return frames;
    }

    private boolean curveRaisesLiability(List<NexMarketCurveFrame> before, List<NexMarketCurveFrame> after) {
        BigDecimal oldPeak = weekPeak(before);
        BigDecimal newPeak = weekPeak(after);
        boolean raisesPeak = newPeak.compareTo(oldPeak) > 0;
        boolean raisesPump = averagePump(after).compareTo(averagePump(before)) > 0;
        return raisesPeak || raisesPump;
    }

    private BigDecimal weekPeak(List<NexMarketCurveFrame> frames) {
        return frames.stream()
                .map(NexMarketCurveFrame::targetPrice)
                .max(BigDecimal::compareTo)
                .orElse(currentPrice());
    }

    private BigDecimal averagePump(List<NexMarketCurveFrame> frames) {
        return frames.stream()
                .map(NexMarketCurveFrame::pumpProbability)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(frames.size()), 6, RoundingMode.HALF_UP);
    }

    private NexMarketCurveFrame frameFor(List<NexMarketCurveFrame> frames, int dayIndex) {
        return frames.stream()
                .filter(frame -> frame.dayIndex() == dayIndex)
                .findFirst()
                .orElse(frames.get(0));
    }

    private int activeDayIndex() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).getDayOfWeek().getValue() - 1;
    }

    private BigDecimal currentPrice() {
        return configFacade.activeValue(CURRENT_PRICE_KEY)
                .flatMap(value -> Optional.ofNullable(parseDecimal(value, null)))
                .or(() -> marketRepository.latestNexUsdtPrice())
                .orElse(new BigDecimal("0.171"));
    }

    private BigDecimal deltaPercent(BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice == null || oldPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return newPrice.subtract(oldPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(oldPrice, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeProbability(BigDecimal raw) {
        BigDecimal value = requirePositive(raw, "pumpProbability");
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("pumpProbability must be 0-1 or 0-100 percent");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal requirePositive(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private Map<String, Object> coverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("coverageRatio", snapshot.coverageRatio());
        coverage.put("redlinePct", snapshot.redlinePct());
        coverage.put("redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0);
        coverage.put("precheck", "week peak NEX liability is checked before raising price or pump probability");
        return coverage;
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}
