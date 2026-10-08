package ffdd.opsconsole.content.dto;

/** Actor identity always comes from the authenticated session. */
public final class SupportGroupRequests {
    private SupportGroupRequests() {}
    public record Create(String name, Long supervisorAdminId, String reason) {}
    public record Rename(String name, Long expectedVersion, String reason) {}
    public record Status(String status, Long expectedVersion, String reason) {}
    public record Owner(Long supervisorAdminId, Long expectedVersion, String reason) {}
    public record Move(Long targetGroupId, Long expectedMemberVersion, Long sourceGroupVersion,
                       Long targetGroupVersion, String reason) {}
    public record Qualification(String qualificationKind, String state, Long expectedQualificationVersion,
                                Long expectedAccountVersion, String reason) {}
}
