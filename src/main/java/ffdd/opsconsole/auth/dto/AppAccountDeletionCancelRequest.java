package ffdd.opsconsole.auth.dto;

public record AppAccountDeletionCancelRequest(Long expectedVersion, String reason) {
}
