package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.application.AmbassadorMaterialService;
import ffdd.opsconsole.team.dto.AmbassadorMaterialRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class AmbassadorMaterialController {
    private static final String APP="/api/app/team/ambassador-applications/{id}/materials";
    private static final String ADMIN="/api/admin/teams/ambassador-applications/{id}/materials";
    private final AmbassadorMaterialService materials;

    @GetMapping(APP)
    public ApiResult<Map<String,Object>> app(@PathVariable long id,Authentication auth) { return ApiResult.ok(materials.appPacket(user(auth),id)); }
    @PostMapping(APP)
    public ApiResult<Map<String,Object>> submit(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody AmbassadorMaterialRequest request,Authentication auth) {
        return materials.submit(user(auth),id,key,request);
    }
    @PostMapping(value=APP+"/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String,Object>> upload(@PathVariable long id,@RequestParam String kind,@RequestHeader("Idempotency-Key") String key,@RequestPart MultipartFile file,Authentication auth) {
        return ApiResult.ok(materials.upload(user(auth),id,kind,key,file));
    }
    @GetMapping(APP+"/files/{fileId}")
    public ResponseEntity<byte[]> appFile(@PathVariable long id,@PathVariable String fileId,Authentication auth) { return file(materials.appFile(user(auth),id,fileId)); }
    @GetMapping(ADMIN)
    @PreAuthorize("hasAuthority('network_f4_read')")
    public ApiResult<Map<String,Object>> admin(@PathVariable long id) { return ApiResult.ok(materials.adminPacket(id)); }
    @GetMapping(ADMIN+"/files/{fileId}")
    @PreAuthorize("hasAuthority('network_f4_read')")
    public ResponseEntity<byte[]> adminFile(@PathVariable long id,@PathVariable String fileId) { return file(materials.adminFile(id,fileId)); }
    @PostMapping(ADMIN+"/review")
    @PreAuthorize("hasAnyAuthority('network_f4_ambassador_material_review','network_f4_ambassador_finance_review')")
    public ApiResult<Map<String,Object>> review(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody AmbassadorMaterialRequest.Review request) {
        return materials.review(id,key,request);
    }
    private ResponseEntity<byte[]> file(AmbassadorMaterialService.Download file) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(file.name(),StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
    private long user(Authentication auth) {
        if(auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details) || !"USER".equals(details.get("subjectType")))
            throw new BizException(403,"USER_AUTH_REQUIRED");
        try { long id=Long.parseLong(String.valueOf(auth.getPrincipal())); if(id>0)return id; }
        catch(NumberFormatException ex) { throw new BizException(403,"USER_AUTH_REQUIRED"); }
        throw new BizException(403,"USER_AUTH_REQUIRED");
    }
}
