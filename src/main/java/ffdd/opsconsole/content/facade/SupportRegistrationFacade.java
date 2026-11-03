package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.content.application.SupportBindingService;
import ffdd.opsconsole.content.application.SupportPaymentHistoryBirthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Registration participates in the caller's account/invitation transaction. */
@Component
@RequiredArgsConstructor
public class SupportRegistrationFacade {
    private final SupportBindingService bindings;
    private final SupportPaymentHistoryBirthService paymentHistory;
    private final ffdd.opsconsole.content.application.SupportWelcomeService welcome;
    public void register(Long customer,Long inviter, String advisorCode, String language) {
        bindings.register(customer,inviter,advisorCode);
        welcome.register(customer,language);
        paymentHistory.registerNewAccount(customer);
    }
    public void register(Long customer,Long inviter) {
        bindings.register(customer,inviter);
        paymentHistory.registerNewAccount(customer);
    }
}
