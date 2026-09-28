package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisDepositService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deposits")
@RequiredArgsConstructor
public class CregisDepositController {
    private final CregisDepositService service;

    @GetMapping("/address")
    public ApiResult<Map<String, Object>> address(@RequestParam String network, Authentication auth) {
        if (!"BEP20".equalsIgnoreCase(network)) return ApiResult.fail(422, "DEPOSIT_NETWORK_UNAVAILABLE");
        return ApiResult.ok(service.address(user(auth)));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> deposits(Authentication auth) {
        return ApiResult.ok(service.deposits(user(auth)));
    }

    private long user(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))) throw new BizException(401, "USER_AUTH_REQUIRED");
        try { long id = Long.parseLong(String.valueOf(auth.getPrincipal())); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new BizException(401, "USER_AUTH_REQUIRED");
    }
}
