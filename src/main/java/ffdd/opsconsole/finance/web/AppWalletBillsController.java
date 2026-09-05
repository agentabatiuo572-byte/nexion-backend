package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.AppWalletBillsService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/wallet/bills")
@RequiredArgsConstructor
public class AppWalletBillsController {
    private final AppWalletBillsService service;

    @GetMapping
    public ApiResult<Map<String, Object>> list(Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String cursor) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : service.list(userId, page, pageSize, asset, direction, category, cursor);
    }

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.summary(userId);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long id = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
