package ffdd.opsconsole.content.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.terms.domain.LegalTermsRepository;
import ffdd.opsconsole.content.terms.domain.LegalTermsSection;
import ffdd.opsconsole.content.terms.domain.LegalTermsCurrentView;
import ffdd.opsconsole.content.terms.domain.LegalTermsVersionView;
import ffdd.opsconsole.content.terms.dto.LegalTermsAckRequest;
import ffdd.opsconsole.content.terms.dto.LegalTermsDraftRequest;
import ffdd.opsconsole.content.terms.web.LegalTermsController;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;

class LegalTermsServiceTest {
    @Mock LegalTermsRepository repository;
    @Mock AuditLogService audit;
    private LegalTermsService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        MockEnvironment environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-1");
        service = new LegalTermsService(repository, clock, environment, audit);
    }

    @Test
    void resolvesExactLocaleAndJurisdictionBeforeBaseAndGlobalFallback() {
        LegalTermsVersionView exact = version("zh-CN", "CN", "v2");
        LegalTermsVersionView base = version("zh", "CN", "v1");
        LegalTermsVersionView global = version("en", "GLOBAL", "v0");
        when(repository.findPublished(eq("zh-CN"), eq("CN"))).thenReturn(java.util.Optional.of(exact));
        when(repository.findPublished(eq("zh"), eq("CN"))).thenReturn(java.util.Optional.of(base));
        when(repository.findPublished(eq("en"), eq("GLOBAL"))).thenReturn(java.util.Optional.of(global));
        ApiResult<?> result = service.current("zh-CN", "CN", null);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().toString()).contains("zh-CN", "CN", "v2");
    }

    @Test
    void failsClosedWhenPublishedContentIsMissingOrMalformed() {
        when(repository.findPublished(any(), any())).thenReturn(java.util.Optional.empty());
        assertThat(service.current("en", "VN", null).getMessage()).isEqualTo("LEGAL_TERMS_UNAVAILABLE");
    }

    @Test
    void localeFallbackPrecedesJurisdictionFallbackWithinEachLocaleTier() {
        LegalTermsVersionView exactLocaleGlobal = version("zh-CN", "GLOBAL", "v2");
        LegalTermsVersionView baseLocaleExactJurisdiction = version("zh", "CN", "v1");
        when(repository.findPublished("zh-CN", "CN")).thenReturn(java.util.Optional.empty());
        when(repository.findPublished("zh-CN", "GLOBAL")).thenReturn(java.util.Optional.of(exactLocaleGlobal));
        when(repository.findPublished("zh", "CN")).thenReturn(java.util.Optional.of(baseLocaleExactJurisdiction));
        ApiResult<?> result = service.current("zh-CN", "CN", null);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().toString()).contains("resolvedLocale=zh-CN", "resolvedJurisdiction=GLOBAL", "version=v2");
    }

    @Test
    void staleRevisionIsRejectedBeforeDraftWrite() {
        LegalTermsDraftRequest request = new LegalTermsDraftRequest(
                "en", "VN", "v3", LocalDateTime.parse("2026-08-18T00:00:00"),
                "Terms", "Summary", List.of(new LegalTermsSection("eligibility", "Eligibility", "18+", 10)), 4L, "legal review update");
        when(repository.currentRevision("en", "VN", "v3")).thenReturn(5L);
        assertThat(service.saveDraft(request).getMessage()).isEqualTo("LEGAL_TERMS_VERSION_CONFLICT");
    }

    @Test
    void acknowledgmentIsIdempotentAndMustMatchCurrentVersionAndRun() {
        LegalTermsVersionView current = version("en", "VN", "v4");
        when(repository.findPublished("en", "VN")).thenReturn(java.util.Optional.of(current));
        when(repository.findAck(eq(42L), eq("PRODUCTION"), eq(""), eq("en"), eq("VN")))
                .thenReturn(java.util.Optional.empty());
        LegalTermsAckRequest request = new LegalTermsAckRequest("en", "VN", "v4", true, "idem-1", "");
        assertThat(service.acknowledge(42L, request).getCode()).isZero();
    }

    @Test
    void duplicateAcknowledgementWithSameKeyDoesNotWriteAgain() {
        LegalTermsVersionView current = version("en", "VN", "v4");
        when(repository.findPublished("en", "VN")).thenReturn(java.util.Optional.of(current));
        when(repository.findAck(42L, "PRODUCTION", "", "en", "VN"))
                .thenReturn(java.util.Optional.of(new LegalTermsRepository.LegalTermsAcknowledgement(
                        "v4", LocalDateTime.parse("2026-08-17T00:00:00"), "idem-1")));
        ApiResult<?> result = service.acknowledge(42L,
                new LegalTermsAckRequest("en", "VN", "v4", true, "idem-1", ""));
        assertThat(result.getCode()).isZero();
        verify(repository, never()).saveAck(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateAcknowledgementWithDifferentKeyCannotReplaceTheExistingReceipt() {
        LegalTermsVersionView current = version("en", "VN", "v4");
        when(repository.findPublished("en", "VN")).thenReturn(java.util.Optional.of(current));
        when(repository.findAck(42L, "PRODUCTION", "", "en", "VN"))
                .thenReturn(java.util.Optional.of(new LegalTermsRepository.LegalTermsAcknowledgement(
                        "v4", LocalDateTime.parse("2026-08-17T00:00:00"), "idem-1")));
        ApiResult<?> result = service.acknowledge(42L,
                new LegalTermsAckRequest("en", "VN", "v4", true, "idem-2", ""));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("LEGAL_TERMS_ACK_IDEMPOTENCY_CONFLICT");
        verify(repository, never()).saveAck(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void newPublishedVersionMakesPreviousAcknowledgementStale() {
        LegalTermsVersionView current = version("en", "VN", "v5");
        when(repository.findPublished("en", "VN")).thenReturn(java.util.Optional.of(current));
        when(repository.findAck(42L, "PRODUCTION", "", "en", "VN"))
                .thenReturn(java.util.Optional.of(new LegalTermsRepository.LegalTermsAcknowledgement(
                        "v4", LocalDateTime.parse("2026-08-17T00:00:00"), "idem-old")));
        ApiResult<?> result = service.current("en", "VN", 42L);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().toString()).contains("v5").doesNotContain("acknowledged=true");
    }

    @Test
    void acknowledgementScopeSeparatesUsersAndSandboxRunIds() {
        LegalTermsVersionView current = version("en", "VN", "v7");
        when(repository.findPublished("en", "VN")).thenReturn(java.util.Optional.of(current));
        when(repository.findAck(42L, "PRODUCTION", "", "en", "VN"))
                .thenReturn(java.util.Optional.of(new LegalTermsRepository.LegalTermsAcknowledgement(
                        "v7", LocalDateTime.parse("2026-08-17T00:00:00"), "idem-42")));
        when(repository.findAck(43L, "PRODUCTION", "", "en", "VN")).thenReturn(java.util.Optional.empty());
        assertThat(service.current("en", "VN", 42L).getData().toString()).contains("acknowledged=true");
        assertThat(service.current("en", "VN", 43L).getData().toString()).contains("acknowledged=false");

        MockEnvironment sandboxEnvironment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-1");
        sandboxEnvironment.setActiveProfiles("dev");
        LegalTermsService sandbox = new LegalTermsService(repository, clock, sandboxEnvironment, audit);
        assertThat(sandbox.acknowledge(42L,
                new LegalTermsAckRequest("en", "VN", "v7", true, "idem-sandbox", ""))
                .getMessage()).isEqualTo("LEGAL_TERMS_RUN_SCOPE_INVALID");
    }

    @Test
    void devProfileWithoutAnAcceptanceRunIdStillReturnsAConsistentSandboxContract() {
        LegalTermsVersionView current = version("en", "GLOBAL", "v4");
        when(repository.findPublished("en", "GLOBAL")).thenReturn(java.util.Optional.of(current));
        MockEnvironment devEnvironment = new MockEnvironment();
        devEnvironment.setActiveProfiles("dev");
        LegalTermsService devService = new LegalTermsService(repository, clock, devEnvironment, audit);

        ApiResult<LegalTermsCurrentView> result = devService.current("en", "GLOBAL", null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(result.getData().runId()).isEqualTo("dev");
    }

    @Test
    void devDefaultRunIdSupportsAcknowledgementAndAcknowledgedReadback() {
        LegalTermsVersionView current = version("en", "GLOBAL", "v4");
        when(repository.findPublished("en", "GLOBAL")).thenReturn(java.util.Optional.of(current));
        when(repository.findAck(42L, "SANDBOX", "dev", "en", "GLOBAL"))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(new LegalTermsRepository.LegalTermsAcknowledgement(
                        "v4", LocalDateTime.parse("2026-08-17T00:00:00"), "idem-dev")));
        MockEnvironment devEnvironment = new MockEnvironment();
        devEnvironment.setActiveProfiles("dev");
        LegalTermsService devService = new LegalTermsService(repository, clock, devEnvironment, audit);

        ApiResult<LegalTermsCurrentView> result = devService.acknowledge(42L,
                new LegalTermsAckRequest("en", "GLOBAL", "v4", true, "idem-dev", "dev"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(result.getData().runId()).isEqualTo("dev");
        assertThat(result.getData().acknowledged()).isTrue();
        verify(repository).saveAck(42L, "SANDBOX", "dev", "en", "GLOBAL", "v4", "idem-dev",
                LocalDateTime.parse("2026-08-17T00:00:00"));
    }

    @Test
    void prodProfileKeepsTheRunIdEmpty() {
        LegalTermsVersionView current = version("en", "GLOBAL", "v4");
        when(repository.findPublished("en", "GLOBAL")).thenReturn(java.util.Optional.of(current));
        MockEnvironment prodEnvironment = new MockEnvironment();
        prodEnvironment.setActiveProfiles("prod");
        LegalTermsService prodService = new LegalTermsService(repository, clock, prodEnvironment, audit);

        ApiResult<LegalTermsCurrentView> result = prodService.current("en", "GLOBAL", null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
    }

    @Test
    void publishAndRevokeRequireReasonAndUseVersionCas() {
        LegalTermsVersionView draft = version("en", "VN", "v6", "DRAFT");
        LegalTermsVersionView published = version("en", "VN", "v6", "PUBLISHED");
        LegalTermsVersionView revoked = version("en", "VN", "v6", "REVOKED");
        when(repository.publish("en", "VN", "v6", 1L, "system", LocalDateTime.parse("2026-08-17T00:00:00"))).thenReturn(published);
        when(repository.revoke("en", "VN", "v6", 2L, "system", LocalDateTime.parse("2026-08-17T00:00:00"))).thenReturn(revoked);
        when(repository.publish("en", "VN", "v6", 99L, "system", LocalDateTime.parse("2026-08-17T00:00:00")))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("conflict"));
        assertThat(service.publish("en", "VN", "v6", 1L, "legal approves release").getData()).isEqualTo(published);
        assertThat(service.revoke("en", "VN", "v6", 2L, "legal withdraws release").getData()).isEqualTo(revoked);
        assertThat(service.publish("en", "VN", "v6", 99L, "legal approves release").getMessage()).isEqualTo("LEGAL_TERMS_VERSION_CONFLICT");
    }

    @Test
    void adminControllerDeclaresSeparatedReadWritePublishPermissions() throws Exception {
        assertThat(LegalTermsController.class.getMethod("list", String.class, String.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('content_legal_terms_read')");
        assertThat(LegalTermsController.class.getMethod("draft", LegalTermsDraftRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('content_legal_terms_write')");
        assertThat(LegalTermsController.class.getMethod("publish", String.class, String.class, String.class, LegalTermsController.TransitionRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('content_legal_terms_publish')");
    }

    @Test
    void rejectsForeignRunIdAndMalformedSectionContent() {
        assertThat(service.acknowledge(42L,
                new LegalTermsAckRequest("en", "VN", "v4", true, "idem-2", "other-run"))
                .getMessage()).isEqualTo("LEGAL_TERMS_RUN_SCOPE_INVALID");
        LegalTermsDraftRequest malformed = new LegalTermsDraftRequest(
                "en", "VN", "v5", LocalDateTime.now(), "", "summary", List.of(), 0L, "review this terms change");
        assertThat(service.saveDraft(malformed).getMessage()).isEqualTo("LEGAL_TERMS_CONTENT_INVALID");
    }

    private LegalTermsVersionView version(String locale, String jurisdiction, String version) {
        return version(locale, jurisdiction, version, "PUBLISHED");
    }

    private LegalTermsVersionView version(String locale, String jurisdiction, String version, String status) {
        return new LegalTermsVersionView(1L, locale, jurisdiction, version,
                LocalDateTime.parse("2026-08-17T00:00:00"), status, "Terms", "Summary",
                List.of(new LegalTermsSection("eligibility", "Eligibility", "18+", 10)), 1L,
                LocalDateTime.parse("2026-08-17T00:00:00"), null);
    }
}
