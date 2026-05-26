package ffdd.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.NotificationMutationResponse;
import ffdd.notification.dto.NotificationUnreadCountResponse;
import ffdd.notification.mapper.NotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class NotificationCenterService {
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationMapper notificationMapper;
    private final NotificationUnreadCounter unreadCounter;

    public NotificationCenterService(NotificationMapper notificationMapper, NotificationUnreadCounter unreadCounter) {
        this.notificationMapper = notificationMapper;
        this.unreadCounter = unreadCounter;
    }

    public PageResult<Notification> pageForUser(
            Long userId,
            Integer readFlag,
            String type,
            long pageNum,
            long pageSize) {
        requireUserId(userId);
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsDeleted, 0)
                .orderByDesc(Notification::getCreatedAt)
                .orderByDesc(Notification::getId);
        if (readFlag != null) {
            wrapper.eq(Notification::getReadFlag, normalizeReadFlag(readFlag));
        }
        if (StringUtils.hasText(type)) {
            wrapper.eq(Notification::getType, type.trim());
        }
        Page<Notification> page =
                notificationMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public NotificationUnreadCountResponse unreadCount(Long userId) {
        requireUserId(userId);
        return unreadCounter.count(userId, () -> notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .eq(Notification::getIsDeleted, 0)));
    }

    public Notification markRead(Long userId, Long notificationId) {
        Notification notification = requireOwnedNotification(userId, notificationId);
        boolean wasUnread = Integer.valueOf(0).equals(notification.getReadFlag());
        Notification patch = new Notification();
        patch.setId(notificationId);
        patch.setReadFlag(1);
        int affectedRows = notificationMapper.updateById(patch);
        if (affectedRows < 1) {
            throw new BizException("Notification not found");
        }
        notification.setReadFlag(1);
        if (wasUnread) {
            unreadCounter.invalidate(userId);
        }
        return notification;
    }

    public NotificationMutationResponse markAllRead(Long userId) {
        requireUserId(userId);
        Notification patch = new Notification();
        patch.setReadFlag(1);
        int affectedRows = notificationMapper.update(patch, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .eq(Notification::getIsDeleted, 0));
        unreadCounter.invalidate(userId);
        return new NotificationMutationResponse(userId, affectedRows);
    }

    public NotificationMutationResponse delete(Long userId, Long notificationId) {
        Notification notification = requireOwnedNotification(userId, notificationId);
        Notification patch = new Notification();
        patch.setId(notificationId);
        patch.setIsDeleted(1);
        int affectedRows = notificationMapper.updateById(patch);
        if (Integer.valueOf(0).equals(notification.getReadFlag())) {
            unreadCounter.invalidate(userId);
        }
        return new NotificationMutationResponse(userId, affectedRows);
    }

    private Notification requireOwnedNotification(Long userId, Long notificationId) {
        requireUserId(userId);
        if (notificationId == null || notificationId < 1) {
            throw new BizException("Notification id is required");
        }
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null
                || Integer.valueOf(1).equals(notification.getIsDeleted())
                || !userId.equals(notification.getUserId())) {
            throw new BizException("Notification not found");
        }
        return notification;
    }

    private int normalizeReadFlag(Integer readFlag) {
        if (readFlag == null || readFlag == 0) {
            return 0;
        }
        if (readFlag == 1) {
            return 1;
        }
        throw new BizException("Unsupported read flag");
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }
}
