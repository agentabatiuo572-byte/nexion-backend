package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppProductCatalogController {
    private final AppProductCatalogService service;

    @GetMapping("/api/store/catalog")
    public ApiResult<Map<String, Object>> catalog(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        }
        return service.catalog();
    }
}
