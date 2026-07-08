package ffdd.opsconsole.platform.domain;
public record AuditReplayContext(String operator, String reason, String idempotencyKey) {}
