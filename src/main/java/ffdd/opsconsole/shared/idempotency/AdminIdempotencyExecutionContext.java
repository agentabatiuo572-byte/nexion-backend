package ffdd.opsconsole.shared.idempotency;

import java.util.ArrayList;
import java.util.List;

/** Server-only ancestry permits a batch's own item commands, never a second HTTP request. */
public final class AdminIdempotencyExecutionContext {
    public record Command(long recordId, String scope, String commandKey, String requestHash, String targetsJson) {}
    private static final ThreadLocal<List<Command>> ACTIVE = new ThreadLocal<>();
    public static Command current() {
        var active = ACTIVE.get();
        return active == null ? null : active.get(active.size() - 1);
    }
    static List<Command> enter(AdminIdempotencyRecordEntity original) {
        var previous = ACTIVE.get();
        var active = previous == null ? new ArrayList<Command>() : new ArrayList<>(previous);
        active.add(new Command(original.getId(), original.getScope(), original.getIdempotencyKey(), original.getRequestHash(), original.getOriginTargets()));
        ACTIVE.set(List.copyOf(active));
        return previous;
    }
    static void restore(List<Command> previous) {
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}
