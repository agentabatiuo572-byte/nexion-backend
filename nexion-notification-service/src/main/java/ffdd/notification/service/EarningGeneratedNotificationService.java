package ffdd.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.EarningGeneratedPayload;
import ffdd.notification.mapper.NotificationMapper;
import java.math.BigDecimal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EarningGeneratedNotificationService {
    private static final String TYPE_EARNING = "EARNING";
    private static final String PUSH_PENDING = "PENDING";

    private final NotificationMapper notificationMapper;

    public EarningGeneratedNotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public Notification create(EarningGeneratedPayload payload) {
        validate(payload);
        String bizNo = bizNo(payload.getEventNo());
        Notification existing = findByBizNo(bizNo);
        if (existing != null) {
            return existing;
        }

        Notification notification = new Notification();
        notification.setBizNo(bizNo);
        notification.setUserId(payload.getUserId());
        notification.setType(TYPE_EARNING);
        notification.setTitle("Earning generated");
        notification.setBody(body(payload));
        notification.setReadFlag(0);
        notification.setPushStatus(PUSH_PENDING);
        notification.setIsDeleted(0);
        try {
            notificationMapper.insert(notification);
            return notification;
        } catch (DuplicateKeyException ex) {
            return findByBizNo(bizNo);
        }
    }

    private Notification findByBizNo(String bizNo) {
        return notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getBizNo, bizNo)
                .eq(Notification::getIsDeleted, 0));
    }

    private String bizNo(String eventNo) {
        return "EarningGenerated:" + eventNo;
    }

    private String body(EarningGeneratedPayload payload) {
        BigDecimal amount = payload.getAmount() == null ? BigDecimal.ZERO : payload.getAmount();
        String receiptNo = StringUtils.hasText(payload.getReceiptNo()) ? payload.getReceiptNo() : "-";
        return "+" + amount.stripTrailingZeros().toPlainString() + " " + payload.getAsset()
                + " is ready from receipt " + receiptNo + ".";
    }

    private void validate(EarningGeneratedPayload payload) {
        if (payload == null) {
            throw new BizException("EarningGenerated payload is required");
        }
        if (!StringUtils.hasText(payload.getEventNo())) {
            throw new BizException("EarningGenerated eventNo is required");
        }
        if (payload.getUserId() == null) {
            throw new BizException("EarningGenerated userId is required");
        }
        if (!StringUtils.hasText(payload.getAsset())) {
            throw new BizException("EarningGenerated asset is required");
        }
    }
}
