package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.domain.AppTrustSectionsView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content/trust/sections")
@RequiredArgsConstructor
public class AppTrustSectionController {
    private final OpsTrustDisclosureService service;

    @GetMapping("/current")
    @PreAuthorize("permitAll()")
    public ApiResult<AppTrustSectionsView> current() {
        return service.publishedSections();
    }
}
