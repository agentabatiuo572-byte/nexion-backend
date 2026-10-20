package ffdd.opsconsole.user.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.user.application.PhoneReplacementOperationsService;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PhoneReplacementOperationsController {
    private final PhoneReplacementOperationsService service;
    @GetMapping("/users/profiles/{userId}/phone-binding")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<?> binding(@PathVariable long userId){return ApiResult.ok(service.binding(userId));}
    @GetMapping("/users/profiles/{userId}/phone-replacement-requests")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<?> requests(@PathVariable long userId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){
        return ApiResult.ok(service.requests(userId,pageNum,pageSize));
    }
    @GetMapping("/users/profiles/{userId}/phone-replacements")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<?> history(@PathVariable long userId,@RequestParam(defaultValue="SUCCESS") String kind,
                               @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){
        return ApiResult.ok(service.history(userId,kind,pageNum,pageSize));
    }
    @GetMapping("/users/profiles/{userId}/phone-replacement-verifications/by-idempotency-key")
    @PreAuthorize("hasAuthority('user_c5_phone_replacement_exception_grant')")
    public ApiResult<?> verification(@PathVariable long userId,@RequestParam String idempotencyKey) {
        var result=service.verificationByKey(userId,idempotencyKey);
        return result==null?ApiResult.fail(404,"OPERATION_RESULT_UNKNOWN"):ApiResult.ok(result);
    }
    @PostMapping("/users/profiles/{userId}/phone-replacement-requests/{requestId}/verify")
    @PreAuthorize("hasAuthority('user_c5_phone_replacement_exception_grant')")
    public ApiResult<?> verify(@PathVariable long userId,@PathVariable String requestId,@RequestHeader("Idempotency-Key") String key,@RequestBody Verify body){
        return ApiResult.ok(service.verify(userId,requestId,body,key));
    }
    @PostMapping("/users/profiles/{userId}/phone-replacement-requests/{requestId}/close")
    @PreAuthorize("hasAuthority('user_c5_phone_replacement_exception_grant')")
    public ApiResult<Receipt> close(@PathVariable long userId,@PathVariable String requestId,@RequestHeader("Idempotency-Key") String key,@RequestBody Confirm body){
        return ApiResult.ok(service.close(userId,requestId,body,key));
    }
    @PostMapping("/users/profiles/{userId}/phone-replacement-grants")
    @PreAuthorize("hasAuthority('user_c5_phone_replacement_exception_grant')")
    public ApiResult<Receipt> issue(@PathVariable long userId,@RequestHeader("Idempotency-Key") String key,@RequestBody Issue body){
        return ApiResult.ok(service.issue(userId,body,key));
    }
    @PostMapping("/users/profiles/{userId}/phone-replacement-grants/{grantId}/revoke")
    @PreAuthorize("hasAuthority('user_c5_phone_replacement_exception_revoke')")
    public ApiResult<Receipt> revoke(@PathVariable long userId,@PathVariable String grantId,@RequestHeader("Idempotency-Key") String key,@RequestBody Confirm body){
        return ApiResult.ok(service.revoke(userId,grantId,body,key));
    }
    @GetMapping("/phone-replacement-operations/{operationId}")
    @PreAuthorize("hasAnyAuthority('device_e6_write','device_e6_flag_toggle','user_c5_phone_replacement_exception_grant','user_c5_phone_replacement_exception_revoke') or (hasAuthority('platform_a2_operation_approve') and hasAuthority('device_e6_read'))")
    public ApiResult<Receipt> operation(@PathVariable String operationId){return found(service.operation(operationId));}
    @GetMapping("/phone-replacement-operations/by-idempotency-key")
    @PreAuthorize("hasAnyAuthority('device_e6_write','device_e6_flag_toggle','user_c5_phone_replacement_exception_grant','user_c5_phone_replacement_exception_revoke') or (hasAuthority('platform_a2_operation_approve') and hasAuthority('device_e6_read'))")
    public ApiResult<Receipt> byKey(@RequestParam String commandType,@RequestParam String idempotencyKey){
        return found(service.operationByKey(commandType,idempotencyKey));
    }
    private ApiResult<Receipt> found(Receipt receipt){return receipt==null?ApiResult.fail(404,"OPERATION_RESULT_UNKNOWN"):ApiResult.ok(receipt);}
}
