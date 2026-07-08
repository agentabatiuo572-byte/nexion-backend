package ffdd.opsconsole.platform.domain;
import java.util.Map;
public record AuditReplayCommand(String domain, String op, Map<String, Object> params) {}
