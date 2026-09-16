package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Beneficiary;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Quote;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/** Local ownership and immutable recipient snapshot checks; no external account verification. */
public final class BankWithdrawalEligibility {
    private BankWithdrawalEligibility() { }

    public static String beneficiaryBlock(Beneficiary current, long owner) {
        if (current == null) return "BANK_BENEFICIARY_REQUIRED";
        return Objects.equals(current.userId(), owner) ? null : "BANK_BENEFICIARY_CHANGED";
    }

    public static String quoteBlock(Beneficiary current, Quote quote) {
        if (quote == null) return "BANK_PAYOUT_SNAPSHOT_MISMATCH";
        String block = beneficiaryBlock(current, quote.userId());
        if (block != null) return block;
        return Objects.equals(current.beneficiaryNo(), quote.beneficiaryNo())
                && Objects.equals(current.version(), quote.beneficiaryVersion())
                ? null : "BANK_BENEFICIARY_CHANGED";
    }

    public static Map<String, Object> quoteEligibilityView(Beneficiary current, Quote quote) {
        String block = quoteBlock(current, quote);
        return BankWithdrawalService.map("canWithdraw", block == null, "reasonCode", block);
    }

    /** Caller holds the shared account mutex; a settled bank order never blocks an unrelated new withdrawal. */
    public static boolean hasUnresolvedIntent(BankWithdrawalMapper bank, long userId, LocalDateTime now) {
        bank.sealExpiredQuotes(userId, now);
        return !bank.activeQuotes(userId).isEmpty() || !bank.unresolvedOrders(userId).isEmpty();
    }

}
