package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.platform.application.BusinessCalendarService;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** D-16: the business calendar the App uses to cut days and label rule times; public like the other App config reads. */
@RestController
@RequestMapping("/api/config/business-calendar")
@RequiredArgsConstructor
public class AppBusinessCalendarController {
    private final BusinessCalendarService service;

    @GetMapping
    @PreAuthorize("permitAll()")
    public ApiResult<BusinessCalendarService.AppCalendarView> view() {
        return service.appView();
    }
}
