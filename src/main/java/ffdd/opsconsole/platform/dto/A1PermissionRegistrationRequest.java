package ffdd.opsconsole.platform.dto;

public record A1PermissionRegistrationRequest(
        String permissionCode,
        String permissionName,
        String resourcePath,
        String permType,
        Boolean amplifies,
        Boolean expectedAbsent,
        String reason,
        String operator) {
}
