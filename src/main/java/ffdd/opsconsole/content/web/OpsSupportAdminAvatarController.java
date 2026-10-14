package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportAdminAvatarService;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.content.application.SupportSelfAvatarService;
import ffdd.opsconsole.content.dto.SupportAvatarRequests.*;
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
    private final SupportSelfAvatarService self;
    @PostMapping("/avatar-assets/default") @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<Map<String,Object>> prepareDefault(@RequestHeader("Idempotency-Key") String key,@RequestBody DefaultPrepare request){return ApiResult.ok(self.prepareAdmin(key,request));}
    @PostMapping(value="/self/avatar-assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfUpload(@RequestParam Map<String,String> params,@RequestHeader("Idempotency-Key") String key,@RequestPart MultipartFile file){
        if(!params.keySet().equals(java.util.Set.of("clientUploadId")))throw new ffdd.opsconsole.shared.exception.BizException(422,"AVATAR_COMMAND_INVALID");
        return ApiResult.ok(self.upload(params.get("clientUploadId"),key,file));
    }
    @PostMapping("/self/avatar-assets/default") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfDefault(@RequestHeader("Idempotency-Key") String key,@RequestBody DefaultPrepare request){return ApiResult.ok(self.prepareDefault(key,request));}
    @GetMapping("/self/avatar-assets/{id}") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> selfPreview(@PathVariable String id){return image(self.preview(id));}
    @DeleteMapping("/self/avatar-assets/{id}") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfCancel(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return ApiResult.ok(self.cancel(id,key));}
    @GetMapping("/self/avatar") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfSnapshot(){return ApiResult.ok(self.snapshot());}
    @PatchMapping("/self/avatar") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfSave(@RequestHeader("Idempotency-Key") String key,@RequestBody SelfSave request){return ApiResult.ok(self.save(key,request));}
    @GetMapping("/self/avatar/content") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> selfContent(){return image(self.content());}
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
