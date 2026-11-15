package ffdd.opsconsole.shared.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 409 contract for a new write blocked by an active A2 ticket. The caller has already passed the
 * business object's write authority; only the real lock ticket id is exposed, never the initiator,
 * command payload or any other object's lock.
 */
public class ObjectLockedByA2Exception extends BizException {
    private final String operationId;

    public ObjectLockedByA2Exception(String operationId) {
        super(409, "OBJECT_LOCKED_BY_A2");
        this.operationId = operationId;
    }

    public Map<String, Object> errorData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pending", true);
        if (operationId != null && !operationId.isBlank()) {
            data.put("operationId", operationId);
        }
        return data;
    }
}
