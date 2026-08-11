package ffdd.opsconsole.team.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface F5CommissionMapper {

    @Select("""
            <script>
            SELECT CONCAT('CM-', e.id) AS commissionId,
                   e.id AS eventId,
                   e.user_id AS userId,
                   LOWER(e.commission_type) AS kind,
                   UPPER(e.currency) AS currency,
                   CASE WHEN UPPER(e.currency) = 'NEX' THEN e.amount_nex ELSE e.amount_usdt END AS amount,
                   e.source_user_id AS sourceUserId,
                   e.layer_no AS layer,
                   e.order_no AS orderNo,
                   e.status AS rawStatus,
                   e.version AS version,
                   e.frozen_from_status AS frozenFromStatus,
                   (
                     SELECT l.biz_no
                       FROM nx_wallet_ledger l
                      WHERE l.is_deleted = 0
                        AND l.user_id = e.user_id
                        AND l.biz_type = 'TEAM_COMMISSION'
                        AND (
                          (LOWER(e.commission_type) = 'network' AND
                            (l.biz_no = CONCAT('F2-NETWORK-', e.id)
                              OR l.biz_no = CONCAT('F2-NETWORK-NEX-', e.id)))
                          OR (LOWER(e.commission_type) = 'binary' AND l.biz_no = CONCAT(
                            'F3-BINARY-', e.user_id, '-',
                            (SELECT DATE_FORMAT(s.settlement_date, '%Y%m%d')
                               FROM nx_binary_commission_settlement s
                              WHERE s.commission_event_id = e.id AND s.is_deleted = 0
                              LIMIT 1)))
                          OR (LOWER(e.commission_type) = 'leadership'
                            AND l.biz_no LIKE CONCAT('F4-POOL-%-', e.id))
                          OR (LOWER(e.commission_type) = 'cultivation'
                            AND l.biz_no = CONCAT('F1-VRANKREWARD-', e.id))
                          OR l.biz_no = CONCAT('F5-REISSUE-', e.id)
                          OR l.remark LIKE CONCAT('%commissionId=CM-', e.id, '%')
                          OR l.remark LIKE CONCAT('%eventId=', e.id, '%')
                        )
                      ORDER BY l.id DESC
                      LIMIT 1
                   ) AS ledgerBizNo,
                   DATE_FORMAT(COALESCE(e.updated_at, e.created_at), '%Y-%m-%d %H:%i:%s') AS settledAt,
                   CASE
                     WHEN LOWER(e.commission_type) IN ('network', 'binary')
                       THEN GREATEST(0, DATEDIFF(e.unlock_at, NOW()))
                     ELSE 0
                   END AS coolingDaysLeft,
                   CASE
                     WHEN UPPER(e.status) IN ('REVERSED', 'ROLLBACK', 'REJECTED') THEN 'reversed'
                     WHEN UPPER(e.status) = 'FROZEN' THEN 'frozen'
                      WHEN UPPER(e.status) IN ('PAID', 'WITHDRAWN', 'SETTLED') THEN 'withdrawn'
                      WHEN UPPER(e.status) IN ('UNLOCKED', 'AVAILABLE') THEN 'unlocked'
                      WHEN UPPER(e.status) IN ('PENDING', 'COOLING')
                        AND LOWER(e.commission_type) NOT IN ('network', 'binary') THEN 'unlocked'
                      WHEN UPPER(e.status) IN ('PENDING', 'COOLING') THEN 'cooling'
                      ELSE 'unknown'
                   END AS status,
                   DATE_FORMAT(u.created_at, '%Y-%m') AS cohort
              FROM nx_commission_event e
              LEFT JOIN nx_user u ON u.id = e.user_id AND u.is_deleted = 0
             WHERE e.is_deleted = 0
               AND LOWER(e.commission_type) IN
                   ('network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis')
             <if test="kind != null and kind != ''">
               AND LOWER(e.commission_type) = LOWER(#{kind})
             </if>
             <if test="currency != null and currency != ''">
               AND UPPER(e.currency) = UPPER(#{currency})
             </if>
             <if test="userId != null">
               AND e.user_id = #{userId}
             </if>
             <if test="cohort != null and cohort != ''">
               AND DATE_FORMAT(u.created_at, '%Y-%m') = #{cohort}
             </if>
             <if test="status != null and status != ''">
               AND (
                 (LOWER(#{status}) = 'cooling'
                   AND LOWER(e.commission_type) IN ('network', 'binary')
                   AND UPPER(e.status) IN ('PENDING', 'COOLING'))
                 OR (LOWER(#{status}) = 'unlocked'
                   AND (UPPER(e.status) IN ('UNLOCKED', 'AVAILABLE')
                     OR (LOWER(e.commission_type) NOT IN ('network', 'binary')
                       AND UPPER(e.status) IN ('PENDING', 'COOLING'))))
                 OR (LOWER(#{status}) = 'withdrawn'
                   AND UPPER(e.status) IN ('PAID', 'WITHDRAWN', 'SETTLED'))
                 OR (LOWER(#{status}) = 'reversed'
                   AND UPPER(e.status) IN ('REVERSED', 'ROLLBACK', 'REJECTED'))
                 OR (LOWER(#{status}) = 'frozen' AND UPPER(e.status) = 'FROZEN')
               )
             </if>
             <if test="cursor != null">
               AND e.id &lt; #{cursor}
             </if>
             ORDER BY e.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> queryEvents(
            @Param("kind") String kind,
            @Param("currency") String currency,
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("cohort") String cohort,
            @Param("cursor") Long cursor,
            @Param("limit") int limit);

    /**
     * Bounded server-side export page. The projection intentionally omits order numbers,
     * ledger references and every other field that is not part of the redacted CSV contract.
     */
    @Select("""
            <script>
            SELECT CONCAT('CM-', e.id) AS commissionId,
                   e.id AS eventId,
                   e.user_id AS userId,
                   LOWER(e.commission_type) AS kind,
                   UPPER(e.currency) AS currency,
                   CASE WHEN UPPER(e.currency) = 'NEX' THEN e.amount_nex ELSE e.amount_usdt END AS amount,
                   e.source_user_id AS sourceUserId,
                   e.layer_no AS layer,
                   DATE_FORMAT(COALESCE(e.updated_at, e.created_at), '%Y-%m-%d %H:%i:%s') AS settledAt,
                   CASE
                     WHEN UPPER(e.status) IN ('REVERSED', 'ROLLBACK', 'REJECTED') THEN 'reversed'
                     WHEN UPPER(e.status) = 'FROZEN' THEN 'frozen'
                     WHEN UPPER(e.status) IN ('PAID', 'WITHDRAWN', 'SETTLED') THEN 'withdrawn'
                     WHEN UPPER(e.status) IN ('UNLOCKED', 'AVAILABLE') THEN 'unlocked'
                     WHEN UPPER(e.status) IN ('PENDING', 'COOLING')
                       AND LOWER(e.commission_type) NOT IN ('network', 'binary') THEN 'unlocked'
                     WHEN UPPER(e.status) IN ('PENDING', 'COOLING') THEN 'cooling'
                     ELSE 'unknown'
                   END AS status
              FROM nx_commission_event e
              LEFT JOIN nx_user u ON u.id = e.user_id AND u.is_deleted = 0
             WHERE e.is_deleted = 0
               AND LOWER(e.commission_type) IN
                   ('network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis')
             <if test="kind != null and kind != ''">
               AND LOWER(e.commission_type) = LOWER(#{kind})
             </if>
             <if test="currency != null and currency != ''">
               AND UPPER(e.currency) = UPPER(#{currency})
             </if>
             <if test="userId != null">
               AND e.user_id = #{userId}
             </if>
             <if test="cohort != null and cohort != ''">
               AND DATE_FORMAT(u.created_at, '%Y-%m') = #{cohort}
             </if>
             <if test="status != null and status != ''">
               AND (
                 (LOWER(#{status}) = 'cooling'
                   AND LOWER(e.commission_type) IN ('network', 'binary')
                   AND UPPER(e.status) IN ('PENDING', 'COOLING'))
                 OR (LOWER(#{status}) = 'unlocked'
                   AND (UPPER(e.status) IN ('UNLOCKED', 'AVAILABLE')
                     OR (LOWER(e.commission_type) NOT IN ('network', 'binary')
                       AND UPPER(e.status) IN ('PENDING', 'COOLING'))))
                 OR (LOWER(#{status}) = 'withdrawn'
                   AND UPPER(e.status) IN ('PAID', 'WITHDRAWN', 'SETTLED'))
                 OR (LOWER(#{status}) = 'reversed'
                   AND UPPER(e.status) IN ('REVERSED', 'ROLLBACK', 'REJECTED'))
                 OR (LOWER(#{status}) = 'frozen' AND UPPER(e.status) = 'FROZEN')
               )
             </if>
             <if test="cursor != null">
               AND e.id &lt; #{cursor}
             </if>
             ORDER BY e.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> queryExportEvents(
            @Param("kind") String kind,
            @Param("currency") String currency,
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("cohort") String cohort,
            @Param("cursor") Long cursor,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_commission_event e
              LEFT JOIN nx_user u ON u.id = e.user_id AND u.is_deleted = 0
             WHERE e.is_deleted = 0
               AND LOWER(e.commission_type) IN
                   ('network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis')
             <if test="kind != null and kind != ''">
               AND LOWER(e.commission_type) = LOWER(#{kind})
             </if>
             <if test="currency != null and currency != ''">
               AND UPPER(e.currency) = UPPER(#{currency})
             </if>
             <if test="userId != null">
               AND e.user_id = #{userId}
             </if>
             <if test="cohort != null and cohort != ''">
               AND DATE_FORMAT(u.created_at, '%Y-%m') = #{cohort}
             </if>
             <if test="status != null and status != ''">
               AND (
                 (LOWER(#{status}) = 'cooling'
                   AND LOWER(e.commission_type) IN ('network', 'binary')
                   AND UPPER(e.status) IN ('PENDING', 'COOLING'))
                 OR (LOWER(#{status}) = 'unlocked'
                   AND (UPPER(e.status) IN ('UNLOCKED', 'AVAILABLE')
                     OR (LOWER(e.commission_type) NOT IN ('network', 'binary')
                       AND UPPER(e.status) IN ('PENDING', 'COOLING'))))
                 OR (LOWER(#{status}) = 'withdrawn'
                   AND UPPER(e.status) IN ('PAID', 'WITHDRAWN', 'SETTLED'))
                 OR (LOWER(#{status}) = 'reversed'
                   AND UPPER(e.status) IN ('REVERSED', 'ROLLBACK', 'REJECTED'))
                 OR (LOWER(#{status}) = 'frozen' AND UPPER(e.status) = 'FROZEN')
               )
             </if>
            </script>
            """)
    long countEvents(
            @Param("kind") String kind,
            @Param("currency") String currency,
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("cohort") String cohort);

    @Select("""
            SELECT LOWER(e.commission_type) AS kind,
                   UPPER(e.currency) AS currency,
                   COALESCE(SUM(CASE WHEN UPPER(e.currency)='NEX' THEN e.amount_nex ELSE e.amount_usdt END), 0) AS amount,
                   COUNT(1) AS count
              FROM nx_commission_event e
             WHERE e.is_deleted=0
               AND LOWER(e.commission_type) IN
                   ('network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis')
             GROUP BY LOWER(e.commission_type), UPPER(e.currency)
             ORDER BY FIELD(kind,
                       'network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis'),
                      currency
            """)
    List<Map<String, Object>> aggregateCommissionKinds();

    @Select("""
            SELECT normalized.status,
                   normalized.currency,
                   COALESCE(SUM(normalized.amount), 0) AS amount,
                   COUNT(1) AS count
              FROM (
                    SELECT CASE
                             WHEN UPPER(e.status) IN ('REVERSED', 'ROLLBACK', 'REJECTED') THEN 'reversed'
                             WHEN UPPER(e.status)='FROZEN' THEN 'frozen'
                              WHEN UPPER(e.status) IN ('PAID', 'WITHDRAWN', 'SETTLED') THEN 'withdrawn'
                              WHEN UPPER(e.status) IN ('UNLOCKED', 'AVAILABLE') THEN 'unlocked'
                              WHEN UPPER(e.status) IN ('PENDING', 'COOLING')
                                AND LOWER(e.commission_type) NOT IN ('network', 'binary') THEN 'unlocked'
                              WHEN UPPER(e.status) IN ('PENDING', 'COOLING') THEN 'cooling'
                              ELSE 'unknown'
                           END AS status,
                           UPPER(e.currency) AS currency,
                           CASE WHEN UPPER(e.currency)='NEX' THEN e.amount_nex ELSE e.amount_usdt END AS amount
                      FROM nx_commission_event e
                     WHERE e.is_deleted=0
                       AND LOWER(e.commission_type) IN
                           ('network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis')
              ) normalized
             GROUP BY normalized.status, normalized.currency
             ORDER BY FIELD(normalized.status, 'unlocked', 'cooling', 'withdrawn', 'reversed', 'frozen'),
                      normalized.currency
            """)
    List<Map<String, Object>> aggregateCommissionStatuses();

    @Select("""
            SELECT COUNT(1)
              FROM nx_commission_event
             WHERE is_deleted=0
               AND (commission_type IS NULL OR LOWER(commission_type) NOT IN
                   ('network', 'binary', 'peer', 'cultivation', 'leadership', 'genesis',
                    'vrank_reward'))
            """)
    // vrank_reward is the authoritative F1 self-reward event, not one of F5's six commission classes.
    // It is intentionally excluded from F5 totals but is known, so it must not trip the unknown-kind fuse.
    long unknownCommissionKindCount();

    @Select("""
            SELECT CONCAT('CM-', e.id) AS commissionId,
                   e.id AS eventId,
                   e.user_id AS userId,
                   LOWER(e.commission_type) AS kind,
                   UPPER(e.currency) AS currency,
                   CASE WHEN UPPER(e.currency) = 'NEX' THEN e.amount_nex ELSE e.amount_usdt END AS amount,
                   e.source_user_id AS sourceUserId,
                   e.layer_no AS layer,
                   e.order_no AS orderNo,
                   e.status AS rawStatus,
                   e.version AS version,
                   e.frozen_from_status AS frozenFromStatus
              FROM nx_commission_event e
             WHERE e.id = #{eventId}
               AND e.is_deleted = 0
             FOR UPDATE
            """)
    Map<String, Object> findEventForUpdate(@Param("eventId") Long eventId);

    @Select("""
            SELECT id
             FROM nx_commission_operation
             WHERE operation_type = 'REISSUE'
               AND source_commission_id = #{eventId}
             ORDER BY id DESC
             LIMIT 1
             FOR UPDATE
            """)
    Long findReissueOperationForUpdate(@Param("eventId") Long eventId);

    @Select("""
            SELECT COUNT(1)
              FROM (
                SELECT e.order_no AS evidence_ref
                  FROM nx_commission_event e
                 WHERE e.id = #{eventId} AND e.order_no IS NOT NULL
                UNION ALL
                SELECT p.payout_id
                  FROM nx_v_rank_reward_payout p
                 WHERE p.commission_event_id = #{eventId} AND p.is_deleted = 0
                UNION ALL
                SELECT t.ticket_no
                  FROM nx_support_ticket t
                 WHERE t.is_deleted = 0
              ) evidence
             WHERE evidence.evidence_ref = #{refundRef}
            """)
    int countEvidenceReference(@Param("eventId") Long eventId, @Param("refundRef") String refundRef);

    @Update("""
            UPDATE nx_commission_event
               SET status = 'REVERSED',
                   updated_at = NOW()
             WHERE id = #{eventId}
               AND is_deleted = 0
               AND UPPER(status) IN ('PENDING', 'COOLING', 'UNLOCKED', 'AVAILABLE')
            """)
    int reverseEventCas(@Param("eventId") Long eventId);

    @Insert("""
            INSERT INTO nx_commission_event
              (user_id, commission_type, source_user_id, source_user_name,
               layer_no, order_no, order_amount_usd, amount_usdt, amount_nex,
               currency, status, unlock_at, remark)
            SELECT e.user_id, e.commission_type, e.source_user_id, e.source_user_name,
                   e.layer_no, CONCAT('F5-REISSUE-', e.id, '-', #{operationNo}), e.order_amount_usd,
                   e.amount_usdt, e.amount_nex, e.currency,
                   CASE WHEN LOWER(e.commission_type) IN ('network', 'binary')
                        THEN 'COOLING' ELSE 'UNLOCKED' END,
                   CASE WHEN LOWER(e.commission_type) IN ('network', 'binary')
                        THEN DATE_ADD(NOW(), INTERVAL #{coolingDays} DAY) ELSE NOW() END,
                   CONCAT('F5 reissue from CM-', e.id, ' | ', #{reason})
              FROM nx_commission_event e
             WHERE e.id = #{eventId}
               AND e.is_deleted = 0
               AND UPPER(e.status) IN ('REVERSED', 'REJECTED', 'ROLLBACK')
            """)
    int insertReissueFromOriginal(
            @Param("eventId") Long eventId,
            @Param("operationNo") String operationNo,
            @Param("coolingDays") int coolingDays,
            @Param("reason") String reason);

    @Select("SELECT LAST_INSERT_ID()")
    Long selectLastInsertId();

    @Insert("""
            INSERT INTO nx_commission_operation
              (operation_no, operation_type, source_commission_id, result_commission_id,
               user_id, kinds, amount, currency, evidence_ref, reason, operator,
               idempotency_key, status, created_at)
            VALUES
              (#{operationNo}, #{operationType}, #{sourceCommissionId}, #{resultCommissionId},
               #{userId}, #{kinds}, #{amount}, #{currency}, #{evidenceRef}, #{reason}, #{operator},
               #{idempotencyKey}, 'SUCCESS', NOW())
            """)
    int insertOperation(
            @Param("operationNo") String operationNo,
            @Param("operationType") String operationType,
            @Param("sourceCommissionId") Long sourceCommissionId,
            @Param("resultCommissionId") Long resultCommissionId,
            @Param("userId") Long userId,
            @Param("kinds") String kinds,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("evidenceRef") String evidenceRef,
            @Param("reason") String reason,
            @Param("operator") String operator,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO nx_commission_user_suspension
              (user_id, kind, status, reason, operator, created_at, updated_at)
            VALUES (#{userId}, #{kind}, 'SUSPENDED', #{reason}, #{operator}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              status = CASE WHEN status = 'SUSPENDED' THEN status ELSE 'SUSPENDED' END,
              reason = VALUES(reason),
              operator = VALUES(operator),
              updated_at = NOW()
            """)
    int suspendUserKind(
            @Param("userId") Long userId,
            @Param("kind") String kind,
            @Param("reason") String reason,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_commission_user_suspension
               SET status = 'ACTIVE',
                   reason = #{reason},
                   operator = #{operator},
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND kind = #{kind}
               AND status = 'SUSPENDED'
            """)
    int resumeUserKind(
            @Param("userId") Long userId,
            @Param("kind") String kind,
            @Param("reason") String reason,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_commission_event
               SET status = 'FROZEN',
                   unlock_at = NULL,
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND LOWER(commission_type) = #{kind}
               AND UPPER(status) IN ('PENDING', 'COOLING', 'UNLOCKED', 'AVAILABLE')
               AND is_deleted = 0
            """)
    int freezeOpenEventsForSuspension(@Param("userId") Long userId, @Param("kind") String kind);

    @Select("""
            SELECT operation_no AS operationNo,
                   operation_type AS operationType,
                   CONCAT('CM-', source_commission_id) AS sourceCommissionId,
                   CASE WHEN result_commission_id IS NULL THEN NULL
                        ELSE CONCAT('CM-', result_commission_id) END AS resultCommissionId,
                   user_id AS userId,
                   kinds,
                   amount,
                   currency,
                   evidence_ref AS evidenceRef,
                   reason,
                   operator,
                   status,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
              FROM nx_commission_operation
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> recentOperations(@Param("limit") int limit);

    @Select("""
            SELECT user_id AS userId, kind, status, reason, operator,
                   DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt
              FROM nx_commission_user_suspension
             WHERE status = 'SUSPENDED'
             ORDER BY updated_at DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> activeSuspensions(@Param("limit") int limit);

    @Select("""
            SELECT layer_no AS layer, ROUND(usdt_rate * 100, 6) AS theoreticalPct
              FROM nx_commission_rule
             WHERE LOWER(commission_type) = 'unilevel'
               AND status = 1
               AND is_deleted = 0
             ORDER BY layer_no
            """)
    List<Map<String, Object>> theoreticalLayerRates();

    @Update("""
            UPDATE nx_commission_event
               SET status = 'UNLOCKED',
                   version = version + 1,
                   updated_at = NOW()
             WHERE id = #{eventId}
               AND version = #{expectedVersion}
               AND UPPER(status) = 'COOLING'
               AND unlock_at IS NOT NULL
               AND unlock_at <= NOW()
               AND is_deleted = 0
            """)
    int unlockCoolingEventCas(@Param("eventId") Long eventId,
                              @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT INTO nx_commission_operation
              (operation_no, operation_type, source_commission_id, result_commission_id,
               user_id, kinds, amount, currency, evidence_ref, reason, operator,
               idempotency_key, expected_version, status, created_at)
            SELECT CONCAT('F5-AUTO-UNLOCK-', e.id), 'AUTO_UNLOCK', e.id, NULL,
                   e.user_id, LOWER(e.commission_type),
                   CASE WHEN UPPER(e.currency)='NEX' THEN e.amount_nex ELSE e.amount_usdt END,
                   UPPER(e.currency), CONCAT('unlock_at:', DATE_FORMAT(e.unlock_at,'%Y-%m-%dT%H:%i:%s')),
                   'cooling period elapsed', 'system', CONCAT('F5-AUTO-UNLOCK-', e.id),
                   #{expectedVersion}, 'SUCCESS', NOW()
              FROM nx_commission_event e
             WHERE e.id=#{eventId}
               AND e.version=#{expectedVersion}+1
               AND UPPER(e.status)='UNLOCKED'
               AND e.is_deleted=0
            """)
    int insertAutoUnlockOperation(@Param("eventId") Long eventId,
                                  @Param("expectedVersion") Long expectedVersion);
}
