package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Verification;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Beneficiary;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Quote;
import java.time.LocalDateTime;
import java.util.Map;

/** Shared quote/submit/dispatch rules. No browser or administrator can supply verification evidence. */
public final class BankWithdrawalEligibility {
    private BankWithdrawalEligibility() { }

    public static Map<String, Object> capabilitySummary() {
        // A contracted, authenticated verification integration must replace this unavailable capability.
        return BankWithdrawalService.map("status", "unavailable", "provider", null, "country", "VN", "currency", "VND",
                "recipientIdentifier", "bank_account", "accountVerificationAvailable", false,
                "ownershipVerificationAvailable", false, "reasonCode", "BANK_VERIFICATION_PROVIDER_UNAVAILABLE",
                "capabilityVersion", null, "checkedAt", null);
    }

    public static boolean capabilityReady(Object summary) {
        return summary instanceof Map<?, ?> m && "ready".equals(m.get("status"))
                && Boolean.TRUE.equals(m.get("accountVerificationAvailable"))
                && Boolean.TRUE.equals(m.get("ownershipVerificationAvailable"))
                && present(m.get("provider")) && present(m.get("capabilityVersion"));
    }

    public static String block(Verification v, String beneficiary, long owner, long version, LocalDateTime now) {
        if (v == null || !beneficiary.equals(v.beneficiaryNo()) || !Long.valueOf(owner).equals(v.userId())
                || !Long.valueOf(version).equals(v.beneficiaryVersion())) return "BANK_BENEFICIARY_UNVERIFIED";
        if ("rejected".equals(v.verificationStatus()) || "unsupported".equals(v.payoutCapability())
                || "mismatched".equals(v.ownershipStatus()) || "credit_card".equals(v.accountType())
                || "prepaid".equals(v.accountType())) return "BANK_BENEFICIARY_INELIGIBLE";
        if (!"verified".equals(v.verificationStatus()) || !"supported".equals(v.payoutCapability())
                || !"matched".equals(v.ownershipStatus()) || !"payment_account".equals(v.accountType())
                || !present(v.evidenceRef()) || !present(v.capabilityVersion()) || !present(v.provider())
                || v.checkedAt() == null || v.checkedAt().isAfter(now) || v.expiresAt() == null)
            return "BANK_BENEFICIARY_UNVERIFIED";
        return v.expiresAt().isAfter(now) ? null : "BANK_BENEFICIARY_VERIFICATION_EXPIRED";
    }

    public static boolean matchesCapability(Verification v, Object summary) {
        return v != null && capabilityReady(summary) && summary instanceof Map<?, ?> m
                && java.util.Objects.equals(v.capabilityVersion(), m.get("capabilityVersion"))
                && java.util.Objects.equals(v.provider(), m.get("provider"));
    }

    public static Map<String, Object> evidenceView(Verification v) {
        return BankWithdrawalService.map("verificationStatus", v == null ? "pending" : v.verificationStatus(),
                "payoutCapability", v == null ? "unknown" : v.payoutCapability(),
                "ownershipStatus", v == null ? "unknown" : v.ownershipStatus(),
                "accountType", v == null ? "unknown" : v.accountType(),
                "reasonCode", v == null ? "BANK_BENEFICIARY_UNVERIFIED" : v.reasonCode(),
                "checkedAt", v == null ? null : v.checkedAt(), "expiresAt", v == null ? null : v.expiresAt(),
                "evidenceRef", v == null ? null : v.evidenceRef(), "capabilityVersion", v == null ? null : v.capabilityVersion());
    }

    public static Map<String, Object> quoteEvidenceView(Verification v, Beneficiary current, Quote quote, LocalDateTime now) {
        Map<String, Object> result = evidenceView(v);
        boolean eligible = current != null && quote != null && current.userId().equals(quote.userId())
                && current.beneficiaryNo().equals(quote.beneficiaryNo()) && current.version().equals(quote.beneficiaryVersion())
                && !current.effectiveAt().isAfter(now)
                && block(v, quote.beneficiaryNo(), quote.userId(), quote.beneficiaryVersion(), now) == null
                && matchesCapability(v, capabilitySummary());
        result.put("canWithdraw", eligible);
        return result;
    }

    /** Caller holds the shared account mutex; a settled bank order never blocks an unrelated new withdrawal. */
    public static boolean hasUnresolvedIntent(BankWithdrawalMapper bank, long userId, LocalDateTime now) {
        bank.sealExpiredQuotes(userId, now);
        return !bank.activeQuotes(userId).isEmpty() || !bank.unresolvedOrders(userId).isEmpty();
    }

    private static boolean present(Object value) { return value instanceof String s && !s.isBlank(); }
}
