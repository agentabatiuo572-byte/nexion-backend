package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;

public final class TaskPoolException extends BizException {
    private final Map<String,Object> data;
    public TaskPoolException(String code,Map<String,Object> data) {super(409,code);this.data=Map.copyOf(data);}
    public Map<String,Object> errorData() {return data;}
}
