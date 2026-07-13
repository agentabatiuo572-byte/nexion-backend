package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AppRiskDisclosureView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.dto.AppRiskDisclosureAckRequest;
import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.content.infrastructure.DisclosureAckStatusEntity;
import ffdd.opsconsole.content.mapper.DisclosureAckStatusMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppRiskDisclosureService implements RiskDisclosureGateFacade {
    private static final int READ_TOKEN_BYTES = 32;
    private static final int MAX_BUSINESS_FLOW_ID_LENGTH = 128;

    private final TrustDisclosureRepository repository;
    private final DisclosureAckStatusMapper ackMapper;
    private final Clock clock;
    private final RiskDisclosureAckProperties ackProperties;
    private final AuditLogService auditLogService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiResult<AppRiskDisclosureView> current(Long userId) {
        return loadCurrent(userId, null, true);
    }

    /**
     * Reserved for a trusted edge integration. Controllers must not pass a client-provided country header here.
     * Verified KYC remains authoritative; trusted IP is only a fallback when KYC has no approved country.
     */
    ApiResult<AppRiskDisclosureView> currentWithTrustedIpCountry(Long userId, String trustedIpCountry) {
        return loadCurrent(userId, trustedIpCountry, true);
    }

    @Transactional
    public ApiResult<AppRiskDisclosureView> acknowledge(Long userId, AppRiskDisclosureAckRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            return ApiResult.fail(422, "RISK_DISCLOSURE_CONFIRMATION_REQUIRED");
        }
        if (!StringUtils.hasText(request.acknowledgmentToken())) {
            return ApiResult.fail(422, "RISK_DISCLOSURE_READ_TOKEN_REQUIRED");
        }
        ApiResult<AppRiskDisclosureView> current = loadCurrent(userId, null, false);
        if (current.getCode() != 0) return current;
        AppRiskDisclosureView disclosure = current.getData();
        if (!StringUtils.hasText(request.jurisdiction()) || !StringUtils.hasText(request.version())
                || !disclosure.jurisdiction().equalsIgnoreCase(request.jurisdiction().trim())
                || !disclosure.version().equals(request.version().trim())) {
            return ApiResult.fail(409, "RISK_DISCLOSURE_VERSION_CHANGED");
        }
        String tokenHash = hashToken(request.acknowledgmentToken().trim());
        LocalDateTime consumedAt = now();
        if (ackMapper.consumeReadToken(tokenHash, userId, disclosure.jurisdiction(), disclosure.version(),
                consumedAt, consumedAt.minusSeconds(ackProperties.boundedMinReadSeconds())) != 1) {
            return ApiResult.fail(409, "RISK_DISCLOSURE_READ_TOKEN_INVALID");
        }
        ackMapper.acknowledge(userId, disclosure.jurisdiction(), disclosure.version(), now());
        auditAcknowledgment(userId, disclosure);
        return loadCurrent(userId, null, false);
    }

    @Transactional
    public ApiResult<Void> checkGate(Long userId, String actionKey) {
        return checkUserGate(userId, actionKey, null);
    }

    @Transactional
    public ApiResult<Void> checkGate(Long userId, String actionKey, String businessFlowId) {
        return checkUserGate(userId, actionKey, businessFlowId);
    }

    @Override
    @Transactional
    public ApiResult<Void> checkUserGate(Long userId, String actionKey, String businessFlowId) {
        if (!StringUtils.hasText(actionKey)) return ApiResult.fail(422, "DISCLOSURE_GATE_ACTION_REQUIRED");
        if (StringUtils.hasText(businessFlowId) && businessFlowId.trim().length() > MAX_BUSINESS_FLOW_ID_LENGTH) {
            return ApiResult.fail(422, "DISCLOSURE_GATE_BUSINESS_FLOW_ID_INVALID");
        }
        DisclosureGateActionView action = repository.listGateActions().stream()
                .filter(row -> row.key().equalsIgnoreCase(actionKey.trim()))
                .findFirst().orElse(null);
        if (action == null) return ApiResult.fail(404, "DISCLOSURE_GATE_ACTION_NOT_FOUND");
        if (!action.active()) return ApiResult.ok(null);
        ApiResult<AppRiskDisclosureView> current = loadCurrent(userId, null, false);
        if (current.getCode() != 0) return ApiResult.fail(current.getCode(), current.getMessage());
        if (!current.getData().acknowledged()) {
            recordBlockedOnce(userId, current.getData().jurisdiction(), action.key(), businessFlowId);
            return ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED");
        }
        return ApiResult.ok(null);
    }

    private ApiResult<AppRiskDisclosureView> loadCurrent(Long userId, String trustedIpCountry, boolean issueReadToken) {
        if (userId == null || userId <= 0) return ApiResult.fail(401, "USER_AUTH_REQUIRED");
        JurisdictionResolution resolution = resolveJurisdiction(userId, trustedIpCountry);
        if (resolution.error() != null) return ApiResult.fail(resolution.error().code(), resolution.error().message());
        DisclosureJurisdictionView jurisdiction = resolution.jurisdiction();
        DisclosureDraftView disclosure = repository.findDisclosureVersion(jurisdiction.code(), jurisdiction.version()).orElse(null);
        if (disclosure == null || !("published".equalsIgnoreCase(disclosure.status())
                || "superseded".equalsIgnoreCase(disclosure.status()))) {
            return ApiResult.fail(404, "RISK_DISCLOSURE_PUBLISHED_VERSION_NOT_FOUND");
        }
        DisclosureAckStatusEntity ack = ackMapper.findUserAck(userId, jurisdiction.code());
        boolean acknowledged = ack != null && "ACKED".equalsIgnoreCase(ack.getAckStatus())
                && disclosure.version().equals(ack.getAcknowledgedVersion())
                && disclosure.version().equals(ack.getRequiredVersion());
        IssuedReadToken token = !acknowledged && issueReadToken
                ? issueReadToken(userId, jurisdiction.code(), disclosure.version()) : null;
        return ApiResult.ok(new AppRiskDisclosureView(
                jurisdiction.code(), jurisdiction.name(), disclosure.version(), disclosure.languageScope(),
                disclosure.effectiveDate(), acknowledged, acknowledged ? ack.getAcknowledgedAt() : null,
                repository.listChapters(jurisdiction.code(), disclosure.version()),
                token == null ? null : token.rawToken(), token == null ? null : token.expiresAt(),
                ackProperties.boundedMinReadSeconds()));
    }

    private JurisdictionResolution resolveJurisdiction(Long userId, String trustedIpCountry) {
        String kycCountry = CountryCodeNormalizer.normalize(ackMapper.findVerifiedKycCountry(userId));
        String ipCountry = CountryCodeNormalizer.normalize(trustedIpCountry);
        String profileCountry = CountryCodeNormalizer.normalize(ackMapper.findUserCountryCode(userId));
        String country = StringUtils.hasText(kycCountry) ? kycCountry
                : StringUtils.hasText(ipCountry) ? ipCountry : profileCountry;
        if (!StringUtils.hasText(country)) {
            return JurisdictionResolution.error(404, "RISK_DISCLOSURE_JURISDICTION_NOT_CONFIGURED");
        }
        var activeJurisdictions = repository.listActiveJurisdictionCatalog().stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> row.code().toUpperCase(java.util.Locale.ROOT), row -> row.name(),
                        (first, ignored) -> first, LinkedHashMap::new));
        List<DisclosureJurisdictionView> matches = repository.listJurisdictions().stream()
                .filter(row -> activeJurisdictions.containsKey(row.code().toUpperCase(java.util.Locale.ROOT)))
                .filter(row -> "published".equalsIgnoreCase(row.status()))
                .filter(row -> row.countryCodes().stream()
                        .map(CountryCodeNormalizer::normalize)
                        .anyMatch(country::equals))
                .map(row -> new DisclosureJurisdictionView(
                        row.code(), activeJurisdictions.get(row.code().toUpperCase(java.util.Locale.ROOT)),
                        row.countryCodes(), row.version(), row.status(), row.publishedAt(), row.affected(),
                        row.ackProgress(), row.blocked()))
                .toList();
        if (matches.isEmpty()) return JurisdictionResolution.error(404, "RISK_DISCLOSURE_JURISDICTION_NOT_CONFIGURED");
        if (matches.size() > 1) return JurisdictionResolution.error(409, "RISK_DISCLOSURE_JURISDICTION_AMBIGUOUS");
        return new JurisdictionResolution(matches.get(0), null);
    }

    private IssuedReadToken issueReadToken(Long userId, String jurisdiction, String version) {
        LocalDateTime issuedAt = now();
        LocalDateTime expiresAt = issuedAt.plusMinutes(ackProperties.boundedTokenTtlMinutes());
        ackMapper.deleteExpiredReadTokens(userId, issuedAt);
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] entropy = new byte[READ_TOKEN_BYTES];
            secureRandom.nextBytes(entropy);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            if (ackMapper.insertReadToken(hashToken(rawToken), userId, jurisdiction, version, expiresAt, issuedAt) > 0) {
                return new IssuedReadToken(rawToken, expiresAt);
            }
        }
        throw new IllegalStateException("RISK_DISCLOSURE_READ_TOKEN_ISSUE_FAILED");
    }

    private void recordBlockedOnce(Long userId, String jurisdiction, String actionKey, String businessFlowId) {
        if (!StringUtils.hasText(businessFlowId)) return;
        String normalizedFlowId = businessFlowId.trim();
        if (ackMapper.recordBlockedIfAbsent(userId, jurisdiction, actionKey, normalizedFlowId, now()) == 1) {
            ackMapper.incrementBlocked(jurisdiction, now());
        }
    }

    private String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void auditAcknowledgment(Long userId, AppRiskDisclosureView disclosure) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("jurisdiction", disclosure.jurisdiction());
        detail.put("version", disclosure.version());
        detail.put("confirmed", true);
        detail.put("minReadSeconds", ackProperties.boundedMinReadSeconds());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("I5_DISCLOSURE_ACKNOWLEDGED")
                .resourceType("DISCLOSURE_ACK")
                .resourceId(userId + ":" + disclosure.jurisdiction())
                .bizNo("i5:ack:" + userId + ":" + disclosure.jurisdiction() + ":" + disclosure.version())
                .userId(userId)
                .actorId(userId)
                .actorType("USER")
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private record IssuedReadToken(String rawToken, LocalDateTime expiresAt) {
    }

    private record ResolutionError(int code, String message) {
    }

    private record JurisdictionResolution(DisclosureJurisdictionView jurisdiction, ResolutionError error) {
        private static JurisdictionResolution error(int code, String message) {
            return new JurisdictionResolution(null, new ResolutionError(code, message));
        }
    }
}
