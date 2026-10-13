package ffdd.opsconsole.platform.dto;

public record AdminAccountCreateRequest(
        String username,
        String displayName,
        String email,
        String role,
        String ignoredCredentialDelivery,
        String reason,
        String operator,
        String initialPassword,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String avatarAssetId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String avatarGender,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean useDefaultAvatar) {
    public AdminAccountCreateRequest(String username,String displayName,String email,String role,String ignoredCredentialDelivery,String reason,String operator,String initialPassword,String avatarAssetId) {
        this(username,displayName,email,role,ignoredCredentialDelivery,reason,operator,initialPassword,avatarAssetId,null,null);
    }
    public AdminAccountCreateRequest(String username,String displayName,String email,String role,String ignoredCredentialDelivery,String reason,String operator,String initialPassword) {
        this(username,displayName,email,role,ignoredCredentialDelivery,reason,operator,initialPassword,null);
    }
    @Override public String toString() {
        String legacy="AdminAccountCreateRequest[username="+username+", displayName="+displayName+", email="+email+", role="+role+", ignoredCredentialDelivery="+ignoredCredentialDelivery+", reason="+reason+", operator="+operator+", initialPassword="+initialPassword+"]";
        return avatarAssetId==null && avatarGender==null && useDefaultAvatar==null?legacy:ffdd.opsconsole.content.dto.SupportMessagePayload.encode(this);
    }
    public AdminAccountCreateRequest(
            String displayName,
            String email,
            String role,
            String ignoredCredentialDelivery,
            String reason,
            String operator) {
        this(null, displayName, email, role, ignoredCredentialDelivery, reason, operator, null);
    }

    public AdminAccountCreateRequest(
            String displayName,
            String email,
            String role,
            String ignoredCredentialDelivery,
            String reason,
            String operator,
            String initialPassword) {
        this(null, displayName, email, role, ignoredCredentialDelivery, reason, operator, initialPassword);
    }
}
