package ffdd.notice.controller;

import ffdd.common.api.ApiResult;
import ffdd.notice.domain.Notification;
import ffdd.notice.service.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @GetMapping("/mine")
    public ApiResult<List<Notification>> listMine() {
        return ApiResult.ok(notificationService.listMine());
    }
}

