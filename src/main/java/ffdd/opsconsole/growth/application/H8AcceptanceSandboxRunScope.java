package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The acceptance fixture identity belongs to the server process, never to a
 * query string or command body.  H8 read, command, idempotency and audit paths
 * share this gate so a valid RunID from another run cannot select its facts.
 */
@Component
@RequiredArgsConstructor
public class H8AcceptanceSandboxRunScope {
    private final Environment environment;

    public String requireRunId() {
        String configured = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (!StringUtils.hasText(configured) || !configured.trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
            throw new BizException(503, "H8_SANDBOX_RUN_ID_REQUIRED");
        }
        return configured.trim();
    }

    public String requireCurrentRunId(String requestedRunId) {
        String current = requireRunId();
        if (!StringUtils.hasText(requestedRunId) || !current.equals(requestedRunId.trim())) {
            throw new BizException(409, "H8_SANDBOX_RUN_ID_MISMATCH");
        }
        return current;
    }
}
