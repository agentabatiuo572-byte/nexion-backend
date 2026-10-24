package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.HomeDisplayService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile({"dev", "prod", "default"})
public class HomeDisplayController {
    private final HomeDisplayService display;

    @GetMapping("/api/config/home-display")
    public ApiResult<Map<String, Object>> current(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResult.ok(display.current());
    }
}
