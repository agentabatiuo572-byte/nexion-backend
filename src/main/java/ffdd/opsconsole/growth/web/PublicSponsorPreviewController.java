package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.PublicSponsorPreviewService;
import ffdd.opsconsole.growth.domain.PublicSponsorPreviewView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/referrals")
@RequiredArgsConstructor
public class PublicSponsorPreviewController {
    private final PublicSponsorPreviewService service;

    @GetMapping("/{code}/preview")
    public ApiResult<PublicSponsorPreviewView> preview(@PathVariable String code) {
        return service.preview(code);
    }
}
