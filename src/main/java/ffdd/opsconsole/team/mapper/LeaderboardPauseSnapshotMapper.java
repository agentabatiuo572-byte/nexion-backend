package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

/** Display facts only. These rows are never settlement candidates. */
public interface LeaderboardPauseSnapshotMapper extends BaseMapper<Object> {
    @SelectProvider(type = CaptureSql.class, method = "capture")
    List<CaptureRow> capture(Map<String, Object> parameters);

    @Insert("""
            INSERT INTO nx_page_snapshot
                (page_code, locale, snapshot_key, snapshot_value, status, published_at, is_deleted)
            VALUES ('f4.leaderboard.pause.v1', 'und', #{key}, CAST(#{envelope} AS JSON), 'ACTIVE', NOW(), 0)
            """)
    int insert(@Param("key") String key, @Param("envelope") String envelope);

    @Select("""
            SELECT paused.config_value pausedValue, ref.config_value snapshotRef,
                   snap.snapshot_key storedKey, snap.snapshot_value envelope
              FROM (SELECT 1) anchor
              LEFT JOIN nx_config_item paused
                ON paused.config_key = 'team.ui.F.leaderboard.paused'
               AND paused.status = 1 AND paused.is_deleted = 0
              LEFT JOIN nx_config_item ref
                ON ref.config_key = 'team.runtime.F.leaderboard.activePauseSnapshot'
               AND ref.status = 1 AND ref.is_deleted = 0
              LEFT JOIN nx_page_snapshot snap
                ON snap.page_code = 'f4.leaderboard.pause.v1' AND snap.locale = 'und'
               AND BINARY snap.snapshot_key = BINARY JSON_UNQUOTE(JSON_EXTRACT(
                   CASE WHEN JSON_VALID(ref.config_value) THEN ref.config_value ELSE '{}' END, '$.key'))
               AND snap.status = 'ACTIVE' AND snap.is_deleted = 0
            """)
    CurrentRow current();

    record CaptureRow(String kind, String payload) { }
    record CurrentRow(String pausedValue, String snapshotRef, String storedKey, String envelope) { }

    final class CaptureSql {
        private CaptureSql() { }

        public static String capture() {
            List<String> parts = new ArrayList<>();
            parts.add("""
                    SELECT 'config' kind, JSON_OBJECT(
                      'minUsd', MAX(CASE WHEN config_key='team.ui.F.leaderboard.minUsd' THEN config_value END),
                      'weekPoolUsd', MAX(CASE WHEN config_key='team.ui.F.leaderboard.poolUsd' THEN config_value END),
                      'periodPrize', MAX(CASE WHEN config_key='team.ui.F.pool.periodPrize' THEN config_value END)) payload
                    FROM nx_config_item WHERE status=1 AND is_deleted=0 AND config_key IN
                      ('team.ui.F.leaderboard.minUsd','team.ui.F.leaderboard.poolUsd','team.ui.F.pool.periodPrize')
                    """);
            parts.add("SELECT 'pcSummary' kind, JSON_OBJECT('participantCount',x.participantCount,"
                    + "'fraudHitCount',x.fraudHitCount,'poolUsd',x.poolUsd,'periodStatus',x.periodStatus) payload FROM ("
                    + xml(TeamCommissionMapper.LEADERBOARD_SUMMARY_SQL) + ") x");
            parts.add("SELECT 'pcPodium' kind, JSON_OBJECT('rank',x.`rank`,'memberUserId',x.memberUserId,"
                    + "'userId',x.userId,'gmvLabel',x.gmvLabel,'volumeUsd',x.volumeUsd,'tip',x.tip,'className',x.className) payload FROM ("
                    + xml(TeamCommissionMapper.LEADERBOARD_PODIUM_SQL)
                        .replace("#{limit}", "#{pcLimit}") + ") x");
            for (String period : List.of("today", "week", "month", "all")) {
                String query = AppTeamInsightsMapper.LEADERBOARD_ELIGIBLE_SQL
                        .replace("<script>", "").replace("</script>", "");
                for (String parameter : List.of("actionPeriod", "fromInclusive", "toExclusive", "limit")) {
                    String renamed = period + Character.toUpperCase(parameter.charAt(0)) + parameter.substring(1);
                    query = query.replace("#{" + parameter + "}", "#{" + renamed + "}")
                            .replace("test=\"" + parameter + " != null\"", "test=\"" + renamed + " != null\"");
                }
                parts.add("SELECT '" + period + "' kind, JSON_OBJECT('rank',x.`rank`,'userId',x.userId,"
                        + "'nickname',x.nickname,'vRank',x.vRank,'earnedUsdt',x.earnedUsdt,'directs',x.directs,"
                        + "'teamSize',x.teamSize,'hasDevice',x.hasDevice) payload FROM (" + query + ") x");
            }
            return "<script>" + String.join(" UNION ALL ", parts) + "</script>";
        }

        private static String xml(String sql) { return sql.replace("&", "&amp;").replace("<", "&lt;"); }
    }
}
