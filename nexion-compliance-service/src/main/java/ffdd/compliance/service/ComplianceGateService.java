package ffdd.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.compliance.client.WalletClient;
import ffdd.compliance.client.dto.WalletRiskDecisionApplyRequest;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.RiskBlacklist;
import ffdd.compliance.domain.RiskDecision;
import ffdd.compliance.dto.ComplianceGateRequest;
import ffdd.compliance.dto.ComplianceGateResponse;
import ffdd.compliance.dto.ManualRiskReviewRequest;
import ffdd.compliance.mapper.KycProfileMapper;
import ffdd.compliance.mapper.RiskBlacklistMapper;
import ffdd.compliance.mapper.RiskDecisionMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private static final String BIZ_TYPE_WITHDRAWAL = "WITHDRAWAL";
    private static final String BIZ_TYPE_EXCHANGE = "EXCHANGE";

    private final KycProfileMapper kycProfileMapper;
    private final RiskDecisionMapper riskDecisionMapper;
    private final RiskBlacklistMapper riskBlacklistMapper;
    private final WalletClient walletClient;
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
            WalletClient walletClient) {
        this.kycProfileMapper = kycProfileMapper;
        this.riskDecisionMapper = riskDecisionMapper;
        this.riskBlacklistMapper = riskBlacklistMapper;
        this.walletClient = walletClient;
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
                .eq(RiskBlacklist::getIsDeleted, 0));
        if (blacklist != null) {
            return new DecisionResult(DECISION_REJECT, REASON_BLACKLISTED);
        }

        KycProfile kyc = kycProfileMapper.selectOne(new LambdaQueryWrapper<KycProfile>()
                .eq(KycProfile::getUserId, request.getUserId())
                .eq(KycProfile::getIsDeleted, 0));
        if (kyc == null || !KYC_APPROVED.equals(kyc.getStatus())) {
            return new DecisionResult(DECISION_REJECT, REASON_KYC_NOT_APPROVED);
        }

        if (requiresAmountReview(request)) {
            return new DecisionResult(DECISION_REVIEW, REASON_AMOUNT_REVIEW);
        }
        if (dailyDecisionCount(request) >= dailyReviewCount) {
            return new DecisionResult(DECISION_REVIEW, REASON_FREQUENCY_REVIEW);
        }
        return new DecisionResult(DECISION_APPROVE, REASON_KYC_APPROVED);
    }

    private boolean requiresAmountReview(ComplianceGateRequest request) {
        BigDecimal threshold = "EXCHANGE".equals(request.getBizType()) ? exchangeReviewAmount : withdrawalReviewAmount;
        return request.getAmount().compareTo(threshold) > 0;
    }

    private long dailyDecisionCount(ComplianceGateRequest request) {
        Long count = riskDecisionMapper.selectCount(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getUserId, request.getUserId())
                .eq(RiskDecision::getBizType, request.getBizType())
                .eq(RiskDecision::getIsDeleted, 0)
                .ge(RiskDecision::getCreatedAt, LocalDate.now().atStartOfDay()));
        return count == null ? 0 : count;
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
            applyWalletRiskDecision(decision);
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
        applyWalletRiskDecision(decision);
        return decision;
    }

    private void applyWalletRiskDecision(RiskDecision decision) {
        if (!isWalletBizType(decision.getBizType())) {
            return;
        }
        WalletRiskDecisionApplyRequest request = new WalletRiskDecisionApplyRequest();
        request.setDecisionId(decision.getId());
        request.setDecisionNo(decision.getDecisionNo());
        request.setBizType(decision.getBizType());
        request.setBizNo(decision.getBizNo());
        request.setDecision(decision.getDecision());
        request.setReason(decision.getReason());
        try {
            ApiResult<Map<String, Object>> result = walletClient.applyRiskDecision(request);
            if (result == null || result.getCode() != 0) {
                throw new BizException("Wallet risk decision apply failed");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("Wallet risk decision apply failed");
        }
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
        return response;
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

    private record DecisionResult(String decision, String reason) {
    }
}
