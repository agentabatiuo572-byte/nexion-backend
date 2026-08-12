package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** Causal scope for checking that a learning acceptance run left production untouched. */
public record LearningSandboxObservationWindow(long userCount, LocalDateTime fromAt, LocalDateTime toAt) {
    public boolean available() { return userCount > 0 && fromAt != null && toAt != null; }
}
