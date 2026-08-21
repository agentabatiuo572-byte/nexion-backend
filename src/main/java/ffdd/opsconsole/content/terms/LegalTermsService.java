package ffdd.opsconsole.content.terms;

import ffdd.opsconsole.content.terms.domain.LegalTermsCurrentView;
import ffdd.opsconsole.content.terms.domain.LegalTermsRepository;
import ffdd.opsconsole.content.terms.domain.LegalTermsSection;
import ffdd.opsconsole.content.terms.domain.LegalTermsVersionView;
import ffdd.opsconsole.content.terms.dto.LegalTermsAckRequest;
import ffdd.opsconsole.content.terms.dto.LegalTermsDraftRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Server-authoritative, versioned Terms of Service. */
@Service
public class LegalTermsService {
    private final LegalTermsRepository repository;
    private final Clock clock;
    private final Environment environment;
    private final AuditLogService audit;

    public LegalTermsService(LegalTermsRepository repository, Clock clock, Environment environment, AuditLogService audit) {
        this.repository = repository;
        this.clock = clock;
        this.environment = environment;
        this.audit = audit;
    }

    public ApiResult<LegalTermsCurrentView> current(String locale, String jurisdiction, Long userId) {
        try {
            String requestedLocale = normalizeLocale(locale);
            String requestedJurisdiction = normalizeJurisdiction(jurisdiction);
            Resolution resolved = resolve(requestedLocale, requestedJurisdiction);
            if (resolved.version == null || !valid(resolved.version)) return unavailable();
            UserAuthEnvironment env = sourceEnvironment();
            String runId = env == UserAuthEnvironment.SANDBOX ? acceptanceRunId() : "";
            boolean acknowledged = false;
            LocalDateTime acknowledgedAt = null;
            if (userId != null && userId > 0) {
                Optional<LegalTermsRepository.LegalTermsAcknowledgement> ack = repository.findAck(
                        userId, env.name(), runId, resolved.locale, resolved.jurisdiction);
                acknowledged = ack.isPresent() && resolved.version.version().equals(ack.get().version());
                acknowledgedAt = acknowledged ? ack.get().acknowledgedAt() : null;
            }
            return ApiResult.ok(new LegalTermsCurrentView(
                    "server", env == UserAuthEnvironment.SANDBOX ? "SANDBOX" : "PRODUCTION", runId,
                    requestedLocale, resolved.locale, requestedJurisdiction, resolved.jurisdiction,
                    resolved.provenance, resolved.version.version(), resolved.version.effectiveAt(),
                    resolved.version.title(), resolved.version.summary(), resolved.version.sections(),
                    acknowledged, acknowledgedAt));
        } catch (Exception ex) {
            return unavailable();
        }
    }

    @Transactional
    public ApiResult<LegalTermsCurrentView> acknowledge(Long userId, LegalTermsAckRequest request) {
        if (userId == null || userId <= 0) return ApiResult.fail(401, "USER_AUTH_REQUIRED");
        if (request == null || !Boolean.TRUE.equals(request.confirmed())
                || !StringUtils.hasText(request.version()) || !StringUtils.hasText(request.idempotencyKey()))
            return ApiResult.fail(422, "LEGAL_TERMS_CONFIRMATION_REQUIRED");
        UserAuthEnvironment env;
        try { env = sourceEnvironment(); } catch (Exception ex) { return ApiResult.fail(503, "LEGAL_TERMS_ENVIRONMENT_UNAVAILABLE"); }
        String expectedRun = env == UserAuthEnvironment.SANDBOX ? acceptanceRunId() : "";
        if (!expectedRun.equals(request.runId() == null ? "" : request.runId().trim()))
            return ApiResult.fail(409, "LEGAL_TERMS_RUN_SCOPE_INVALID");
        String locale = normalizeLocale(request.locale());
        String jurisdiction = normalizeJurisdiction(request.jurisdiction());
        Resolution resolved;
        try { resolved = resolve(locale, jurisdiction); } catch (Exception ex) { return unavailable(); }
        if (resolved.version == null || !valid(resolved.version)) return unavailable();
        if (!resolved.version.version().equals(request.version().trim())
                || !resolved.locale.equals(locale) || !resolved.jurisdiction.equals(jurisdiction))
            return ApiResult.fail(409, "LEGAL_TERMS_VERSION_CHANGED");
        try {
            Optional<LegalTermsRepository.LegalTermsAcknowledgement> existing = repository.findAck(
                    userId, env.name(), expectedRun, resolved.locale, resolved.jurisdiction);
            if (existing.isPresent() && resolved.version.version().equals(existing.get().version())) {
                if (request.idempotencyKey().trim().equals(existing.get().idempotencyKey())) {
                    return current(locale, jurisdiction, userId);
                }
                // A receipt for this exact published version is immutable. A
                // different key is not a retry; accepting it would overwrite
                // the idempotency fence and make concurrent callers observe a
                // different acknowledgement identity.
                return ApiResult.fail(409, "LEGAL_TERMS_ACK_IDEMPOTENCY_CONFLICT");
            }
            if (audit == null) return ApiResult.fail(503, "LEGAL_TERMS_AUDIT_UNAVAILABLE");
            repository.saveAck(userId, env.name(), expectedRun, resolved.locale, resolved.jurisdiction,
                    resolved.version.version(), request.idempotencyKey().trim(), now());
            if (audit != null) audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("LEGAL_TERMS_ACKNOWLEDGED").resourceType("LEGAL_TERMS_ACK")
                    .resourceId(userId + ":" + resolved.jurisdiction).userId(userId).actorId(userId)
                    .actorType("USER").result("SUCCESS").riskLevel("MEDIUM")
                    .detail(java.util.Map.of("locale", resolved.locale, "jurisdiction", resolved.jurisdiction,
                            "version", resolved.version.version())).build());
            return current(locale, jurisdiction, userId);
        } catch (IllegalStateException ex) {
            return ApiResult.fail(409, ex.getMessage());
        } catch (Exception ex) {
            return unavailable();
        }
    }

    public ApiResult<List<LegalTermsVersionView>> adminList(String locale, String jurisdiction) {
        try { return ApiResult.ok(repository.list(normalizeLocale(locale), normalizeJurisdiction(jurisdiction))); }
        catch (Exception ex) { return ApiResult.fail(503, "LEGAL_TERMS_UNAVAILABLE"); }
    }

    @Transactional
    public ApiResult<LegalTermsVersionView> saveDraft(LegalTermsDraftRequest request) {
        String invalid = validateDraft(request);
        if (invalid != null) return ApiResult.fail(422, invalid);
        try {
            long actual = repository.currentRevision(request.locale(), request.jurisdiction(), request.version());
            if (request.expectedRevision() == null || actual != request.expectedRevision())
                return ApiResult.fail(409, "LEGAL_TERMS_VERSION_CONFLICT");
            LegalTermsVersionView saved = repository.saveDraft(request, actual, operator(), now());
            auditAdmin("LEGAL_TERMS_DRAFT_SAVED", saved, request.reason());
            return ApiResult.ok(saved);
        } catch (Exception ex) { return ApiResult.fail(422, "LEGAL_TERMS_CONTENT_INVALID"); }
    }

    @Transactional
    public ApiResult<LegalTermsVersionView> publish(String locale, String jurisdiction, String version, long expectedRevision, String reason) {
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8) return ApiResult.fail(422, "LEGAL_TERMS_REASON_REQUIRED");
        try { LegalTermsVersionView saved = repository.publish(normalizeLocale(locale), normalizeJurisdiction(jurisdiction), version.trim(), expectedRevision, operator(), now()); auditAdmin("LEGAL_TERMS_PUBLISHED", saved, reason); return ApiResult.ok(saved); }
        catch (org.springframework.dao.OptimisticLockingFailureException ex) { return ApiResult.fail(409, "LEGAL_TERMS_VERSION_CONFLICT"); }
        catch (Exception ex) { return ApiResult.fail(422, "LEGAL_TERMS_PUBLISH_INVALID"); }
    }

    @Transactional
    public ApiResult<LegalTermsVersionView> revoke(String locale, String jurisdiction, String version, long expectedRevision, String reason) {
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8) return ApiResult.fail(422, "LEGAL_TERMS_REASON_REQUIRED");
        try { LegalTermsVersionView saved = repository.revoke(normalizeLocale(locale), normalizeJurisdiction(jurisdiction), version.trim(), expectedRevision, operator(), now()); auditAdmin("LEGAL_TERMS_REVOKED", saved, reason); return ApiResult.ok(saved); }
        catch (org.springframework.dao.OptimisticLockingFailureException ex) { return ApiResult.fail(409, "LEGAL_TERMS_VERSION_CONFLICT"); }
        catch (Exception ex) { return ApiResult.fail(422, "LEGAL_TERMS_REVOKE_INVALID"); }
    }

    private Resolution resolve(String locale, String jurisdiction) {
        List<String> locales = new ArrayList<>(List.of(locale));
        int dash = locale.indexOf('-');
        if (dash > 0) locales.add(locale.substring(0, dash));
        if (!locales.contains("en")) locales.add("en");
        List<String> jurisdictions = new ArrayList<>(List.of(jurisdiction));
        if (!"GLOBAL".equals(jurisdiction)) jurisdictions.add("GLOBAL");
        for (String l : locales) for (String j : jurisdictions) {
            Optional<LegalTermsVersionView> row = repository.findPublished(l, j);
            if (row.isPresent()) return new Resolution(l, j, row.get(),
                    l.equals(locale) && j.equals(jurisdiction) ? "exact" : "fallback:" + l + "/" + j);
        }
        return new Resolution(locale, jurisdiction, null, "unresolved");
    }

    private String validateDraft(LegalTermsDraftRequest r) {
        if (r == null || !StringUtils.hasText(r.locale()) || !r.locale().matches("[a-z]{2}(?:-[a-z0-9]{2,8})?")
                || !StringUtils.hasText(r.jurisdiction()) || !r.jurisdiction().matches("[A-Z][A-Z0-9_-]{1,15}")
                || !StringUtils.hasText(r.version()) || !r.version().matches("v[1-9][0-9]{0,8}(?:\\.[0-9]{1,8})*")
                || r.effectiveAt() == null || !StringUtils.hasText(r.title()) || !StringUtils.hasText(r.summary())
                || r.title().length() > 200 || r.summary().length() > 2000
                || r.sections() == null || r.sections().isEmpty() || r.sections().size() > 50
                || r.expectedRevision() == null || r.expectedRevision() < 0
                || !StringUtils.hasText(r.reason()) || r.reason().trim().length() < 8) return "LEGAL_TERMS_CONTENT_INVALID";
        if (r.sections().stream().anyMatch(s -> s == null || !StringUtils.hasText(s.key())
                || !StringUtils.hasText(s.title()) || !StringUtils.hasText(s.body())
                || s.key().length() > 64 || s.title().length() > 200 || s.body().length() > 10000
                || s.sortOrder() < 0)) return "LEGAL_TERMS_CONTENT_INVALID";
        return null;
    }

    private boolean valid(LegalTermsVersionView v) {
        return "PUBLISHED".equalsIgnoreCase(v.status()) && StringUtils.hasText(v.version())
                && v.effectiveAt() != null && StringUtils.hasText(v.title())
                && v.sections() != null && !v.sections().isEmpty()
                && v.sections().stream().allMatch(s -> s != null && StringUtils.hasText(s.key())
                        && StringUtils.hasText(s.title()) && StringUtils.hasText(s.body()));
    }
    private String normalizeLocale(String s) {
        if (!StringUtils.hasText(s)) return "en";
        String[] parts = s.trim().replace('_', '-').split("-");
        parts[0] = parts[0].toLowerCase(Locale.ROOT);
        for (int i = 1; i < parts.length; i++) parts[i] = parts[i].toUpperCase(Locale.ROOT);
        return String.join("-", parts);
    }
    private String normalizeJurisdiction(String s) { return StringUtils.hasText(s) ? s.trim().toUpperCase(Locale.ROOT) : "GLOBAL"; }
    private UserAuthEnvironment sourceEnvironment() { return UserAuthEnvironment.resolve(environment).orElseThrow(() -> new IllegalStateException("LEGAL_TERMS_ENVIRONMENT_UNAVAILABLE")); }
    private String acceptanceRunId() {
        String configured = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "");
        // Dev is a real Java runtime profile even when no acceptance batch is
        // configured. Keep the retained SANDBOX provenance internally
        // consistent so clients can distinguish it from production without
        // weakening their response validation.
        return StringUtils.hasText(configured) ? configured.trim() : "dev";
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private String operator() { return AdminActorResolver.resolve("system"); }
    private void auditAdmin(String action, LegalTermsVersionView row, String reason) {
        if (audit == null) throw new IllegalStateException("LEGAL_TERMS_AUDIT_UNAVAILABLE");
        if (row == null) throw new IllegalStateException("LEGAL_TERMS_VERSION_NOT_FOUND");
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("LEGAL_TERMS_VERSION")
                .resourceId(row.locale() + ":" + row.jurisdiction() + ":" + row.version())
                .result("SUCCESS").riskLevel("HIGH")
                .detail(java.util.Map.of("locale", row.locale(), "jurisdiction", row.jurisdiction(),
                        "version", row.version(), "revision", row.revision(), "reason", reason == null ? "" : reason.trim())).build());
    }
    private <T> ApiResult<T> unavailable() { return ApiResult.fail(503, "LEGAL_TERMS_UNAVAILABLE"); }
    private record Resolution(String locale, String jurisdiction, LegalTermsVersionView version, String provenance) { }
}
