package ffdd.opsconsole.content.domain;

/** Durable, acceptance-only quiz receipt; never stored in nx_admin_idempotency_record. */
public record LearningSandboxIdempotencyRow(String requestHash, String status, String resultJson) {
}
