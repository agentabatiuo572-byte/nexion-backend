package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Board;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Context;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Period;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Scope;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;

/** Fixed ten-minute public snapshot policy; the former configurable interval is retired. */
final class SupportLeaderboardPolicy {
    static final String DEFINITION = ffdd.opsconsole.content.domain.SupportLeaderboard.NET_DEFINITION;
    private SupportLeaderboardPolicy() { }
    static final int REFRESH_MINUTES=10;
    // Set only in an environment with a verified production launch and starting roster.
    static final Instant PRODUCTION_START = Optional.ofNullable(System.getenv("NEXION_SUPPORT_LEADERBOARD_PRODUCTION_START"))
        .map(Instant::parse).orElse(null);

    static boolean beforeProduction(YearMonth month) {
        return PRODUCTION_START!=null && month.isBefore(BusinessCalendar.legacy().month(PRODUCTION_START));
    }

    static Context context(Board board, YearMonth rankMonth, YearMonth referenceMonth, String currency,
                           Scope scope, Set<Long> groups, String definition, Instant evaluatedAt) {
        Context monthly = new Context(board,rankMonth,referenceMonth,currency,scope,groups,definition,evaluatedAt);
        Period period = monthly.businessPeriod();
        if (PRODUCTION_START == null || !PRODUCTION_START.isAfter(period.from()) || !PRODUCTION_START.isBefore(period.to()))
            return monthly;
        return new Context(board,rankMonth,referenceMonth,currency,scope,groups,definition,evaluatedAt,
            new Period(period.zoneId(),PRODUCTION_START,period.to()));
    }
    static BizException unavailable() { return new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED"); }
}
