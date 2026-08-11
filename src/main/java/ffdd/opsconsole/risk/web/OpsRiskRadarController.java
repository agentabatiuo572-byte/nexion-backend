package ffdd.opsconsole.risk.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.risk.application.OpsRiskRadarService;
import ffdd.opsconsole.risk.dto.B5AlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.B5BankRunThresholdRequest;
import ffdd.opsconsole.risk.dto.B5ThresholdPreviewRequest;
import ffdd.opsconsole.risk.dto.B5TriageRequest;
import ffdd.opsconsole.risk.dto.B5SignalStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/risk")
@RequiredArgsConstructor
public class OpsRiskRadarController {
    private static final Map<String, String> TARGET_READ_AUTHORITIES = Map.of(
            "/finance/withdrawals", "finance_d2_read",
            "/risk/multi-account", "risk_k1_read",
            "/risk/abuse", "risk_k2_read",
            "/emergency/kill-switch", "emergency_j1_read",
            "/overview/dual-ledger", "overview_b1_read");
    private final OpsRiskRadarService service;

    @GetMapping("/radar")
    @PreAuthorize("hasAuthority('overview_b5_read')")
    public ApiResult<Map<String, Object>> radar() {
        return service.radar();
    }

    @GetMapping(value = "/radar/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('overview_b5_read')")
    public SseEmitter stream() {
        Map<String, Object> initial = service.radarView();
        // The browser already performs a 30s authoritative refresh. Keep the stream open
        // until the transport actually disconnects instead of manufacturing a 60s outage.
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("radar").data(initial));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    @PostMapping("/bankrun-thresholds/preview")
    @PreAuthorize("hasAnyAuthority('overview_b5_threshold_write','ROLE_SUPER_ADMIN','ROLE_RISK_LEAD')")
    public ApiResult<Map<String, Object>> preview(@RequestBody B5ThresholdPreviewRequest request) {
        return service.preview(request);
    }

    @PutMapping("/bankrun-thresholds")
    @PreAuthorize("hasAnyAuthority('overview_b5_threshold_write','ROLE_SUPER_ADMIN','ROLE_RISK_LEAD')")
    public ApiResult<Map<String, Object>> updateThresholds(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody B5BankRunThresholdRequest request) {
        return service.updateThresholds(idempotencyKey, request);
    }

    @GetMapping("/alert-subscription")
    @PreAuthorize("hasAuthority('overview_b5_subscribe')")
    public ApiResult<Map<String, Object>> subscription() {
        return service.subscription();
    }

    @PutMapping("/alert-subscription")
    @PreAuthorize("hasAuthority('overview_b5_subscribe')")
    public ApiResult<Map<String, Object>> updateSubscription(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody B5AlertSubscriptionRequest request) {
        return service.updateSubscription(idempotencyKey, request);
    }

    @GetMapping("/radar/inbox")
    @PreAuthorize("hasAuthority('overview_b5_read')")
    public ApiResult<List<Map<String, Object>>> inbox() { return service.alertInbox(); }

    @PostMapping("/radar/inbox/{deliveryId}/acknowledge")
    @PreAuthorize("hasAuthority('overview_b5_triage')")
    public ApiResult<Map<String, Object>> acknowledge(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable long deliveryId) {
        return service.acknowledgeAlert(idempotencyKey, deliveryId);
    }

    @PostMapping("/radar/triage")
    @PreAuthorize("hasAuthority('overview_b5_triage')")
    public ApiResult<Map<String, Object>> triage(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody B5TriageRequest request,
            Authentication authentication) {
        String required = request == null ? null : TARGET_READ_AUTHORITIES.get(request.target());
        if (required != null && (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> required.equals(authority.getAuthority())))) {
            throw new AccessDeniedException("B5_TRIAGE_TARGET_READ_REQUIRED");
        }
        return service.triage(idempotencyKey, request);
    }

    @PatchMapping("/radar/signals/{signalNo}/status")
    @PreAuthorize("hasAuthority('overview_b5_triage')")
    public ApiResult<Map<String, Object>> updateSignalStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String signalNo,
            @RequestBody(required = false) B5SignalStatusRequest request) {
        return service.updateSignalStatus(idempotencyKey, signalNo, request);
    }
}
