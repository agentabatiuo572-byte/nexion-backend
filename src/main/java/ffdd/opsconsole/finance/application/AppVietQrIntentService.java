package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppVietQrIntentService {
    private static final BigDecimal MIN_DEPOSIT_USDT = new BigDecimal("10.00");
    private static final BigDecimal ABSOLUTE_MAX_DEPOSIT_USDT = new BigDecimal("10000.00");
    private static final int MAX_ACTIVE_INTENTS = 5;
    private static final int MAX_LIST_LIMIT = 50;

    private final AppVietQrIntentMapper mapper;
    private final FinanceSensitiveDataCipher cipher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> paymentConfig() {
        Map<String, Object> config = required(mapper.findVietQrConfig(), "PAYMENT_CONFIG_UNAVAILABLE");
        Map<String, Object> vietQr = new LinkedHashMap<>();
        vietQr.put("enabled", mapper.countAvailableBankAccounts() > 0);
        vietQr.put("minDepositUsdt", MIN_DEPOSIT_USDT);
        vietQr.put("maxDepositUsdt", decimal(config.get("perTxLimitUsd")));
        vietQr.put("toleranceVnd", decimal(config.get("toleranceVnd")));
        vietQr.put("graceMinutes", integer(config.get("graceMinutes")));
        vietQr.put("version", longValue(config.get("version")));
        return ApiResult.ok(Map.of("vietQr", vietQr));
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> fxQuote(String fiat, String asset) {
        if (!"VND".equalsIgnoreCase(clean(fiat)) || !"USDT".equalsIgnoreCase(clean(asset))) {
            throw new BizException(422, "FX_PAIR_NOT_SUPPORTED");
        }
        Map<String, Object> config = required(mapper.findFxQuoteConfig(), "FX_QUOTE_UNAVAILABLE");
        BigDecimal base = decimal(config.get("baseRateVndPerUsdt"));
        BigDecimal spread = decimal(config.get("buySpreadPct"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseRateVndPerUsdt", base);
        result.put("buySpreadPct", spread);
        result.put("quoteRateVndPerUsdt", VietnamPaymentPolicy.quoteRate(base, spread));
        result.put("lockWindowMinutes", integer(config.get("lockWindowMinutes")));
        result.put("version", longValue(config.get("version")));
        result.put("asOf", Instant.now(clock).toString());
        return ApiResult.ok(result);
    }

    @Transactional
    public ApiResult<Map<String, Object>> create(
            Long userId, String idempotencyKey, BigDecimal requestedAmount) {
        requireUser(userId);
        String key = commandKey(idempotencyKey);
        BigDecimal amount = depositAmount(requestedAmount);
        String requestHash = sha256(amount.toPlainString());
        if (mapper.lockActiveUserForIntentCreation(userId) == null) {
            throw new BizException(403, "USER_NOT_ACTIVE");
        }

        Map<String, Object> replay = mapper.findIntentByCreateKey(userId, key);
        if (replay != null) {
            mapper.ensureInFlightReconciliation(text(replay.get("intentNo")));
            return replayCreate(replay, requestHash);
        }

        Map<String, Object> vietQr = required(mapper.findVietQrConfig(), "PAYMENT_CONFIG_UNAVAILABLE");
        BigDecimal max = decimal(vietQr.get("perTxLimitUsd")).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.compareTo(MIN_DEPOSIT_USDT) < 0 || amount.compareTo(max) > 0) {
            throw new BizException(422, "VIETQR_AMOUNT_OUT_OF_RANGE");
        }
        if (mapper.countActiveIntentsForUser(userId) >= MAX_ACTIVE_INTENTS) {
            throw new BizException(409, "VIETQR_ACTIVE_INTENT_LIMIT_REACHED");
        }

        Map<String, Object> fx = required(mapper.findFxQuoteConfig(), "FX_QUOTE_UNAVAILABLE");
        BigDecimal rate = VietnamPaymentPolicy.quoteRate(
                decimal(fx.get("baseRateVndPerUsdt")), decimal(fx.get("buySpreadPct")));
        BigDecimal payableVnd = amount.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        int lockMinutes = integer(fx.get("lockWindowMinutes"));
        if (lockMinutes <= 0) {
            throw new BizException(503, "FX_QUOTE_UNAVAILABLE");
        }
        LocalDateTime expiresAt = LocalDateTime.now(clock).plusMinutes(lockMinutes);

        List<Map<String, Object>> accounts = safeList(mapper.listActiveBankAccountsForUpdate());
        Map<String, Object> account = selectAccount(
                accounts, payableVnd, text(vietQr.get("rotationStrategy")));
        if (account == null) {
            throw new BizException(503, "VIETQR_BANK_RAIL_UNAVAILABLE");
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            String intentNo = "VQR-" + compactUuid(20);
            String memoCode = "NX-" + compactUuid(8);
            int inserted = mapper.insertIntent(
                    intentNo, userId, key, requestHash, amount, payableVnd, rate,
                    longValue(fx.get("version")), longValue(account.get("id")), memoCode, expiresAt);
            mapper.ensureInFlightReconciliation(intentNo);
            Map<String, Object> concurrentReplay = mapper.findIntentByCreateKey(userId, key);
            if (concurrentReplay != null) {
                return replayCreate(concurrentReplay, requestHash);
            }
            if (inserted == 1) {
                throw new BizException(503, "VIETQR_INTENT_READ_AFTER_WRITE_FAILED");
            }
        }
        throw new BizException(409, "VIETQR_INTENT_UNIQUE_ALLOCATION_CONFLICT");
    }

    @Transactional
    public ApiResult<Map<String, Object>> get(Long userId, String intentNo) {
        requireUser(userId);
        String normalizedIntentNo = intentNo(intentNo);
        mapper.expireIntentForUser(userId, normalizedIntentNo);
        mapper.closeInactiveInFlightReconciliationsForUser(userId);
        return ApiResult.ok(toView(required(
                mapper.findIntentForUser(userId, normalizedIntentNo), "VIETQR_INTENT_NOT_FOUND", 404)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> list(Long userId, Integer requestedLimit) {
        requireUser(userId);
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(requestedLimit, MAX_LIST_LIMIT));
        mapper.expireIntentsForUser(userId);
        mapper.closeInactiveInFlightReconciliationsForUser(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : safeList(mapper.listIntentsForUser(userId, limit))) {
            items.add(toView(row));
        }
        return ApiResult.ok(Map.of("items", items));
    }

    @Transactional
    public ApiResult<Map<String, Object>> cancel(
            Long userId, String intentNo, String idempotencyKey, Long expectedVersion) {
        requireUser(userId);
        String normalizedIntentNo = intentNo(intentNo);
        String key = commandKey(idempotencyKey);
        if (expectedVersion == null || expectedVersion < 0) {
            throw new BizException(422, "VIETQR_EXPECTED_VERSION_REQUIRED");
        }
        String requestHash = sha256(normalizedIntentNo + "|" + expectedVersion);
        mapper.expireIntentForUser(userId, normalizedIntentNo);
        mapper.closeInactiveInFlightReconciliationsForUser(userId);
        Map<String, Object> before = required(
                mapper.findIntentForUser(userId, normalizedIntentNo), "VIETQR_INTENT_NOT_FOUND", 404);
        String status = text(before.get("status"));
        if ("CANCELLED".equals(status)) {
            if (key.equals(text(before.get("cancelIdempotencyKey")))
                    && requestHash.equals(text(before.get("cancelRequestHash")))) {
                return ApiResult.ok(toView(before));
            }
            throw new BizException(409, "VIETQR_INTENT_ALREADY_CANCELLED");
        }
        if (!"AWAITING_PAYMENT".equals(status)) {
            throw new BizException(409, "VIETQR_INTENT_NOT_CANCELLABLE");
        }
        if (longValue(before.get("version")) != expectedVersion) {
            throw new BizException(409, "VIETQR_INTENT_VERSION_CONFLICT");
        }
        if (mapper.cancelIntent(
                userId, normalizedIntentNo, expectedVersion, key, requestHash) != 1) {
            throw new BizException(409, "VIETQR_INTENT_VERSION_CONFLICT");
        }
        mapper.closeInFlightReconciliation(normalizedIntentNo, "CANCELLED");
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", "CANCELLED");
        after.put("version", expectedVersion + 1);
        after.put("cancelIdempotencyKey", key);
        after.put("cancelRequestHash", requestHash);
        return ApiResult.ok(toView(after));
    }

    private ApiResult<Map<String, Object>> replayCreate(
            Map<String, Object> existing, String requestHash) {
        if (!requestHash.equals(text(existing.get("createRequestHash")))) {
            throw new BizException(409, "IDEMPOTENCY_REQUEST_CONFLICT");
        }
        return ApiResult.ok(toView(existing));
    }

    private Map<String, Object> selectAccount(
            List<Map<String, Object>> accounts, BigDecimal payableVnd, String rotationStrategy) {
        List<Map<String, Object>> eligible = new ArrayList<>();
        for (Map<String, Object> account : accounts) {
            long id = longValue(account.get("id"));
            BigDecimal committed = decimal(account.get("receivedTodayVnd"))
                    .add(decimal(mapper.sumActiveReservedVnd(id)));
            if (committed.add(payableVnd).compareTo(decimal(account.get("dailyCapVnd"))) <= 0) {
                Map<String, Object> candidate = new LinkedHashMap<>(account);
                candidate.put("remainingCapacityVnd",
                        decimal(account.get("dailyCapVnd")).subtract(committed).subtract(payableVnd));
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) return null;
        if ("REMAINING_CAPACITY".equalsIgnoreCase(rotationStrategy)) {
            Map<String, Object> best = eligible.get(0);
            for (Map<String, Object> candidate : eligible) {
                if (decimal(candidate.get("remainingCapacityVnd"))
                        .compareTo(decimal(best.get("remainingCapacityVnd"))) > 0) {
                    best = candidate;
                }
            }
            return best;
        }
        Long lastAssigned = mapper.findLastAssignedBankAccountId();
        if (lastAssigned == null) return eligible.get(0);
        for (Map<String, Object> candidate : eligible) {
            if (longValue(candidate.get("id")) > lastAssigned) return candidate;
        }
        return eligible.get(0);
    }

    private Map<String, Object> toView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("intentNo", text(row.get("intentNo")));
        view.put("usdtAmount", decimal(row.get("requestedUsdt")));
        view.put("fxRate", decimal(row.get("lockedFxRateVndPerUsdt")));
        view.put("vndAmount", decimal(row.get("payableVnd")));
        view.put("memoCode", text(row.get("memoCode")));
        String status = text(row.get("status"));
        String last4 = text(row.get("accountNumberLast4"));
        String accountNumber = "****" + last4;
        if ("AWAITING_PAYMENT".equals(status)
                && "ACTIVE".equals(text(row.get("bankAccountStatus")))
                && StringUtils.hasText(text(row.get("accountNumberEncrypted")))) {
            accountNumber = cipher.decrypt(
                    text(row.get("accountNumberEncrypted")),
                    text(row.get("accountNumberHash")));
        }
        view.put("bankAccount", Map.of(
                "accountName", text(row.get("accountHolder")),
                "accountNumber", accountNumber,
                "bankName", text(row.get("bankName"))));
        view.put("status", status.toLowerCase(Locale.ROOT));
        view.put("expiresAt", isoInstant(row.get("expiresAt")));
        view.put("creditedUsdt", decimal(row.get("creditedUsdt")));
        view.put("version", longValue(row.get("version")));
        putIfPresent(view, "receivedVnd", row.get("receivedVnd"));
        if (row.get("matchedAt") != null) view.put("matchedAt", isoInstant(row.get("matchedAt")));
        if (row.get("createdAt") != null) view.put("createdAt", isoInstant(row.get("createdAt")));
        return view;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private String isoInstant(Object value) {
        LocalDateTime local = null;
        if (value instanceof LocalDateTime localDateTime) local = localDateTime;
        if (value instanceof java.sql.Timestamp timestamp) local = timestamp.toLocalDateTime();
        if (local == null) throw new BizException(503, "VIETQR_TIMESTAMP_INVALID");
        return local.atZone(clock.getZone()).toInstant().toString();
    }

    private BigDecimal depositAmount(BigDecimal value) {
        if (value == null) throw new BizException(422, "VIETQR_AMOUNT_REQUIRED");
        if (value.signum() <= 0
                || value.scale() < -4
                || value.scale() > 2
                || value.precision() > 10
                || value.compareTo(ABSOLUTE_MAX_DEPOSIT_USDT) > 0) {
            throw new BizException(422, "VIETQR_AMOUNT_SCALE_INVALID");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BizException(422, "VIETQR_AMOUNT_SCALE_INVALID");
        }
    }

    private String commandKey(String value) {
        String key = clean(value);
        if (key.length() < 8 || key.length() > 128 || !key.matches("[A-Za-z0-9:_-]+")) {
            throw new BizException(400, "IDEMPOTENCY_KEY_INVALID");
        }
        return key;
    }

    private String intentNo(String value) {
        String normalized = clean(value);
        if (normalized.length() < 8 || normalized.length() > 64
                || !normalized.matches("[A-Za-z0-9:_-]+")) {
            throw new BizException(422, "VIETQR_INTENT_NO_INVALID");
        }
        return normalized;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_SUBJECT_REQUIRED");
    }

    private String compactUuid(int length) {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT).substring(0, length);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("VIETQR_REQUEST_HASH_FAILED", ex);
        }
    }

    private Map<String, Object> required(Map<String, Object> value, String message) {
        return required(value, message, 503);
    }

    private Map<String, Object> required(Map<String, Object> value, String message, int code) {
        if (value == null || value.isEmpty()) throw new BizException(code, message);
        return value;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text && StringUtils.hasText(text)) return new BigDecimal(text);
        return BigDecimal.ZERO;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
    }

    private int integer(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }
}
