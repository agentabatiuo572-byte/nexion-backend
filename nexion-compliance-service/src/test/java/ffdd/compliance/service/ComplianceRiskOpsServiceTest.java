package ffdd.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.compliance.domain.RiskBlacklist;
import ffdd.compliance.dto.RiskBlacklistReleaseRequest;
import ffdd.compliance.dto.RiskBlacklistUpsertRequest;
import ffdd.compliance.dto.RiskDecisionSummaryResponse;
import ffdd.compliance.mapper.KycProfileMapper;
import ffdd.compliance.mapper.RiskBlacklistMapper;
import ffdd.compliance.mapper.RiskDecisionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComplianceRiskOpsServiceTest {
    private final RiskDecisionMapper riskDecisionMapper = mock(RiskDecisionMapper.class);
    private final RiskBlacklistMapper riskBlacklistMapper = mock(RiskBlacklistMapper.class);
    private final KycProfileMapper kycProfileMapper = mock(KycProfileMapper.class);
    private final ComplianceRiskOpsService service =
            new ComplianceRiskOpsService(riskDecisionMapper, riskBlacklistMapper, kycProfileMapper);

    @Test
    void createsActiveBlacklistEntryWithOperationalMetadata() {
        when(riskBlacklistMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            RiskBlacklist blacklist = invocation.getArgument(0);
            blacklist.setId(11L);
            return 1;
        }).when(riskBlacklistMapper).insert(any(RiskBlacklist.class));

        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);
        RiskBlacklistUpsertRequest request = new RiskBlacklistUpsertRequest();
        request.setUserId(10001L);
        request.setReason("SANCTION_SCREENING_HIT");
        request.setRiskLevel("HIGH");
        request.setSource("OPS");
        request.setOperator("admin-1");
        request.setExpiresAt(expiresAt);

        RiskBlacklist result = service.upsertBlacklist(request);

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getReason()).isEqualTo("SANCTION_SCREENING_HIT");
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getSource()).isEqualTo("OPS");
        assertThat(result.getCreatedBy()).isEqualTo("admin-1");
        assertThat(result.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void releasesActiveBlacklistEntry() {
        RiskBlacklist active = new RiskBlacklist();
        active.setId(12L);
        active.setUserId(10002L);
        active.setStatus("ACTIVE");
        active.setReason("MANUAL_HOLD");

        when(riskBlacklistMapper.selectOne(any())).thenReturn(active);

        RiskBlacklistReleaseRequest request = new RiskBlacklistReleaseRequest();
        request.setOperator("admin-2");
        request.setReason("false positive");

        RiskBlacklist result = service.releaseBlacklist(10002L, request);

        assertThat(result.getStatus()).isEqualTo("RELEASED");
        assertThat(result.getReleasedBy()).isEqualTo("admin-2");
        assertThat(result.getReleaseReason()).isEqualTo("false positive");
        assertThat(result.getReleasedAt()).isNotNull();

        ArgumentCaptor<RiskBlacklist> captor = ArgumentCaptor.forClass(RiskBlacklist.class);
        verify(riskBlacklistMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(12L);
        assertThat(captor.getValue().getStatus()).isEqualTo("RELEASED");
    }

    @Test
    void summarizesRiskDecisionAndBlacklistBacklog() {
        when(riskDecisionMapper.selectCount(any()))
                .thenReturn(10L)
                .thenReturn(6L)
                .thenReturn(3L)
                .thenReturn(1L);
        when(riskBlacklistMapper.selectCount(any())).thenReturn(2L);

        RiskDecisionSummaryResponse response = service.summarize(7);

        assertThat(response.getDays()).isEqualTo(7);
        assertThat(response.getTotalDecisions()).isEqualTo(10);
        assertThat(response.getApprovedDecisions()).isEqualTo(6);
        assertThat(response.getReviewDecisions()).isEqualTo(3);
        assertThat(response.getRejectedDecisions()).isEqualTo(1);
        assertThat(response.getActiveBlacklists()).isEqualTo(2);
    }

    @Test
    void listsBlacklistWithNormalizedLimit() {
        RiskBlacklist first = new RiskBlacklist();
        first.setUserId(10001L);
        first.setStatus("ACTIVE");
        when(riskBlacklistMapper.selectList(any())).thenReturn(List.of(first));

        List<RiskBlacklist> result = service.listBlacklists("ACTIVE", 1000);

        assertThat(result).containsExactly(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizesOpsKpisAcrossRiskBlacklistAndKyc() {
        when(riskDecisionMapper.selectCount(any()))
                .thenReturn(20L)
                .thenReturn(12L)
                .thenReturn(5L)
                .thenReturn(3L)
                .thenReturn(4L);
        when(riskBlacklistMapper.selectCount(any()))
                .thenReturn(2L)
                .thenReturn(1L);
        when(kycProfileMapper.selectCount(any()))
                .thenReturn(30L)
                .thenReturn(6L)
                .thenReturn(20L)
                .thenReturn(3L)
                .thenReturn(1L)
                .thenReturn(2L);

        Map<String, Object> stats = service.opsStats(120);

        assertThat(stats)
                .containsEntry("service", "nexion-compliance-service")
                .containsEntry("days", 90);
        assertThat((Map<String, Object>) stats.get("risk"))
                .containsEntry("total", 20L)
                .containsEntry("approved", 12L)
                .containsEntry("review", 5L)
                .containsEntry("rejected", 3L)
                .containsEntry("reviewQueue", 4L);
        assertThat((Map<String, Object>) stats.get("blacklists"))
                .containsEntry("active", 2L)
                .containsEntry("released", 1L);
        assertThat((Map<String, Object>) stats.get("kyc"))
                .containsEntry("total", 30L)
                .containsEntry("pending", 6L)
                .containsEntry("approved", 20L)
                .containsEntry("rejected", 3L)
                .containsEntry("expired", 1L)
                .containsEntry("expiringSoon", 2L);
    }
}
