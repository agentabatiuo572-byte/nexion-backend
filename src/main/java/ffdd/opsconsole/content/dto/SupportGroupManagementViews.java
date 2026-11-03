package ffdd.opsconsole.content.dto;

import java.util.List;

/** Versions belong to the named fact; absent and unknown never supply an invented version. */
public final class SupportGroupManagementViews {
    private SupportGroupManagementViews() {}
    public record GroupDirectory(Long id,String name,Long supervisorAdminId,String status,Long version,
                                 String supervisorName,Long memberCount,Long boundCustomers,Long pendingCustomers) {}
    public record MemberFact(String state,String id,String groupId,Long version) {}
    public record InviteCodeSummary(String code,Long version,String issuedAt) {
        public static final InviteCodeSummary EMPTY = new InviteCodeSummary(null,null,null);
    }
    public record InviteCodeView(String code,String link,Long version,String issuedAt) {}
    public record MemberTarget(String adminId,String name,Integer accountStatus,MemberFact currentMember,long boundCustomers,InviteCodeSummary inviteCode) {}
    public record QualificationFact(String kind,String state,String observationState,String id,Long version) {}
    public record AccountQualifications(String adminId,String name,Integer accountStatus,String accountVersion,
                                        List<QualificationFact> qualifications) {}
}
