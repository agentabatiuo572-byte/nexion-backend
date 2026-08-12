package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** Causal, account-scoped window for proving that one support sandbox run never touched formal support facts. */
public record SupportAcceptanceSandboxObservationWindow(long facts, long sandboxAccounts,
                                                         LocalDateTime fromAt, LocalDateTime toAt) {
    public boolean available() {
        return facts > 0 && sandboxAccounts > 0 && fromAt != null && toAt != null;
    }
}
