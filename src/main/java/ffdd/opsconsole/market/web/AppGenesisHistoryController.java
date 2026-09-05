package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.AppGenesisHistoryService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppGenesisHistoryController {
    private final AppGenesisHistoryService service;

    @GetMapping(value="/api/genesis/state", params="history")
    public ApiResult<Map<String, Object>> publicHistory(@RequestParam String history,
            @RequestParam(required=false) String cursor) {
        if (!"listings".equals(history) && !"transactions".equals(history))
            return ApiResult.fail(422, "GENESIS_HISTORY_KIND_INVALID");
        return service.page(history, null, cursor);
    }

    @GetMapping(value="/api/genesis/account", params="history")
    public ApiResult<Map<String, Object>> accountHistory(@RequestParam String history,
            @RequestParam(required=false) String cursor, Authentication authentication) {
        if (!"orders".equals(history) && !"emissions".equals(history))
            return ApiResult.fail(422, "GENESIS_HISTORY_KIND_INVALID");
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        try { return service.page(history, Long.valueOf(String.valueOf(authentication.getPrincipal())), cursor); }
        catch (NumberFormatException ex) { return ApiResult.fail(403, "USER_SUBJECT_REQUIRED"); }
    }
}
