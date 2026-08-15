package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.domain.PublicSponsorPreviewView;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.growth.mapper.PublicSponsorPreviewMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class PublicSponsorPreviewServiceTest {
    private final PublicSponsorPreviewMapper mapper = mock(PublicSponsorPreviewMapper.class);
    private final OpsReferralRewardService rewards = mock(OpsReferralRewardService.class);
    private final Environment environment = mock(Environment.class);
    private final PublicSponsorPreviewService service = new PublicSponsorPreviewService(mapper, rewards, environment);

    @Test
    void validCodeReturnsOnlyMaskedSponsorAndServerGiftFact() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.findActiveByCanonicalCode("NXAB12CD34EF")).thenReturn(List.of(
                new PublicSponsorPreviewMapper.SponsorRow("NXAB12CD34EF", "Alice Example", "V3", 0)));
        when(rewards.publicConfig()).thenReturn(new ReferralRewardPublicConfigView(
                new ReferralRewardPublicConfigView.WelcomeGift("risk_bucket", new BigDecimal("1.25"), new BigDecimal("20")),
                new ReferralRewardPublicConfigView.InviterReward(BigDecimal.ZERO), 8,
                BigDecimal.ONE, BigDecimal.ONE, Instant.parse("2026-08-01T00:00:00Z"), List.of("server")));

        var result = service.preview(" nx-ab12-cd34-ef ");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().code()).isEqualTo("NXAB12CD34EF");
        assertThat(result.getData().sponsor().displayName()).isEqualTo("A•••");
        assertThat(result.getData().sponsor().displayName()).doesNotContain("Alice");
        assertThat(result.getData().gift().usdtAmount()).isEqualByComparingTo("1.25");
        assertThat(result.getData().gift().nexAmount()).isEqualByComparingTo("20");
    }

    @Test
    void invalidMissingAndCrossEnvironmentCodesFailClosed() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        assertThat(service.preview("bad!").getMessage()).isEqualTo("REFERRAL_PREVIEW_INVALID");
        when(mapper.findActiveByCanonicalCode("NXNOTFOUND1")).thenReturn(List.of());
        assertThat(service.preview("NXNOTFOUND1").getMessage()).isEqualTo("REFERRAL_PREVIEW_NOT_FOUND");
        when(mapper.findActiveByCanonicalCode("NXMIXED1")).thenReturn(List.of(
                new PublicSponsorPreviewMapper.SponsorRow("NXMIXED1", "Alice", "V1", 1)));
        assertThat(service.preview("NXMIXED1").getMessage()).isEqualTo("REFERRAL_PREVIEW_NOT_FOUND");
    }

    @Test
    void genericRewardRuntimeFailureFailsClosedAsUnavailable() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(mapper.findActiveByCanonicalCode("AB12CD34")).thenReturn(List.of(
                new PublicSponsorPreviewMapper.SponsorRow("AB12CD34", "Alice", "V1", 0)));
        doThrow(new IllegalArgumentException("malformed config")).when(rewards).publicConfig();

        var result = service.preview("ab12-cd34");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("REFERRAL_PREVIEW_UNAVAILABLE");
    }
}
