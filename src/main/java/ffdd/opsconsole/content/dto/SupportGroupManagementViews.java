package ffdd.opsconsole.content.dto;

import java.util.List;

/** Versions belong to the named fact; absent and unknown never supply an invented version. */
public final class SupportGroupManagementViews {
    private SupportGroupManagementViews() {}
    public record MemberFact(String state,String id,String groupId,Long version) {}
    public record MemberTarget(String adminId,String name,Integer accountStatus,MemberFact currentMember,long boundCustomers) {}
    public record QualificationFact(String kind,String state,String observationState,String id,Long version) {}
    public record AccountQualifications(String adminId,String name,Integer accountStatus,String accountVersion,
                                        List<QualificationFact> qualifications) {}
}
