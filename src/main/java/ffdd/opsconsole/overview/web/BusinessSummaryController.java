package ffdd.opsconsole.overview.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.overview.application.BusinessSummaryService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/overview")
@RequiredArgsConstructor
public class BusinessSummaryController {
    private final BusinessSummaryService service;

    @GetMapping("/business-summary")
    @PreAuthorize(BusinessSummaryService.READ)
    public ApiResult<Map<String,Object>> summary(@RequestParam(defaultValue="today") String period,
            @RequestParam(defaultValue="day") String registrationGranularity,Authentication authentication) {
        return service.summary(period,registrationGranularity,authentication);
    }
}
