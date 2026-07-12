package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AppRiskDisclosureView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.dto.AppRiskDisclosureAckRequest;
import ffdd.opsconsole.content.infrastructure.DisclosureAckStatusEntity;
import ffdd.opsconsole.content.mapper.DisclosureAckStatusMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppRiskDisclosureService {
    private final TrustDisclosureRepository repository;
    private final DisclosureAckStatusMapper ackMapper;
    private final Clock clock;

    public ApiResult<AppRiskDisclosureView> current(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(401, "USER_AUTH_REQUIRED");
        DisclosureJurisdictionView jurisdiction = resolveJurisdiction(userId);
        if (jurisdiction == null) return ApiResult.fail(404, "RISK_DISCLOSURE_JURISDICTION_NOT_CONFIGURED");
        DisclosureDraftView disclosure = repository.findDisclosureVersion(jurisdiction.code(), jurisdiction.version()).orElse(null);
        if (disclosure == null || !"published".equalsIgnoreCase(disclosure.status())) {
            return ApiResult.fail(404, "RISK_DISCLOSURE_PUBLISHED_VERSION_NOT_FOUND");
        }
        DisclosureAckStatusEntity ack = ackMapper.findUserAck(userId, jurisdiction.code());
        boolean acknowledged = ack != null && "ACKED".equalsIgnoreCase(ack.getAckStatus())
                && disclosure.version().equals(ack.getAcknowledgedVersion())
                && disclosure.version().equals(ack.getRequiredVersion());
        return ApiResult.ok(new AppRiskDisclosureView(
                jurisdiction.code(), jurisdiction.name(), disclosure.version(), disclosure.languageScope(),
                disclosure.effectiveDate(), acknowledged, acknowledged ? ack.getAcknowledgedAt() : null,
                repository.listChapters(jurisdiction.code(), disclosure.version())));
    }

    @Transactional
    public ApiResult<AppRiskDisclosureView> acknowledge(Long userId, AppRiskDisclosureAckRequest request) {
        ApiResult<AppRiskDisclosureView> current = current(userId);
        if (current.getCode() != 0) return current;
        AppRiskDisclosureView disclosure = current.getData();
        if (request == null || !StringUtils.hasText(request.jurisdiction()) || !StringUtils.hasText(request.version())
                || !disclosure.jurisdiction().equalsIgnoreCase(request.jurisdiction().trim())
                || !disclosure.version().equals(request.version().trim())) {
            return ApiResult.fail(409, "RISK_DISCLOSURE_VERSION_CHANGED");
        }
        ackMapper.acknowledge(userId, disclosure.jurisdiction(), disclosure.version(), now());
        return current(userId);
    }

    @Transactional
    public ApiResult<Void> checkGate(Long userId, String actionKey) {
        if (!StringUtils.hasText(actionKey)) return ApiResult.fail(422, "DISCLOSURE_GATE_ACTION_REQUIRED");
        DisclosureGateActionView action = repository.listGateActions().stream()
                .filter(row -> row.key().equalsIgnoreCase(actionKey.trim()))
                .findFirst().orElse(null);
        if (action == null) return ApiResult.fail(404, "DISCLOSURE_GATE_ACTION_NOT_FOUND");
        if (!action.active()) return ApiResult.ok(null);
        ApiResult<AppRiskDisclosureView> current = current(userId);
        if (current.getCode() != 0) return ApiResult.fail(current.getCode(), current.getMessage());
        if (!current.getData().acknowledged()) {
            ackMapper.incrementBlocked(current.getData().jurisdiction(), now());
            return ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED");
        }
        return ApiResult.ok(null);
    }

    private DisclosureJurisdictionView resolveJurisdiction(Long userId) {
        String country = ackMapper.findUserCountryCode(userId);
        if (!StringUtils.hasText(country)) return null;
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        return repository.listJurisdictions().stream()
                .filter(row -> "published".equalsIgnoreCase(row.status()) && row.countryCodes().contains(normalized))
                .findFirst().orElse(null);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
