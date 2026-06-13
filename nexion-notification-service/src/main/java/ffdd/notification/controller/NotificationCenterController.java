package ffdd.notification.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.security.AuthHeaders;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.NotificationBroadcastRequest;
import ffdd.notification.dto.NotificationBroadcastResponse;
import ffdd.notification.dto.NotificationCreateRequest;
import ffdd.notification.dto.NotificationMutationResponse;
import ffdd.notification.dto.NotificationPushResult;
import ffdd.notification.dto.NotificationUnreadCountResponse;
import ffdd.notification.service.NotificationCenterService;
import ffdd.notification.service.NotificationPushService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationCenterController {
    private final NotificationCenterService notificationCenterService;
    private final NotificationPushService notificationPushService;

    public NotificationCenterController(
            NotificationCenterService notificationCenterService,
            NotificationPushService notificationPushService) {
        this.notificationCenterService = notificationCenterService;
        this.notificationPushService = notificationPushService;
    }

    @GetMapping
    public ApiResult<PageResult<Notification>> page(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @RequestParam(required = false) Integer readFlag,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(notificationCenterService.pageForUser(userId, readFlag, type, pageNum, pageSize));
    }

    @GetMapping("/unread-count")
    public ApiResult<NotificationUnreadCountResponse> unreadCount(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(notificationCenterService.unreadCount(userId));
    }

    @GetMapping("/{notificationId}")
    public ApiResult<Notification> detail(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable Long notificationId) {
        return ApiResult.ok(notificationCenterService.detailForUser(userId, notificationId));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResult<Notification> markRead(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable Long notificationId) {
        return ApiResult.ok(notificationCenterService.markRead(userId, notificationId));
    }

    @PostMapping("/read-all")
    public ApiResult<NotificationMutationResponse> markAllRead(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(notificationCenterService.markAllRead(userId));
    }

    @DeleteMapping("/{notificationId}")
    public ApiResult<NotificationMutationResponse> delete(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable Long notificationId) {
        return ApiResult.ok(notificationCenterService.delete(userId, notificationId));
    }

    @PostMapping("/ops/push-pending")
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_WRITE')")
    public ApiResult<NotificationPushResult> pushPending(@RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(notificationPushService.pushPending(limit));
    }

    @GetMapping("/ops/records")
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_READ')")
    public ApiResult<PageResult<Notification>> pageForOps(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer readFlag,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String pushStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(notificationCenterService.pageForOps(
                userId, readFlag, type, pushStatus, keyword, pageNum, pageSize));
    }

    @PostMapping("/ops/records/{notificationId}/retry")
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_WRITE')")
    public ApiResult<Notification> retryForOps(@PathVariable Long notificationId) {
        return ApiResult.ok(notificationCenterService.retryForOps(notificationId));
    }

    @DeleteMapping("/ops/records/{notificationId}")
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_WRITE')")
    public ApiResult<NotificationMutationResponse> deleteForOps(@PathVariable Long notificationId) {
        return ApiResult.ok(notificationCenterService.deleteForOps(notificationId));
    }

    @PostMapping("/internal")
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_WRITE')")
    public ApiResult<Notification> createInternal(@Valid @RequestBody NotificationCreateRequest request) {
        return ApiResult.ok(notificationCenterService.create(request));
    }

    @PostMapping("/ops/broadcast")
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_WRITE')")
    public ApiResult<NotificationBroadcastResponse> broadcast(
            @Valid @RequestBody NotificationBroadcastRequest request) {
        return ApiResult.ok(notificationCenterService.broadcast(request));
    }
}
