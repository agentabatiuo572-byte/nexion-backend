package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.ComputeV2ReadService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AppComputeV2Controller {
    private final ComputeV2ReadService service;

    @GetMapping("/api/tasks/v2/assignments")
    public ApiResult<Map<String,Object>> assignments(Authentication authentication) {
        return ApiResult.ok(service.assignments(userId(authentication)));
    }

    @GetMapping("/api/tasks/v2/settlements")
    public ApiResult<Map<String,Object>> settlements(@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") String limit,Authentication authentication) {
        Long userId=userId(authentication);
        if (limit==null || !limit.matches("[0-9]{1,2}")) throw new BizException(422,"COMPUTE_V2_PAGE_INVALID");
        return ApiResult.ok(service.settlements(userId,cursor,Integer.parseInt(limit)));
    }

    @GetMapping("/api/tasks/v2/settlements/{batchNo}")
    public ApiResult<Map<String,Object>> settlement(@PathVariable String batchNo,Authentication authentication) {
        return ApiResult.ok(service.settlement(userId(authentication),batchNo));
    }

    private Long userId(Authentication authentication) {
        if (authentication==null || !authentication.isAuthenticated() || authentication.getPrincipal()==null
                || !(authentication.getDetails() instanceof Map<?,?> details) || !"USER".equals(details.get("subjectType")))
            throw new BizException(403,"USER_SUBJECT_REQUIRED");
        try {
            long value=Long.parseLong(String.valueOf(authentication.getPrincipal()));
            if (value>0) return value;
        } catch (NumberFormatException ignored) { }
        throw new BizException(403,"USER_SUBJECT_REQUIRED");
    }
}
