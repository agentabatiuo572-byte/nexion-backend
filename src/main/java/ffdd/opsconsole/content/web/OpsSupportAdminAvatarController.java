package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportAdminAvatarService;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.content.application.SupportSelfAvatarService;
import ffdd.opsconsole.content.dto.SupportAvatarRequests.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminCommandInvestigationService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/platform/accounts")
@RequiredArgsConstructor
public class OpsSupportAdminAvatarController {
    private static final Pattern ASSET_ID = Pattern.compile("[a-f0-9-]{36}");
    private static final Pattern COMMAND_KEY = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    private final SupportAdminAvatarService service;
    private final SupportSelfAvatarService self;
    private final AdminIdempotencyService idempotency;
    private final AdminCommandInvestigationService investigations;
    @PostMapping("/avatar-assets/default") @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<Map<String,Object>> prepareDefault(@RequestHeader("Idempotency-Key") String key,@RequestBody DefaultPrepare request){return ApiResult.ok(self.prepareAdmin(key,request));}
    @PostMapping(value="/self/avatar-assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfUpload(@RequestParam Map<String,String> params,
            @RequestHeader("Idempotency-Key") String key, @RequestPart MultipartFile file,
            HttpServletRequest httpRequest) {
        SupportSelfAccountGuard.requireMatch(httpRequest);
        if(!params.keySet().equals(java.util.Set.of("clientUploadId")))throw new ffdd.opsconsole.shared.exception.BizException(422,"AVATAR_COMMAND_INVALID");
        return ApiResult.ok(self.upload(params.get("clientUploadId"),key,file));
    }
    @PostMapping("/self/avatar-assets/default") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfDefault(@RequestHeader("Idempotency-Key") String key,
            @RequestBody DefaultPrepare request, HttpServletRequest httpRequest) {
        SupportSelfAccountGuard.requireMatch(httpRequest);
        return ApiResult.ok(self.prepareDefault(key,request));
    }
    @GetMapping("/self/avatar-assets/{id}") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> selfPreview(@PathVariable String id){return image(self.preview(id));}
    @DeleteMapping("/self/avatar-assets/{id}") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfCancel(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest httpRequest) {
        SupportSelfAccountGuard.requireMatch(httpRequest);
        return ApiResult.ok(self.cancel(id,key));
    }
    @GetMapping("/self/avatar") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfSnapshot(){return ApiResult.ok(self.snapshot());}
    @PatchMapping("/self/avatar") @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> selfSave(@RequestHeader("Idempotency-Key") String key,
            @RequestBody SelfSave request, HttpServletRequest httpRequest) {
        SupportSelfAccountGuard.requireMatch(httpRequest);
        return ApiResult.ok(self.save(key,request));
    }
    @GetMapping("/self/avatar/content") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> selfContent(){return image(self.content());}
    @PostMapping(value="/avatar-assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<Map<String,Object>> upload(@RequestParam String clientUploadId,@RequestHeader("Idempotency-Key") String key,@RequestPart MultipartFile file){return ApiResult.ok(service.upload(clientUploadId,key,file));}
    @GetMapping("/avatar-assets/{id}") @PreAuthorize("hasAuthority('platform_a1_write')")
    public ResponseEntity<byte[]> preview(@PathVariable String id){return image(service.preview(id));}
    @DeleteMapping("/avatar-assets/{id}") @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<Map<String,Object>> cancel(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){
        if(id==null || !ASSET_ID.matcher(id).matches()) return ApiResult.fail(404,"AVATAR_NOT_FOUND");
        if(key==null || !COMMAND_KEY.matcher(key).matches()) return ApiResult.fail(422,"AVATAR_COMMAND_INVALID");
        Long actor=currentAdminId();
        if(actor==null) return ApiResult.fail(403,"AVATAR_WRITE_FORBIDDEN");
        String scope="A1:AVATAR_CANCEL:"+actor+":"+id;
        String hash=sha256(actor+"\0"+id);
        return (ApiResult<Map<String,Object>>) (ApiResult) idempotency.execute(
                scope,key,hash,ApiResult.class,()->ApiResult.ok(service.cancel(id,key)));
    }

    @GetMapping("/avatar-assets/{id}/cancel-result") @PreAuthorize("hasAuthority('platform_a1_read')")
    public ApiResult<AdminCommandInvestigationService.ReceiptStatus> cancelResult(
            @PathVariable String id,@RequestParam("commandKey") String commandKey){
        if(id==null || !ASSET_ID.matcher(id).matches()) return ApiResult.fail(404,"AVATAR_NOT_FOUND");
        if(commandKey==null || !COMMAND_KEY.matcher(commandKey).matches())
            return ApiResult.fail(422,"AVATAR_COMMAND_INVALID");
        Long actor=currentAdminId();
        if(actor==null) return ApiResult.fail(403,"AVATAR_READ_FORBIDDEN");
        return ApiResult.ok(investigations.status("A1:AVATAR_CANCEL:"+actor+":"+id,commandKey));
    }

    private static Long currentAdminId(){
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?,?> details)
                || !"ADMIN".equals(details.get("subjectType"))) return null;
        try{long id=Long.parseLong(authentication.getName());return id>0?id:null;}
        catch(NumberFormatException ex){return null;}
    }

    private static String sha256(String value){
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable",ex);}
    }
    @GetMapping("/{accountId}/avatar") @PreAuthorize("hasAuthority('platform_a1_read')")
    public ResponseEntity<byte[]> content(@PathVariable Long accountId){return image(service.content(accountId,false));}
    public static ResponseEntity<byte[]> image(SupportAttachmentService.Content content){return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mime())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(content.bytes());}
}
