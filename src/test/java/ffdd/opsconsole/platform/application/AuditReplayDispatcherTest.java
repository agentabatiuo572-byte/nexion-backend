package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuditReplayDispatcherTest {
    record StubReplayable(String domain, java.util.function.Function<AuditReplayCommand, ApiResult<?>> fn) implements AuditReplayable {
        @Override public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) { return fn.apply(cmd); }
    }

    @Test
    void dispatch_routesByDomain() {
        var d = new StubReplayable("D", c -> ApiResult.ok(Map.of("op", c.op())));
        var dispatcher = new AuditReplayDispatcher(List.of(d));
        ApiResult<?> r = dispatcher.dispatch(
                new AuditReplayCommand("D", "d2_withdraw_approve", Map.of()),
                new AuditReplayContext("op", "reason", "idem"));
        assertThat(r.getCode()).isEqualTo(0);
    }

    @Test
    void dispatch_unknownDomain_fails422() {
        var dispatcher = new AuditReplayDispatcher(List.of());
        ApiResult<?> r = dispatcher.dispatch(
                new AuditReplayCommand("Z", "x", Map.of()),
                new AuditReplayContext("op", "reason", "idem"));
        assertThat(r.getCode()).isEqualTo(422);
        assertThat(r.getMessage()).contains("UNKNOWN_REPLAY_DOMAIN");
    }
}
