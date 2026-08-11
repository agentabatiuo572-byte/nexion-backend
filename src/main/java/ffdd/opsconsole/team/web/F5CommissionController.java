package ffdd.opsconsole.team.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.F5CommissionService;
import ffdd.opsconsole.team.dto.F5CommissionAnomalyConfigRequest;
import ffdd.opsconsole.team.dto.F5CommissionExportPayload;
import ffdd.opsconsole.team.dto.F5CommissionExportRequest;
import ffdd.opsconsole.team.dto.F5CommissionQuery;
import ffdd.opsconsole.team.dto.F5CommissionReissueRequest;
import ffdd.opsconsole.team.dto.F5CommissionReverseRequest;
import ffdd.opsconsole.team.dto.F5CommissionSuspensionRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX)
@RequiredArgsConstructor
public class F5CommissionController {
    private final F5CommissionService service;
    private final A2RuntimePolicy a2RuntimePolicy;

    @GetMapping("/commissions")
    @PreAuthorize("hasAuthority('network_f5_read')")
    public ApiResult<Map<String, Object>> commissions(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cohort,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit) {
        return service.overview(new F5CommissionQuery(
                kind, currency, userId, status, cohort, cursor, limit));
    }

    @PostMapping("/commissions/export")
    @PreAuthorize("hasAuthority('network_f5_read')")
    public ResponseEntity<byte[]> export(
            @RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody F5CommissionExportRequest request,
            Authentication authentication) {
        a2RuntimePolicy.validateReason(request == null ? null : request.reason());
        String operator = authentication == null ? null : authentication.getName();
        F5CommissionExportPayload payload = service.export(idempotencyKey, request, operator);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(payload.filename()).build());
        headers.setCacheControl("no-store");
        headers.set("X-Export-Id", payload.exportId());
        headers.set("X-Export-Row-Count", Long.toString(payload.rowCount()));
        headers.set("X-Export-Byte-Size", Integer.toString(payload.byteSize()));
        headers.set("X-Export-Sha256", payload.sha256());
        headers.set("X-Export-Redacted", "true");
        return ResponseEntity.ok().headers(headers).body(payload.content());
    }

    @GetMapping("/commissions/anomalies")
    @PreAuthorize("hasAuthority('network_f5_read')")
    public ApiResult<Map<String, Object>> anomalies(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long cursor) {
        return service.anomalies(type, cursor);
    }

    @PostMapping("/commissions/{commissionId}/reverse")
    @PreAuthorize("hasAuthority('network_f5_commission_reject')")
    public ApiResult<Map<String, Object>> reverse(
            @PathVariable String commissionId,
            @RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody F5CommissionReverseRequest request) {
        return service.reverse(commissionId, idempotencyKey, request);
    }

    @PostMapping("/commissions/reissue")
    @PreAuthorize("hasAuthority('network_f5_commission_dispose')")
    public ApiResult<Map<String, Object>> reissue(
            @RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody F5CommissionReissueRequest request) {
        return service.reissue(idempotencyKey, request);
    }

    @PostMapping("/users/{userId}/commission/suspend")
    @PreAuthorize("hasAuthority('network_f5_commission_reject')")
    public ApiResult<Map<String, Object>> suspend(
            @PathVariable Long userId,
            @RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody F5CommissionSuspensionRequest request) {
        return service.suspend(userId, idempotencyKey, request);
    }

    @PutMapping("/commissions/anomaly-config")
    @PreAuthorize("hasAuthority('network_f5_write')")
    public ApiResult<Map<String, Object>> anomalyConfig(
            @RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody F5CommissionAnomalyConfigRequest request) {
        return service.updateAnomalyConfig(idempotencyKey, request);
    }
}
