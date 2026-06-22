package ffdd.opsconsole.auth.dto;

import java.util.List;

public record AdminLoginResponse(String accessToken, String tokenType, AdminSession session) {
    public record AdminSession(
            Long adminId,
            String username,
            String operator,
            String role,
            List<String> authorities) {
    }
}
