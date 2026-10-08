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
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String avatarAssetId) {
    public AdminAccountCreateRequest(String username,String displayName,String email,String role,String ignoredCredentialDelivery,String reason,String operator,String initialPassword) {
        this(username,displayName,email,role,ignoredCredentialDelivery,reason,operator,initialPassword,null);
    }
    @Override public String toString() {
        String legacy="AdminAccountCreateRequest[username="+username+", displayName="+displayName+", email="+email+", role="+role+", ignoredCredentialDelivery="+ignoredCredentialDelivery+", reason="+reason+", operator="+operator+", initialPassword="+initialPassword+"]";
        return avatarAssetId==null?legacy:ffdd.opsconsole.content.dto.SupportMessagePayload.encode(this);
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
