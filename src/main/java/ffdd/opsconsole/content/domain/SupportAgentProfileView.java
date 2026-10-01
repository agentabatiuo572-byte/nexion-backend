package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportAgentProfileView(
        String id,
        Long adminId,
        String name,
        String email,
        String adminRole,
        String status,
        String seatType,
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        Long assignedUserCount,
        Long version,
        String updatedAt,String avatarAssetId,Long avatarVersion) {
    public String getAvatarRef() {
        return avatarAssetId==null || avatarAssetId.isBlank()?null:"/api/admin/content/support-agents/"+adminId+"/avatar";
    }
    public SupportAgentProfileView(String id,Long adminId,String name,String email,String adminRole,String status,String seatType,String position,
            List<String> serviceTypes,List<String> tags,Integer maxConcurrent,Boolean enabled,Boolean transferable,Boolean busy,Long assignedUserCount,Long version,String updatedAt) {
        this(id,adminId,name,email,adminRole,status,seatType,position,serviceTypes,tags,maxConcurrent,enabled,transferable,busy,assignedUserCount,version,updatedAt,null,0L);
    }
    public SupportAgentProfileView(
            String id, Long adminId, String name, String email, String adminRole, String status,
            String seatType, String position, List<String> serviceTypes, List<String> tags,
            Integer maxConcurrent, Boolean enabled, Boolean transferable, Boolean busy,
            Long assignedUserCount, String updatedAt) {
        this(id, adminId, name, email, adminRole, status, seatType, position, serviceTypes, tags,
                maxConcurrent, enabled, transferable, busy, assignedUserCount, 1L, updatedAt);
    }
}
