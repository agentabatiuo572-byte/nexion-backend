package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;

public final class TrialPurchaseConflict extends BizException {
    private final Map<String, Object> details;

    public TrialPurchaseConflict(String code, Map<String, Object> details) {
        super(409, code);
        this.details = details;
    }

    @Override
    public Map<String, Object> errorData() {
        return details;
    }
}
