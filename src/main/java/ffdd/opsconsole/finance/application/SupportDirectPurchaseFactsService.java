package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade;
import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
import ffdd.opsconsole.shared.config.StorageTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = RuntimeException.class)
public class SupportDirectPurchaseFactsService implements SupportDirectPurchaseFactsFacade {
    private static final String BANK_PAYMENT = "HDPAY_BANK";
    private static final Set<String> DIRECT_PAYMENT_METHODS =
            Set.of("CREGIS_USDT_BSC", "CREGIS_USDT_ERC20", "CREGIS_USDT_TRC20", BANK_PAYMENT);

    private final SupportPaymentFactMapper mapper;
    private final ObjectReader quoteReader;

    public SupportDirectPurchaseFactsService(SupportPaymentFactMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.quoteReader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Override
    public Snapshot readDirectPurchases(Collection<Long> customerIds) {
        return readDirectPurchases(customerIds, null, null);
    }

    @Override
    public Snapshot readDirectPurchases(Collection<Long> customerIds, Instant from, Instant to) {
        if (customerIds == null || customerIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Explicit positive customer scope required");
        }
        if (customerIds.isEmpty()) {
            return new Snapshot(List.of(), List.of());
        }

        List<Fact> facts = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        List<Refund> refunds = new ArrayList<>();
        LocalDateTime fromInStorage = from == null ? null : LocalDateTime.ofInstant(from, StorageTime.ZONE);
        LocalDateTime toInStorage = to == null ? null : LocalDateTime.ofInstant(to, StorageTime.ZONE);
        List<Long> scope = customerIds.stream().distinct().toList();
        List<SupportPaymentFactMapper.DirectPurchaseEvidence> rows = new ArrayList<>();
        for (int offset = 0; offset < scope.size(); offset += 200) {
            rows.addAll(mapper.directPurchases(scope.subList(offset, Math.min(offset + 200, scope.size())),
                    fromInStorage, toInStorage));
        }
        for (var evidence : rows) {
            if (evidence.orderNo() == null) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "MISSING_DIRECT_PAYMENT_PROOF"));
                continue;
            }
            if (!DIRECT_PAYMENT_METHODS.contains(evidence.paymentMethod())) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "MISSING_PAYMENT_CHANNEL"));
                continue;
            }
            if (evidence.providerPaidAt() == null) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "MISSING_PAYMENT_TIME"));
                continue;
            }
            if ("PAYMENT_REORG_REVIEW".equals(evidence.reviewReason())) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "PAYMENT_REORG_REVIEW"));
                continue;
            }

            JsonNode quote;
            try {
                quote = quoteReader.readTree(evidence.quoteJson());
            } catch (JsonProcessingException ex) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "INVALID_DIRECT_PAYMENT_QUOTE"));
                continue;
            }
            BigDecimal businessAmount = evidence.businessAmountUsdt();
            BigDecimal quotedAmount = amount(quote, "businessAmountUsdt");
            BigDecimal subtotal = amount(quote, "subtotalUsdt"), rounding = amount(quote, "roundingAdjustmentUsdt");
            BigDecimal channelFee = amount(quote, "channelFeeUsdt"), networkFee = amount(quote, "networkConfirmFeeUsdt");
            BigDecimal total = amount(quote, "totalUsdt");
            boolean bank = BANK_PAYMENT.equals(evidence.paymentMethod());
            BigDecimal vnd = bank ? amount(quote, "amountVnd") : null;
            BigDecimal fxRate = bank ? amount(quote, "fxRateVndPerUsdt") : null;
            if (businessAmount == null || businessAmount.signum() <= 0 || quotedAmount == null
                    || businessAmount.compareTo(quotedAmount) != 0 || subtotal == null || rounding == null
                    || businessAmount.add(rounding).compareTo(subtotal) != 0 || channelFee == null || channelFee.signum() < 0
                    || networkFee == null || networkFee.signum() < 0 || total == null
                    || subtotal.add(channelFee).add(networkFee).setScale(2, RoundingMode.HALF_UP).compareTo(total) != 0
                    || (bank && (vnd == null || vnd.signum() <= 0 || fxRate == null || fxRate.signum() <= 0
                    || total.multiply(fxRate).setScale(0, RoundingMode.HALF_UP).compareTo(vnd) != 0))) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "INVALID_DIRECT_PAYMENT_AMOUNT"));
                continue;
            }

            facts.add(new Fact(evidence.intentNo(), evidence.customerId(), evidence.orderNo(),
                    evidence.purchaseKind(), evidence.paymentMethod(), evidence.providerReference(),
                    evidence.providerPaidAt().atZone(StorageTime.ZONE).toInstant(), businessAmount,
                    subtotal, rounding, channelFee, networkFee, total,
                    bank && evidence.confirmedBankAmountVnd() != null && vnd.compareTo(evidence.confirmedBankAmountVnd()) == 0
                            ? evidence.confirmedBankAmountVnd() : null));
            if (evidence.refundReceiptJson() != null) {
                var refund = readRefund(evidence, bank, total, vnd);
                if (refund != null) refunds.add(refund);
                else issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "EXTERNAL_REFUND_UNVERIFIED"));
            } else if ("PAYMENT_REFUNDED_REVIEW".equals(evidence.reviewReason())) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "EXTERNAL_REFUND_UNVERIFIED"));
            }
        }
        return new Snapshot(facts, issues, refunds);
    }

    private Refund readRefund(SupportPaymentFactMapper.DirectPurchaseEvidence evidence, boolean bank,
                              BigDecimal paidUsdt, BigDecimal paidVnd) {
        try {
            var receipt = quoteReader.readTree(evidence.refundReceiptJson());
            String currency = bank ? "VND" : "USDT";
            var refunded = amount(receipt, "amount");
            if (!currency.equals(receipt.path("currency").asText()) || refunded == null || refunded.signum() <= 0
                    || (bank ? refunded.compareTo(paidVnd) != 0
                             : refunded.compareTo(paidUsdt) > 0 || refunded.compareTo(paidUsdt) < 0
                                && refunded.compareTo(evidence.businessAmountUsdt()) > 0)
                    || (bank && (!"HDPAY".equals(receipt.path("channel").asText())
                        || receipt.path("providerStatus").asInt() != 4
                        || !receipt.path("providerOrderId").asText().equals(evidence.providerReference())))
                    || (!bank && (!"CREGIS".equals(receipt.path("channel").asText())
                        || receipt.path("txid").asText().isBlank() || !receipt.path("logIndex").isIntegralNumber()))) return null;
            Instant confirmed = Instant.parse(receipt.path("closedAt").asText());
            if (confirmed.isBefore(evidence.providerPaidAt().atZone(StorageTime.ZONE).toInstant())) return null;
            BigDecimal businessRefund = bank || refunded.compareTo(paidUsdt) == 0
                    ? evidence.businessAmountUsdt() : refunded;
            return new Refund(evidence.intentNo(), evidence.customerId(), evidence.orderNo(), confirmed,
                    businessRefund, refunded, currency);
        } catch (JsonProcessingException | DateTimeParseException invalid) {
            return null;
        }
    }

    private static BigDecimal amount(JsonNode quote, String field) {
        JsonNode value = quote.path(field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.textValue());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
