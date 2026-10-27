package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.BusinessCalendarService;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** R-1: global business time zone. Every signed-in console reads it to cut and label time; only the super admin changes it. */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/business-calendar")
@RequiredArgsConstructor
public class OpsBusinessCalendarController {
    private final BusinessCalendarService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResult<BusinessCalendarService.CalendarView> view() {
        return service.view();
    }

    @PutMapping
    @PreAuthorize("@superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<BusinessCalendarService.CalendarView> schedule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BusinessCalendarService.ScheduleRequest request) {
        return service.schedule(idempotencyKey, request);
    }

    @PostMapping("/cancel")
    @PreAuthorize("@superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<BusinessCalendarService.CalendarView> cancel(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BusinessCalendarService.CancelRequest request) {
        return service.cancel(idempotencyKey, request);
    }
}
