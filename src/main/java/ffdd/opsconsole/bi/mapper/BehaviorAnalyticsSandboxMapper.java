package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Acceptance-only L6 facts. This mapper must never target production tables. */
@Mapper
public interface BehaviorAnalyticsSandboxMapper extends BaseMapper<Object> {
    @Insert("""
            INSERT INTO nx_behavior_sandbox_fact
              (event_id,client_event_id,dedupe_key,fingerprint,run_id,observation_token,source,source_environment,event_name,session_hash,actor_hash,route,page_level,parent_l1,parent_l2,
               dwell_ms,x_norm,y_norm,zone,element_id,device_type,locale,occurred_at,created_at)
            VALUES (#{eventId},#{clientEventId},#{dedupeKey},#{fingerprint},#{runId},#{observationToken},'mock','SANDBOX',#{eventName},#{sessionHash},#{actorHash},#{route},#{pageLevel},#{parentL1},#{parentL2},
                    #{dwellMs},#{xNorm},#{yNorm},#{zone},#{elementId},#{deviceType},#{locale},#{occurredAt},NOW())
            """)
    int insertFact(SandboxFactRow row);

    @Select("SELECT event_name AS eventName,session_hash AS sessionHash,route,fingerprint FROM nx_behavior_sandbox_fact WHERE run_id=#{runId} AND client_event_id=#{clientEventId} LIMIT 1 FOR UPDATE")
    ExistingEventRow findByClientEventId(@Param("runId") String runId, @Param("clientEventId") String clientEventId);

    @Select("SELECT event_name AS eventName,session_hash AS sessionHash,route,fingerprint FROM nx_behavior_sandbox_fact WHERE run_id=#{runId} AND dedupe_key=#{dedupeKey} LIMIT 1 FOR UPDATE")
    ExistingEventRow findByDedupeKey(@Param("runId") String runId, @Param("dedupeKey") String dedupeKey);

    /** A run-scoped acceptance session must never contend with production or another Run. */
    @Select("SELECT GET_LOCK(#{lockKey}, 0)")
    Integer tryAcquireSessionLock(@Param("lockKey") String lockKey);
    @Select("SELECT RELEASE_LOCK(#{lockKey})")
    Integer releaseSessionLock(@Param("lockKey") String lockKey);

    @Select("SELECT COUNT(*) FROM nx_behavior_sandbox_fact WHERE run_id=#{runId} AND session_hash=#{sessionHash} AND event_name=#{eventName} AND occurred_at>=#{since}")
    long countRecent(@Param("runId") String runId, @Param("sessionHash") String sessionHash, @Param("eventName") String eventName, @Param("since") LocalDateTime since);
    @Select("SELECT occurred_at FROM nx_behavior_sandbox_fact WHERE run_id=#{runId} AND session_hash=#{sessionHash} AND event_name=#{eventName} ORDER BY occurred_at DESC LIMIT 1")
    LocalDateTime latestEventAt(@Param("runId") String runId, @Param("sessionHash") String sessionHash, @Param("eventName") String eventName);
    @Select("SELECT occurred_at FROM nx_behavior_sandbox_fact WHERE run_id=#{runId} AND session_hash=#{sessionHash} ORDER BY occurred_at DESC LIMIT 1")
    LocalDateTime latestSessionEventAt(@Param("runId") String runId, @Param("sessionHash") String sessionHash);

    @Select("SELECT run_id AS runId,actor_hash AS actorHash,session_hash AS sessionHash FROM nx_behavior_sandbox_fact WHERE observation_token=#{observationToken} LIMIT 1")
    ObservationScope findObservationScope(@Param("observationToken") String observationToken);

    @Select("""
            <script>SELECT COALESCE(SUM(event_name='app.page_viewed'),0) AS pageViews,
                   COALESCE(SUM(event_name='app.element_clicked'),0) AS clicks
              FROM nx_behavior_sandbox_fact WHERE run_id=#{runId}
              <if test="actorHash != null">AND actor_hash=#{actorHash}</if>
              <if test="sessionHash != null">AND session_hash=#{sessionHash}</if>
              <if test="route != null">AND route=#{route}</if>
              <if test="from != null">AND occurred_at &gt;= #{from}</if>
              <if test="to != null">AND occurred_at &lt;= #{to}</if></script>
            """)
    SandboxSummary summary(@Param("runId") String runId, @Param("actorHash") String actorHash,
                           @Param("sessionHash") String sessionHash, @Param("route") String route,
                           @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** The zero-pollution proof uses the actual Sandbox ingest interval, not a
     * client-supplied occurred_at window that can be backdated by up to 24h. */
    @Select("""
            <script>SELECT MIN(created_at) AS firstIngestedAt,MAX(created_at) AS lastIngestedAt
              FROM nx_behavior_sandbox_fact WHERE run_id=#{runId} AND actor_hash=#{actorHash} AND session_hash=#{sessionHash}
              <if test="route != null">AND route=#{route}</if>
              AND occurred_at &gt;= #{from} AND occurred_at &lt;= #{to}</script>
            """)
    IngestWindow ingestWindow(@Param("runId") String runId, @Param("actorHash") String actorHash,
                              @Param("sessionHash") String sessionHash, @Param("route") String route,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Any row in the production fact table is contamination, even if a bad writer labelled it SANDBOX. */
    @Select("SELECT COUNT(*) FROM nx_behavior_event_fact WHERE actor_hash=#{actorHash} AND session_hash=#{sessionHash} AND created_at>=#{from} AND created_at<=#{to}")
    long productionFactDelta(@Param("actorHash") String actorHash, @Param("sessionHash") String sessionHash,
                             @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    @Select("SELECT COUNT(*) FROM nx_event_outbox WHERE aggregate_type='APP_BEHAVIOR' AND aggregate_id=#{sessionHash} AND created_at>=#{from} AND created_at<=#{to}")
    long productionOutboxDelta(@Param("sessionHash") String sessionHash, @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    record SandboxFactRow(String eventId, String clientEventId, String dedupeKey, String fingerprint, String runId, String observationToken, String eventName, String sessionHash,
                          String actorHash, String route, int pageLevel, String parentL1, String parentL2, Long dwellMs,
                          Double xNorm, Double yNorm, String zone, String elementId, String deviceType, String locale,
                          LocalDateTime occurredAt) {}
    record ExistingEventRow(String eventName, String sessionHash, String route, String fingerprint) {}
    record SandboxSummary(Long pageViews, Long clicks) {}
    record IngestWindow(LocalDateTime firstIngestedAt, LocalDateTime lastIngestedAt) {}
    record ObservationScope(String runId, String actorHash, String sessionHash) {}
}
