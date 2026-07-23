package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppI18nService;
import ffdd.opsconsole.content.domain.AppI18nBundle;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/content/i18n", "/i18n"})
@RequiredArgsConstructor
public class AppI18nController {
    private final AppI18nService service;

    @GetMapping
    public ApiResult<AppI18nBundle> all(@RequestParam(defaultValue = "en") String locale) {
        return service.all(locale);
    }

    @GetMapping("/{namespace}")
    public ApiResult<AppI18nBundle> namespace(
            @PathVariable String namespace,
            @RequestParam(defaultValue = "en") String locale) {
        return service.namespace(namespace, locale);
    }
}
