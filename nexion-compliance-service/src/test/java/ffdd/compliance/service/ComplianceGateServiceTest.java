package ffdd.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComplianceGateServiceTest {
    private final KycProfileMapper kycProfileMapper = mock(KycProfileMapper.class);
    private final RiskDecisionMapper riskDecisionMapper = mock(RiskDecisionMapper.class);
    private final RiskBlacklistMapper riskBlacklistMapper = mock(RiskBlacklistMapper.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final ComplianceGateService service = new ComplianceGateService(
            kycProfileMapper,
            riskDecisionMapper,
            riskBlacklistMapper,
            new BigDecimal("1000.000000"),
            new BigDecimal("5000.000000"),
            3,
            outboxService);

    @Test
    void approvesWithdrawalWhenKycApproved() {
        KycProfile kyc = new KycProfile();
        kyc.setUserId(10001L);
        kyc.setStatus("APPROVED");

        when(kycProfileMapper.selectOne(any())).thenReturn(kyc);
        when(riskDecisionMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            RiskDecision decision = invocation.getArgument(0);
            decision.setId(91L);
            return 1;
        }).when(riskDecisionMapper).insert(any(RiskDecision.class));

        ComplianceGateResponse response = service.check(request("WITHDRAWAL", "WD-1"));

        assertThat(response.getDecision()).isEqualTo("APPROVE");
        assertThat(response.getDecisionId()).isEqualTo(91L);
        assertThat(response.getReason()).isEqualTo("KYC_APPROVED");
    }

    @Test
    void rejectsWhenKycIsMissing() {
        when(kycProfileMapper.selectOne(any())).thenReturn(null);
        when(riskDecisionMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            RiskDecision decision = invocation.getArgument(0);
            decision.setId(92L);
            return 1;
        }).when(riskDecisionMapper).insert(any(RiskDecision.class));

        ComplianceGateResponse response = service.check(request("EXCHANGE", "EX-1"));

        assertThat(response.getDecision()).isEqualTo("REJECT");
        assertThat(response.getDecisionId()).isEqualTo(92L);
        assertThat(response.getReason()).isEqualTo("KYC_NOT_APPROVED");
    }

    @Test
    void returnsExistingDecisionForRepeatedBizNo() {
        RiskDecision existing = new RiskDecision();
        existing.setId(93L);
        existing.setDecisionNo("RISK-WITHDRAWAL-WD-2");
        existing.setDecision("APPROVE");
        existing.setReason("KYC_APPROVED");

        when(riskDecisionMapper.selectOne(any())).thenReturn(existing);

        ComplianceGateResponse response = service.check(request("WITHDRAWAL", "WD-2"));

        assertThat(response.getDecision()).isEqualTo("APPROVE");
        assertThat(response.getDecisionId()).isEqualTo(93L);
    }

    @Test
    void rejectsWhenUserIsBlacklisted() {
        RiskBlacklist blacklist = new RiskBlacklist();
        blacklist.setUserId(10001L);
        blacklist.setStatus("ACTIVE");
        blacklist.setReason("SANCTIONED");
        blacklist.setRiskLevel("HIGH");

        KycProfile kyc = new KycProfile();
        kyc.setUserId(10001L);
        kyc.setStatus("APPROVED");

        when(riskDecisionMapper.selectOne(any())).thenReturn(null);
        when(riskBlacklistMapper.selectOne(any())).thenReturn(blacklist);
        when(kycProfileMapper.selectOne(any())).thenReturn(kyc);
        doAnswer(invocation -> {
            RiskDecision decision = invocation.getArgument(0);
            decision.setId(94L);
            return 1;
        }).when(riskDecisionMapper).insert(any(RiskDecision.class));

        ComplianceGateResponse response = service.check(request("WITHDRAWAL", "WD-BLACK"));

        assertThat(response.getDecision()).isEqualTo("REJECT");
        assertThat(response.getReason()).isEqualTo("BLACKLISTED");
        assertThat(response.getRiskScore()).isEqualTo(100);
        assertThat(response.getRuleCodes()).contains("BLACKLIST_ACTIVE");
    }

    @Test
    void ignoresExpiredBlacklistAndApprovesWhenKycApproved() {
        RiskBlacklist blacklist = new RiskBlacklist();
        blacklist.setUserId(10001L);
        blacklist.setStatus("ACTIVE");
        blacklist.setReason("TEMP_HOLD");
        blacklist.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        KycProfile kyc = new KycProfile();
        kyc.setUserId(10001L);
        kyc.setStatus("APPROVED");

        when(riskDecisionMapper.selectOne(any())).thenReturn(null);
        when(riskBlacklistMapper.selectOne(any())).thenReturn(blacklist);
        when(kycProfileMapper.selectOne(any())).thenReturn(kyc);
        doAnswer(invocation -> {
            RiskDecision decision = invocation.getArgument(0);
            decision.setId(9401L);
            return 1;
        }).when(riskDecisionMapper).insert(any(RiskDecision.class));

        ComplianceGateResponse response = service.check(request("WITHDRAWAL", "WD-EXPIRED-BLACKLIST"));

        assertThat(response.getDecision()).isEqualTo("APPROVE");
        assertThat(response.getReason()).isEqualTo("KYC_APPROVED");
        assertThat(response.getRiskScore()).isZero();
        assertThat(response.getRuleCodes()).contains("KYC_APPROVED");
    }

    @Test
    void reviewsWithdrawalWhenAmountExceedsThreshold() {
        KycProfile kyc = new KycProfile();
        kyc.setUserId(10001L);
        kyc.setStatus("APPROVED");

        when(riskDecisionMapper.selectOne(any())).thenReturn(null);
        when(kycProfileMapper.selectOne(any())).thenReturn(kyc);
        doAnswer(invocation -> {
            RiskDecision decision = invocation.getArgument(0);
            decision.setId(95L);
            return 1;
        }).when(riskDecisionMapper).insert(any(RiskDecision.class));

        ComplianceGateRequest request = request("WITHDRAWAL", "WD-LARGE");
        request.setAmount(new BigDecimal("1500.000000"));
        ComplianceGateResponse response = service.check(request);

        assertThat(response.getDecision()).isEqualTo("REVIEW");
        assertThat(response.getReason()).isEqualTo("AMOUNT_REVIEW");
        assertThat(response.getRiskScore()).isEqualTo(60);
        assertThat(response.getRuleCodes()).contains("AMOUNT_THRESHOLD");
    }

    @Test
    void reviewsWhenDailyFrequencyLimitIsReached() {
        KycProfile kyc = new KycProfile();
        kyc.setUserId(10001L);
        kyc.setStatus("APPROVED");

        when(riskDecisionMapper.selectOne(any())).thenReturn(null);
        when(kycProfileMapper.selectOne(any())).thenReturn(kyc);
        when(riskDecisionMapper.selectCount(any())).thenReturn(3L);
        doAnswer(invocation -> {
            RiskDecision decision = invocation.getArgument(0);
            decision.setId(96L);
            return 1;
        }).when(riskDecisionMapper).insert(any(RiskDecision.class));

        ComplianceGateResponse response = service.check(request("WITHDRAWAL", "WD-FREQ"));

        assertThat(response.getDecision()).isEqualTo("REVIEW");
        assertThat(response.getReason()).isEqualTo("FREQUENCY_REVIEW");
    }

    @Test
    void approvesReviewDecisionManuallyAndPublishesFinalizedOutbox() {
        RiskDecision review = new RiskDecision();
        review.setId(97L);
        review.setDecisionNo("RISK-WITHDRAWAL-WD-REVIEW");
        review.setUserId(10001L);
        review.setBizType("WITHDRAWAL");
        review.setBizNo("WD-REVIEW");
        review.setDecision("REVIEW");
        review.setReason("AMOUNT_REVIEW");

        when(riskDecisionMapper.selectOne(any())).thenReturn(review);
        when(outboxService.publish(any(), any(), any(), any())).thenReturn("evt-risk-1");

        RiskDecision result = service.approveDecision("RISK-WITHDRAWAL-WD-REVIEW", reviewRequest("admin-1", "verified"));

        assertThat(result.getDecision()).isEqualTo("APPROVE");
        assertThat(result.getReason()).isEqualTo("MANUAL_APPROVE: verified");
        ArgumentCaptor<RiskDecisionFinalizedPayload> captor =
                ArgumentCaptor.forClass(RiskDecisionFinalizedPayload.class);
        verify(outboxService).publish(
                eq("RISK_DECISION"),
                eq("RISK-WITHDRAWAL-WD-REVIEW"),
                eq(ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED),
                captor.capture());
        assertThat(captor.getValue().getDecisionId()).isEqualTo(97L);
        assertThat(captor.getValue().getDecisionNo()).isEqualTo("RISK-WITHDRAWAL-WD-REVIEW");
        assertThat(captor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(captor.getValue().getBizType()).isEqualTo("WITHDRAWAL");
        assertThat(captor.getValue().getBizNo()).isEqualTo("WD-REVIEW");
        assertThat(captor.getValue().getDecision()).isEqualTo("APPROVE");
        assertThat(captor.getValue().getReviewedBy()).isEqualTo("admin-1");
    }

    @Test
    void rejectsReviewDecisionManuallyAndPublishesFinalizedOutbox() {
        RiskDecision review = new RiskDecision();
        review.setId(98L);
        review.setDecisionNo("RISK-WITHDRAWAL-WD-REVIEW-2");
        review.setUserId(10001L);
        review.setBizType("WITHDRAWAL");
        review.setBizNo("WD-REVIEW-2");
        review.setDecision("REVIEW");
        review.setReason("FREQUENCY_REVIEW");

        when(riskDecisionMapper.selectOne(any())).thenReturn(review);
        when(outboxService.publish(any(), any(), any(), any())).thenReturn("evt-risk-2");

        RiskDecision result = service.rejectDecision("RISK-WITHDRAWAL-WD-REVIEW-2", reviewRequest("admin-1", "suspicious"));

        assertThat(result.getDecision()).isEqualTo("REJECT");
        assertThat(result.getReason()).isEqualTo("MANUAL_REJECT: suspicious");
        ArgumentCaptor<RiskDecisionFinalizedPayload> captor =
                ArgumentCaptor.forClass(RiskDecisionFinalizedPayload.class);
        verify(outboxService).publish(
                eq("RISK_DECISION"),
                eq("RISK-WITHDRAWAL-WD-REVIEW-2"),
                eq(ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED),
                captor.capture());
        assertThat(captor.getValue().getDecisionId()).isEqualTo(98L);
        assertThat(captor.getValue().getBizType()).isEqualTo("WITHDRAWAL");
        assertThat(captor.getValue().getBizNo()).isEqualTo("WD-REVIEW-2");
        assertThat(captor.getValue().getDecision()).isEqualTo("REJECT");
    }

    private ComplianceGateRequest request(String bizType, String bizNo) {
        ComplianceGateRequest request = new ComplianceGateRequest();
        request.setUserId(10001L);
        request.setBizType(bizType);
        request.setBizNo(bizNo);
        request.setAsset("USDT");
        request.setAmount(new BigDecimal("1.000000"));
        return request;
    }

    private ManualRiskReviewRequest reviewRequest(String reviewer, String reason) {
        ManualRiskReviewRequest request = new ManualRiskReviewRequest();
        request.setReviewer(reviewer);
        request.setReason(reason);
        return request;
    }
}
