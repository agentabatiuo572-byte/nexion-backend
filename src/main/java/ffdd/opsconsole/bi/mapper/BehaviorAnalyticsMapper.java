package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BehaviorAnalyticsMapper extends BaseMapper<Object> {
    @Delete("""
            DELETE FROM nx_behavior_event_fact
             WHERE occurred_at < #{cutoff}
             ORDER BY occurred_at,id
             LIMIT #{limit}
            """)
    int deleteExpiredFacts(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("""
            SELECT route, title_zh AS titleZh, page_level AS pageLevel,
                   parent_l1 AS parentL1, parent_l2 AS parentL2, tracked
              FROM nx_behavior_page_catalog
             WHERE route=#{route} AND tracked=1 AND is_deleted=0
             LIMIT 1
            """)
    CatalogRow findTrackedPage(@Param("route") String route);

    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND is_deleted=0 LIMIT 1")
    Boolean isSandboxUser(@Param("userId") Long userId);

    @Select("""
            SELECT route, title_zh AS titleZh, page_level AS pageLevel,
                   parent_l1 AS parentL1, parent_l2 AS parentL2, tracked
              FROM nx_behavior_page_catalog
             WHERE is_deleted=0
             ORDER BY page_level, route
            """)
    List<CatalogRow> listCatalog();

    @Insert("""
            INSERT INTO nx_behavior_event_fact
              (event_id,client_event_id,dedupe_key,fingerprint,event_name,session_hash,actor_hash,route,page_level,parent_l1,parent_l2,
               dwell_ms,x_norm,y_norm,zone,element_id,device_type,locale,source_environment,occurred_at,created_at)
            VALUES
              (#{eventId},#{clientEventId},#{dedupeKey},#{fingerprint},#{eventName},#{sessionHash},#{actorHash},#{route},#{pageLevel},#{parentL1},#{parentL2},
               #{dwellMs},#{xNorm},#{yNorm},#{zone},#{elementId},#{deviceType},#{locale},#{sourceEnvironment},#{occurredAt},NOW())
            """)
    int insertFact(BehaviorFactRow row);

    /** Finalizes an already-claimed fact only for its original claimant. */
    @Update("UPDATE nx_behavior_event_fact SET event_id=#{eventId} WHERE client_event_id=#{clientEventId} AND event_id=#{claimEventId}")
    int replaceClaimEventId(@Param("eventId") String eventId, @Param("claimEventId") String claimEventId,
                            @Param("clientEventId") String clientEventId);

    /** Sampling happens after claim. A sampled-out event leaves no production fact. */
    @Delete("DELETE FROM nx_behavior_event_fact WHERE event_id=#{claimEventId} AND client_event_id=#{clientEventId}")
    int deleteClaim(@Param("claimEventId") String claimEventId, @Param("clientEventId") String clientEventId);

    /** Connection-scoped MySQL mutex used to serialize one production session's L6 decisions. */
    @Select("SELECT GET_LOCK(#{lockKey}, 0)")
    Integer tryAcquireSessionLock(@Param("lockKey") String lockKey);

    @Select("SELECT RELEASE_LOCK(#{lockKey})")
    Integer releaseSessionLock(@Param("lockKey") String lockKey);

    @Select("""
            SELECT event_name AS eventName,session_hash AS sessionHash,route,fingerprint
             FROM nx_behavior_event_fact
             WHERE client_event_id=#{clientEventId}
             LIMIT 1
             FOR UPDATE
            """)
    ExistingEventRow findByClientEventId(@Param("clientEventId") String clientEventId);

    @Select("SELECT event_name AS eventName,session_hash AS sessionHash,route,fingerprint FROM nx_behavior_event_fact WHERE dedupe_key=#{dedupeKey} LIMIT 1 FOR UPDATE")
    ExistingEventRow findByDedupeKey(@Param("dedupeKey") String dedupeKey);

    @Select("""
            SELECT COUNT(*) FROM nx_behavior_event_fact
             WHERE session_hash=#{sessionHash} AND event_name=#{eventName} AND occurred_at>=#{since}
            """)
    long countRecent(@Param("sessionHash") String sessionHash, @Param("eventName") String eventName,
                     @Param("since") LocalDateTime since);

    @Select("""
            SELECT occurred_at FROM nx_behavior_event_fact
             WHERE session_hash=#{sessionHash} AND event_name=#{eventName}
             ORDER BY occurred_at DESC LIMIT 1
            """)
    LocalDateTime latestEventAt(@Param("sessionHash") String sessionHash, @Param("eventName") String eventName);

    @Select("""
            SELECT occurred_at FROM nx_behavior_event_fact
             WHERE session_hash=#{sessionHash}
             ORDER BY occurred_at DESC LIMIT 1
            """)
    LocalDateTime latestSessionEventAt(@Param("sessionHash") String sessionHash);

    @Select("""
            <script>
            SELECT
                   <choose>
                     <when test="depth == 'L1'">pc.parent_l1</when>
                     <when test="depth == 'L2'">pc.parent_l2</when>
                     <otherwise>p.route</otherwise>
                   </choose> AS route,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT p.actor_hash) AS uv,
                   COALESCE(c.clicks,0) AS clicks,
                   ROUND(AVG(p.dwell_ms)) AS dwellMs,
                   ROUND(SUM(CASE WHEN NOT EXISTS (
                     SELECT 1 FROM nx_behavior_event_fact n
                      WHERE n.event_name='app.page_viewed' AND n.session_hash=p.session_hash
                        AND n.source_environment='PRODUCTION'
                        AND n.occurred_at&gt;p.occurred_at AND n.occurred_at&lt;=#{endAt}
                   ) THEN 1 ELSE 0 END) / COUNT(*), 4) AS bounceRate,
                   COUNT(DISTINCT p.route) AS pageCount
              FROM nx_behavior_event_fact p
              JOIN nx_behavior_page_catalog pc ON pc.route=p.route AND pc.tracked=1 AND pc.is_deleted=0
              LEFT JOIN (
                SELECT
                       <choose>
                         <when test="depth == 'L1'">cc.parent_l1</when>
                         <when test="depth == 'L2'">cc.parent_l2</when>
                         <otherwise>ce.route</otherwise>
                       </choose> AS route,
                       COUNT(*) clicks
                  FROM nx_behavior_event_fact ce
                  JOIN nx_behavior_page_catalog cc ON cc.route=ce.route AND cc.tracked=1 AND cc.is_deleted=0
                 WHERE ce.event_name='app.element_clicked' AND ce.source_environment='PRODUCTION'
                   AND ce.occurred_at BETWEEN #{startAt} AND #{endAt}
                 <if test="deviceType != null">AND ce.device_type=#{deviceType}</if>
                 <if test="locale != null">AND ce.locale=#{locale}</if>
                 <if test="depth == 'L3'">AND cc.page_level=3</if>
                 GROUP BY
                       <choose>
                         <when test="depth == 'L1'">cc.parent_l1</when>
                         <when test="depth == 'L2'">cc.parent_l2</when>
                         <otherwise>ce.route</otherwise>
                       </choose>
              ) c ON c.route=
                   <choose>
                     <when test="depth == 'L1'">pc.parent_l1</when>
                     <when test="depth == 'L2'">pc.parent_l2</when>
                     <otherwise>p.route</otherwise>
                   </choose>
             WHERE p.event_name='app.page_viewed' AND p.source_environment='PRODUCTION'
               AND p.occurred_at BETWEEN #{startAt} AND #{endAt}
             <if test="deviceType != null">AND p.device_type=#{deviceType}</if>
             <if test="locale != null">AND p.locale=#{locale}</if>
             <if test="depth == 'L3'">AND pc.page_level=3</if>
             GROUP BY
                   <choose>
                     <when test="depth == 'L1'">pc.parent_l1</when>
                     <when test="depth == 'L2'">pc.parent_l2</when>
                     <otherwise>p.route</otherwise>
                   </choose>,c.clicks
             ORDER BY pv DESC,route
            </script>
            """)
    List<ActivityRow> activity(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                               @Param("deviceType") String deviceType, @Param("locale") String locale,
                               @Param("depth") String depth);

    @Select("""
            <script>
            SELECT DATE_FORMAT(occurred_at,'%Y-%m-%d') bucket,
                   SUM(event_name='app.page_viewed') pv,
                   SUM(event_name='app.element_clicked') clicks
              FROM nx_behavior_event_fact
             WHERE source_environment='PRODUCTION' AND occurred_at BETWEEN #{startAt} AND #{endAt}
             <if test="deviceType != null">AND device_type=#{deviceType}</if>
             <if test="locale != null">AND locale=#{locale}</if>
             GROUP BY DATE_FORMAT(occurred_at,'%Y-%m-%d') ORDER BY bucket
            </script>
            """)
    List<TrendRow> dailyTrend(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                              @Param("deviceType") String deviceType, @Param("locale") String locale);

    @Select("""
            <script>
            SELECT DATE_FORMAT(occurred_at,'%x-W%v') bucket,
                   SUM(event_name='app.page_viewed') pv,
                   SUM(event_name='app.element_clicked') clicks
              FROM nx_behavior_event_fact
             WHERE source_environment='PRODUCTION' AND occurred_at BETWEEN #{startAt} AND #{endAt}
             <if test="deviceType != null">AND device_type=#{deviceType}</if>
             <if test="locale != null">AND locale=#{locale}</if>
             GROUP BY DATE_FORMAT(occurred_at,'%x-W%v') ORDER BY bucket
            </script>
            """)
    List<TrendRow> weeklyTrend(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
                               @Param("deviceType") String deviceType, @Param("locale") String locale);

    @Select("""
            <script>
            SELECT ROUND(x_norm,2) x,ROUND(y_norm,2) y,COUNT(*) weight
              FROM nx_behavior_event_fact
             WHERE event_name='app.element_clicked' AND source_environment='PRODUCTION' AND route=#{route}
               AND occurred_at BETWEEN #{startAt} AND #{endAt}
             <if test="deviceType != null">AND device_type=#{deviceType}</if>
             <if test="locale != null">AND locale=#{locale}</if>
             GROUP BY ROUND(x_norm,2),ROUND(y_norm,2) ORDER BY weight DESC LIMIT 300
            </script>
            """)
    List<ClickPointRow> clickPoints(@Param("route") String route, @Param("startAt") LocalDateTime startAt,
                                    @Param("endAt") LocalDateTime endAt,
                                    @Param("deviceType") String deviceType, @Param("locale") String locale);

    @Select("""
            <script>
            SELECT zone,COUNT(*) count
              FROM nx_behavior_event_fact
             WHERE event_name='app.element_clicked' AND source_environment='PRODUCTION' AND route=#{route}
               AND occurred_at BETWEEN #{startAt} AND #{endAt}
             <if test="deviceType != null">AND device_type=#{deviceType}</if>
             <if test="locale != null">AND locale=#{locale}</if>
             GROUP BY zone ORDER BY count DESC
            </script>
            """)
    List<ZoneRow> zones(@Param("route") String route, @Param("startAt") LocalDateTime startAt,
                        @Param("endAt") LocalDateTime endAt,
                        @Param("deviceType") String deviceType, @Param("locale") String locale);

    record CatalogRow(String route, String titleZh, int pageLevel, String parentL1, String parentL2, boolean tracked) {}
    record BehaviorFactRow(String eventId, String clientEventId, String dedupeKey, String fingerprint, String eventName, String sessionHash, String actorHash, String route,
                           int pageLevel, String parentL1, String parentL2, Long dwellMs, Double xNorm, Double yNorm,
                           String zone, String elementId, String deviceType, String locale,String sourceEnvironment, LocalDateTime occurredAt) {}
    record ExistingEventRow(String eventName, String sessionHash, String route, String fingerprint) {}
    record ActivityRow(String route, long pv, long uv, long clicks, long dwellMs, double bounceRate, int pageCount) {}
    record TrendRow(String bucket, long pv, long clicks) {}
    record ClickPointRow(double x, double y, long weight) {}
    record ZoneRow(String zone, long count) {}
}
