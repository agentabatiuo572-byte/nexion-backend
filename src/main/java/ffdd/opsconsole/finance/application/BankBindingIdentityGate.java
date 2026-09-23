package ffdd.opsconsole.finance.application;

import org.springframework.stereotype.Component;

/** New bank bindings stay closed until provider-backed identity validation is implemented. */
@Component
public class BankBindingIdentityGate {
    public boolean verified() { return false; }
}
