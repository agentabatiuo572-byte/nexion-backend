package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserTeamMemberView(
        Long memberUserId,
        String memberNo,
        String nickname,
        String vRank,
        Integer level,
        BigDecimal volume,
        LocalDateTime createdAt) {
}
