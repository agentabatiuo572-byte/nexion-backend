package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.dto.AppRiskDisclosureAckRequest;
import ffdd.opsconsole.content.infrastructure.DisclosureAckStatusEntity;
import ffdd.opsconsole.content.mapper.DisclosureAckStatusMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppRiskDisclosureServiceTest {
    private final TrustDisclosureRepository repository = mock(TrustDisclosureRepository.class);
    private final DisclosureAckStatusMapper ackMapper = mock(DisclosureAckStatusMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-12T03:00:00Z"), ZoneOffset.UTC);
    private final AppRiskDisclosureService service = new AppRiskDisclosureService(repository, ackMapper, clock);

    @BeforeEach
    void setUpPublishedVietnamDisclosure() {
        when(ackMapper.findUserCountryCode(42L)).thenReturn("vn");
        when(repository.listJurisdictions()).thenReturn(List.of(
                new DisclosureJurisdictionView("SBV", "越南国家银行", List.of("VN"), "v13", "published", "2026-07-12", 100, 0, 0)));
        when(repository.findDisclosureVersion("SBV", "v13")).thenReturn(Optional.of(new DisclosureDraftView(
                "v13", "SBV", "zh+vi+en", "2026-07-12", true,
                "中文", "Tiếng Việt", "English", "published")));
        when(repository.listChapters("SBV", "v13")).thenReturn(List.of(new DisclosureChapterView(
                "SBV", "v13", "01", "风险说明", "Công bố rủi ro", "Risk disclosure",
                "中文正文", "Nội dung", "English body")));
        when(repository.listGateActions()).thenReturn(List.of(
                new DisclosureGateActionView("withdraw", "提现", "提现前检查", "已实装", "warn", true),
                new DisclosureGateActionView("staking", "质押锁仓", "当前未启用", "未启用", "dim", false)));
    }

    @Test
    void currentUsesUserCountryAndCanonicalAckStatus() {
        DisclosureAckStatusEntity ack = ack("v13", "v13", "ACKED");
        when(ackMapper.findUserAck(42L, "SBV")).thenReturn(ack);

        var result = service.current(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().jurisdiction()).isEqualTo("SBV");
        assertThat(result.getData().acknowledged()).isTrue();
        assertThat(result.getData().chapters()).hasSize(1);
    }

    @Test
    void acknowledgeRejectsStaleClientVersionAndWritesCurrentVersion() {
        var stale = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v12"));
        assertThat(stale.getCode()).isEqualTo(409);
        assertThat(stale.getMessage()).isEqualTo("RISK_DISCLOSURE_VERSION_CHANGED");

        when(ackMapper.findUserAck(42L, "SBV"))
                .thenReturn(null)
                .thenReturn(ack("v13", "v13", "ACKED"));
        var accepted = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v13"));

        assertThat(accepted.getCode()).isZero();
        assertThat(accepted.getData().acknowledged()).isTrue();
        verify(ackMapper).acknowledge(eq(42L), eq("SBV"), eq("v13"), any(LocalDateTime.class));
    }

    @Test
    void activeGateBlocksStaleAckAndInactiveGatePasses() {
        when(ackMapper.findUserAck(42L, "SBV")).thenReturn(ack("v13", null, "STALE"));

        var blocked = service.checkGate(42L, "withdraw");
        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        verify(ackMapper).incrementBlocked(eq("SBV"), any(LocalDateTime.class));

        var inactive = service.checkGate(42L, "staking");
        assertThat(inactive.getCode()).isZero();
    }

    private static DisclosureAckStatusEntity ack(String required, String acknowledged, String status) {
        DisclosureAckStatusEntity entity = new DisclosureAckStatusEntity();
        entity.setUserId(42L);
        entity.setJurisdictionCode("SBV");
        entity.setRequiredVersion(required);
        entity.setAcknowledgedVersion(acknowledged);
        entity.setAckStatus(status);
        entity.setAcknowledgedAt(LocalDateTime.of(2026, 7, 12, 3, 0));
        return entity;
    }
}
