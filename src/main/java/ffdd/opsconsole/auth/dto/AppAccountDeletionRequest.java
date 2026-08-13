package ffdd.opsconsole.auth.dto;

public record AppAccountDeletionRequest(String currentPassword, String confirmation) {
}
