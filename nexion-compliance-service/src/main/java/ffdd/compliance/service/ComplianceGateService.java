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
import java.util.List;
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
    private static final String REASON_KYC_NOT_APPROVED = "KYC_NOT_APPROVED";
    private static final String REASON_BLACKLISTED = "BLACKLISTED";
    private static final String REASON_AMOUNT_REVIEW = "AMOUNT_REVIEW";
    private static final String REASON_FREQUENCY_REVIEW = "FREQUENCY_REVIEW";
    private static final String RULE_BLACKLIST_ACTIVE = "BLACKLIST_ACTIVE";
    private static final String RULE_KYC_APPROVED = "KYC_APPROVED";
    private static final String RULE_KYC_NOT_APPROVED = "KYC_NOT_APPROVED";
    private static final String RULE_AMOUNT_THRESHOLD = "AMOUNT_THRESHOLD";
    private static final String RULE_DAILY_FREQUENCY = "DAILY_FREQUENCY";
    private static final String BIZ_TYPE_WITHDRAWAL = "WITHDRAWAL";
    private static final String BIZ_TYPE_EXCHANGE = "EXCHANGE";

    private final KycProfileMapper kycProfileMapper;
    private final RiskDecisionMapper riskDecisionMapper;
    private final RiskBlacklistMapper riskBlacklistMapper;
    private final EventOutboxService outboxService;
    private final BigDecimal withdrawalReviewAmount;
    private final BigDecimal exchangeReviewAmount;
    private final int dailyReviewCount;

    public ComplianceGateService(
            KycProfileMapper kycProfileMapper,
            RiskDecisionMapper riskDecisionMapper,
            RiskBlacklistMapper riskBlacklistMapper,
            @Value("${nexion.compliance.risk.withdrawal-review-amount:1000.000000}") BigDecimal withdrawalReviewAmount,
            @Value("${nexion.compliance.risk.exchange-review-amount:5000.000000}") BigDecimal exchangeReviewAmount,
            @Value("${nexion.compliance.risk.daily-review-count:3}") int dailyReviewCount,
            EventOutboxService outboxService) {
        this.kycProfileMapper = kycProfileMapper;
        this.riskDecisionMapper = riskDecisionMapper;
        this.riskBlacklistMapper = riskBlacklistMapper;
        this.outboxService = outboxService;
        this.withdrawalReviewAmount = withdrawalReviewAmount;
        this.exchangeReviewAmount = exchangeReviewAmount;
        this.dailyReviewCount = Math.max(1, dailyReviewCount);
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
                            + ";riskLevel=" + safeSnapshotValue(blacklist.getRiskLevel()));
        }

        KycProfile kyc = kycProfileMapper.selectOne(new LambdaQueryWrapper<KycProfile>()
                .eq(KycProfile::getUserId, request.getUserId())
                .eq(KycProfile::getIsDeleted, 0));
        if (kyc == null || !KYC_APPROVED.equals(kyc.getStatus())) {
            return new DecisionResult(
                    DECISION_REJECT,
                    REASON_KYC_NOT_APPROVED,
                    80,
                    RULE_KYC_NOT_APPROVED,
                    "kycStatus=" + (kyc == null ? "MISSING" : safeSnapshotValue(kyc.getStatus())));
        }

        List<String> ruleCodes = new ArrayList<>();
        int riskScore = 0;
        String primaryReason = REASON_KYC_APPROVED;
        BigDecimal threshold = reviewThreshold(request.getBizType());
        if (requiresAmountReview(request, threshold)) {
            ruleCodes.add(RULE_AMOUNT_THRESHOLD);
            riskScore = Math.max(riskScore, 60);
            primaryReason = REASON_AMOUNT_REVIEW;
        }
        long dailyCount = dailyDecisionCount(request);
        if (dailyCount >= dailyReviewCount) {
            ruleCodes.add(RULE_DAILY_FREQUENCY);
            riskScore = Math.max(riskScore, 50);
            if (REASON_KYC_APPROVED.equals(primaryReason)) {
                primaryReason = REASON_FREQUENCY_REVIEW;
            }
        }
        if (!ruleCodes.isEmpty()) {
            return new DecisionResult(
                    DECISION_REVIEW,
                    primaryReason,
                    riskScore,
                    String.join(",", ruleCodes),
                    "amount=" + request.getAmount().toPlainString()
                            + ";threshold=" + threshold.toPlainString()
                            + ";dailyCount=" + dailyCount
                            + ";dailyLimit=" + dailyReviewCount);
        }
        return new DecisionResult(DECISION_APPROVE, REASON_KYC_APPROVED, 0, RULE_KYC_APPROVED, "kycStatus=APPROVED");
    }

    private boolean requiresAmountReview(ComplianceGateRequest request, BigDecimal threshold) {
        return request.getAmount().compareTo(threshold) > 0;
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
        return value.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
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
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        if (!StringUtils.hasText(request.getBizType())) {
            throw new BizException("Biz type is required");
        }
        if (!StringUtils.hasText(request.getBizNo())) {
            throw new BizException("Biz no is required");
        }
        if (!StringUtils.hasText(request.getAsset())) {
            throw new BizException("Asset is required");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BizException("Amount must be positive");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private record DecisionResult(String decision, String reason, Integer riskScore, String ruleCodes, String ruleSnapshot) {
    }
}
