package ffdd.opsconsole.shared.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.A2AuditAggregate;
import ffdd.opsconsole.shared.audit.infrastructure.AuditLogEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
    @Select("""
            SELECT COUNT(*)
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND action = #{action}
               AND resource_type = #{resourceType}
            """)
    long countByActionAndResourceType(@Param("action") String action,
                                      @Param("resourceType") String resourceType);

    @Insert("""
            INSERT INTO nx_audit_log (
              trace_id, service_name, action, resource_type, resource_id, biz_no,
              user_id, actor_id, actor_type, actor_username, client_ip, method, path,
              result, risk_level, detail_json, created_at, is_deleted
            ) VALUES (
              #{log.traceId}, #{log.serviceName}, #{log.action}, #{log.resourceType}, #{log.resourceId}, #{log.bizNo},
              #{log.userId}, #{log.actorId}, #{log.actorType}, #{log.actorUsername}, #{log.clientIp}, #{log.method}, #{log.path},
              #{log.result}, #{log.riskLevel}, #{log.detailJson}, NOW(), 0
            )
            """)
    int insertAuditLog(@Param("log") AuditLogWrite log);

    @Select("""
            <script>
            SELECT id,
                   trace_id AS traceId,
                   service_name AS serviceName,
                   action,
                   resource_type AS resourceType,
                   resource_id AS resourceId,
                   biz_no AS bizNo,
                   user_id AS userId,
                   actor_id AS actorId,
                   actor_type AS actorType,
                   actor_username AS actorUsername,
                   client_ip AS clientIp,
                   method,
                   path,
                   result,
                   risk_level AS riskLevel,
                   detail_json AS detailJson,
                   created_at AS createdAt
              FROM nx_audit_log
             WHERE is_deleted = 0
             <if test='traceId != null and traceId != ""'>AND trace_id = #{traceId}</if>
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action LIKE CONCAT('%', #{action}, '%')</if>
             <if test='resourceType != null and resourceType != ""'>AND resource_type = #{resourceType}</if>
             <if test='resourceId != null and resourceId != ""'>AND resource_id = #{resourceId}</if>
             <if test='bizNo != null and bizNo != ""'>AND biz_no = #{bizNo}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='operator != null and operator != ""'>AND actor_username LIKE CONCAT('%', #{operator}, '%')</if>
             <if test='operatorExact != null and operatorExact != ""'>AND actor_username = #{operatorExact}</if>
             <if test='object != null and object != ""'>
               AND (resource_type LIKE CONCAT('%', #{object}, '%')
                 OR resource_id LIKE CONCAT('%', #{object}, '%')
                 OR biz_no LIKE CONCAT('%', #{object}, '%')
                 OR detail_json LIKE CONCAT('%', #{object}, '%'))
             </if>
             <if test='startTime != null'>AND created_at &gt;= #{startTime}</if>
             <if test='endTime != null'>AND created_at &lt;= #{endTime}</if>
             <if test='domain != null and domain != ""'>AND (CASE
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain'))), 1)
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain'))), 1)
               WHEN UPPER(COALESCE(action, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(action), 1)
               WHEN UPPER(COALESCE(resource_type, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(resource_type), 1)
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'WITHDRAW|提现|账单' THEN 'D'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'USER|账户|余额' THEN 'C'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) LIKE '%PHASE%' THEN 'H'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'CONTENT|披露' THEN 'I'
               ELSE 'A' END) = UPPER(#{domain})</if>
             <if test='allowedDomains != null'>
               AND (CASE
                 WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain'))), 1)
                 WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain'))), 1)
                 WHEN UPPER(COALESCE(action, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(action), 1)
                 WHEN UPPER(COALESCE(resource_type, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(resource_type), 1)
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'WITHDRAW|提现|账单' THEN 'D'
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'USER|账户|余额' THEN 'C'
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) LIKE '%PHASE%' THEN 'H'
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'CONTENT|披露' THEN 'I'
                 ELSE 'A' END) IN
                 (<foreach collection='allowedDomains' item='allowedDomain' separator=','>UPPER(#{allowedDomain})</foreach>)
             </if>
             ORDER BY id DESC
             LIMIT #{limit}
            </script>
            """)
    List<AuditLogRecord> list(@Param("traceId") String traceId,
                              @Param("serviceName") String serviceName,
                              @Param("action") String action,
                              @Param("resourceType") String resourceType,
                              @Param("resourceId") String resourceId,
                              @Param("bizNo") String bizNo,
                              @Param("userId") Long userId,
                              @Param("actorId") Long actorId,
                              @Param("result") String result,
                              @Param("riskLevel") String riskLevel,
                              @Param("domain") String domain,
                              @Param("operator") String operator,
                              @Param("operatorExact") String operatorExact,
                              @Param("object") String object,
                              @Param("startTime") LocalDateTime startTime,
                              @Param("endTime") LocalDateTime endTime,
                              @Param("allowedDomains") List<String> allowedDomains,
                              @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_audit_log
             WHERE is_deleted = 0
             <if test='q.traceId != null and q.traceId != ""'>AND trace_id = #{q.traceId}</if>
             <if test='q.serviceName != null and q.serviceName != ""'>AND service_name = #{q.serviceName}</if>
             <if test='q.action != null and q.action != ""'>AND action LIKE CONCAT('%', #{q.action}, '%')</if>
             <if test='q.resourceType != null and q.resourceType != ""'>AND resource_type = #{q.resourceType}</if>
             <if test='q.resourceId != null and q.resourceId != ""'>AND resource_id = #{q.resourceId}</if>
             <if test='q.bizNo != null and q.bizNo != ""'>AND biz_no = #{q.bizNo}</if>
             <if test='q.userId != null'>AND user_id = #{q.userId}</if>
             <if test='q.actorId != null'>AND actor_id = #{q.actorId}</if>
             <if test='q.result != null and q.result != ""'>AND result = #{q.result}</if>
             <if test='q.riskLevel != null and q.riskLevel != ""'>AND risk_level = #{q.riskLevel}</if>
             <if test='q.operator != null and q.operator != ""'>AND actor_username LIKE CONCAT('%', #{q.operator}, '%')</if>
             <if test='q.operatorExact != null and q.operatorExact != ""'>AND actor_username = #{q.operatorExact}</if>
             <if test='q.object != null and q.object != ""'>
               AND (resource_type LIKE CONCAT('%', #{q.object}, '%') OR resource_id LIKE CONCAT('%', #{q.object}, '%')
                 OR biz_no LIKE CONCAT('%', #{q.object}, '%') OR detail_json LIKE CONCAT('%', #{q.object}, '%'))
             </if>
             <if test='q.startTime != null'>AND created_at &gt;= #{q.startTime}</if>
             <if test='q.endTime != null'>AND created_at &lt;= #{q.endTime}</if>
             <if test='q.domain != null and q.domain != ""'>AND (CASE
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain'))), 1)
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain'))), 1)
               WHEN UPPER(COALESCE(action, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(action), 1)
               WHEN UPPER(COALESCE(resource_type, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(resource_type), 1)
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'WITHDRAW|提现|账单' THEN 'D'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'USER|账户|余额' THEN 'C'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) LIKE '%PHASE%' THEN 'H'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'CONTENT|披露' THEN 'I'
               ELSE 'A' END) = UPPER(#{q.domain})</if>
             <if test='q.allowedDomains != null'>
               AND (CASE
                 WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain'))), 1)
                 WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain'))), 1)
                 WHEN UPPER(COALESCE(action, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(action), 1)
                 WHEN UPPER(COALESCE(resource_type, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(resource_type), 1)
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'WITHDRAW|提现|账单' THEN 'D'
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'USER|账户|余额' THEN 'C'
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) LIKE '%PHASE%' THEN 'H'
                 WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'CONTENT|披露' THEN 'I'
                 ELSE 'A' END) IN
                 (<foreach collection='q.allowedDomains' item='d' separator=','>UPPER(#{d})</foreach>)
             </if>
            </script>
            """)
    long countFiltered(@Param("q") ffdd.opsconsole.shared.audit.AuditLogQueryRequest query);

    @Select("""
            <script>
            SELECT COALESCE(result,'UNKNOWN') result, COALESCE(risk_level,'UNKNOWN') riskLevel,
                   COALESCE(action,'UNKNOWN') action, COUNT(*) count
              FROM nx_audit_log WHERE is_deleted=0
             <if test='q.traceId != null and q.traceId != ""'>AND trace_id = #{q.traceId}</if>
             <if test='q.serviceName != null and q.serviceName != ""'>AND service_name = #{q.serviceName}</if>
             <if test='q.action != null and q.action != ""'>AND action LIKE CONCAT('%', #{q.action}, '%')</if>
             <if test='q.resourceType != null and q.resourceType != ""'>AND resource_type = #{q.resourceType}</if>
             <if test='q.resourceId != null and q.resourceId != ""'>AND resource_id = #{q.resourceId}</if>
             <if test='q.bizNo != null and q.bizNo != ""'>AND biz_no = #{q.bizNo}</if>
             <if test='q.userId != null'>AND user_id = #{q.userId}</if>
             <if test='q.actorId != null'>AND actor_id = #{q.actorId}</if>
             <if test='q.result != null and q.result != ""'>AND result = #{q.result}</if>
             <if test='q.riskLevel != null and q.riskLevel != ""'>AND risk_level = #{q.riskLevel}</if>
             <if test='q.operator != null and q.operator != ""'>AND actor_username LIKE CONCAT('%', #{q.operator}, '%')</if>
             <if test='q.operatorExact != null and q.operatorExact != ""'>AND actor_username = #{q.operatorExact}</if>
             <if test='q.object != null and q.object != ""'>
               AND (resource_type LIKE CONCAT('%', #{q.object}, '%') OR resource_id LIKE CONCAT('%', #{q.object}, '%')
                 OR biz_no LIKE CONCAT('%', #{q.object}, '%') OR detail_json LIKE CONCAT('%', #{q.object}, '%'))
             </if>
             <if test='q.startTime != null'>AND created_at &gt;= #{q.startTime}</if>
             <if test='q.endTime != null'>AND created_at &lt;= #{q.endTime}</if>
             <if test='q.domain != null and q.domain != ""'>AND (CASE
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain'))), 1)
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain'))), 1)
               WHEN UPPER(COALESCE(action, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(action), 1)
               WHEN UPPER(COALESCE(resource_type, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(resource_type), 1)
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'WITHDRAW|提现|账单' THEN 'D'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'USER|账户|余额' THEN 'C'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) LIKE '%PHASE%' THEN 'H'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'CONTENT|披露' THEN 'I'
               ELSE 'A' END) = UPPER(#{q.domain})</if>
             <if test='q.allowedDomains != null'>AND (CASE
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.sourceDomain'))), 1)
               WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain')), '')) REGEXP '^[A-M]([0-9]|_|$)' THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.domain'))), 1)
               WHEN UPPER(COALESCE(action, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(action), 1)
               WHEN UPPER(COALESCE(resource_type, '')) REGEXP '^[A-M]([0-9]|_)' THEN LEFT(UPPER(resource_type), 1)
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'WITHDRAW|提现|账单' THEN 'D'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'USER|账户|余额' THEN 'C'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) LIKE '%PHASE%' THEN 'H'
               WHEN UPPER(CONCAT(COALESCE(action, ''), ' ', COALESCE(resource_type, ''))) REGEXP 'CONTENT|披露' THEN 'I'
               ELSE 'A' END) IN
               (<foreach collection='q.allowedDomains' item='d' separator=','>UPPER(#{d})</foreach>)</if>
             GROUP BY COALESCE(result,'UNKNOWN'), COALESCE(risk_level,'UNKNOWN'), COALESCE(action,'UNKNOWN')
            </script>
            """)
    List<A2AuditAggregate> aggregateFiltered(@Param("q") ffdd.opsconsole.shared.audit.AuditLogQueryRequest query);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND created_at &gt;= #{startAt}
               AND created_at &lt; #{endAt}
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
            </script>
            """)
    long countStats(@Param("startAt") LocalDateTime startAt,
                    @Param("endAt") LocalDateTime endAt,
                    @Param("serviceName") String serviceName,
                    @Param("action") String action,
                    @Param("riskLevel") String riskLevel,
                    @Param("result") String result,
                    @Param("userId") Long userId,
                    @Param("actorId") Long actorId);

    @Select("""
            <script>
            SELECT COALESCE(result, 'UNKNOWN') AS `key`, COUNT(*) AS count
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND created_at &gt;= #{startAt}
               AND created_at &lt; #{endAt}
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
             GROUP BY COALESCE(result, 'UNKNOWN')
             ORDER BY count DESC, `key` ASC
             LIMIT #{limit}
            </script>
            """)
    List<AuditStatsBucket> groupByResult(@Param("startAt") LocalDateTime startAt,
                                         @Param("endAt") LocalDateTime endAt,
                                         @Param("serviceName") String serviceName,
                                         @Param("action") String action,
                                         @Param("riskLevel") String riskLevel,
                                         @Param("result") String result,
                                         @Param("userId") Long userId,
                                         @Param("actorId") Long actorId,
                                         @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COALESCE(risk_level, 'UNKNOWN') AS `key`, COUNT(*) AS count
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND created_at &gt;= #{startAt}
               AND created_at &lt; #{endAt}
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
             GROUP BY COALESCE(risk_level, 'UNKNOWN')
             ORDER BY count DESC, `key` ASC
             LIMIT #{limit}
            </script>
            """)
    List<AuditStatsBucket> groupByRiskLevel(@Param("startAt") LocalDateTime startAt,
                                            @Param("endAt") LocalDateTime endAt,
                                            @Param("serviceName") String serviceName,
                                            @Param("action") String action,
                                            @Param("riskLevel") String riskLevel,
                                            @Param("result") String result,
                                            @Param("userId") Long userId,
                                            @Param("actorId") Long actorId,
                                            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COALESCE(action, 'UNKNOWN') AS `key`, COUNT(*) AS count
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND created_at &gt;= #{startAt}
               AND created_at &lt; #{endAt}
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
             GROUP BY COALESCE(action, 'UNKNOWN')
             ORDER BY count DESC, `key` ASC
             LIMIT #{limit}
            </script>
            """)
    List<AuditStatsBucket> topActions(@Param("startAt") LocalDateTime startAt,
                                      @Param("endAt") LocalDateTime endAt,
                                      @Param("serviceName") String serviceName,
                                      @Param("action") String action,
                                      @Param("riskLevel") String riskLevel,
                                      @Param("result") String result,
                                      @Param("userId") Long userId,
                                      @Param("actorId") Long actorId,
                                      @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COALESCE(service_name, 'UNKNOWN') AS `key`, COUNT(*) AS count
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND created_at &gt;= #{startAt}
               AND created_at &lt; #{endAt}
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
             GROUP BY COALESCE(service_name, 'UNKNOWN')
             ORDER BY count DESC, `key` ASC
             LIMIT #{limit}
            </script>
            """)
    List<AuditStatsBucket> topServices(@Param("startAt") LocalDateTime startAt,
                                       @Param("endAt") LocalDateTime endAt,
                                       @Param("serviceName") String serviceName,
                                       @Param("action") String action,
                                       @Param("riskLevel") String riskLevel,
                                       @Param("result") String result,
                                       @Param("userId") Long userId,
                                       @Param("actorId") Long actorId,
                                       @Param("limit") int limit);

    @Select("""
            <script>
            SELECT CAST(user_id AS CHAR) AS `key`, COUNT(*) AS count
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND user_id IS NOT NULL
               AND created_at &gt;= #{startAt}
               AND created_at &lt; #{endAt}
             <if test='serviceName != null and serviceName != ""'>AND service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND result = #{result}</if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='actorId != null'>AND actor_id = #{actorId}</if>
             GROUP BY CAST(user_id AS CHAR)
             ORDER BY count DESC, `key` ASC
             LIMIT #{limit}
            </script>
            """)
    List<AuditStatsBucket> topUsers(@Param("startAt") LocalDateTime startAt,
                                    @Param("endAt") LocalDateTime endAt,
                                    @Param("serviceName") String serviceName,
                                    @Param("action") String action,
                                    @Param("riskLevel") String riskLevel,
                                    @Param("result") String result,
                                    @Param("userId") Long userId,
                                    @Param("actorId") Long actorId,
                                    @Param("limit") int limit);

    @Select("""
            <script>
            SELECT prefixes.prefix AS `key`,
                   COUNT(logs.id) AS count
              FROM (
                <foreach collection='prefixes' item='prefix' separator=' UNION ALL '>
                SELECT #{prefix} AS prefix
                </foreach>
              ) prefixes
              LEFT JOIN nx_audit_log logs
                ON logs.is_deleted = 0
               AND logs.created_at &gt;= #{startAt}
               AND logs.created_at &lt; #{endAt}
               AND (
                    UPPER(COALESCE(logs.action, '')) LIKE CONCAT(prefixes.prefix, '%')
                 OR UPPER(COALESCE(logs.resource_type, '')) LIKE CONCAT(prefixes.prefix, '%')
               )
             <if test='serviceName != null and serviceName != ""'>AND logs.service_name = #{serviceName}</if>
             <if test='action != null and action != ""'>AND logs.action = #{action}</if>
             <if test='riskLevel != null and riskLevel != ""'>AND logs.risk_level = #{riskLevel}</if>
             <if test='result != null and result != ""'>AND logs.result = #{result}</if>
             <if test='userId != null'>AND logs.user_id = #{userId}</if>
             <if test='actorId != null'>AND logs.actor_id = #{actorId}</if>
             GROUP BY prefixes.prefix
            </script>
            """)
    List<AuditStatsBucket> countActionsByPrefixes(@Param("startAt") LocalDateTime startAt,
                                                  @Param("endAt") LocalDateTime endAt,
                                                  @Param("serviceName") String serviceName,
                                                  @Param("action") String action,
                                                  @Param("riskLevel") String riskLevel,
                                                  @Param("result") String result,
                                                  @Param("userId") Long userId,
                                                  @Param("actorId") Long actorId,
                                                  @Param("prefixes") List<String> prefixes);

    record AuditLogWrite(
            String traceId,
            String serviceName,
            String action,
            String resourceType,
            String resourceId,
            String bizNo,
            Long userId,
            Long actorId,
            String actorType,
            String actorUsername,
            String clientIp,
            String method,
            String path,
            String result,
            String riskLevel,
            String detailJson) {
    }
}
