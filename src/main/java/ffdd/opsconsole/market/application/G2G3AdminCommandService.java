package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.ExchangeKycReviewRequest;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueBatchRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class G2G3AdminCommandService {
    private final OpsNexMarketService market;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final G2ExchangeQueueBatchService queueBatch;
    private final AuditLogService audit;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> exchangeParam(String key,String paramKey,ExchangeParamUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G2:PARAM:" + paramKey,key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.updateExchangeParam(key,paramKey,request));
            String event = List.of("fee","feeMin").contains(paramKey)
                    ? "admin.exchange_fee_changed" : "admin.exchange_caps_changed";
            outbox.publish("EXCHANGE_CONFIG",paramKey,event,linked("field",paramKey,"value",request.value(),
                    "reason",request.reason().trim(),"operator",actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> pauseExchange(String key,ExchangeSwapStatusRequest request) {
        requireReason(request == null ? null : request.reason());
        if (request == null || !Boolean.FALSE.equals(request.enabled())) {
            throw new BizException(422,"G2_RESUME_MUST_USE_J1");
        }
        String basis = normalizeBasis(request.triggerBasis());
        List<String> geo = request.geoBlock() == null ? List.of() : request.geoBlock().stream()
                .filter(StringUtils::hasText).map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        return once("G2:PAUSE",key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.updateExchangeSwapStatus(key,request));
            outbox.publish("EXCHANGE_CONTROL","exchange","admin.exchange_paused",linked(
                    "reason",request.reason().trim(),"operator",actor(request.operator()),
                    "geoBlock",geo,"triggerBasis",basis));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> cancelExchange(String key,String exchangeNo,ExchangeQueueCancelRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G2:CANCEL:" + exchangeNo,key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.cancelExchangeQueueOrder(key,exchangeNo,request));
            outbox.publish("EXCHANGE_ORDER",exchangeNo,"admin.exchange_queue_cancelled",linked(
                    "exchangeNo",exchangeNo,"reason",request.reason().trim(),"operator",actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> kycReview(String key,String exchangeNo,ExchangeKycReviewRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G2:KYC:" + exchangeNo,key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.triggerExchangeKycReview(key,exchangeNo,request));
            outbox.publish("EXCHANGE_ORDER",exchangeNo,"admin.exchange_kyc_review_requested",linked(
                    "exchangeNo",exchangeNo,"reason",request.reason().trim(),"operator",actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> processQueue(String key,ExchangeQueueBatchRequest request) {
        requireReason(request == null ? null : request.reason());
        int limit = request.limit() == null ? 50 : request.limit();
        return once("G2:QUEUE_BATCH",key,request,() -> {
            Map<String,Object> batch = queueBatch.process(limit);
            outbox.publish("EXCHANGE_QUEUE","daily","admin.exchange_queue_batch_processed",linked(
                    "completedCount",batch.get("completedCount"),"skippedCount",batch.get("skippedCount"),
                    "reason",request.reason().trim(),"operator",actor(request.operator())));
            audit.recordRequired(AuditLogWriteRequest.builder().action("G2_EXCHANGE_QUEUE_BATCH_PROCESSED")
                    .resourceType("EXCHANGE_QUEUE").resourceId("daily").actorType("ADMIN")
                    .actorUsername(actor(request.operator())).method("POST").path("/api/admin/market/exchange/queue/process")
                    .result("SUCCESS").riskLevel("HIGH").detail(linked("reason",request.reason().trim(),"batch",batch)).build());
            Map<String,Object> data = new LinkedHashMap<>(market.exchangeOverview().getData());
            data.put("batch",batch);
            return ApiResult.ok(data);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> weeklyCurve(String key,NexMarketCurveUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G3:CURVE",key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.updateWeeklyCurve(key,request));
            outbox.publish("NEX_MARKET","weekly","admin.nex_price_curve_changed",linked(
                    "frameCount",request.frames() == null ? 0 : request.frames().size(),
                    "reason",request.reason().trim(),"operator",actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> advance(String key,NexMarketAdvanceRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G3:ADVANCE",key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.advanceCurrentFrame(key,request));
            outbox.publish("NEX_MARKET","weekly","market.curve_advanced",linked(
                    "mode","MANUAL","reason",request.reason().trim(),"operator",actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> control(String key,String controlKey,NexMarketValueUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G3:CONTROL:" + controlKey,key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.updateControl(key,controlKey,request));
            outbox.publish("NEX_MARKET","weekly","admin.market_schedule_changed",linked(
                    "field",controlKey,"value",request.value(),"reason",request.reason().trim(),
                    "operator",actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> override(String key,String overrideKey,NexMarketValueUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("G3:OVERRIDE:" + overrideKey,key,request,() -> {
            ApiResult<Map<String,Object>> result = success(market.updateOverride(key,overrideKey,request));
            String event = switch (overrideKey) {
                case "oracle" -> "admin.nex_oracle_source_changed";
                case "currentPrice" -> "admin.nex_price_overridden";
                case "paused" -> parseBoolean(request.value()) ? "admin.market_paused" : "admin.market_resumed";
                default -> "admin.nex_market_override_changed";
            };
            outbox.publish("NEX_MARKET","weekly",event,linked("field",overrideKey,"value",request.value(),
                    "reason",request.reason().trim(),"operator",actor(request.operator())));
            return result;
        });
    }

    private void requireReason(String reason) {
        if (!StringUtils.hasText(reason)) throw new BizException(422,"REASON_REQUIRED");
        int length = reason.trim().length();
        if (length < 8 || length > 200) throw new BizException(422,"REASON_LENGTH_INVALID");
    }
    private String normalizeBasis(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!List.of("REGULATORY","PRICE_ANOMALY","SECURITY_INCIDENT","OTHER").contains(normalized))
            throw new BizException(422,"G2_TRIGGER_BASIS_INVALID");
        return normalized;
    }
    private boolean parseBoolean(String value) {
        return value != null && List.of("true","1","on","enabled").contains(value.trim().toLowerCase(Locale.ROOT));
    }
    private String actor(String requested) { return AdminActorResolver.resolve(requested); }
    private ApiResult<Map<String,Object>> success(ApiResult<Map<String,Object>> result) {
        if (result == null || result.getCode() != 0) throw new BizException(result == null ? 500 : result.getCode(),
                result == null ? "G_DOMAIN_COMMAND_FAILED" : result.getMessage());
        return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private ApiResult<Map<String,Object>> once(String scope,String key,Object request,Supplier<ApiResult<Map<String,Object>>> action) {
        return (ApiResult<Map<String,Object>>) (ApiResult) idempotency.execute("ADMIN:" + scope,key,sha256(String.valueOf(request)),
                ApiResult.class,(Supplier) action);
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private Map<String,Object> linked(Object... values) {
        Map<String,Object> map = new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2) map.put(String.valueOf(values[i]),values[i+1]); return map;
    }
}
