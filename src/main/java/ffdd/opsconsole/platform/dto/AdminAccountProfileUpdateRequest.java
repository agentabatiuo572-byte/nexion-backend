package ffdd.opsconsole.platform.dto;

public record AdminAccountProfileUpdateRequest(
        String username,
        String displayName,
        String email,
        String reason,
        String operator,
        String expectedVersion,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String avatarAssetId) {
    public AdminAccountProfileUpdateRequest(String username,String displayName,String email,String reason,String operator,String expectedVersion) {
        this(username,displayName,email,reason,operator,expectedVersion,null);
    }
    @Override public String toString() {
        String legacy="AdminAccountProfileUpdateRequest[username="+username+", displayName="+displayName+", email="+email+", reason="+reason+", operator="+operator+", expectedVersion="+expectedVersion+"]";
        return avatarAssetId==null?legacy:ffdd.opsconsole.content.dto.SupportMessagePayload.encode(this);
    }
    public AdminAccountProfileUpdateRequest(
            String username,
            String displayName,
            String email,
            String reason,
            String operator) {
        this(username, displayName, email, reason, operator, null);
    }
}
