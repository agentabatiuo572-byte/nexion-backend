package ffdd.opsconsole.content.domain;

/** Current responsibility, independent of historical conversation owners and socket presence. */
public record AppSupportAdvisorView(Long assignmentId, Long currentAdvisorId, String currentAdvisorName,
                                    String assignmentState, String availability,String avatarAssetId,Long avatarVersion) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public AppSupportAdvisorView {}
    public AppSupportAdvisorView(Long assignmentId,Long currentAdvisorId,String currentAdvisorName,String assignmentState,String availability) {
        this(assignmentId,currentAdvisorId,currentAdvisorName,assignmentState,availability,null,0L);
    }
    public java.util.Map<String,Object> getCurrentAdvisorAvatar(){return avatarAssetId==null?null:java.util.Map.of("assetId",avatarAssetId,"version",avatarVersion==null?0L:avatarVersion);}
    public static AppSupportAdvisorView unbound() {
        return new AppSupportAdvisorView(null, null, null, "UNBOUND", "UNBOUND");
    }
}
