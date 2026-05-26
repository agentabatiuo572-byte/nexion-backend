package ffdd.commerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.commerce.domain.CommerceOrder;
import ffdd.commerce.domain.PaymentCallbackEvent;
import ffdd.commerce.domain.PaymentRecord;
import ffdd.commerce.dto.OrderPayRequest;
import ffdd.commerce.dto.PaymentAnomalyResponse;
import ffdd.commerce.dto.PaymentCallbackResponse;
import ffdd.commerce.dto.PaymentCheckoutRequest;
import ffdd.commerce.dto.PaymentCheckoutResponse;
import ffdd.commerce.dto.PaymentOpsResult;
import ffdd.commerce.dto.PaymentRecordQueryRequest;
import ffdd.commerce.dto.PaymentReconcileResponse;
import ffdd.commerce.mapper.PaymentCallbackEventMapper;
import ffdd.commerce.mapper.PaymentRecordMapper;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PaymentService {
    private static final String PAYMENT_PENDING = "PENDING";
    private static final String PAYMENT_PAID = "PAID";
    private static final String PAYMENT_FAILED = "FAILED";
    private static final String PAYMENT_EXPIRED = "EXPIRED";
    private static final String EVENT_PROCESSING = "PROCESSING";
    private static final String EVENT_SUCCESS = "SUCCESS";
    private static final String EVENT_FAILED = "FAILED";
    private static final String SIGNATURE_VERIFIED = "VERIFIED";
    private static final String DEFAULT_CURRENCY = "USDT";
    private static final int MAX_RAW_PAYLOAD_LENGTH = 4096;
    private static final long RECONCILE_RETRY_DELAY_SECONDS = 60;

    private final PaymentRecordMapper recordMapper;
    private final PaymentCallbackEventMapper eventMapper;
    private final CommerceService commerceService;
    private final Map<String, PaymentProvider> providers;
    private final Clock clock;

    @Autowired
    public PaymentService(
            PaymentRecordMapper recordMapper,
            PaymentCallbackEventMapper eventMapper,
            CommerceService commerceService,
            List<PaymentProvider> providers) {
        this(recordMapper, eventMapper, commerceService, providers, Clock.systemDefaultZone());
    }

    PaymentService(
            PaymentRecordMapper recordMapper,
            PaymentCallbackEventMapper eventMapper,
            CommerceService commerceService,
            List<PaymentProvider> providers,
            Clock clock) {
        this.recordMapper = recordMapper;
        this.eventMapper = eventMapper;
        this.commerceService = commerceService;
        this.providers = indexProviders(providers);
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public PaymentCheckoutResponse checkout(PaymentCheckoutRequest request) {
        if (request == null) {
            throw new BizException("Payment checkout request is required");
        }
        String providerCode = normalizeProvider(request.getProvider());
        PaymentProvider provider = requireProvider(providerCode);
        CommerceOrder order = commerceService.getOrder(request.getOrderNo());
        if (!PAYMENT_PENDING.equals(order.getPaymentStatus())) {
            throw new BizException("Order is not pending payment");
        }
        if (order.getAmountUsdt() == null || order.getAmountUsdt().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("Order amount is invalid");
        }

        PaymentRecord existing = findReusablePendingRecord(order.getOrderNo(), providerCode);
        if (existing != null) {
            return toCheckoutResponse(existing);
        }

        String paymentNo = nextPaymentNo();
        PaymentSession session = provider.createSession(order, paymentNo, request);
        PaymentRecord record = new PaymentRecord();
        record.setPaymentNo(paymentNo);
        record.setOrderNo(order.getOrderNo());
        record.setUserId(order.getUserId());
        record.setProvider(providerCode);
        record.setProviderPaymentId(session.providerPaymentId());
        record.setAmountUsdt(order.getAmountUsdt());
        record.setCurrency(DEFAULT_CURRENCY);
        record.setPaymentStatus(PAYMENT_PENDING);
        record.setCheckoutUrl(session.checkoutUrl());
        record.setExpiresAt(session.expiresAt());
        record.setIsDeleted(0);
        recordMapper.insert(record);
        return toCheckoutResponse(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public PaymentCallbackResponse handleCallback(String providerCode, Map<String, String> headers, String rawBody) {
        String provider = normalizeProvider(providerCode);
        PaymentProvider paymentProvider = requireProvider(provider);
        PaymentProviderCallback callback = paymentProvider.parseAndVerifyCallback(headers, rawBody);
        String eventStatus = normalizeCallbackStatus(callback.status());

        PaymentCallbackEvent existingEvent = findCallbackEvent(provider, callback.eventId());
        if (existingEvent != null) {
            return toDuplicateResponse(existingEvent);
        }

        PaymentCallbackEvent event;
        try {
            event = insertCallbackEvent(provider, callback, eventStatus, rawBody);
        } catch (DuplicateKeyException ex) {
            PaymentCallbackEvent existingEventAfterRace = findCallbackEvent(provider, callback.eventId());
            if (existingEventAfterRace != null) {
                return toDuplicateResponse(existingEventAfterRace);
            }
            throw ex;
        }
        if (!EVENT_PROCESSING.equals(event.getProcessingStatus())) {
            return toDuplicateResponse(event);
        }
        try {
            PaymentRecord record = requireRecord(provider, callback);
            validateCallback(record, callback);
            if (PAYMENT_PAID.equals(eventStatus)) {
                return processPaidCallback(record, event, callback, rawBody);
            }
            if (PAYMENT_FAILED.equals(eventStatus)) {
                return processFailedCallback(record, event, callback, rawBody);
            }
            markEventSuccess(event, null);
            return new PaymentCallbackResponse(
                    provider,
                    callback.eventId(),
                    record.getPaymentNo(),
                    record.getOrderNo(),
                    record.getPaymentStatus(),
                    null,
                    null,
                    false,
                    "Payment callback recorded");
        } catch (BizException ex) {
            markEventFailed(event, ex.getMessage());
            throw ex;
        }
    }

    public PageResult<PaymentRecord> pageRecords(PaymentRecordQueryRequest request) {
        PaymentRecordQueryRequest query = request == null ? new PaymentRecordQueryRequest() : request;
        long pageNum = normalizePageNum(query.getPageNum());
        long pageSize = normalizePageSize(query.getPageSize());
        LambdaQueryWrapper<PaymentRecord> wrapper = new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getIsDeleted, 0)
                .eq(query.getUserId() != null, PaymentRecord::getUserId, query.getUserId())
                .eq(StringUtils.hasText(query.getOrderNo()), PaymentRecord::getOrderNo, query.getOrderNo())
                .eq(StringUtils.hasText(query.getPaymentNo()), PaymentRecord::getPaymentNo, query.getPaymentNo())
                .eq(StringUtils.hasText(query.getProvider()), PaymentRecord::getProvider, normalizeProvider(query.getProvider()))
                .eq(StringUtils.hasText(query.getPaymentStatus()), PaymentRecord::getPaymentStatus, normalizeStatus(query.getPaymentStatus()))
                .orderByDesc(PaymentRecord::getCreatedAt);
        Page<PaymentRecord> page = recordMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public PaymentRecord getRecord(String paymentNo) {
        PaymentRecord record = recordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getPaymentNo, paymentNo)
                .eq(PaymentRecord::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (record == null) {
            throw new BizException("Payment record not found");
        }
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public PaymentOpsResult expirePendingPayments(int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PaymentRecord> records = recordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getPaymentStatus, PAYMENT_PENDING)
                .eq(PaymentRecord::getIsDeleted, 0)
                .isNotNull(PaymentRecord::getExpiresAt)
                .le(PaymentRecord::getExpiresAt, now)
                .orderByAsc(PaymentRecord::getExpiresAt)
                .last("LIMIT " + normalizeLimit(limit)));
        PaymentOpsResult result = new PaymentOpsResult();
        for (PaymentRecord record : records) {
            result.incrementScanned();
            if (!PAYMENT_PENDING.equals(record.getPaymentStatus())) {
                result.incrementSkipped();
                continue;
            }
            PaymentRecord patch = paymentRecordPatch(record);
            patch.setPaymentStatus(PAYMENT_EXPIRED);
            patch.setExpiredAt(now);
            patch.setFailedAt(now);
            patch.setFailureReason("Payment session expired");
            recordMapper.updateById(patch);
            result.incrementExpired(record.getPaymentNo());
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public PaymentReconcileResponse reconcilePayment(String paymentNo) {
        if (!StringUtils.hasText(paymentNo)) {
            throw new BizException("Payment no is required");
        }
        PaymentRecord record = getRecord(paymentNo);
        PaymentProvider provider = requireProvider(record.getProvider());
        try {
            PaymentProviderStatus providerStatus = provider.queryPayment(record);
            if (providerStatus == null) {
                markReconcileSuccess(record, null);
                return toReconcileResponse(record, null, null, false, "Payment provider returned no status");
            }
            validateProviderStatus(record, providerStatus);
            String status = normalizeProviderStatus(providerStatus.status());
            if (PAYMENT_PAID.equals(status)) {
                return reconcilePaid(record, providerStatus);
            }
            if (PAYMENT_FAILED.equals(status)) {
                return reconcileFailed(record, providerStatus);
            }
            if (PAYMENT_EXPIRED.equals(status)) {
                return reconcileExpired(record, providerStatus);
            }
            markReconcileSuccess(record, null);
            return toReconcileResponse(record, providerStatus, null, false, "Payment is still pending");
        } catch (RuntimeException ex) {
            markReconcileFailure(record, ex);
            throw ex;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public PaymentOpsResult reconcileDuePayments(int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PaymentRecord> records = recordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getPaymentStatus, PAYMENT_PENDING)
                .eq(PaymentRecord::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .isNull(PaymentRecord::getNextReconcileAt)
                        .or()
                        .le(PaymentRecord::getNextReconcileAt, now))
                .orderByAsc(PaymentRecord::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
        PaymentOpsResult result = new PaymentOpsResult();
        for (PaymentRecord record : records) {
            result.incrementScanned();
            try {
                PaymentReconcileResponse response = reconcilePayment(record.getPaymentNo());
                result.incrementReconciled(record.getPaymentNo());
                if (PAYMENT_PAID.equals(response.getPaymentStatus())) {
                    result.incrementPaid();
                } else if (PAYMENT_FAILED.equals(response.getPaymentStatus())
                        || PAYMENT_EXPIRED.equals(response.getPaymentStatus())) {
                    result.incrementFailed();
                } else if (!response.isChanged()) {
                    result.incrementSkipped();
                }
            } catch (RuntimeException ex) {
                result.incrementErrors();
            }
        }
        return result;
    }

    public List<PaymentAnomalyResponse> listPaymentAnomalies(int limit) {
        List<PaymentRecord> records = recordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getIsDeleted, 0)
                .orderByDesc(PaymentRecord::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
        LocalDateTime now = LocalDateTime.now(clock);
        List<PaymentAnomalyResponse> anomalies = new ArrayList<>();
        for (PaymentRecord record : records) {
            CommerceOrder order;
            try {
                order = commerceService.getOrder(record.getOrderNo());
            } catch (BizException ex) {
                anomalies.add(new PaymentAnomalyResponse(
                        "ORDER_NOT_FOUND",
                        record.getProvider(),
                        record.getPaymentNo(),
                        record.getOrderNo(),
                        record.getPaymentStatus(),
                        null,
                        "Payment record has no matching order"));
                continue;
            }
            if (PAYMENT_PAID.equals(record.getPaymentStatus())
                    && !PAYMENT_PAID.equals(order.getPaymentStatus())) {
                anomalies.add(new PaymentAnomalyResponse(
                        "PAYMENT_PAID_ORDER_NOT_PAID",
                        record.getProvider(),
                        record.getPaymentNo(),
                        record.getOrderNo(),
                        record.getPaymentStatus(),
                        order.getPaymentStatus(),
                        "Payment is paid but order is not marked paid"));
            } else if (PAYMENT_PAID.equals(order.getPaymentStatus())
                    && !PAYMENT_PAID.equals(record.getPaymentStatus())) {
                anomalies.add(new PaymentAnomalyResponse(
                        "ORDER_PAID_PAYMENT_NOT_PAID",
                        record.getProvider(),
                        record.getPaymentNo(),
                        record.getOrderNo(),
                        record.getPaymentStatus(),
                        order.getPaymentStatus(),
                        "Order is paid but payment record is not paid"));
            } else if (PAYMENT_PENDING.equals(record.getPaymentStatus())
                    && record.getExpiresAt() != null
                    && !record.getExpiresAt().isAfter(now)) {
                anomalies.add(new PaymentAnomalyResponse(
                        "PENDING_PAYMENT_EXPIRED",
                        record.getProvider(),
                        record.getPaymentNo(),
                        record.getOrderNo(),
                        record.getPaymentStatus(),
                        order.getPaymentStatus(),
                        "Payment session expired but record is still pending"));
            }
        }
        return anomalies;
    }

    private PaymentCallbackResponse processPaidCallback(
            PaymentRecord record, PaymentCallbackEvent event, PaymentProviderCallback callback, String rawBody) {
        CommerceOrder order = null;
        if (!PAYMENT_PAID.equals(record.getPaymentStatus())) {
            PaymentRecord patch = paymentRecordPatch(record);
            patch.setPaymentStatus(PAYMENT_PAID);
            patch.setCallbackEventId(callback.eventId());
            patch.setSignatureStatus(SIGNATURE_VERIFIED);
            patch.setRawCallback(truncate(rawBody, MAX_RAW_PAYLOAD_LENGTH));
            patch.setPaidAt(LocalDateTime.now(clock));
            patch.setFailureReason(null);
            recordMapper.updateById(patch);

            OrderPayRequest payRequest = new OrderPayRequest();
            payRequest.setPaymentNo(record.getPaymentNo());
            order = commerceService.markPaid(record.getOrderNo(), payRequest);
            record.setPaymentStatus(PAYMENT_PAID);
            record.setPaidAt(patch.getPaidAt());
        }
        markEventSuccess(event, null);
        return new PaymentCallbackResponse(
                record.getProvider(),
                callback.eventId(),
                record.getPaymentNo(),
                record.getOrderNo(),
                PAYMENT_PAID,
                order == null ? PAYMENT_PAID : order.getPaymentStatus(),
                order == null ? null : order.getActivationStatus(),
                false,
                "Payment callback processed");
    }

    private PaymentCallbackResponse processFailedCallback(
            PaymentRecord record, PaymentCallbackEvent event, PaymentProviderCallback callback, String rawBody) {
        if (!PAYMENT_PAID.equals(record.getPaymentStatus())) {
            PaymentRecord patch = paymentRecordPatch(record);
            patch.setPaymentStatus(PAYMENT_FAILED);
            patch.setCallbackEventId(callback.eventId());
            patch.setSignatureStatus(SIGNATURE_VERIFIED);
            patch.setRawCallback(truncate(rawBody, MAX_RAW_PAYLOAD_LENGTH));
            patch.setFailedAt(LocalDateTime.now(clock));
            patch.setFailureReason(truncate(callback.failureReason(), 255));
            recordMapper.updateById(patch);
            record.setPaymentStatus(PAYMENT_FAILED);
        }
        markEventSuccess(event, null);
        return new PaymentCallbackResponse(
                record.getProvider(),
                callback.eventId(),
                record.getPaymentNo(),
                record.getOrderNo(),
                record.getPaymentStatus(),
                null,
                null,
                false,
                "Payment failure callback processed");
    }

    private PaymentReconcileResponse reconcilePaid(PaymentRecord record, PaymentProviderStatus providerStatus) {
        CommerceOrder order = null;
        boolean changed = !PAYMENT_PAID.equals(record.getPaymentStatus());
        if (changed) {
            PaymentRecord patch = reconcileSuccessPatch(record);
            patch.setPaymentStatus(PAYMENT_PAID);
            patch.setCallbackEventId(providerStatus.providerEventId());
            patch.setPaidAt(LocalDateTime.now(clock));
            patch.setFailureReason(null);
            recordMapper.updateById(patch);

            OrderPayRequest payRequest = new OrderPayRequest();
            payRequest.setPaymentNo(record.getPaymentNo());
            order = commerceService.markPaid(record.getOrderNo(), payRequest);
            record.setPaymentStatus(PAYMENT_PAID);
        } else {
            markReconcileSuccess(record, providerStatus);
        }
        return toReconcileResponse(record, providerStatus, order, changed, "Payment reconciled as paid");
    }

    private PaymentReconcileResponse reconcileFailed(PaymentRecord record, PaymentProviderStatus providerStatus) {
        boolean changed = !PAYMENT_PAID.equals(record.getPaymentStatus()) && !PAYMENT_FAILED.equals(record.getPaymentStatus());
        if (changed) {
            PaymentRecord patch = reconcileSuccessPatch(record);
            patch.setPaymentStatus(PAYMENT_FAILED);
            patch.setCallbackEventId(providerStatus.providerEventId());
            patch.setFailedAt(LocalDateTime.now(clock));
            patch.setFailureReason(truncate(
                    providerStatus.failureReason() == null ? "Payment provider reported failure" : providerStatus.failureReason(),
                    255));
            recordMapper.updateById(patch);
            record.setPaymentStatus(PAYMENT_FAILED);
        } else {
            markReconcileSuccess(record, providerStatus);
        }
        return toReconcileResponse(record, providerStatus, null, changed, "Payment reconciled as failed");
    }

    private PaymentReconcileResponse reconcileExpired(PaymentRecord record, PaymentProviderStatus providerStatus) {
        boolean changed = PAYMENT_PENDING.equals(record.getPaymentStatus());
        if (changed) {
            PaymentRecord patch = reconcileSuccessPatch(record);
            patch.setPaymentStatus(PAYMENT_EXPIRED);
            patch.setCallbackEventId(providerStatus.providerEventId());
            patch.setExpiredAt(LocalDateTime.now(clock));
            patch.setFailedAt(LocalDateTime.now(clock));
            patch.setFailureReason(truncate(
                    providerStatus.failureReason() == null ? "Payment provider reported expiration" : providerStatus.failureReason(),
                    255));
            recordMapper.updateById(patch);
            record.setPaymentStatus(PAYMENT_EXPIRED);
        } else {
            markReconcileSuccess(record, providerStatus);
        }
        return toReconcileResponse(record, providerStatus, null, changed, "Payment reconciled as expired");
    }

    private PaymentRecord requireRecord(String provider, PaymentProviderCallback callback) {
        PaymentRecord record = recordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getProvider, provider)
                .eq(PaymentRecord::getPaymentNo, callback.paymentNo())
                .eq(PaymentRecord::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (record == null) {
            throw new BizException("Payment record not found");
        }
        return record;
    }

    private void validateCallback(PaymentRecord record, PaymentProviderCallback callback) {
        if (!record.getProviderPaymentId().equals(callback.providerPaymentId())) {
            throw new BizException("Payment callback providerPaymentId mismatch");
        }
        if (!record.getOrderNo().equals(callback.orderNo())) {
            throw new BizException("Payment callback orderNo mismatch");
        }
        if (record.getAmountUsdt().compareTo(callback.amountUsdt()) != 0) {
            throw new BizException("Payment callback amount mismatch");
        }
        String currency = normalizeCurrency(callback.currency());
        if (!record.getCurrency().equals(currency)) {
            throw new BizException("Payment callback currency mismatch");
        }
    }

    private void validateProviderStatus(PaymentRecord record, PaymentProviderStatus providerStatus) {
        if (StringUtils.hasText(providerStatus.providerPaymentId())
                && !record.getProviderPaymentId().equals(providerStatus.providerPaymentId())) {
            throw new BizException("Payment provider status providerPaymentId mismatch");
        }
        if (providerStatus.amountUsdt() != null && record.getAmountUsdt().compareTo(providerStatus.amountUsdt()) != 0) {
            throw new BizException("Payment provider status amount mismatch");
        }
        String currency = normalizeCurrency(providerStatus.currency());
        if (!record.getCurrency().equals(currency)) {
            throw new BizException("Payment provider status currency mismatch");
        }
    }

    private PaymentRecord findReusablePendingRecord(String orderNo, String provider) {
        return recordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .eq(PaymentRecord::getProvider, provider)
                .eq(PaymentRecord::getPaymentStatus, PAYMENT_PENDING)
                .eq(PaymentRecord::getIsDeleted, 0)
                .orderByDesc(PaymentRecord::getCreatedAt)
                .last("LIMIT 1"));
    }

    private PaymentCallbackEvent findCallbackEvent(String provider, String providerEventId) {
        return eventMapper.selectOne(new LambdaQueryWrapper<PaymentCallbackEvent>()
                .eq(PaymentCallbackEvent::getProvider, provider)
                .eq(PaymentCallbackEvent::getProviderEventId, providerEventId)
                .eq(PaymentCallbackEvent::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private PaymentCallbackEvent insertCallbackEvent(
            String provider, PaymentProviderCallback callback, String eventStatus, String rawBody) {
        PaymentCallbackEvent event = new PaymentCallbackEvent();
        event.setProvider(provider);
        event.setProviderEventId(callback.eventId());
        event.setPaymentNo(callback.paymentNo());
        event.setOrderNo(callback.orderNo());
        event.setEventStatus(eventStatus);
        event.setProcessingStatus(EVENT_PROCESSING);
        event.setSignatureStatus(SIGNATURE_VERIFIED);
        event.setRawPayload(truncate(rawBody, MAX_RAW_PAYLOAD_LENGTH));
        event.setIsDeleted(0);
        eventMapper.insert(event);
        return event;
    }

    private void markEventSuccess(PaymentCallbackEvent event, String message) {
        PaymentCallbackEvent patch = callbackEventPatch(event);
        patch.setProcessingStatus(EVENT_SUCCESS);
        patch.setFailureReason(message);
        eventMapper.updateById(patch);
    }

    private void markEventFailed(PaymentCallbackEvent event, String failureReason) {
        PaymentCallbackEvent patch = callbackEventPatch(event);
        patch.setProcessingStatus(EVENT_FAILED);
        patch.setFailureReason(truncate(failureReason, 255));
        eventMapper.updateById(patch);
    }

    private void markReconcileSuccess(PaymentRecord record, PaymentProviderStatus providerStatus) {
        PaymentRecord patch = reconcileSuccessPatch(record);
        if (providerStatus != null && StringUtils.hasText(providerStatus.providerEventId())) {
            patch.setCallbackEventId(providerStatus.providerEventId());
        }
        recordMapper.updateById(patch);
    }

    private void markReconcileFailure(PaymentRecord record, RuntimeException ex) {
        PaymentRecord patch = paymentRecordPatch(record);
        int attempts = reconcileAttempts(record) + 1;
        LocalDateTime now = LocalDateTime.now(clock);
        patch.setReconcileAttempts(attempts);
        patch.setLastReconcileAt(now);
        patch.setNextReconcileAt(now.plusSeconds(RECONCILE_RETRY_DELAY_SECONDS));
        patch.setLastReconcileError(truncate(ex.getMessage(), 512));
        recordMapper.updateById(patch);
    }

    private PaymentRecord reconcileSuccessPatch(PaymentRecord record) {
        PaymentRecord patch = paymentRecordPatch(record);
        patch.setReconcileAttempts(reconcileAttempts(record) + 1);
        patch.setLastReconcileAt(LocalDateTime.now(clock));
        patch.setNextReconcileAt(null);
        patch.setLastReconcileError(null);
        return patch;
    }

    private PaymentRecord paymentRecordPatch(PaymentRecord record) {
        PaymentRecord patch = new PaymentRecord();
        patch.setId(record.getId());
        return patch;
    }

    private PaymentCallbackEvent callbackEventPatch(PaymentCallbackEvent event) {
        PaymentCallbackEvent patch = new PaymentCallbackEvent();
        patch.setId(event.getId());
        return patch;
    }

    private PaymentCallbackResponse toDuplicateResponse(PaymentCallbackEvent event) {
        return new PaymentCallbackResponse(
                event.getProvider(),
                event.getProviderEventId(),
                event.getPaymentNo(),
                event.getOrderNo(),
                event.getEventStatus(),
                null,
                null,
                true,
                "Payment callback event already processed");
    }

    private PaymentReconcileResponse toReconcileResponse(
            PaymentRecord record,
            PaymentProviderStatus providerStatus,
            CommerceOrder order,
            boolean changed,
            String message) {
        return new PaymentReconcileResponse(
                record.getProvider(),
                record.getPaymentNo(),
                record.getOrderNo(),
                providerStatus == null ? null : normalizeProviderStatus(providerStatus.status()),
                record.getPaymentStatus(),
                order == null ? null : order.getPaymentStatus(),
                order == null ? null : order.getActivationStatus(),
                changed,
                message);
    }

    private PaymentCheckoutResponse toCheckoutResponse(PaymentRecord record) {
        return new PaymentCheckoutResponse(
                record.getPaymentNo(),
                record.getOrderNo(),
                record.getProvider(),
                record.getProviderPaymentId(),
                record.getPaymentStatus(),
                record.getAmountUsdt(),
                record.getCurrency(),
                record.getCheckoutUrl(),
                record.getExpiresAt());
    }

    private PaymentProvider requireProvider(String providerCode) {
        PaymentProvider provider = providers.get(providerCode);
        if (provider == null) {
            throw new BizException("Payment provider not supported");
        }
        return provider;
    }

    private Map<String, PaymentProvider> indexProviders(List<PaymentProvider> providers) {
        Map<String, PaymentProvider> index = new HashMap<>();
        if (providers != null) {
            for (PaymentProvider provider : providers) {
                index.put(normalizeProvider(provider.code()), provider);
            }
        }
        return index;
    }

    private String normalizeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toUpperCase(Locale.ROOT) : "MOCK";
    }

    private String normalizeCallbackStatus(String status) {
        String normalized = normalizeStatus(status);
        return switch (normalized) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "PAID" -> PAYMENT_PAID;
            case "FAIL", "FAILED", "CANCELED", "CANCELLED", "EXPIRED" -> PAYMENT_FAILED;
            default -> PAYMENT_PENDING;
        };
    }

    private String normalizeProviderStatus(String status) {
        String normalized = normalizeStatus(status);
        return switch (normalized) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "PAID" -> PAYMENT_PAID;
            case "FAIL", "FAILED", "CANCELED", "CANCELLED" -> PAYMENT_FAILED;
            case "EXPIRED" -> PAYMENT_EXPIRED;
            default -> PAYMENT_PENDING;
        };
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeCurrency(String currency) {
        return StringUtils.hasText(currency) ? currency.trim().toUpperCase(Locale.ROOT) : DEFAULT_CURRENCY;
    }

    private long normalizePageNum(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private int reconcileAttempts(PaymentRecord record) {
        return record.getReconcileAttempts() == null ? 0 : record.getReconcileAttempts();
    }

    private String nextPaymentNo() {
        String date = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "PAY-" + date + "-" + suffix;
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
