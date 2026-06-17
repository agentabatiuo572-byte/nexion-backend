package ffdd.opsconsole.platform.dto;

import java.util.Map;

public record AuditExportRequest(String reason, Map<String, Object> filter) {
    public AuditExportRequest {
        filter = filter == null ? Map.of() : Map.copyOf(filter);
    }
}
