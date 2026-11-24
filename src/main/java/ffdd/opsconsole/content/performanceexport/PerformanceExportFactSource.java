package ffdd.opsconsole.content.performanceexport;

import java.util.List;
import java.util.Map;

/**
 * B80-BE-06 isolation seam. The net-inflow fact and aggregate sources are delivered by
 * B80-BE-01/BE-02/BE-04/BE-05; until those land this source reports UNKNOWN with an explicit
 * reason, so the export tasks never present legacy net contribution or fabricated amounts.
 */
public interface PerformanceExportFactSource {
    enum Type { SUMMARY, CUSTOMER_DETAIL, TRANSACTION_DETAIL }

    enum Status { COMPLETE, PARTIAL, UNKNOWN, FAILED }

    record Window(String definitionVersion, String sourceVersion, String month, String basis,
                  String from, String to, String asOf, String businessZone) { }

    record Scope(String kind, List<String> groupIds, List<String> names) { }

    record Coverage(Status status, List<String> reasons) { }

    record Table(Type type, List<String> columns, List<Map<String, String>> rows, Coverage coverage,
                 Integer restrictedHistoryRows, List<Long> disclosedCustomerIds) { }

    /** Real supported months from the formal first month; empty means no published catalog yet. */
    List<String> months();

    /** The window for one month under the given scope; null when the month is not supported. */
    Window window(String month, Scope scope);

    Table read(Type type, Window window, Scope scope);

    final class Columns {
        private Columns() { }
        static final List<String> SUMMARY_COLUMNS = List.of(
                "agent", "group", "supervisorAtCutoff", "month", "performanceAmount", "inflowTotal",
                "inflowByChannel.CHAIN_TOPUP", "inflowByChannel.VN_BANK_TOPUP",
                "inflowByChannel.CHAIN_DIRECT_PURCHASE", "inflowByChannel.VN_BANK_DIRECT_PURCHASE",
                "correctionTotal", "correctionByChannel.CHAIN_TOPUP", "correctionByChannel.VN_BANK_TOPUP",
                "correctionByChannel.CHAIN_DIRECT_PURCHASE", "correctionByChannel.VN_BANK_DIRECT_PURCHASE",
                "withdrawalTotal", "withdrawalByChannel.TRC20.walletDebited", "withdrawalByChannel.TRC20.fee",
                "withdrawalByChannel.TRC20.netReceived", "withdrawalByChannel.BEP20.walletDebited",
                "withdrawalByChannel.BEP20.fee", "withdrawalByChannel.BEP20.netReceived",
                "withdrawalByChannel.ERC20.walletDebited", "withdrawalByChannel.ERC20.fee",
                "withdrawalByChannel.ERC20.netReceived", "withdrawalByChannel.VN_BANK.walletDebited",
                "withdrawalByChannel.VN_BANK.fee", "withdrawalByChannel.VN_BANK.netReceived",
                "vndReference.bankTopupReceived", "vndReference.bankDirectReceived",
                "vndReference.bankPayoutReceived", "vndReference.bankInflowCorrection",
                "references.devicePurchases", "references.refunds", "references.manualCredits",
                "references.rewardsCredited", "counts.customers", "counts.negativePerformanceCustomers",
                "counts.firstPaymentCustomers", "counts.directInviteCustomers",
                "counts.withinRewardRangeCustomers", "counts.beyondRewardRangeCustomers",
                "asOf", "businessZone", "coverage", "missingSources");
        static final List<String> CUSTOMER_COLUMNS = List.of(
                "customerNo", "maskedNickname", "eventAgent", "bindingPeriods", "month",
                "inflowByChannel.CHAIN_TOPUP", "inflowByChannel.VN_BANK_TOPUP",
                "inflowByChannel.CHAIN_DIRECT_PURCHASE", "inflowByChannel.VN_BANK_DIRECT_PURCHASE",
                "correctionTotal", "withdrawalByChannel.TRC20.walletDebited",
                "withdrawalByChannel.BEP20.walletDebited", "withdrawalByChannel.ERC20.walletDebited",
                "withdrawalByChannel.VN_BANK.walletDebited", "devicePurchases", "refunds",
                "performanceAmount", "customerAgentCumulativePerformance", "asOf", "businessZone",
                "coverage", "missingSources");
        static final List<String> TRANSACTION_COLUMNS = List.of(
                "effectiveAt", "occurredAt", "customerNo", "eventAgent", "eventGroup", "kind",
                "referenceCategories", "channel", "network", "businessNo", "businessAmount",
                "businessCurrency", "roundingAdjustmentUsdt", "totalPaidUsdt", "performanceDeltaUsdt",
                "walletDebitUsdt", "withdrawalFeeUsdt", "channelFeeUsdt", "networkFeeUsdt",
                "receivedUsdt", "vndAmount", "sourceStatus", "status", "originalBusinessNo",
                "coverageReason");

        static List<String> forType(Type type) {
            return switch (type) {
                case SUMMARY -> SUMMARY_COLUMNS;
                case CUSTOMER_DETAIL -> CUSTOMER_COLUMNS;
                case TRANSACTION_DETAIL -> TRANSACTION_COLUMNS;
            };
        }
    }
}
