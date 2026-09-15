package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.auth.application.UserOtpDeliveryService;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service @RequiredArgsConstructor
public class BankWithdrawalService {
    private final BankWithdrawalMapper bank;
    private final AppWithdrawalMapper wallet;
    private final AppPayoutAddressMapper addresses;
    private final UserOtpDeliveryService otpDelivery;
    private final PayoutAddressOtpAttemptService otpAttempts;
    private final FinanceSensitiveDataCipher cipher;
    private final AppWithdrawalService withdrawals;
    private final PayoutVndConfigService d7;
    private final HdPayProperties transport;
    private final HdPayPayoutProperties payout;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final Environment environment;
    private final Clock clock;

    public record BindRequest(String bankCode, String account, String holder, String challengeNo, String code) {
        @Override public String toString() { return "BindBankRequest[REDACTED]"; }
    }
    // Historical bank labels only. New BANKQR bindings and outbound requests use an empty bank code.
    public static final Map<String, String> BANKS = Map.ofEntries(
            Map.entry("VCB", "Vietcombank"), Map.entry("BIDV", "BIDV"), Map.entry("VTB", "VietinBank"),
            Map.entry("TCB", "Techcombank"), Map.entry("ACB", "ACB"), Map.entry("MB", "MB Bank"),
            Map.entry("VPB", "VPBank"), Map.entry("TPB", "TPBank"), Map.entry("SHB", "SHB"),
            Map.entry("HDB", "HDBank"), Map.entry("VIB", "VIB"), Map.entry("OCB", "OCB"),
            Map.entry("MSB", "MSB"), Map.entry("AGB", "Agribank"), Map.entry("SACB", "Sacombank"));

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> config(long userId) {
        requireUser(userId, false);
        ApiResult<Map<String, Object>> response = d7.overview();
        Map<String, Object> data = response.getCode() == 0 ? response.getData() : Map.of();
        boolean enabled = payout.ready(transport) && Boolean.TRUE.equals(data.get("channelEnabled"))
                && Boolean.TRUE.equals(data.get("providerReady"));
        return ApiResult.ok(map("enabled", enabled, "provider", "HDPAY", "currency", "VND", "banks", List.of(),
                "bankCodeRequired", false, "bindingOtpRequired", false, "payType", "BANKQR",
                "reason", enabled ? "" : "BANK_WITHDRAWAL_CHANNEL_UNAVAILABLE", "beneficiary", beneficiaryView(bank.beneficiary(userId)),
                "policy", data, "source", "D7+HDPAY", "bindingDelayHours", 24, "changeCooldownDays", 7));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiResult<Map<String, Object>> sendOtp(long userId) {
        requireUser(userId, false);
        // Compatibility response for old clients: never send an SMS for bank binding.
        throw error(410, "BANK_BINDING_OTP_NOT_REQUIRED");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> bind(long userId, BindRequest request, String key) {
        requireUser(userId, false);
        // Accept legacy codes here solely so retained successful requests can still replay.
        if (request == null || request.bankCode() == null
                || (!request.bankCode().isEmpty() && !BANKS.containsKey(request.bankCode()))) throw error(422, "BANK_CODE_INVALID");
        try { HttpHdPayPayoutGateway.account(request.account()); HttpHdPayPayoutGateway.holder(request.holder()); }
        catch (HdPayGatewayException invalidRecipient) { throw error(422, "BANK_BENEFICIARY_INVALID"); }
        // Optional legacy OTP fields only preserve hashes of already successful retained requests.
        // They neither authorize nor block a new binding; the authenticated USER owns this resource.
        String hash = HdPayPayoutDigest.sha(userId + "|" + request.bankCode() + "|" + request.account() + "|"
                + request.holder() + "|" + request.challengeNo() + "|" + request.code());
        return (ApiResult) idempotency.executeRetained("BANK_BIND:" + userId, key, hash, ApiResult.class, () -> {
            requireUser(userId, true);
            // Provider-confirmed BANKQR uses an explicitly empty bnkCode; no client-selected bank routing.
            if (!request.bankCode().isEmpty()) throw error(422, "BANK_CODE_MUST_BE_EMPTY");
            LocalDateTime now = LocalDateTime.now(clock);
            Beneficiary before = bank.lockBeneficiary(userId);
            if (addresses.unsettledWithdrawalCount(userId) > 0) throw error(409, "BANK_WITHDRAWAL_IN_FLIGHT");
            if (before != null && before.nextChangeAt().isAfter(now)) throw error(409, "BANK_CHANGE_COOLDOWN");
            cipher.validateConfiguration();
            String no = "BNK-" + UUID.randomUUID().toString().replace("-", "");
            long version = before == null ? 0 : before.version() + 1;
            String recipient = cipher.encrypt(request.account() + "\n" + request.holder(), beneficiaryAad(userId, no));
            String masked = "****" + request.account().substring(request.account().length() - 4);
            if (bank.saveBeneficiary(userId, no, request.bankCode(), masked, recipient, now.plusHours(24), now.plusDays(7), version, now) < 1)
                throw error(409, "BANK_BIND_CONFLICT");
            audit.recordRequired(AuditLogWriteRequest.builder().action("BANK_PAYOUT_BENEFICIARY_BOUND")
                    .resourceType("BANK_BENEFICIARY").resourceId(no).userId(userId).actorId(userId).actorType("USER")
                    .actorUsername("user:" + userId).riskLevel("CRITICAL").result("SUCCESS")
                    .detail(map("bankCode", request.bankCode(), "maskedAccount", masked, "version", version,
                            "effectiveAt", now.plusHours(24))).build());
            return ApiResult.ok(map("beneficiary", beneficiaryView(bank.beneficiary(userId))));
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> quote(long userId, BigDecimal requested) {
        requireUser(userId, true);
        Map<String, Object> config = requireChannel();
        LocalDateTime now = LocalDateTime.now(clock);
        if (bank.recentQuotes(userId, now) >= 10) throw error(429, "BANK_QUOTE_RATE_LIMIT");
        Beneficiary recipient = requireBeneficiary(userId, now);
        var price = BankWithdrawalPricing.calculate(requested, config);
        var policy = withdrawals.policy(userId);
        if (policy.getCode() != 0 || !Boolean.TRUE.equals(policy.getData().get("withdrawalEnabled"))) throw error(409, "WITHDRAWAL_KILL_SWITCH_DISABLED");
        String quoteNo = "BQ-" + UUID.randomUUID().toString().replace("-", "");
        String snapshot = cipher.encrypt(cipher.decrypt(recipient.recipientCipher(), beneficiaryAad(userId, recipient.beneficiaryNo())), quoteAad(userId, quoteNo));
        Quote quote = new Quote(quoteNo, userId, recipient.beneficiaryNo(), recipient.version(), recipient.bankCode(), recipient.maskedAccount(),
                snapshot, price.amount(), price.fee(), price.net(), price.rate(), price.vnd(),
                Long.parseLong(config.get("version").toString()), policy.getData().get("policyVersion").toString(), now,
                now.plusMinutes(Long.parseLong(config.get("quoteTtlMinWithdraw").toString())), null);
        if (bank.insertQuote(quote) != 1) throw error(409, "BANK_QUOTE_CONFLICT");
        return ApiResult.ok(quoteView(quote));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> submit(long userId, String quoteNo, String key) {
        requireUser(userId, false);
        if (quoteNo == null || !quoteNo.matches("BQ-[a-f0-9]{32}")) throw error(422, "BANK_QUOTE_INVALID");
        return (ApiResult) idempotency.executeRetained("BANK_WITHDRAW:" + userId, key,
                HdPayPayoutDigest.sha(userId + "|" + quoteNo), ApiResult.class, () -> {
                    requireUser(userId, true);
                    Quote quote = bank.lockQuote(quoteNo, userId);
                    if (quote == null) throw error(404, "BANK_QUOTE_NOT_FOUND");
                    if (quote.withdrawalNo() != null) return orderView(userId, quote.withdrawalNo());
                    if (bank.cancelled(quoteNo) > 0) throw error(409, "BANK_QUOTE_ABANDONED");
                    LocalDateTime now = LocalDateTime.now(clock);
                    if (!quote.expiresAt().isAfter(now)) throw error(409, "BANK_QUOTE_EXPIRED");
                    var current = requireChannel();
                    if (!current.get("version").toString().equals(quote.d7Version().toString())) throw error(409, "BANK_QUOTE_RECONFIRM_REQUIRED");
                    Beneficiary recipient = requireBeneficiary(userId, now);
                    if (!recipient.beneficiaryNo().equals(quote.beneficiaryNo()) || !recipient.version().equals(quote.beneficiaryVersion()))
                        throw error(409, "BANK_BENEFICIARY_CHANGED");
                    var result = withdrawals.reserveBank(userId, quote, "BANK:" + HdPayPayoutDigest.sha(userId + "|" + key));
                    if (result.getCode() != 0) throw error(result.getCode(), result.getMessage());
                    String order = result.getData().get("withdrawalNo").toString();
                    if (bank.useQuote(quoteNo, order) != 1 || bank.insertOrder(order, quoteNo, userId, now) != 1)
                        throw error(409, "BANK_ORDER_LINK_CONFLICT");
                    return orderView(userId, order);
                });
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> recoverQuote(long userId, String quoteNo) {
        requireUser(userId, false);
        Quote quote = bank.quote(quoteNo);
        if (quote == null || quote.userId() != userId) throw error(404, "BANK_QUOTE_NOT_FOUND");
        if (bank.cancelled(quoteNo) > 0) return ApiResult.ok(map("state", "ABANDONED"));
        return quote.withdrawalNo() == null ? ApiResult.ok(map("state", "NOT_SUBMITTED", "quote", quoteView(quote)))
                : orderView(userId, quote.withdrawalNo());
    }

    /** Durable cancellation fence: a late request using this quote cannot create a new debit. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> abandonQuote(long userId, String quoteNo) {
        requireUser(userId, true);
        Quote quote = bank.lockQuote(quoteNo, userId);
        if (quote == null) throw error(404, "BANK_QUOTE_NOT_FOUND");
        if (quote.withdrawalNo() != null) return orderView(userId, quote.withdrawalNo());
        bank.cancelQuote(quoteNo);
        return ApiResult.ok(map("state", "ABANDONED"));
    }

    public ApiResult<Map<String, Object>> orderView(long userId, String orderNo) {
        var order = bank.order(orderNo);
        if (order == null || order.userId() != userId) throw error(404, "BANK_WITHDRAWAL_NOT_FOUND");
        var canonical = withdrawals.get(userId, orderNo);
        if (canonical.getCode() != 0) return canonical;
        return ApiResult.ok(map("state", "COMMITTED", "withdrawalNo", orderNo, "withdrawal", canonical.getData().get("withdrawal"),
                "bank", quoteView(bank.quote(order.quoteNo())), "providerState", order.state()));
    }

    private Map<String, Object> requireChannel() {
        var result = d7.overview();
        if (!payout.ready(transport) || result.getCode() != 0 || !Boolean.TRUE.equals(result.getData().get("channelEnabled"))
                || !Boolean.TRUE.equals(result.getData().get("providerReady"))) throw error(409, "BANK_WITHDRAWAL_CHANNEL_UNAVAILABLE");
        return result.getData();
    }
    private Beneficiary requireBeneficiary(long userId, LocalDateTime now) {
        Beneficiary recipient = bank.lockBeneficiary(userId);
        if (recipient == null) throw error(409, "BANK_BENEFICIARY_REQUIRED");
        if (recipient.effectiveAt().isAfter(now)) throw error(409, "BANK_BENEFICIARY_PENDING");
        return recipient;
    }
    private void requireUser(long userId, boolean lock) {
        if (!FundsSandboxProfileGuard.isStrictProductionProfile(environment.getActiveProfiles())) throw error(503, "BANK_WITHDRAWAL_PROFILE_FORBIDDEN");
        if (userId <= 0 || (lock ? wallet.lockActiveUser(userId) : wallet.findActiveUser(userId)) == null) throw error(404, "USER_NOT_FOUND");
        if (Integer.valueOf(1).equals(wallet.isSandboxUser(userId))) throw error(403, "WITHDRAWAL_SANDBOX_USER_FORBIDDEN");
    }
    public static String beneficiaryAad(long userId, String no) { return "BANK-BENEFICIARY:" + userId + ":" + no; }
    public static String quoteAad(long userId, String no) { return "BANK-QUOTE:" + userId + ":" + no; }
    private static String bankName(String code) { return "".equals(code) ? "BANKQR" : BANKS.getOrDefault(code, code); }
    private static Map<String, Object> beneficiaryView(Beneficiary b) {
        return b == null ? null : map("bankCode", b.bankCode(), "bankName", bankName(b.bankCode()),
                "maskedAccount", b.maskedAccount(), "effectiveAt", b.effectiveAt(), "nextChangeAt", b.nextChangeAt(), "version", b.version());
    }
    public static Map<String, Object> quoteView(Quote q) {
        return map("quoteNo", q.quoteNo(), "amountUsdt", q.amountUsdt(), "feeUsdt", q.feeUsdt(), "netUsdt", q.netUsdt(),
                "rateVnd", q.rateVnd(), "amountVnd", q.amountVnd(), "bankCode", q.bankCode(), "bankName", bankName(q.bankCode()),
                "maskedAccount", q.maskedAccount(), "expiresAt", q.expiresAt(), "d7Version", q.d7Version());
    }
    private static BizException error(int code, String reason) { return new BizException(code, reason); }
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i+1]);
        return result;
    }
}
