package ffdd.opsconsole.platform.dto;

import java.util.Map;

public record AdminOption(
        String label,
        String value,
        boolean disabled,
        Map<String, Object> meta) {
    public static AdminOption of(String label, String value) {
        return new AdminOption(label, value, false, Map.of());
    }

    public static AdminOption of(String label, String value, Map<String, Object> meta) {
        return new AdminOption(label, value, false, meta);
    }
}
