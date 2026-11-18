package ffdd.opsconsole.shared.exception;

import java.util.Map;

/** Preserves the missing-field payload when H4 enablement is rejected before A2 ticket creation. */
public final class H4EventRuleIncompleteException extends BizException {
    private final Map<String, Object> errorData;

    public H4EventRuleIncompleteException(int code, String message, Map<String, Object> errorData) {
        super(code, message);
        this.errorData = errorData;
    }

    public Map<String, Object> errorData() {
        return errorData;
    }
}
