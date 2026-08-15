package ffdd.opsconsole.auth.dto;

public record AdminAccountDeletionCommandRequest(Long expectedVersion, String reason) {
}
