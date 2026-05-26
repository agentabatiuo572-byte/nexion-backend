package ffdd.commerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentCallbackEvent;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.domain.TradeinApplication;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.PaymentCallbackEventMapper;
import ffdd.commerce.mapper.PaymentRecordMapper;
import ffdd.commerce.mapper.TradeinApplicationMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CommerceOpsStatsService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String PAYMENT_PENDING = "PENDING";
    private static final String PAYMENT_PAID = "PAID";
    private static final String PAYMENT_FAILED = "FAILED";
    private static final String PAYMENT_EXPIRED = "EXPIRED";
    private static final String ACTIVATION_WAITING_PAYMENT = "WAITING_PAYMENT";
    private static final String CALLBACK_FAILED = "FAILED";
    private static final String TRADEIN_SUBMITTED = "SUBMITTED";

    private final CommerceOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentCallbackEventMapper callbackEventMapper;
    private final TradeinApplicationMapper tradeinApplicationMapper;
    private final Clock clock;

    public CommerceOpsStatsService(
            CommerceOrderMapper orderMapper,
            PaymentRecordMapper paymentRecordMapper,
            PaymentCallbackEventMapper callbackEventMapper,
            TradeinApplicationMapper tradeinApplicationMapper) {
        this(orderMapper, paymentRecordMapper, callbackEventMapper, tradeinApplicationMapper, Clock.systemDefaultZone());
    }

    CommerceOpsStatsService(
            CommerceOrderMapper orderMapper,
            PaymentRecordMapper paymentRecordMapper,
            PaymentCallbackEventMapper callbackEventMapper,
            TradeinApplicationMapper tradeinApplicationMapper,
            Clock clock) {
        this.orderMapper = orderMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.callbackEventMapper = callbackEventMapper;
        this.tradeinApplicationMapper = tradeinApplicationMapper;
        this.clock = clock;
    }

    public Map<String, Object> summary(int days) {
        int normalizedDays = normalizeDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(normalizedDays - 1L).toLocalDate().atStartOfDay();

        Map<String, Object> response = section(
                "service", "nexion-commerce-service",
                "days", normalizedDays,
                "startAt", since,
                "endAt", now);
        response.put("orders", section(
                "total", countOrders(since, null, null),
                "paid", countOrders(since, PAYMENT_PAID, null),
                "pendingPayment", countOrders(since, PAYMENT_PENDING, null),
                "waitingActivation", countOrders(since, null, ACTIVATION_WAITING_PAYMENT)));
        response.put("payments", section(
                "total", countPayments(since, null),
                "paid", countPayments(since, PAYMENT_PAID),
                "pending", countPayments(since, PAYMENT_PENDING),
                "failed", countPayments(since, PAYMENT_FAILED),
                "expired", countPayments(since, PAYMENT_EXPIRED),
                "reconcileDue", countReconcileDue(now),
                "expiredPending", countExpiredPending(now)));
        response.put("callbacks", section(
                "total", countCallbacks(since, null),
                "failed", countCallbacks(since, CALLBACK_FAILED)));
        response.put("tradeins", section(
                "total", countTradeins(since, null),
                "submitted", countTradeins(since, TRADEIN_SUBMITTED)));
        return response;
    }

    private long countOrders(LocalDateTime since, String paymentStatus, String activationStatus) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<CommerceOrder>()
                .eq(CommerceOrder::getIsDeleted, 0)
                .ge(CommerceOrder::getCreatedAt, since)
                .eq(StringUtils.hasText(paymentStatus), CommerceOrder::getPaymentStatus, paymentStatus)
                .eq(StringUtils.hasText(activationStatus), CommerceOrder::getActivationStatus, activationStatus));
        return nullToZero(count);
    }

    private long countPayments(LocalDateTime since, String paymentStatus) {
        Long count = paymentRecordMapper.selectCount(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getIsDeleted, 0)
                .ge(PaymentRecord::getCreatedAt, since)
                .eq(StringUtils.hasText(paymentStatus), PaymentRecord::getPaymentStatus, paymentStatus));
        return nullToZero(count);
    }

    private long countReconcileDue(LocalDateTime now) {
        Long count = paymentRecordMapper.selectCount(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getIsDeleted, 0)
                .eq(PaymentRecord::getPaymentStatus, PAYMENT_PENDING)
                .le(PaymentRecord::getNextReconcileAt, now));
        return nullToZero(count);
    }

    private long countExpiredPending(LocalDateTime now) {
        Long count = paymentRecordMapper.selectCount(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getIsDeleted, 0)
                .eq(PaymentRecord::getPaymentStatus, PAYMENT_PENDING)
                .le(PaymentRecord::getExpiresAt, now));
        return nullToZero(count);
    }

    private long countCallbacks(LocalDateTime since, String processingStatus) {
        Long count = callbackEventMapper.selectCount(new LambdaQueryWrapper<PaymentCallbackEvent>()
                .eq(PaymentCallbackEvent::getIsDeleted, 0)
                .ge(PaymentCallbackEvent::getCreatedAt, since)
                .eq(StringUtils.hasText(processingStatus), PaymentCallbackEvent::getProcessingStatus, processingStatus));
        return nullToZero(count);
    }

    private long countTradeins(LocalDateTime since, String status) {
        Long count = tradeinApplicationMapper.selectCount(new LambdaQueryWrapper<TradeinApplication>()
                .eq(TradeinApplication::getIsDeleted, 0)
                .ge(TradeinApplication::getCreatedAt, since)
                .eq(StringUtils.hasText(status), TradeinApplication::getStatus, status));
        return nullToZero(count);
    }

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private long nullToZero(Long count) {
        return count == null ? 0 : count;
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
