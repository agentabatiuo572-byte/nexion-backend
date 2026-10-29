package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.exception.BizException;

/** Fixed ten-minute public snapshot policy; the former configurable interval is retired. */
final class SupportLeaderboardPolicy {
    static final String DEFINITION = ffdd.opsconsole.content.domain.SupportLeaderboard.NET_DEFINITION;
    private SupportLeaderboardPolicy() { }
    static final int REFRESH_MINUTES=10;
    static BizException unavailable() { return new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED"); }
}
