package ffdd.opsconsole.team.domain;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;

/** Shared ranking unit and minimum; monetary configuration is never converted to points. */
public final class LeaderboardComputePoints {
    public static final String METRIC = "COMPUTE_POINTS";
    public static final String CONFIG_KEY = "team.ui.F.leaderboard.minComputePoints";
    public static final BigDecimal MAX = new BigDecimal("9007199254740991");

    private LeaderboardComputePoints() { }

    public static boolean valid(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0
                && value.compareTo(MAX) <= 0;
    }

    public static BigDecimal require(BigDecimal value) {
        if (!valid(value)) throw new BizException(503, "TEAM_LEADERBOARD_COMPUTE_POINTS_INVALID");
        return value;
    }

    public static BigDecimal minimum(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ONE;
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (!valid(value)) throw new BizException(503, "TEAM_LEADERBOARD_MIN_COMPUTE_POINTS_INVALID");
            return value;
        } catch (NumberFormatException invalid) {
            throw new BizException(503, "TEAM_LEADERBOARD_MIN_COMPUTE_POINTS_INVALID");
        }
    }
}
