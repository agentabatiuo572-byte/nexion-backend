package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.outbox.infrastructure.EventOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface A4OutboxDiagnosticsMapper extends BaseMapper<EventOutboxEntity> {
    String UNRESOLVED = """
            (o.event_type = 'ADMIN_USER_PROFILE_VIEWED' AND NOT EXISTS (
              SELECT 1 FROM nx_audit_log a
               WHERE a.biz_no = CONCAT('C1-VIEW-', o.event_id)
                 AND BINARY a.biz_no = BINARY CONCAT('C1-VIEW-', o.event_id) AND a.is_deleted = 0))
            """;
    String BACKLOG = " o.is_deleted = 0 AND o.status IN ('PENDING','FAILED') ";
    String SAFE_STATUS = "CASE WHEN BINARY o.status IN ('PENDING','FAILED') THEN o.status ELSE 'OTHER' END";
    // Registry names passed A4's PII validation; legacy transport constants are explicitly enumerated.
    // Arbitrary producer input is never an operator-visible label, even when it resembles a code.
    String SAFE_TYPE = """
            CASE WHEN BINARY o.event_type IN (
              'ADMIN_USER_PROFILE_VIEWED','ADMIN_USER_LIST_EXPORTED','ADMIN_KILLSWITCH_TOGGLED',
              'RISK_TAMPER_DETECTED','ADMIN_J3_TAMPER_CONFIG_CHANGED','VRANK_PROMOTION_COMPLETED',
              'H8_REFERRAL_REWARD_SETTLED','LEARNING_COURSE_COMPLETED','COMMISSION_UNLOCKED',
              'H3_STOREFRONT_THREE_PRODUCTS_VIEWED','H3_GENESIS_SECONDARY_MARKET_VIEWED',
              'H3_COMPUTE_COMPLETED_50','H3_REFERRAL_REGISTERED','H3_EXCHANGE_COMPLETED',
              'H3_DAY_ONE_EARN_PAGE_VIEWED','H3_DAY_ONE_STORE_PAGE_VIEWED','H3_DAY_ONE_PROFILE_SAVED',
              'H3_DAY_ONE_CARD_BOUND','H3_DAY_ONE_S1_ROI_VIEWED')
              OR EXISTS (SELECT 1 FROM nx_event_schema_registry s
                 WHERE s.event_name = o.event_type AND BINARY s.event_name = BINARY o.event_type
                   AND s.status = 'ACTIVE' AND s.is_deleted = 0)
            THEN o.event_type ELSE 'UNREGISTERED_EVENT_TYPE' END
            """;
    // Preserve indexable predicates; cap elapsed query time rather than silently sample totals.
    @Select("SELECT /*+ MAX_EXECUTION_TIME(1500) */ COUNT(*) AS total, COALESCE(SUM(" + UNRESOLVED + "),0) AS unresolved, "
            + "COALESCE(TIMESTAMPDIFF(SECOND,MIN(o.created_at),NOW()),0) AS oldestSeconds "
            + "FROM nx_event_outbox o WHERE " + BACKLOG)
    @Options(timeout = 3)
    Summary summary();

    @Select("SELECT /*+ MAX_EXECUTION_TIME(1500) */ " + SAFE_TYPE + " AS eventType, " + SAFE_STATUS + " AS safeStatus, COUNT(*) AS count, MIN(o.created_at) AS oldestAt, "
            + "SUM(" + UNRESOLVED + ") AS unresolved FROM nx_event_outbox o WHERE " + BACKLOG
            + " GROUP BY BINARY eventType, eventType, safeStatus ORDER BY MIN(o.id) LIMIT 201")
    @Options(timeout = 3)
    List<GroupRow> groups();

    @Select("<script>SELECT /*+ MAX_EXECUTION_TIME(1500) */ o.id, o.event_id AS eventId, " + SAFE_TYPE + " AS eventType, " + SAFE_STATUS + " AS status, "
            + "o.retry_count AS retryCount, o.created_at AS createdAt, o.next_retry_at AS nextRetryAt, "
            + "CASE WHEN BINARY o.event_type IN ('app.page_viewed','app.element_clicked') AND NOT EXISTS ("
            + "SELECT 1 FROM nx_behavior_event_fact f WHERE f.event_id=o.event_id AND BINARY f.event_id=BINARY o.event_id) THEN 'L6_EVIDENCE_FACT_MISSING' "
            + "WHEN o.last_error IS NULL OR o.last_error = '' THEN NULL "
            + "WHEN BINARY o.last_error IN ('C1_AUDIT_EVIDENCE_NOT_UNIQUE','C1_AUDIT_ENVELOPE_INVALID',"
            + "'C1_AUDIT_PAYLOAD_INVALID','C1_AUDIT_DELIVERY_NOT_COMPLETE','C1_AUDIT_RECEIPT_NOT_PERSISTED','C1_AUDIT_SOURCE_INVALID',"
            + "'H3_EVENT_BINDING_PENDING','L6_EVIDENCE_ENVELOPE_INVALID','L6_EVIDENCE_PAYLOAD_INVALID',"
            + "'L6_EVIDENCE_FACT_CONFLICT','L6_EVIDENCE_RECEIPT_FAILED','L6_EVIDENCE_RECEIPT_CONFLICT',"
            + "'L6_EVIDENCE_PUBLICATION_FAILED','L6_EVIDENCE_VERIFICATION_UNAVAILABLE') THEN o.last_error ELSE 'OTHER_ERROR' END AS errorCode, "
            + UNRESOLVED + " AS auditLinkUnresolved FROM nx_event_outbox o WHERE " + BACKLOG
            + " AND o.id &gt; #{afterId}"
            + "<if test='eventType != null'><choose><when test='eventType == &quot;UNREGISTERED_EVENT_TYPE&quot;'> AND "
            + SAFE_TYPE + " = 'UNREGISTERED_EVENT_TYPE'</when><otherwise> AND o.event_type = #{eventType} AND BINARY o.event_type = BINARY #{eventType}</otherwise></choose></if>"
            + "<if test='status != null'><choose><when test='status == &quot;OTHER&quot;'> AND " + SAFE_STATUS
            + " = 'OTHER'</when><otherwise> AND o.status = #{status} AND BINARY o.status = BINARY #{status}</otherwise></choose></if>"
            + "<if test='unresolvedOnly'> AND " + UNRESOLVED + "</if>"
            + " ORDER BY o.id LIMIT #{limit}</script>")
    @Options(timeout = 3)
    List<EventRow> page(@Param("afterId") long afterId, @Param("eventType") String eventType,
            @Param("status") String status, @Param("unresolvedOnly") boolean unresolvedOnly, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT /*+ MAX_EXECUTION_TIME(1500) */ event_id AS eventId,
                   CASE WHEN BINARY status IN ('SUCCESS','FAILED','DEAD','PROCESSING','SKIPPED','PENDING','PENDING_BINDING')
                        THEN status ELSE 'OTHER' END AS status, COUNT(*) AS count
              FROM nx_event_consumer_delivery
             WHERE is_deleted = 0 AND event_id IN
               <foreach collection='eventIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
             GROUP BY BINARY event_id, event_id,
               CASE WHEN BINARY status IN ('SUCCESS','FAILED','DEAD','PROCESSING','SKIPPED','PENDING','PENDING_BINDING')
                    THEN status ELSE 'OTHER' END
             ORDER BY event_id, status
            </script>
            """)
    @Options(timeout = 3)
    List<ReceiptRow> receipts(@Param("eventIds") List<String> eventIds);

    record Summary(long total, long unresolved, long oldestSeconds) {}
    record GroupRow(String eventType, String safeStatus, long count, LocalDateTime oldestAt, long unresolved) {}
    record EventRow(long id, String eventId, String eventType, String status, int retryCount,
            LocalDateTime createdAt, LocalDateTime nextRetryAt, String errorCode, boolean auditLinkUnresolved) {}
    record ReceiptRow(String eventId, String status, long count) {}
}
