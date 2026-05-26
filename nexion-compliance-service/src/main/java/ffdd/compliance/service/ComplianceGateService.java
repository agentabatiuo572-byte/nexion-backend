package ffdd.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.common.outbox.EventOutboxService;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.RiskBlacklist;
import ffdd.compliance.domain.RiskDecision;
import ffdd.compliance.dto.ComplianceGateRequest;
import ffdd.compliance.dto.ComplianceGateResponse;
import ffdd.compliance.dto.ManualRiskReviewRequest;
import ffdd.compliance.dto.RiskDecisionFinalizedPayload;
import ffdd.compliance.mapper.KycProfileMapper;
import ffdd.compliance.mapper.RiskBlacklistMapper;
import ffdd.compliance.mapper.RiskDecisionMapper;
import ffdd.compliance.worker.ComplianceOutboxRocketPublisher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ComplianceGateService {
    private static final String KYC_APPROVED = "APPROVED";
    private static final String BLACKLIST_ACTIVE = "ACTIVE";
    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REJECT = "REJECT";
    private static final String DECISION_REVIEW = "REVIEW";
    private static final String REASON_KYC_APPROVED = "KYC_APPROVED";
    private static final String REASON_KYC_EXPIRED = "KYC_EXPIRED";
    private static final String REASON_KYC_NOT_APPROVED = "KYC_NOT_APPROVED";
    private static final String REASON_BLACKLISTED = "BLACKLISTED";
    private static final String REASON_REGION_BLOCKED = "REGION_BLOCKED";
    private static final String REASON_REGION_REVIEW = "REGION_REVIEW";
    private static final String REASON_REGION_MISMATCH = "REGION_MISMATCH";
    private static final String REASON_AMOUNT_REVIEW = "AMOUNT_REVIEW";
    private static final String REASON_USER_TIER_REVIEW = "USER_TIER_REVIEW";
    private static final String REASON_FREQUENCY_REVIEW = "FREQUENCY_REVIEW";
    private static final String REASON_IP_FREQUENCY_REVIEW = "IP_FREQUENCY_REVIEW";
    private static final String REASON_DEVICE_FREQUENCY_REVIEW = "DEVICE_FREQUENCY_REVIEW";
    private static final String RULE_BLACKLIST_ACTIVE = "BLACKLIST_ACTIVE";
    private static final String RULE_KYC_APPROVED = "KYC_APPROVED";
    private static final String RULE_KYC_EXPIRED = "KYC_EXPIRED";
    private static final String RULE_KYC_NOT_APPROVED = "KYC_NOT_APPROVED";
    private static final String RULE_REGION_BLOCKED = "REGION_BLOCKED";
    private static final String RULE_REGION_REVIEW = "REGION_REVIEW";
    private static final String RULE_REGION_MISMATCH = "REGION_MISMATCH";
    private static final String RULE_AMOUNT_THRESHOLD = "AMOUNT_THRESHOLD";
    private static final String RULE_USER_TIER_AMOUNT = "USER_TIER_AMOUNT";
    private static final String RULE_DAILY_FREQUENCY = "DAILY_FREQUENCY";
    private static final String RULE_IP_DAILY_FREQUENCY = "IP_DAILY_FREQUENCY";
    private static final String RULE_DEVICE_DAILY_FREQUENCY = "DEVICE_DAILY_FREQUENCY";
    private static final String BIZ_TYPE_WITHDRAWAL = "WITHDRAWAL";
    private static final String BIZ_TYPE_EXCHANGE = "EXCHANGE";
    private static final Pattern SAFE_CONTEXT_TOKEN = Pattern.compile("[A-Za-z0-9._:@%\\-]+");

    private final KycProfileMapper kycProfileMapper;
    private final RiskDecisionMapper riskDecisionMapper;
    private final RiskBlacklistMapper riskBlacklistMapper;
    private final EventOutboxService outboxService;
    private final BigDecimal withdrawalReviewAmount;
    private final BigDecimal exchangeReviewAmount;
    private final int dailyReviewCount;
    private final Set<String> blockedRegions;
    private final Set<String> reviewRegions;
    private final BigDecimal lowTierReviewAmount;
    private final Set<String> lowTierLevels;
    private final int ipDailyReviewCount;
    private final int deviceDailyReviewCount;

    public ComplianceGateService(
            KycProfileMapper kycProfileMapper,
            RiskDecisionMapper riskDecisionMapper,
            RiskBlacklistMapper riskBlacklistMapper,
            @Value("${nexion.compliance.risk.withdrawal-review-amount:1000.000000}") BigDecimal withdrawalReviewAmount,
            @Value("${nexion.compliance.risk.exchange-review-amount:5000.000000}") BigDecimal exchangeReviewAmount,
            @Value("${nexion.compliance.risk.daily-review-count:3}") int dailyReviewCount,
            @Value("${nexion.compliance.risk.blocked-regions:}") String blockedRegions,
            @Value("${nexion.compliance.risk.review-regions:}") String reviewRegions,
            @Value("${nexion.compliance.risk.low-tier-review-amount:100.000000}") BigDecimal lowTierReviewAmount,
            @Value("${nexion.compliance.risk.low-tier-levels:L0,L1}") String lowTierLevels,
            @Value("${nexion.compliance.risk.ip-daily-review-count:10}") int ipDailyReviewCount,
            @Value("${nexion.compliance.risk.device-daily-review-count:5}") int deviceDailyReviewCount,
            EventOutboxService outboxService) {
        this.kycProfileMapper = kycProfileMapper;
        this.riskDecisionMapper = riskDecisionMapper;
        this.riskBlacklistMapper = riskBlacklistMapper;
        this.outboxService = outboxService;
        this.withdrawalReviewAmount = withdrawalReviewAmount;
        this.exchangeReviewAmount = exchangeReviewAmount;
        this.dailyReviewCount = Math.max(1, dailyReviewCount);
        this.blockedRegions = parseTokenSet(blockedRegions);
        this.reviewRegions = parseTokenSet(reviewRegions);
        this.lowTierReviewAmount = lowTierReviewAmount == null
                ? new BigDecimal("100.000000")
                : lowTierReviewAmount;
        this.lowTierLevels = parseTokenSet(lowTierLevels);
        this.ipDailyReviewCount = Math.max(0, ipDailyReviewCount);
        this.deviceDailyReviewCount = Math.max(0, deviceDailyReviewCount);
    }

    public ComplianceGateResponse check(ComplianceGateRequest request) {
        validate(request);
        String decisionNo = decisionNo(request.getBizType(), request.getBizNo());
        RiskDecision existing = findDecision(decisionNo);
        if (existing != null) {
            return response(existing);
        }

        DecisionResult result = evaluate(request);

        RiskDecision decision = new RiskDecision();
        decision.setDecisionNo(decisionNo);
        decision.setUserId(request.getUserId());
        decision.setBizType(request.getBizType());
        decision.setBizNo(request.getBizNo());
        decision.setRegion(result.context().region());
        decision.setUserLevel(result.context().userLevel());
        decision.setClientIp(result.context().clientIp());
        decision.setDeviceFingerprint(result.context().deviceFingerprint());
        decision.setDecision(result.decision());
        decision.setReason(result.reason());
        decision.setRiskScore(result.riskScore());
        decision.setRuleCodes(result.ruleCodes());
        decision.setRuleSnapshot(result.ruleSnapshot());
        decision.setIsDeleted(0);
        try {
            riskDecisionMapper.insert(decision);
            return response(decision);
        } catch (DuplicateKeyException ex) {
            RiskDecision duplicate = findDecision(decisionNo);
            if (duplicate == null) {
                throw new BizException("Duplicate risk decision exists in an invalid state");
            }
            return response(duplicate);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskDecision approveDecision(String decisionNo, ManualRiskReviewRequest request) {
        return reviewDecision(decisionNo, DECISION_APPROVE, "MANUAL_APPROVE", request);
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskDecision rejectDecision(String decisionNo, ManualRiskReviewRequest request) {
        return reviewDecision(decisionNo, DECISION_REJECT, "MANUAL_REJECT", request);
    }

    public List<RiskDecision> listReview(int limit) {
        return riskDecisionMapper.selectList(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getDecision, DECISION_REVIEW)
                .eq(RiskDecision::getIsDeleted, 0)
                .orderByAsc(RiskDecision::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
    }

    private DecisionResult evaluate(ComplianceGateRequest request) {
        RiskContext requestContext = riskContext(request, null);
        RiskBlacklist blacklist = riskBlacklistMapper.selectOne(new LambdaQueryWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getUserId, request.getUserId())
                .eq(RiskBlacklist::getStatus, BLACKLIST_ACTIVE)
                .eq(RiskBlacklist::getIsDeleted, 0)
                .and(wrapper -> wrapper.isNull(RiskBlacklist::getExpiresAt)
                        .or()
                        .gt(RiskBlacklist::getExpiresAt, LocalDateTime.now())));
        if (isActiveBlacklist(blacklist)) {
            return new DecisionResult(
                    DECISION_REJECT,
                    REASON_BLACKLISTED,
                    100,
                    RULE_BLACKLIST_ACTIVE,
                    "blacklistReason=" + safeSnapshotValue(blacklist.getReason())
                            + ";riskLevel=" + safeSnapshotValue(blacklist.getRiskLevel())
                            + contextSnapshot(requestContext),
                    requestContext);
        }

        KycProfile kyc = kycProfileMapper.selectOne(new LambdaQueryWrapper<KycProfile>()
                .eq(KycProfile::getUserId, request.getUserId())
                .eq(KycProfile::getIsDeleted, 0));
        if (kyc == null) {
            return new DecisionResult(
                    DECISION_REJECT,
                    REASON_KYC_NOT_APPROVED,
                    80,
                    RULE_KYC_NOT_APPROVED,
                    "kycStatus=MISSING" + contextSnapshot(requestContext),
                    requestContext);
        }
        RiskContext context = riskContext(request, kyc);
        if (isKycExpired(kyc)) {
            return new DecisionResult(
                    DECISION_REJECT,
                    REASON_KYC_EXPIRED,
                    85,
                    RULE_KYC_EXPIRED,
                    kycSnapshot(kyc) + contextSnapshot(context),
                    context);
        }
        if (!KYC_APPROVED.equals(kyc.getStatus())) {
            return new DecisionResult(
                    DECISION_REJECT,
                    REASON_KYC_NOT_APPROVED,
                    80,
                    RULE_KYC_NOT_APPROVED,
                    kycSnapshot(kyc) + contextSnapshot(context),
                    context);
        }

        if (matchesAnyRegion(blockedRegions, context)) {
            return new DecisionResult(
                    DECISION_REJECT,
                    REASON_REGION_BLOCKED,
                    95,
                    RULE_REGION_BLOCKED,
                    kycSnapshot(kyc) + contextSnapshot(context),
                    context);
        }

        List<String> ruleCodes = new ArrayList<>();
        int riskScore = 0;
        String primaryReason = REASON_KYC_APPROVED;
        if (matchesAnyRegion(reviewRegions, context)) {
            ruleCodes.add(RULE_REGION_REVIEW);
            riskScore = Math.max(riskScore, 70);
            primaryReason = firstReviewReason(primaryReason, REASON_REGION_REVIEW);
        }
        if (isRegionMismatch(context)) {
            ruleCodes.add(RULE_REGION_MISMATCH);
            riskScore = Math.max(riskScore, 65);
            primaryReason = firstReviewReason(primaryReason, REASON_REGION_MISMATCH);
        }
        BigDecimal threshold = reviewThreshold(request.getBizType());
        if (requiresAmountReview(request, threshold)) {
            ruleCodes.add(RULE_AMOUNT_THRESHOLD);
            riskScore = Math.max(riskScore, 60);
            primaryReason = firstReviewReason(primaryReason, REASON_AMOUNT_REVIEW);
        }
        if (requiresLowTierReview(request, context)) {
            ruleCodes.add(RULE_USER_TIER_AMOUNT);
            riskScore = Math.max(riskScore, 55);
            primaryReason = firstReviewReason(primaryReason, REASON_USER_TIER_REVIEW);
        }
        long dailyCount = dailyDecisionCount(request);
        if (dailyCount >= dailyReviewCount) {
            ruleCodes.add(RULE_DAILY_FREQUENCY);
            riskScore = Math.max(riskScore, 50);
            primaryReason = firstReviewReason(primaryReason, REASON_FREQUENCY_REVIEW);
        }
        long clientIpDailyCount = clientIpDecisionCount(context.clientIp());
        if (clientIpDailyCount >= ipDailyReviewCount && ipDailyReviewCount > 0) {
            ruleCodes.add(RULE_IP_DAILY_FREQUENCY);
            riskScore = Math.max(riskScore, 70);
            primaryReason = firstReviewReason(primaryReason, REASON_IP_FREQUENCY_REVIEW);
        }
        long deviceDailyCount = deviceFingerprintDecisionCount(context.deviceFingerprint());
        if (deviceDailyCount >= deviceDailyReviewCount && deviceDailyReviewCount > 0) {
            ruleCodes.add(RULE_DEVICE_DAILY_FREQUENCY);
            riskScore = Math.max(riskScore, 70);
            primaryReason = firstReviewReason(primaryReason, REASON_DEVICE_FREQUENCY_REVIEW);
        }
        if (!ruleCodes.isEmpty()) {
            return new DecisionResult(
                    DECISION_REVIEW,
                    primaryReason,
                    riskScore,
                    String.join(",", ruleCodes),
                    kycSnapshot(kyc)
                            + contextSnapshot(context)
                            + ";amount=" + request.getAmount().toPlainString()
                            + ";threshold=" + threshold.toPlainString()
                            + ";lowTierThreshold=" + lowTierReviewAmount.toPlainString()
                            + ";dailyCount=" + dailyCount
                            + ";dailyLimit=" + dailyReviewCount
                            + ";clientIpDailyCount=" + clientIpDailyCount
                            + ";clientIpDailyLimit=" + ipDailyReviewCount
                            + ";deviceDailyCount=" + deviceDailyCount
                            + ";deviceDailyLimit=" + deviceDailyReviewCount,
                    context);
        }
        return new DecisionResult(
                DECISION_APPROVE,
                REASON_KYC_APPROVED,
                0,
                RULE_KYC_APPROVED,
                kycSnapshot(kyc) + contextSnapshot(context),
                context);
    }

    private boolean isKycExpired(KycProfile kyc) {
        return "EXPIRED".equals(kyc.getStatus())
                || (KYC_APPROVED.equals(kyc.getStatus())
                        && kyc.getExpiresAt() != null
                        && !kyc.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private boolean requiresAmountReview(ComplianceGateRequest request, BigDecimal threshold) {
        return request.getAmount().compareTo(threshold) > 0;
    }

    private boolean requiresLowTierReview(ComplianceGateRequest request, RiskContext context) {
        return BIZ_TYPE_WITHDRAWAL.equals(request.getBizType())
                && StringUtils.hasText(context.userLevel())
                && lowTierLevels.contains(context.userLevel())
                && request.getAmount().compareTo(lowTierReviewAmount) > 0;
    }

    private BigDecimal reviewThreshold(String bizType) {
        return BIZ_TYPE_EXCHANGE.equals(bizType) ? exchangeReviewAmount : withdrawalReviewAmount;
    }

    private long dailyDecisionCount(ComplianceGateRequest request) {
        Long count = riskDecisionMapper.selectCount(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getUserId, request.getUserId())
                .eq(RiskDecision::getBizType, request.getBizType())
                .eq(RiskDecision::getIsDeleted, 0)
                .ge(RiskDecision::getCreatedAt, LocalDate.now().atStartOfDay()));
        return count == null ? 0 : count;
    }

    private long clientIpDecisionCount(String clientIp) {
        if (!StringUtils.hasText(clientIp) || ipDailyReviewCount <= 0) {
            return 0;
        }
        Long count = riskDecisionMapper.selectCount(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getClientIp, clientIp)
                .eq(RiskDecision::getIsDeleted, 0)
                .ge(RiskDecision::getCreatedAt, LocalDate.now().atStartOfDay()));
        return count == null ? 0 : count;
    }

    private long deviceFingerprintDecisionCount(String deviceFingerprint) {
        if (!StringUtils.hasText(deviceFingerprint) || deviceDailyReviewCount <= 0) {
            return 0;
        }
        Long count = riskDecisionMapper.selectCount(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getDeviceFingerprint, deviceFingerprint)
                .eq(RiskDecision::getIsDeleted, 0)
                .ge(RiskDecision::getCreatedAt, LocalDate.now().atStartOfDay()));
        return count == null ? 0 : count;
    }

    private boolean matchesAnyRegion(Set<String> regions, RiskContext context) {
        return !regions.isEmpty()
                && ((StringUtils.hasText(context.region()) && regions.contains(context.region()))
                        || (StringUtils.hasText(context.kycCountry()) && regions.contains(context.kycCountry())));
    }

    private boolean isRegionMismatch(RiskContext context) {
        return StringUtils.hasText(context.region())
                && StringUtils.hasText(context.kycCountry())
                && !context.region().equals(context.kycCountry());
    }

    private String firstReviewReason(String currentReason, String nextReason) {
        return REASON_KYC_APPROVED.equals(currentReason) ? nextReason : currentReason;
    }

    private boolean isActiveBlacklist(RiskBlacklist blacklist) {
        return blacklist != null
                && BLACKLIST_ACTIVE.equals(blacklist.getStatus())
                && !Integer.valueOf(1).equals(blacklist.getIsDeleted())
                && (blacklist.getExpiresAt() == null || blacklist.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private RiskDecision reviewDecision(
            String decisionNo, String decisionValue, String reasonPrefix, ManualRiskReviewRequest request) {
        if (!StringUtils.hasText(decisionNo)) {
            throw new BizException("Risk decision no is required");
        }
        validateManualReview(request);
        RiskDecision decision = findDecision(decisionNo);
        if (decision == null) {
            throw new BizException("Risk decision not found");
        }
        if (!DECISION_REVIEW.equals(decision.getDecision()) && decisionValue.equals(decision.getDecision())) {
            publishRiskDecisionFinalized(decision);
            return decision;
        }
        if (!DECISION_REVIEW.equals(decision.getDecision())) {
            throw new BizException("Risk decision is already finalized");
        }
        RiskDecision patch = new RiskDecision();
        patch.setId(decision.getId());
        patch.setDecision(decisionValue);
        patch.setReason(reasonPrefix + ": " + request.getReason());
        patch.setReviewedBy(request.getReviewer());
        patch.setReviewedAt(LocalDateTime.now());
        riskDecisionMapper.updateById(patch);
        decision.setDecision(patch.getDecision());
        decision.setReason(patch.getReason());
        decision.setReviewedBy(patch.getReviewedBy());
        decision.setReviewedAt(patch.getReviewedAt());
        publishRiskDecisionFinalized(decision);
        return decision;
    }

    private void publishRiskDecisionFinalized(RiskDecision decision) {
        if (!isWalletBizType(decision.getBizType())) {
            return;
        }
        RiskDecisionFinalizedPayload payload = new RiskDecisionFinalizedPayload();
        payload.setDecisionId(decision.getId());
        payload.setDecisionNo(decision.getDecisionNo());
        payload.setUserId(decision.getUserId());
        payload.setBizType(decision.getBizType());
        payload.setBizNo(decision.getBizNo());
        payload.setDecision(decision.getDecision());
        payload.setReason(decision.getReason());
        payload.setReviewedBy(decision.getReviewedBy());
        payload.setReviewedAt(decision.getReviewedAt());
        outboxService.publish(
                "RISK_DECISION",
                decision.getDecisionNo(),
                ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED,
                payload);
    }

    private boolean isWalletBizType(String bizType) {
        return BIZ_TYPE_WITHDRAWAL.equals(bizType) || BIZ_TYPE_EXCHANGE.equals(bizType);
    }

    private RiskDecision findDecision(String decisionNo) {
        return riskDecisionMapper.selectOne(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getDecisionNo, decisionNo)
                .eq(RiskDecision::getIsDeleted, 0));
    }

    private String decisionNo(String bizType, String bizNo) {
        return "RISK-" + bizType + "-" + bizNo;
    }

    private ComplianceGateResponse response(RiskDecision decision) {
        ComplianceGateResponse response = new ComplianceGateResponse();
        response.setDecisionId(decision.getId());
        response.setDecision(decision.getDecision());
        response.setReason(decision.getReason());
        response.setRiskScore(decision.getRiskScore());
        response.setRuleCodes(decision.getRuleCodes());
        return response;
    }

    private String safeSnapshotValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "UNKNOWN";
        }
        String sanitized = value.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        return sanitized.length() > 128 ? sanitized.substring(0, 128) : sanitized;
    }

    private String kycSnapshot(KycProfile kyc) {
        return "kycStatus=" + safeSnapshotValue(kyc.getStatus())
                + ";kycCountry=" + safeSnapshotValue(kyc.getCountry())
                + ";kycReviewedBy=" + safeSnapshotValue(kyc.getReviewedBy())
                + ";kycReviewedAt=" + safeSnapshotValue(kyc.getReviewedAt() == null
                        ? null
                        : kyc.getReviewedAt().toString())
                + ";kycExpiresAt=" + safeSnapshotValue(kyc.getExpiresAt() == null
                        ? null
                        : kyc.getExpiresAt().toString());
    }

    private RiskContext riskContext(ComplianceGateRequest request, KycProfile kyc) {
        String requestRegion = normalizeToken(request.getRegion());
        String kycCountry = kyc == null ? null : normalizeToken(kyc.getCountry());
        String effectiveRegion = StringUtils.hasText(requestRegion) ? requestRegion : kycCountry;
        return new RiskContext(
                kycCountry,
                effectiveRegion,
                normalizeToken(request.getUserLevel()),
                normalizeContextValue(request.getClientIp()),
                normalizeContextValue(request.getDeviceFingerprint()));
    }

    private String contextSnapshot(RiskContext context) {
        return ";region=" + safeSnapshotValue(context.region())
                + ";userLevel=" + safeSnapshotValue(context.userLevel())
                + ";clientIp=" + safeSnapshotValue(context.clientIp())
                + ";deviceFingerprint=" + safeSnapshotValue(context.deviceFingerprint());
    }

    private static Set<String> parseTokenSet(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(ComplianceGateService::normalizeToken)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeToken(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String normalizeContextValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void validateManualReview(ManualRiskReviewRequest request) {
        if (request == null || !StringUtils.hasText(request.getReviewer())) {
            throw new BizException("Reviewer is required");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new BizException("Review reason is required");
        }
        if (request.getReviewer().length() > 64) {
            throw new BizException("Reviewer is too long");
        }
        if (request.getReason().length() > 255) {
            throw new BizException("Review reason is too long");
        }
    }

    private void validate(ComplianceGateRequest request) {
        if (request == null) {
            throw new BizException("Compliance gate request is required");
        }
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        validateRequiredToken("Biz type", request.getBizType(), 64);
        validateRequiredToken("Biz no", request.getBizNo(), 96);
        validateRequiredToken("Asset", request.getAsset(), 16);
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BizException("Amount must be positive");
        }
        validateOptionalToken("Region", request.getRegion(), 32);
        validateOptionalToken("User level", request.getUserLevel(), 16);
        validateOptionalToken("Client ip", request.getClientIp(), 64);
        validateOptionalToken("Device fingerprint", request.getDeviceFingerprint(), 128);
    }

    private void validateRequiredToken(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(fieldName + " is required");
        }
        validateToken(fieldName, value, maxLength);
    }

    private void validateOptionalToken(String fieldName, String value, int maxLength) {
        if (StringUtils.hasText(value)) {
            validateToken(fieldName, value, maxLength);
        }
    }

    private void validateToken(String fieldName, String value, int maxLength) {
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BizException(fieldName + " is too long");
        }
        if (!SAFE_CONTEXT_TOKEN.matcher(trimmed).matches()) {
            throw new BizException(fieldName + " contains unsupported characters");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private record RiskContext(
            String kycCountry, String region, String userLevel, String clientIp, String deviceFingerprint) {
    }

    private record DecisionResult(
            String decision,
            String reason,
            Integer riskScore,
            String ruleCodes,
            String ruleSnapshot,
            RiskContext context) {
    }
}
