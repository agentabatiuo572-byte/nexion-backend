package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.finance.application.HdPayPayoutTransactions;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class HdPayPayoutScheduler {
    private final BankWithdrawalMapper bank;
    private final HdPayProperties transport;
    private final HdPayPayoutProperties properties;
    private final HdPayPayoutGateway gateway;
    private final HdPayPayoutTransactions transactions;
    private final Environment environment;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${nexion.finance.hdpay-payout.poll-ms:30000}")
    public void tick() {
        if (!FundsSandboxProfileGuard.isStrictProductionProfile(environment.getActiveProfiles()) || !transport.connectionReady()) return;
        if (bank.schemaTables() != 4) return;
        for (String order : bank.queryDue(LocalDateTime.now(clock))) {
            HdPayPayoutGateway.Order response;
            try { response = gateway.query(order); }
            catch (RuntimeException unavailable) {
                transactions.defer(order);
                log.warn("HDPay payout query deferred order={} code={}", order, safeCode(unavailable));
                continue;
            }
            try { transactions.reconcile(order, response); }
            catch (RuntimeException failed) {
                transactions.deferReconciliation(order);
                log.warn("HDPay payout reconciliation deferred order={} code={}", order, safeCode(failed));
            }
        }
        if (!properties.ready(transport)) return;
        for (String order : bank.ready(LocalDateTime.now(clock))) {
            try {
                var request = transactions.prepare(order);
                if (request != null) gateway.create(request);
            } catch (RuntimeException unavailable) {
                // A prepared order is already DISPATCHING: a failure here MUST NOT reset it to READY or re-create it.
                log.warn("HDPay payout attempt requires query/readback order={}", order);
            }
        }
    }

    private static String safeCode(RuntimeException failure) {
        String message = failure.getMessage();
        return failure instanceof ffdd.opsconsole.shared.exception.BizException
                && message != null && message.matches("[A-Z][A-Z0-9_]{0,127}")
                ? message : failure.getClass().getSimpleName();
    }
}
