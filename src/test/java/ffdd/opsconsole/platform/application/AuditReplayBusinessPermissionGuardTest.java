package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.application.DisclosureContentHash;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AuditReplayBusinessPermissionGuardTest {
    private final TrustDisclosureRepository repository = mock(TrustDisclosureRepository.class);
    private final AuditReplayBusinessPermissionGuard guard = new AuditReplayBusinessPermissionGuard(repository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void standardSectionProposalRejectsUserWithoutStandardPublishPermission() {
        when(repository.listTrustSections()).thenReturn(List.of(
                new TrustSectionView("leadership", "团队", "卡片", "v1", "published", "today", "内容", false)));
        authenticate("content_i4_trust_section_manage");

        var result = guard.validateProposal(sectionCommand("leadership", "publish"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).endsWith("content_i4_publish_standard");
    }

    @Test
    void sensitiveSectionProposalRejectsUserWithOnlyStandardPublishPermission() {
        when(repository.listTrustSections()).thenReturn(List.of(
                new TrustSectionView("financials", "财务", "指标", "v1", "published", "today", "合规", true)));
        authenticate("content_i4_publish_standard");

        var result = guard.validateProposal(sectionCommand("financials", "rollback"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).endsWith("content_i4_trust_section_manage");
    }

    @Test
    void databaseHighSensitivityAlsoRequiresSensitivePermission() {
        when(repository.listTrustSections()).thenReturn(List.of(
                new TrustSectionView("customRisk", "高敏自定义版块", "指标", "v1", "published", "today", "合规", true)));
        authenticate("content_i4_publish_standard");

        assertThat(guard.validateProposal(sectionCommand("customRisk", "archive")).getCode()).isEqualTo(403);
    }

    @Test
    void matchingBusinessPermissionAllowsProposal() {
        when(repository.listTrustSections()).thenReturn(List.of());
        authenticate("content_i4_trust_section_manage", "content_i5_disclosure_publish", "content_i5_gate_adjust");
        DisclosureDraftView unhashed = new DisclosureDraftView(
                "v13", "SFC", "zh+vi+en", "2026-07-13", true,
                "中文", "Tiếng Việt", "English", "draft", 2L, "");
        String hash = DisclosureContentHash.from(unhashed, List.of());
        DisclosureDraftView draft = new DisclosureDraftView(
                unhashed.version(), unhashed.jurisdiction(), unhashed.languageScope(), unhashed.effectiveDate(),
                unhashed.requiresReack(), unhashed.zh(), unhashed.vi(), unhashed.en(), "draft", 2L, hash);
        when(repository.findDisclosureVersion("SFC", "v13")).thenReturn(Optional.of(draft));
        when(repository.listChapters("SFC", "v13")).thenReturn(List.of());

        assertThat(guard.validateProposal(sectionCommand("nexNarrative", "publish")).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand("I", "i5_disclosure_publish", Map.of(
                "jurisdiction", "SFC", "version", "v13",
                "expectedRevision", 2L, "expectedContentHash", hash))).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand("I", "i5_matrix_configure", Map.of())).getCode()).isZero();
        assertThat(guard.validateProposal(new AuditReplayCommand("I", "i5_gate_adjust", Map.of())).getCode()).isZero();
    }

    @Test
    void disclosureProposalRejectsChangedImmutableSnapshot() {
        authenticate("content_i5_disclosure_publish");
        DisclosureDraftView draft = new DisclosureDraftView(
                "v13", "SFC", "zh+vi+en", "2026-07-13", true,
                "中文", "Tiếng Việt", "English", "draft", 3L, "server-hash");
        when(repository.findDisclosureVersion("SFC", "v13")).thenReturn(Optional.of(draft));
        when(repository.listChapters("SFC", "v13")).thenReturn(List.of());

        var result = guard.validateProposal(new AuditReplayCommand("I", "i5_disclosure_publish", Map.of(
                "jurisdiction", "SFC", "version", "v13",
                "expectedRevision", 2L, "expectedContentHash", "client-hash")));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("A2_DISCLOSURE_SNAPSHOT_CHANGED");
    }

    private AuditReplayCommand sectionCommand(String sectionKey, String action) {
        return new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                "sectionKey", sectionKey, "action", action));
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "proposer", "n/a", java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
