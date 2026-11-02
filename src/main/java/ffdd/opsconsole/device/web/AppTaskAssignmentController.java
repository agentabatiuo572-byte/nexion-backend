package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.dto.AppComputeReceiptPage;
import ffdd.opsconsole.device.dto.AppComputeReceiptView;
import ffdd.opsconsole.device.dto.AppTaskAssignmentView;
import ffdd.opsconsole.device.dto.AppTaskAssignmentsResponse;
import ffdd.opsconsole.device.dto.AppTaskClaimRequest;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.device.dto.AppPhoneRuntimeRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppTaskAssignmentController {
    private final AppTaskAssignmentService service;
    private final ffdd.opsconsole.onboarding.application.PhoneInstallationService installations;
    private final ffdd.opsconsole.device.application.SimulatedTaskService simulated;
    private final ffdd.opsconsole.device.application.SimulatedTaskReadService simulatedRead;
    private final java.time.Clock clock;
    private final ffdd.opsconsole.device.application.ComputeV2DeviceLifecycleService lifecycle;

    @GetMapping("/api/tasks/assignments")
    public ApiResult<?> assignments(Authentication authentication) {
        Long userId = userId(authentication);
        if(userId==null)return ApiResult.fail(403,"USER_SUBJECT_REQUIRED");
        return simulated.enabled()?ApiResult.ok(simulatedRead.assignments(userId)):service.assignments(userId);
    }

    public record PhoneOnlineRequest(long deviceId) { }
    @PostMapping("/api/tasks/simulated/phone-online")
    @org.springframework.transaction.annotation.Transactional
    public ApiResult<?> phoneOnline(@RequestBody PhoneOnlineRequest request,
            @RequestHeader(name="X-Phone-Installation-Id") String installationId,Authentication authentication) {
        Long user=userId(authentication);
        if(user==null)throw new BizException(403,"USER_SUBJECT_REQUIRED");
        if(request.deviceId()<=0)throw new BizException(422,"SIMULATED_PHONE_REQUIRED");
        installations.requireSimulatedForDevice(authentication,request.deviceId(),installationId);
        var result=simulated.phoneOnline(user,request.deviceId());
        var data=new java.util.LinkedHashMap<String,Object>();
        data.put("serverNow",java.time.LocalDateTime.now(clock));data.put("deviceId",request.deviceId());
        data.put("phoneValidUntil",result.runtime().phoneValidUntil());
        data.put("simulationState",result.onlineConfirmed()?result.runtime().state():"CLOSING");
        data.put("onlineConfirmed",result.onlineConfirmed());data.put("retryAfterSeconds",result.retryAfterSeconds());
        return ApiResult.ok(data);
    }

    public record StopRequest(long deviceId,boolean afterCurrent,Long expectedVersion) { }
    @PostMapping("/api/tasks/simulated/stop")
    @org.springframework.transaction.annotation.Transactional
    public ApiResult<?> stop(@RequestBody StopRequest request,
            @RequestHeader(name="X-Phone-Installation-Id",required=false) String installationId,Authentication authentication) {
        Long user=userId(authentication);
        if(user==null)throw new BizException(403,"USER_SUBJECT_REQUIRED");
        installations.requireSimulatedStopForDevice(authentication,request.deviceId(),installationId);
        var state=lifecycle.stopSimulated(user,request.deviceId(),request.expectedVersion(),request.afterCurrent());
        var data=new java.util.LinkedHashMap<String,Object>();data.put("deviceId",request.deviceId());
        data.put("simulationState",state.state());data.put("phoneValidUntil",state.phoneValidUntil());
        return ApiResult.ok(data);
    }

    @PostMapping("/api/tasks/assignments/claim")
    @org.springframework.transaction.annotation.Transactional
    public ApiResult<AppTaskAssignmentView> claim(
            @RequestBody(required = false) AppTaskClaimRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Phone-Installation-Id", required = false) String installationId,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId != null && request != null) installations.requireForDevice(authentication, request.deviceId(), installationId);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.claim(userId, idempotencyKey, request);
    }

    @PostMapping("/api/tasks/phone/runtime")
    @org.springframework.transaction.annotation.Transactional
    public ApiResult<AppTaskAssignmentView> phoneRuntime(
            @RequestBody(required = false) AppPhoneRuntimeRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId != null && request != null) installations.requireRuntime(authentication, request.calibrationDeviceId());
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.phoneRuntime(userId, request);
    }

    @GetMapping("/api/tasks/receipts/{receiptNo}")
    public ApiResult<AppComputeReceiptView> receipt(
            @PathVariable String receiptNo,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.receipt(userId, receiptNo);
    }

    @GetMapping("/api/tasks/receipts")
    public ApiResult<AppComputeReceiptPage> receipts(
            @RequestParam(required = false, defaultValue = "0") String offset,
            @RequestParam(required = false, defaultValue = "20") String limit,
            @RequestParam(required = false) String cursor,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.receipts(userId, pageValue(offset), pageValue(limit), cursor);
    }

    @PostMapping("/api/tasks/assignments/{taskNo}/complete")
    @org.springframework.transaction.annotation.Transactional
    public ApiResult<AppTaskAssignmentView> complete(
            @PathVariable String taskNo,
            @RequestBody(required = false) AppTaskCompleteRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Phone-Installation-Id", required = false) String installationId,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (userId != null) installations.requireForTask(authentication, taskNo, installationId);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.complete(userId, taskNo, idempotencyKey, request);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int pageValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[0-9]{1,10}")) throw new BizException(422, "TASK_RECEIPT_PAGE_INVALID");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new BizException(422, "TASK_RECEIPT_PAGE_INVALID");
        }
    }
}
