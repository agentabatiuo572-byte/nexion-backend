package ffdd.opsconsole.content.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.SupportAttachmentPolicy;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.content.domain.SupportAttachment;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Dedicated authenticated binary path; never passes through public CMS media. */
@RestController
@RequiredArgsConstructor
public class SupportAttachmentController {
    private final SupportAttachmentService service;
    private final ObjectMapper json;
    private static final String ADMIN = "/api/admin/content/conversations/attachments";
    private static final String APP = "/api/app/support/attachments";

    @GetMapping(ADMIN + "/policy")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ApiResult<SupportAttachmentPolicy.View> adminPolicy() {
        service.actor("ADMIN");
        return ApiResult.ok(service.policy());
    }

    @GetMapping(APP + "/policy")
    public ApiResult<SupportAttachmentPolicy.View> appPolicy() {
        service.actor("USER");
        return ApiResult.ok(service.policy());
    }

    @PostMapping(value = ADMIN, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<SupportAttachment.View> adminUpload(@RequestPart("file") MultipartFile file,
            @RequestParam String customerId, @RequestParam String expectedAssignmentId,
            @RequestParam String clientUploadId, @RequestHeader("Idempotency-Key") String key) {
        return ApiResult.ok(service.upload(id(customerId), "ADMIN", service.actor("ADMIN"),
                id(expectedAssignmentId), clientUploadId, key, file));
    }

    @PostMapping(value = APP, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<SupportAttachment.View> appUpload(@RequestPart("file") MultipartFile file,
            @RequestParam String clientUploadId, @RequestHeader("Idempotency-Key") String key) {
        Long actor = service.actor("USER");
        return ApiResult.ok(service.upload(actor, "USER", actor, null, clientUploadId, key, file));
    }

    @GetMapping(ADMIN + "/{id}/content")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ResponseEntity<byte[]> adminContent(@PathVariable String id) {
        return content(service.content(id, "ADMIN", service.actor("ADMIN")));
    }

    @GetMapping(APP + "/{id}/content")
    public ResponseEntity<byte[]> appContent(@PathVariable String id) {
        return content(service.content(id, "USER", service.actor("USER")));
    }

    @DeleteMapping(ADMIN + "/{id}")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<SupportAttachment.View> adminCancel(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResult.ok(service.cancel(id, "ADMIN", service.actor("ADMIN"), key));
    }

    @DeleteMapping(APP + "/{id}")
    public ApiResult<SupportAttachment.View> appCancel(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key) {
        return ApiResult.ok(service.cancel(id, "USER", service.actor("USER"), key));
    }

    private ResponseEntity<byte[]> content(SupportAttachmentService.Content value) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.mime()))
                .contentLength(value.bytes().length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header("Vary", "Authorization, Cookie")
                .header("Content-Disposition", "inline").body(value.bytes());
    }

    private Long id(String value) {
        // Multipart numbers arrive as text. Reject noncanonical text, then reuse S3's strict ID decoder.
        if (value == null || !value.matches("[1-9][0-9]*")) throw new BizException(422, "SAFE_INTEGER_REQUIRED");
        try { return json.readValue("{\"id\":" + value + "}", SupportBindingRequest.Customer.class).id(); }
        catch (java.io.IOException ex) { throw new BizException(422, "SAFE_INTEGER_REQUIRED"); }
    }
}
