package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Run identity is server-owned so no App or Commerce request can select another fixture run. */
@Component
@RequiredArgsConstructor
public class FundsSandboxRunScope {
    private final Environment environment;

    public String requireRunId() {
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (runId == null || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
            throw new BizException(503, "FUNDS_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId.trim();
    }
}
