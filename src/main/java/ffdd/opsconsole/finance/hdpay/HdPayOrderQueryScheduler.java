package ffdd.opsconsole.finance.hdpay;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Queries unsettled orders even when HDPay never delivered a callback. */
@Slf4j
@Component
public class HdPayOrderQueryScheduler {
    private static final int BATCH_SIZE = 20;
    private static final int RETRY_SECONDS = 30;
    private final HdPayProperties properties;
    private final HdPayOrderMapper mapper;
    private final HdPayGateway gateway;
    private final HdPayCallbackSettlementService settlement;
    private final Clock clock;

    public HdPayOrderQueryScheduler(HdPayProperties properties, HdPayOrderMapper mapper,
            HdPayGateway gateway, HdPayCallbackSettlementService settlement, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.gateway = gateway;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nexion.finance.hdpay.order-query-delay-ms:30000}")
    public void queryUnsettledOrders() {
        if (!properties.providerMode() || !properties.ready()) return;
        LocalDateTime dueBefore = LocalDateTime.now(clock).minusSeconds(RETRY_SECONDS);
        for (Map<String, Object> order : mapper.listOrdersDueForQuery(dueBefore, BATCH_SIZE)) {
            String merchantOrderId = String.valueOf(order.get("merchantOrderId"));
            long version = ((Number) order.get("version")).longValue();
            boolean claimed = false;
            try {
                if (mapper.claimOrderQuery(merchantOrderId, version, dueBefore) != 1) continue;
                claimed = true;
                // Never create another provider order or manufacture a callback.
                HdPayGateway.PayOrder confirmed = gateway.queryPayOrder(merchantOrderId);
                settlement.settleOrderQuery(merchantOrderId, version + 1, confirmed);
            } catch (RuntimeException ex) {
                try {
                    if (claimed) {
                        mapper.finishOrderQueryAttempt(merchantOrderId, version + 1,
                                "HDPAY_ORDER_QUERY_RETRY_REQUIRED");
                    }
                } catch (RuntimeException releaseError) {
                    log.error("HDPay order query release failed code=HDPAY_ORDER_QUERY_RELEASE_FAILED");
                }
                log.warn("HDPay order query deferred code=HDPAY_ORDER_QUERY_RETRY_REQUIRED");
            }
        }
    }
}
