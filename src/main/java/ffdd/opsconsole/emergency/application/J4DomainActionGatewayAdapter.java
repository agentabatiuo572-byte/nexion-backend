package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.dto.DisclosureChapterInput;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.dto.RiskClusterStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class J4DomainActionGatewayAdapter implements J4DomainActionGateway {
    private static final String C2_PREFIX = "user-freeze:";
    private static final String K1_PREFIX = "cluster-freeze:";
    private static final String I5_PREFIX = "disclosure-publish:";

    private final OpsUserService userService;
    private final UserOpsRepository userRepository;
    private final OpsRiskService riskService;
    private final RiskOpsRepository riskRepository;
    private final OpsTrustDisclosureService trustDisclosureService;
    private final TrustDisclosureRepository trustDisclosureRepository;

    @Override
    public ApiResult<Map<String, Object>> validate(String domain, String reference) {
        String normalizedDomain = text(domain).toUpperCase(Locale.ROOT);
        String ref = text(reference).toLowerCase(Locale.ROOT);
        return switch (normalizedDomain) {
            case "C2" -> validateC2(ref);
            case "K1" -> validateK1(ref);
            case "I5" -> validateI5(ref);
            default -> ApiResult.fail(
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "J4_TARGET_DOMAIN_NOT_SUPPORTED:" + normalizedDomain);
        };
    }

    @Override
    public ApiResult<Map<String, Object>> execute(
            String domain,
            String reference,
            String idempotencyKey,
            String reason,
            String operator) {
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "J4_A2_CONFIRMATION_REQUIRED");
        }
        ApiResult<Map<String, Object>> validation = validate(domain, reference);
        if (validation.getCode() != 0) {
            return validation;
        }
        String normalizedDomain = text(domain).toUpperCase(Locale.ROOT);
        String ref = text(reference).toLowerCase(Locale.ROOT);
        return switch (normalizedDomain) {
            case "C2" -> executeC2(ref, idempotencyKey, reason, operator);
            case "K1" -> executeK1(ref, idempotencyKey, reason, operator);
            case "I5" -> executeI5(ref, idempotencyKey, reason, operator);
            default -> ApiResult.fail(
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "J4_TARGET_DOMAIN_NOT_SUPPORTED:" + normalizedDomain);
        };
    }

    private ApiResult<Map<String, Object>> validateC2(String ref) {
        Long userId = positiveLong(suffix(ref, C2_PREFIX));
        if (userId == null) {
            return validation("J4_C2_USER_REFERENCE_INVALID");
        }
        UserAccountView user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ApiResult.fail(404, "J4_C2_USER_NOT_FOUND");
        }
        if (!"ACTIVE".equalsIgnoreCase(user.status())) {
            return ApiResult.fail(409, "J4_C2_USER_NOT_ACTIVE");
        }
        return ApiResult.ok(linked(
                "domain", "C2",
                "target", String.valueOf(userId),
                "status", "VALIDATED",
                "currentStatus", user.status()));
    }

    private ApiResult<Map<String, Object>> validateK1(String ref) {
        String clusterId = suffix(ref, K1_PREFIX);
        if (!StringUtils.hasText(clusterId)) {
            return validation("J4_K1_CLUSTER_REFERENCE_INVALID");
        }
        RiskOpsRepository.MultiAccountClusterState state =
                riskRepository.multiAccountClusterState(clusterId).orElse(null);
        if (state == null) {
            return ApiResult.fail(404, "J4_K1_CLUSTER_NOT_FOUND");
        }
        if (!"flagged".equalsIgnoreCase(state.status())) {
            return ApiResult.fail(409, "J4_K1_CLUSTER_NOT_FLAGGED");
        }
        return ApiResult.ok(linked(
                "domain", "K1",
                "target", clusterId,
                "status", "VALIDATED",
                "currentStatus", state.status(),
                "expectedVersion", state.version(),
                "affectedAccounts", state.affectedUserIds().size()));
    }

    private ApiResult<Map<String, Object>> validateI5(String ref) {
        DisclosureTarget target = disclosureTarget(ref);
        if (target == null) {
            return validation("J4_I5_DISCLOSURE_REFERENCE_INVALID");
        }
        DisclosureDraftView draft = trustDisclosureRepository
                .findDisclosureVersion(target.jurisdiction(), target.version()).orElse(null);
        if (draft == null) {
            return ApiResult.fail(404, "J4_I5_DISCLOSURE_DRAFT_NOT_FOUND");
        }
        if (!"draft".equalsIgnoreCase(draft.status())) {
            return ApiResult.fail(409, "J4_I5_DISCLOSURE_NOT_DRAFT");
        }
        return ApiResult.ok(linked(
                "domain", "I5",
                "target", target.jurisdiction() + ":" + target.version(),
                "status", "VALIDATED",
                "currentStatus", draft.status(),
                "expectedRevision", draft.revision(),
                "expectedContentHash", draft.contentHash()));
    }

    private ApiResult<Map<String, Object>> executeC2(
            String ref, String idempotencyKey, String reason, String operator) {
        Long userId = positiveLong(suffix(ref, C2_PREFIX));
        ApiResult<UserAccountView> result = userService.updateStatus(
                userId,
                idempotencyKey,
                new UserStatusUpdateRequest("FROZEN", "RISK_HIT", reason, operator));
        if (result.getCode() != 0) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        UserAccountView updated = result.getData();
        return ApiResult.ok(linked(
                "domain", "C2",
                "target", String.valueOf(userId),
                "status", updated == null ? "UNKNOWN" : updated.status(),
                "sessionsRevoked", true,
                "d2PendingWithdrawals", "FROZEN_BY_C2"));
    }

    private ApiResult<Map<String, Object>> executeK1(
            String ref, String idempotencyKey, String reason, String operator) {
        String clusterId = suffix(ref, K1_PREFIX);
        RiskOpsRepository.MultiAccountClusterState before =
                riskRepository.multiAccountClusterState(clusterId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "J4_K1_CLUSTER_NOT_FOUND");
        }
        ApiResult<Map<String, Object>> result = riskService.updateMultiAccountClusterStatus(
                clusterId,
                idempotencyKey,
                new RiskClusterStatusRequest("frozen", reason, operator, before.version()));
        if (result.getCode() != 0) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        RiskOpsRepository.MultiAccountClusterState after =
                riskRepository.multiAccountClusterState(clusterId).orElse(null);
        return ApiResult.ok(linked(
                "domain", "K1",
                "target", clusterId,
                "status", after == null ? "UNKNOWN" : after.status(),
                "beforeVersion", before.version(),
                "afterVersion", after == null ? before.version() : after.version(),
                "affectedAccounts", before.affectedUserIds().size(),
                "d2PendingWithdrawals", "FROZEN_BY_K1"));
    }

    private ApiResult<Map<String, Object>> executeI5(
            String ref, String idempotencyKey, String reason, String operator) {
        DisclosureTarget target = disclosureTarget(ref);
        if (target == null) {
            return validation("J4_I5_DISCLOSURE_REFERENCE_INVALID");
        }
        DisclosureDraftView draft = trustDisclosureRepository
                .findDisclosureVersion(target.jurisdiction(), target.version()).orElse(null);
        if (draft == null) {
            return ApiResult.fail(404, "J4_I5_DISCLOSURE_DRAFT_NOT_FOUND");
        }
        List<DisclosureChapterInput> chapters = trustDisclosureRepository
                .listChapters(target.jurisdiction(), target.version()).stream()
                .map(this::chapterInput)
                .toList();
        DisclosureDraftRequest request = new DisclosureDraftRequest(
                draft.version(),
                draft.jurisdiction(),
                draft.languageScope(),
                draft.effectiveDate(),
                true,
                draft.zh(),
                draft.vi(),
                draft.en(),
                chapters,
                draft.revision(),
                draft.contentHash(),
                operator,
                reason);
        ApiResult<DisclosureDraftView> result = trustDisclosureService.publishDisclosure(
                target.jurisdiction(), idempotencyKey, request);
        if (result.getCode() != 0) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        DisclosureDraftView published = result.getData();
        return ApiResult.ok(linked(
                "domain", "I5",
                "target", target.jurisdiction() + ":" + target.version(),
                "status", published == null ? "UNKNOWN" : published.status(),
                "revision", published == null ? draft.revision() : published.revision(),
                "contentHash", published == null ? draft.contentHash() : published.contentHash()));
    }

    private DisclosureChapterInput chapterInput(DisclosureChapterView chapter) {
        return new DisclosureChapterInput(
                chapter.no(),
                chapter.zh(),
                chapter.vi(),
                chapter.en(),
                chapter.zhBody(),
                chapter.viBody(),
                chapter.enBody());
    }

    private DisclosureTarget disclosureTarget(String ref) {
        String raw = suffix(ref, I5_PREFIX);
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator == raw.length() - 1) {
            return null;
        }
        String jurisdiction = raw.substring(0, separator).trim().toUpperCase(Locale.ROOT);
        String version = raw.substring(separator + 1).trim();
        if (!jurisdiction.matches("^[A-Z0-9_-]{2,32}$")
                || !version.matches("^[a-z0-9._-]{1,32}$")) {
            return null;
        }
        return new DisclosureTarget(jurisdiction, version);
    }

    private String suffix(String ref, String prefix) {
        return ref != null && ref.startsWith(prefix) ? ref.substring(prefix.length()).trim() : "";
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private ApiResult<Map<String, Object>> validation(String message) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record DisclosureTarget(String jurisdiction, String version) {
    }
}
