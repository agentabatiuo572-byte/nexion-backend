package ffdd.opsconsole.market.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
// Statement-only schedule claim/mark and audit projection; no generic entity CRUD surface.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface G3ScheduleExecutionMapper {
    @Insert("""
            INSERT IGNORE INTO nx_g3_schedule_execution(run_date,status,created_at,updated_at)
            VALUES(#{runDate},'PROCESSING',NOW(),NOW())
            """)
    int claimRunDate(@Param("runDate") LocalDate runDate);

    @Update("UPDATE nx_g3_schedule_execution SET status=#{status},updated_at=NOW() WHERE run_date=#{runDate}")
    int mark(@Param("runDate") LocalDate runDate,@Param("status") String status);

    @Select("""
            SELECT run_date AS runDate,status,created_at AS createdAt,updated_at AS updatedAt
            FROM nx_g3_schedule_execution
            ORDER BY run_date DESC,id DESC LIMIT 1
            """)
    Map<String, Object> latest();

    @Select("""
            <script>
            SELECT auditId,
                   JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.attemptedAt')) AS attemptedAt,
                   JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.completedAt')) AS completedAt,
                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.runDate')),'null') AS runDate,
                   JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.outcome')) AS outcome,
                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.reasonCode')),'null') AS reasonCode,
                   CASE JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.claimed')) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END AS claimed,
                   CASE JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.frameApplied')) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END AS frameApplied,
                   CASE JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.frameAdvanced')) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END AS frameAdvanced,
                   CASE JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.priceChanged')) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END AS priceChanged,
                   CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.beforeDayIndex')),'null') AS SIGNED) AS beforeDayIndex,
                   CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.afterDayIndex')),'null') AS SIGNED) AS afterDayIndex,
                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.beforePrice')),'null') AS beforePrice,
                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.afterPrice')),'null') AS afterPrice,
                   recordedAt,storage
            FROM (
              SELECT id AS auditId,detail_json AS detailJson,created_at AS recordedAt,'nx_audit_log' AS storage
              FROM nx_audit_log
              WHERE is_deleted=0 AND action='G3_SCHEDULE_EXECUTION' AND resource_type='NEX_MARKET_CURVE'
                AND resource_id='weekly' AND actor_type='SYSTEM'
              UNION ALL
              SELECT original_audit_id AS auditId,JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.detail')) AS detailJson,
                     original_created_at AS recordedAt,'nx_audit_log_archive' AS storage
              FROM nx_audit_log_archive
              WHERE content_sha256 IS NOT NULL
                AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action'))='G3_SCHEDULE_EXECUTION'
                AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType'))='NEX_MARKET_CURVE'
                AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId'))='weekly'
                AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorType'))='SYSTEM'
            ) attempts
            <if test="advancedOnly">
              WHERE JSON_UNQUOTE(JSON_EXTRACT(detailJson,'$.outcome')) IN ('ADVANCED','PRICE_CHANGED')
            </if>
            ORDER BY auditId DESC LIMIT 1
            </script>
            """)
    ExecutionAttempt latestAttempt(@Param("advancedOnly") boolean advancedOnly);

    record ExecutionAttempt(Long auditId, String attemptedAt, String completedAt, String runDate,
                            String outcome, String reasonCode, Boolean claimed, Boolean frameApplied,
                            Boolean frameAdvanced, Boolean priceChanged, Integer beforeDayIndex, Integer afterDayIndex,
                            String beforePrice, String afterPrice, LocalDateTime recordedAt, String storage) {}
}
