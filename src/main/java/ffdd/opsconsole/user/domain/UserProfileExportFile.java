package ffdd.opsconsole.user.domain;

public record UserProfileExportFile(
        String fileName,
        byte[] body,
        int rowCount) {
}
