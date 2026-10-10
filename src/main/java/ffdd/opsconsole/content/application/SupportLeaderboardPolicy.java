package ffdd.opsconsole.content.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;

/** One policy for request refresh and internal sampling; absent configuration remains unavailable. */
final class SupportLeaderboardPolicy {
    static final String DEFINITION = "support-leaderboard-v2-deposit-no-refund";
    private SupportLeaderboardPolicy() { }
    static int refreshMinutes(PlatformConfigFacade config) {
        String value = config.activeValue(SupportLeaderboardService.REFRESH_KEY)
            .orElseThrow(SupportLeaderboardPolicy::unavailable);
        if (!value.matches("[0-9]{1,2}")) throw unavailable();
        int minutes = Integer.parseInt(value);
        if (minutes < 1 || minutes > 60) throw unavailable();
        return minutes;
    }
    static BizException unavailable() { return new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED"); }
}
