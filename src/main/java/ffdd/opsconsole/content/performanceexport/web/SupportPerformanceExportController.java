package ffdd.opsconsole.content.performanceexport.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.performanceexport.SupportPerformanceExportService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** B80-BE-06: M performance exports with exact codes, freeze, dual approval and vouchers. */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-workbench/performance-exports")
@RequiredArgsConstructor
public class SupportPerformanceExportController {
    private static final String READ_CODES =
            "hasAnyAuthority('service_performance_export_summary','service_performance_export_detail')";
    private static final String VIEW_CODES =
            "hasAnyAuthority('service_performance_export_summary','service_performance_export_detail','service_performance_export_approve')";
    private final SupportPerformanceExportService service;

    @GetMapping("/options")
    @PreAuthorize(READ_CODES)
    public ApiResult<Map<String, Object>> options() {
        return ApiResult.ok(service.options());
    }

    @PostMapping("/preview")
    @PreAuthorize(READ_CODES)
    public ApiResult<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResult.ok(service.preview(request));
    }

    @PostMapping
    @PreAuthorize(READ_CODES)
    public ApiResult<Map<String, Object>> submit(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> request) {
        return ApiResult.ok(service.submit(idempotencyKey, request));
    }

    @GetMapping
    @PreAuthorize(VIEW_CODES)
    public ApiResult<Map<String, Object>> list(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String requestKey,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(service.list(view, requestKey, pageNum, pageSize));
    }

    @GetMapping("/{taskId}")
    @PreAuthorize(VIEW_CODES)
    public ApiResult<Map<String, Object>> detail(@PathVariable String taskId) {
        return ApiResult.ok(service.detail(id(taskId)));
    }

    @PostMapping("/{taskId}/decision")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read') and hasAuthority('service_performance_export_approve')")
    public ApiResult<Map<String, Object>> decide(
            @PathVariable String taskId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> request) {
        return ApiResult.ok(service.decide(id(taskId), idempotencyKey, request));
    }

    @PostMapping("/{taskId}/withdraw")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read') and hasAuthority('service_performance_export_detail')")
    public ApiResult<Map<String, Object>> withdraw(
            @PathVariable String taskId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> request) {
        return ApiResult.ok(service.withdraw(id(taskId), idempotencyKey, request));
    }

    @PostMapping("/{taskId}/download-token")
    @PreAuthorize(READ_CODES)
    public ApiResult<Map<String, Object>> downloadToken(@PathVariable String taskId) {
        return ApiResult.ok(service.downloadToken(id(taskId)));
    }

    @GetMapping("/{taskId}/file")
    @PreAuthorize(READ_CODES)
    public ResponseEntity<byte[]> file(
            @PathVariable String taskId,
            @RequestHeader(value = "X-Download-Token", required = false) String token) {
        var payload = service.file(id(taskId), token);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(payload.fileName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(payload.content(), headers, org.springframework.http.HttpStatus.OK);
    }

    private static Long id(String value) {
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new ffdd.opsconsole.shared.exception.BizException(400, "EXPORT_TASK_ID_INVALID");
        }
    }
}
