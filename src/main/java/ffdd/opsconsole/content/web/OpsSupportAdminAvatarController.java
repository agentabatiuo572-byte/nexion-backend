package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportAdminAvatarService;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/platform/accounts")
@RequiredArgsConstructor
public class OpsSupportAdminAvatarController {
    private final SupportAdminAvatarService service;
    @PostMapping(value="/avatar-assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<Map<String,Object>> upload(@RequestParam String clientUploadId,@RequestHeader("Idempotency-Key") String key,@RequestPart MultipartFile file){return ApiResult.ok(service.upload(clientUploadId,key,file));}
    @GetMapping("/avatar-assets/{id}") @PreAuthorize("hasAuthority('platform_a1_write')")
    public ResponseEntity<byte[]> preview(@PathVariable String id){return image(service.preview(id));}
    @DeleteMapping("/avatar-assets/{id}") @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<Map<String,Object>> cancel(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return ApiResult.ok(service.cancel(id,key));}
    @GetMapping("/{accountId}/avatar") @PreAuthorize("hasAuthority('platform_a1_read')")
    public ResponseEntity<byte[]> content(@PathVariable Long accountId){return image(service.content(accountId,false));}
    public static ResponseEntity<byte[]> image(SupportAttachmentService.Content content){return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mime())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(content.bytes());}
}
