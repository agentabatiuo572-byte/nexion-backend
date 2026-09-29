package ffdd.opsconsole.finance.application;

import org.springframework.stereotype.Component;

/** HDPay confirmed that BANK payouts route by receiving account without a bank code. */
@Component
public class BankAccountRoutingGate {
    public boolean contractConfirmed() { return true; }
}
