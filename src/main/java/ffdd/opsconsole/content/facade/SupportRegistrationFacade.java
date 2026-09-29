package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.content.application.SupportBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Registration participates in the caller's account/invitation transaction. */
@Component
@RequiredArgsConstructor
public class SupportRegistrationFacade {
    private final SupportBindingService bindings;
    public void register(Long customer,Long inviter) {bindings.register(customer,inviter);}
}
