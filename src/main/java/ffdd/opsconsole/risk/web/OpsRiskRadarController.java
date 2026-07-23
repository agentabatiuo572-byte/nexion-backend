package ffdd.opsconsole.risk.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.risk.application.OpsRiskRadarService;
import ffdd.opsconsole.risk.dto.B5AlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.B5BankRunThresholdRequest;
import ffdd.opsconsole.risk.dto.B5ThresholdPreviewRequest;
import ffdd.opsconsole.risk.dto.B5TriageRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/risk")
@RequiredArgsConstructor
public class OpsRiskRadarController {
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
        SseEmitter emitter = new SseEmitter(60_000L);
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

    @PostMapping("/radar/triage")
    @PreAuthorize("hasAuthority('overview_b5_triage')")
    public ApiResult<Map<String, Object>> triage(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody B5TriageRequest request) {
        return service.triage(idempotencyKey, request);
    }
}
