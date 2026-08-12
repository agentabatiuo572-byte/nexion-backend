package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Immutable acceptance execution fence; no commerce sandbox fact crosses runs. */
@Component
@RequiredArgsConstructor
public class CommerceAcceptanceRun {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    @Value("${nexion.commerce.acceptance-run-id:${NEXION_ACCEPTANCE_RUN_ID:}}")
    private final String runId;

    public String requireRunId() {
        String normalized = StringUtils.hasText(runId) ? runId.trim() : "";
        if (!VALID.matcher(normalized).matches()) throw new BizException(503, "COMMERCE_SANDBOX_RUN_ID_REQUIRED");
        return normalized;
    }
}
