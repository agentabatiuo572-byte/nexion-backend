package ffdd.opsconsole.team.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.PublishedRankHowPolicyService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublishedRankHowPolicyController {
    private final PublishedRankHowPolicyService service;

    @GetMapping("/api/config/v-rank-policy")
    public ApiResult<Map<String, Object>> published(@RequestParam(defaultValue = "en") String locale) { return service.publicPolicy(locale); }

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/teams/rank-policy")
    @PreAuthorize("hasAuthority('network_f1_read')")
    public ApiResult<Map<String, Object>> adminView() { return service.adminView(); }

    @PutMapping(OpsAdminApi.ADMIN_PREFIX + "/teams/rank-policy")
    @PreAuthorize("hasAuthority('network_f1_write')")
    public ApiResult<Map<String, Object>> update(@RequestBody(required = false) Request request) {
        return request == null ? ApiResult.fail(422, "RANK_HOW_POLICY_INVALID")
                : service.update(request.version(), request.status(), request.locales(), request.expectedRevision(), request.reason());
    }

    public record Request(String version, String status, Map<String, Object> locales,
                          Long expectedRevision, String reason) { }
}
