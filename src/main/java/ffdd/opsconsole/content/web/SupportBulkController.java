package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.SupportAttachmentService;
import ffdd.opsconsole.content.application.SupportBulkService;
import ffdd.opsconsole.content.dto.SupportBulkRequest;
import ffdd.opsconsole.content.domain.SupportBulk;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/content/support-workbench/bulk")
public class SupportBulkController {
    private final SupportBulkService service;
    private final SupportAttachmentService attachments;

    @PostMapping("/preview") @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<SupportBulk.Preview> preview(@RequestBody SupportBulkRequest.Preview request) {return ApiResult.ok(service.preview(request));}
    @PostMapping @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Map<String,Object>> create(@RequestHeader("Idempotency-Key") String key,@RequestBody SupportBulkRequest.Create request) {return service.create(key,request);}
    @GetMapping @PreAuthorize("hasAnyAuthority('service_m3_read','service_m1_read')")
    public ApiResult<PageResult<Map<String,Object>>> page(@RequestParam(defaultValue="1") long pageNum,@RequestParam(defaultValue="20") int pageSize) {return ApiResult.ok(service.page(pageNum,pageSize));}
    @GetMapping("/{batchId}") @PreAuthorize("hasAnyAuthority('service_m3_read','service_m1_read')")
    public ApiResult<Map<String,Object>> detail(@PathVariable String batchId) {return ApiResult.ok(service.detail(batchId));}
    @GetMapping("/{batchId}/recipients") @PreAuthorize("hasAnyAuthority('service_m3_read','service_m1_read')")
    public ApiResult<PageResult<Map<String,Object>>> recipients(@PathVariable String batchId,@RequestParam(defaultValue="1") long pageNum,@RequestParam(defaultValue="20") int pageSize) {return ApiResult.ok(service.recipients(batchId,pageNum,pageSize));}
    @PostMapping("/{batchId}/cancel") @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Map<String,Object>> cancel(@PathVariable String batchId,@RequestHeader("Idempotency-Key") String key,@RequestBody SupportBulkRequest.Mutation request) {return service.cancel(batchId,key,request);}
    @PostMapping("/{batchId}/retry") @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Map<String,Object>> retry(@PathVariable String batchId,@RequestHeader("Idempotency-Key") String key,@RequestBody SupportBulkRequest.Mutation request) {return service.retry(batchId,key,request);}
    @PostMapping(value="/attachments",consumes="multipart/form-data") @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Map<String,Object>> upload(@RequestHeader("Idempotency-Key") String key,@RequestParam String clientUploadId,@RequestPart MultipartFile file) {
        return ApiResult.ok(attachments.uploadBulk(attachments.actor("ADMIN"),key,clientUploadId,file));
    }
}
