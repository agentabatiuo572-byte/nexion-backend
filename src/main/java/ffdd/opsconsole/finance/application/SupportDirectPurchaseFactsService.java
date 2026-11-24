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
import java.time.Instant;
import java.time.LocalDateTime;
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
        LocalDateTime fromInStorage = from == null ? null : LocalDateTime.ofInstant(from, StorageTime.ZONE);
        LocalDateTime toInStorage = to == null ? null : LocalDateTime.ofInstant(to, StorageTime.ZONE);
        for (var evidence : mapper.directPurchases(customerIds, fromInStorage, toInStorage)) {
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
            if (from != null && "PAYMENT_REORG_REVIEW".equals(evidence.reviewReason())) {
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
            if (businessAmount.signum() <= 0 || quotedAmount == null || businessAmount.compareTo(quotedAmount) != 0) {
                issues.add(new Issue(evidence.intentNo(), evidence.customerId(), "INVALID_DIRECT_PAYMENT_AMOUNT"));
                continue;
            }

            facts.add(new Fact(evidence.intentNo(), evidence.customerId(), evidence.orderNo(),
                    evidence.purchaseKind(), evidence.paymentMethod(), evidence.providerReference(),
                    evidence.providerPaidAt().atZone(StorageTime.ZONE).toInstant(), businessAmount,
                    amount(quote, "subtotalUsdt"), amount(quote, "roundingAdjustmentUsdt"), amount(quote, "channelFeeUsdt"),
                    amount(quote, "networkConfirmFeeUsdt"), amount(quote, "totalUsdt"),
                    BANK_PAYMENT.equals(evidence.paymentMethod()) ? amount(quote, "amountVnd") : null));
        }
        return new Snapshot(facts, issues);
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
