package ffdd.opsconsole.shared.exception;

import java.util.Map;

public final class AppTextChineseNotAllowedException extends BizException {
    private final String field;

    public AppTextChineseNotAllowedException(String field) {
        super(422, "APP_TEXT_CHINESE_NOT_ALLOWED");
        this.field = field;
    }

    public Map<String, String> errorData() {
        return Map.of("field", field);
    }
}
