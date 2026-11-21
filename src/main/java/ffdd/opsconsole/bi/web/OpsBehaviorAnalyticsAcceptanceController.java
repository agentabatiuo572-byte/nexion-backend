package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsAcceptanceProfileCondition;
import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.StorageTime;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test")
@Conditional(BehaviorAnalyticsAcceptanceProfileCondition.class)
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bi/behavior/acceptance")
@RequiredArgsConstructor
public class OpsBehaviorAnalyticsAcceptanceController {
    private final BehaviorAnalyticsService service;

    @GetMapping
    @PreAuthorize("hasAuthority('bi_l6_read')")
    public ApiResult<Map<String, Object>> acceptanceBehavior(
            @RequestParam("runId") String runId,
            @RequestParam(value = "observationToken", required = false) String observationToken,
            @RequestParam(value = "actorHash", required = false) String actorHash,
            @RequestParam(value = "sessionHash", required = false) String sessionHash,
            @RequestParam(value = "route", required = false) String route,
            @RequestParam("from") OffsetDateTime from,
            @RequestParam("to") OffsetDateTime to) {
        return service.acceptanceBehavior(runId, observationToken, actorHash, sessionHash, route,
                LocalDateTime.ofInstant(from.toInstant(), StorageTime.ZONE),
                LocalDateTime.ofInstant(to.toInstant(), StorageTime.ZONE));
    }
}
