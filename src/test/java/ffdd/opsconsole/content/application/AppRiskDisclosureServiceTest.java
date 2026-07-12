package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import ffdd.opsconsole.shared.audit.AuditLogService;
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
    private final RiskDisclosureAckProperties ackProperties = new RiskDisclosureAckProperties();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AppRiskDisclosureService service = new AppRiskDisclosureService(
            repository, ackMapper, clock, ackProperties, auditLogService);

    @BeforeEach
    void setUpPublishedVietnamDisclosure() {
        when(ackMapper.findUserCountryCode(42L)).thenReturn("vn");
        when(ackMapper.insertReadToken(anyString(), eq(42L), eq("SBV"), eq("v13"),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
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
        assertThat(result.getData().acknowledgmentToken()).isNull();
    }

    @Test
    void currentIssuesShortLivedReadTokenForUnacknowledgedDisclosure() {
        when(ackMapper.findUserAck(42L, "SBV")).thenReturn(null);

        var result = service.current(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().acknowledgmentToken()).isNotBlank();
        assertThat(result.getData().acknowledgmentTokenExpiresAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 12, 3, 10));
        assertThat(result.getData().minimumReadingSeconds()).isEqualTo(5);
        verify(ackMapper).insertReadToken(anyString(), eq(42L), eq("SBV"), eq("v13"),
                eq(LocalDateTime.of(2026, 7, 12, 3, 10)), eq(LocalDateTime.of(2026, 7, 12, 3, 0)));
        verify(ackMapper).deleteExpiredReadTokens(42L, LocalDateTime.of(2026, 7, 12, 3, 0));
    }

    @Test
    void acknowledgeRequiresBoundOneTimeReadTokenAndWritesCurrentVersion() {
        var unchecked = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v13", "token", false));
        assertThat(unchecked.getCode()).isEqualTo(422);
        assertThat(unchecked.getMessage()).isEqualTo("RISK_DISCLOSURE_CONFIRMATION_REQUIRED");

        var missing = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v13", "", true));
        assertThat(missing.getCode()).isEqualTo(422);
        assertThat(missing.getMessage()).isEqualTo("RISK_DISCLOSURE_READ_TOKEN_REQUIRED");

        var stale = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v12", "token", true));
        assertThat(stale.getCode()).isEqualTo(409);
        assertThat(stale.getMessage()).isEqualTo("RISK_DISCLOSURE_VERSION_CHANGED");

        when(ackMapper.consumeReadToken(anyString(), eq(42L), eq("SBV"), eq("v13"),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1)
                .thenReturn(0);
        when(ackMapper.findUserAck(42L, "SBV"))
                .thenReturn(null)
                .thenReturn(ack("v13", "v13", "ACKED"));
        var accepted = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v13", "token", true));

        assertThat(accepted.getCode()).isZero();
        assertThat(accepted.getData().acknowledged()).isTrue();
        verify(ackMapper).acknowledge(eq(42L), eq("SBV"), eq("v13"), any(LocalDateTime.class));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "I5_DISCLOSURE_ACKNOWLEDGED".equals(audit.getAction())
                        && Long.valueOf(42L).equals(audit.getUserId())
                        && !String.valueOf(audit.getDetail()).contains("token")));
        verify(ackMapper).consumeReadToken(anyString(), eq(42L), eq("SBV"), eq("v13"),
                eq(LocalDateTime.of(2026, 7, 12, 3, 0)),
                eq(LocalDateTime.of(2026, 7, 12, 2, 59, 55)));

        var replay = service.acknowledge(42L, new AppRiskDisclosureAckRequest("SBV", "v13", "token", true));
        assertThat(replay.getCode()).isEqualTo(409);
        assertThat(replay.getMessage()).isEqualTo("RISK_DISCLOSURE_READ_TOKEN_INVALID");
    }

    @Test
    void activeGateBlocksStaleAckAndInactiveGatePasses() {
        when(ackMapper.findUserAck(42L, "SBV")).thenReturn(ack("v13", null, "STALE"));
        when(ackMapper.recordBlockedIfAbsent(eq(42L), eq("SBV"), eq("withdraw"), eq("WD-1001"), any(LocalDateTime.class)))
                .thenReturn(1)
                .thenReturn(0);

        var blocked = service.checkGate(42L, "withdraw", "WD-1001");
        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        verify(ackMapper).incrementBlocked(eq("SBV"), any(LocalDateTime.class));

        var retry = service.checkGate(42L, "withdraw", "WD-1001");
        assertThat(retry.getCode()).isEqualTo(409);
        verify(ackMapper, times(2)).recordBlockedIfAbsent(
                eq(42L), eq("SBV"), eq("withdraw"), eq("WD-1001"), any(LocalDateTime.class));

        var inactive = service.checkGate(42L, "staking");
        assertThat(inactive.getCode()).isZero();
    }

    @Test
    void gateRejectsOversizedBusinessFlowIdWithoutThrowing() {
        var result = service.checkGate(42L, "withdraw", "x".repeat(129));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("DISCLOSURE_GATE_BUSINESS_FLOW_ID_INVALID");
    }

    @Test
    void acknowledgmentFailsClosedWhenRequiredAuditCannotBeWritten() {
        when(ackMapper.findUserAck(42L, "SBV")).thenReturn(null);
        when(ackMapper.consumeReadToken(anyString(), eq(42L), eq("SBV"), eq("v13"),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("AUDIT_REQUIRED_DISABLED"))
                .when(auditLogService).recordRequired(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.acknowledge(
                        42L, new AppRiskDisclosureAckRequest("SBV", "v13", "token", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUDIT_REQUIRED_DISABLED");
        verify(ackMapper).consumeReadToken(anyString(), eq(42L), eq("SBV"), eq("v13"),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(ackMapper).acknowledge(eq(42L), eq("SBV"), eq("v13"), any(LocalDateTime.class));
    }

    @Test
    void jurisdictionResolutionPrefersVerifiedKycAndRejectsOverlappingMappings() {
        when(ackMapper.findVerifiedKycCountry(42L)).thenReturn("Việt Nam");
        when(repository.listJurisdictions()).thenReturn(List.of(
                new DisclosureJurisdictionView("SBV", "越南国家银行", List.of("VN"), "v13", "published", "2026-07-12", 100, 0, 0),
                new DisclosureJurisdictionView("CN-RISK", "中国法域", List.of("CN"), "v8", "published", "2026-07-12", 100, 0, 0)));

        var result = service.currentWithTrustedIpCountry(42L, "86");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().jurisdiction()).isEqualTo("SBV");

        when(repository.listJurisdictions()).thenReturn(List.of(
                new DisclosureJurisdictionView("SBV", "越南国家银行", List.of("VN"), "v13", "published", "2026-07-12", 100, 0, 0),
                new DisclosureJurisdictionView("VN-ALT", "越南替代法域", List.of("VN"), "v9", "published", "2026-07-12", 100, 0, 0)));
        var ambiguous = service.currentWithTrustedIpCountry(42L, "CN");
        assertThat(ambiguous.getCode()).isEqualTo(409);
        assertThat(ambiguous.getMessage()).isEqualTo("RISK_DISCLOSURE_JURISDICTION_AMBIGUOUS");
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
