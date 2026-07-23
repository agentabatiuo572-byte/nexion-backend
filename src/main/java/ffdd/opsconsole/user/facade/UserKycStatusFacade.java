package ffdd.opsconsole.user.facade;

import java.util.List;
import java.util.Map;

public interface UserKycStatusFacade {
    boolean userExists(String userNo);

    List<Map<String, Object>> reviewCandidates(String keyword, int limit);

    boolean updateKycStatusByUserNo(String userNo, String kycStatus, String reason, String operator);

    default boolean updateKycStatusByUserNo(
            String userNo, String kycStatus, String reason, String operator, String ticketId) {
        return updateKycStatusByUserNo(userNo, kycStatus, reason, operator);
    }
}
