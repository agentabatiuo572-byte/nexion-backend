package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.infrastructure.WithdrawalOrderEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WithdrawalOrderMapper extends BaseMapper<WithdrawalOrderEntity> {

    @Select("""
            SELECT COUNT(1)
              FROM nx_withdrawal_order
             WHERE is_deleted = 0
               AND status IN ('REVIEWING', 'DELAYED', 'FROZEN', 'PENDING_CHAIN', 'CHAIN_SUBMITTED', 'DEAD')
            """)
    long countD2ActionableWithdrawals();

    @Select("""
            <script>
            SELECT COUNT(DISTINCT w.id)
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user k4 ON k4.user_no = CONCAT('U', LPAD(w.user_id, 8, '0')) AND k4.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_override k4o ON k4o.user_no = CONCAT('U', LPAD(w.user_id, 8, '0')) AND k4o.active = 1 AND k4o.is_deleted = 0
              LEFT JOIN (
                    SELECT withdrawal_no,
                           GROUP_CONCAT(CONCAT(rule_id, ':', action) ORDER BY id DESC SEPARATOR ', ') AS hit_rules,
                           GROUP_CONCAT(CONCAT(dimension, '规则命中:', rule_id, ' -> ', action) ORDER BY id DESC SEPARATOR ' | ') AS hit_reasons
                      FROM nx_admin_risk_withdraw_hit
                     WHERE is_deleted = 0
                     GROUP BY withdrawal_no
              ) hit ON (hit.withdrawal_no = w.withdrawal_no)
             WHERE w.is_deleted = 0
             <if test='status != null and status != ""'>AND w.status = #{status}</if>
             <if test='userId != null'>AND w.user_id = #{userId}</if>
             <if test='minAmount != null'>AND w.amount &gt;= #{minAmount}</if>
             <if test='maxAmount != null'>AND w.amount &lt;= #{maxAmount}</if>
             <if test='minRiskScore != null'>
               AND COALESCE(
                   k4o.override_score,
                   k4.model_score,
                   rd.risk_score,
                   (
                       SELECT rd2.risk_score
                         FROM nx_risk_decision rd2
                        WHERE rd2.is_deleted = 0
                          AND rd2.biz_type = 'WITHDRAW_RULE'
                          AND (rd2.biz_no = w.withdrawal_no)
                        ORDER BY rd2.id DESC
                        LIMIT 1
                   ),
                   0
               ) &gt;= #{minRiskScore}
             </if>
             <if test='keyword != null and keyword != ""'>
               AND (w.withdrawal_no LIKE CONCAT('%', #{keyword}, '%')
                    OR w.target_address LIKE CONCAT('%', #{keyword}, '%')
                    OR w.chain_tx_hash LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(w.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR hit.hit_rules LIKE CONCAT('%', #{keyword}, '%')
                    OR hit.hit_reasons LIKE CONCAT('%', #{keyword}, '%')
                    OR rd.reason LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countPage(@Param("status") String status, @Param("userId") Long userId,
                   @Param("keyword") String keyword, @Param("minAmount") BigDecimal minAmount,
                   @Param("maxAmount") BigDecimal maxAmount,
                   @Param("minRiskScore") Integer minRiskScore);

    @Select("""
            <script>
            SELECT w.id,
                   w.user_id AS userId,
                   w.withdrawal_no AS withdrawalNo,
                   w.asset,
                   w.chain,
                   w.amount,
                   w.fee,
                   w.target_address AS targetAddress,
                   COALESCE(
                       w.risk_decision_id,
                       (
                           SELECT rd2.id
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       )
                   ) AS riskDecisionId,
                   w.chain_tx_hash AS chainTxHash,
                   w.status,
                   w.chain_submitted_at AS chainSubmittedAt,
                   w.completed_at AS completedAt,
                   w.failed_at AS failedAt,
                   w.failure_reason AS failureReason,
                   w.chain_broadcast_attempts AS chainBroadcastAttempts,
                   w.next_broadcast_at AS nextBroadcastAt,
                   w.last_broadcast_error AS lastBroadcastError,
                   w.broadcast_dead_at AS broadcastDeadAt,
                   w.created_at AS createdAt,
                   w.updated_at AS updatedAt,
                   CONCAT('U', LPAD(w.user_id, 8, '0')) AS userNo,
                   u.nickname AS nickname,
                   CASE
                      WHEN u.phone IS NULL OR LENGTH(u.phone) &lt; 7 THEN u.phone
                       ELSE CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3))
                   END AS phoneMasked,
                   COALESCE(u.kyc_status, 'PENDING') AS kycStatus,
                   COALESCE(u.status, 'UNKNOWN') AS userStatus,
                   COALESCE(
                       k4o.override_score,
                       k4.model_score,
                       rd.risk_score,
                       (
                           SELECT rd2.risk_score
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       ),
                       0
                   ) AS riskScore,
                   COALESCE(
                       hit.hit_rules,
                       rd.rule_codes,
                       (
                           SELECT rd2.rule_codes
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       )
                   ) AS hitRules,
                   COALESCE(
                       rd.reason,
                       (
                           SELECT rd2.reason
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       ),
                       hit.hit_reasons
                   ) AS riskReason,
                   (
                       SELECT COUNT(1)
                         FROM nx_withdrawal_order w2
                        WHERE w2.user_id = w.user_id
                          AND w2.is_deleted = 0
                          AND w2.created_at >= DATE_SUB(w.created_at, INTERVAL 24 HOUR)
                         AND w2.created_at &lt;= w.created_at
                   ) AS withdrawalCount24h,
                   CONCAT(
                       'created:', DATE_FORMAT(w.created_at, '%Y-%m-%d %H:%i'),
                       '; status:', w.status,
                       IF(w.chain_submitted_at IS NULL, '', CONCAT('; chain:', DATE_FORMAT(w.chain_submitted_at, '%Y-%m-%d %H:%i'))),
                       IF(w.completed_at IS NULL, '', CONCAT('; completed:', DATE_FORMAT(w.completed_at, '%Y-%m-%d %H:%i'))),
                       IF(w.failed_at IS NULL, '', CONCAT('; failed:', DATE_FORMAT(w.failed_at, '%Y-%m-%d %H:%i'))),
                       IF(w.updated_at IS NULL, '', CONCAT('; updated:', DATE_FORMAT(w.updated_at, '%Y-%m-%d %H:%i')))
                   ) AS statusHistory,
                   aud.audit_trail AS auditTrail
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user k4 ON k4.user_no = CONCAT('U', LPAD(w.user_id, 8, '0')) AND k4.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_override k4o ON k4o.user_no = CONCAT('U', LPAD(w.user_id, 8, '0')) AND k4o.active = 1 AND k4o.is_deleted = 0
              LEFT JOIN (
                    SELECT withdrawal_no,
                           GROUP_CONCAT(CONCAT(h.rule_id, ':', h.action, '@', h.time_text) ORDER BY h.id DESC SEPARATOR ', ') AS hit_rules,
                           GROUP_CONCAT(
                               COALESCE(
                                   CONCAT(h.dimension, '规则命中:', r.condition_text, ' -> ', h.action),
                                   CONCAT(h.dimension, '规则命中:', h.rule_id, ' -> ', h.action)
                               )
                               ORDER BY h.id DESC SEPARATOR ' | '
                           ) AS hit_reasons
                      FROM nx_admin_risk_withdraw_hit h
                      LEFT JOIN nx_admin_risk_withdraw_rule r
                        ON r.rule_id = h.rule_id AND r.is_deleted = 0
                     WHERE h.is_deleted = 0
                     GROUP BY h.withdrawal_no
              ) hit ON (hit.withdrawal_no = w.withdrawal_no)
              LEFT JOIN (
                    SELECT resource_id,
                           GROUP_CONCAT(CONCAT(action, '@', DATE_FORMAT(created_at, '%m-%d %H:%i')) ORDER BY created_at DESC SEPARATOR ' | ') AS audit_trail
                      FROM nx_audit_log
                     WHERE is_deleted = 0 AND resource_type = 'WITHDRAWAL'
                     GROUP BY resource_id
              ) aud ON aud.resource_id = w.withdrawal_no
             WHERE w.is_deleted = 0
             <if test='status != null and status != ""'>AND w.status = #{status}</if>
             <if test='userId != null'>AND w.user_id = #{userId}</if>
             <if test='minAmount != null'>AND w.amount &gt;= #{minAmount}</if>
             <if test='maxAmount != null'>AND w.amount &lt;= #{maxAmount}</if>
             <if test='minRiskScore != null'>
               AND COALESCE(
                   k4o.override_score,
                   k4.model_score,
                   rd.risk_score,
                   (
                       SELECT rd2.risk_score
                         FROM nx_risk_decision rd2
                        WHERE rd2.is_deleted = 0
                          AND rd2.biz_type = 'WITHDRAW_RULE'
                          AND (rd2.biz_no = w.withdrawal_no)
                        ORDER BY rd2.id DESC
                        LIMIT 1
                   ),
                   0
               ) &gt;= #{minRiskScore}
             </if>
             <if test='keyword != null and keyword != ""'>
               AND (w.withdrawal_no LIKE CONCAT('%', #{keyword}, '%')
                    OR w.target_address LIKE CONCAT('%', #{keyword}, '%')
                    OR w.chain_tx_hash LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(w.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR hit.hit_rules LIKE CONCAT('%', #{keyword}, '%')
                    OR hit.hit_reasons LIKE CONCAT('%', #{keyword}, '%')
                    OR rd.reason LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY w.created_at DESC, w.id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<WithdrawalOrderView> page(@Param("status") String status, @Param("userId") Long userId,
                                   @Param("keyword") String keyword, @Param("minAmount") BigDecimal minAmount,
                                   @Param("maxAmount") BigDecimal maxAmount,
                                   @Param("minRiskScore") Integer minRiskScore, @Param("pageSize") int pageSize,
                                   @Param("offset") int offset);

    @Select("""
            <script>
            SELECT w.id,
                   w.user_id AS userId,
                   w.withdrawal_no AS withdrawalNo,
                   w.asset,
                   w.chain,
                   w.amount,
                   w.fee,
                   w.target_address AS targetAddress,
                   COALESCE(
                       w.risk_decision_id,
                       (
                           SELECT rd2.id
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       )
                   ) AS riskDecisionId,
                   w.chain_tx_hash AS chainTxHash,
                   w.status,
                   w.chain_submitted_at AS chainSubmittedAt,
                   w.completed_at AS completedAt,
                   w.failed_at AS failedAt,
                   w.failure_reason AS failureReason,
                   w.chain_broadcast_attempts AS chainBroadcastAttempts,
                   w.next_broadcast_at AS nextBroadcastAt,
                   w.last_broadcast_error AS lastBroadcastError,
                   w.broadcast_dead_at AS broadcastDeadAt,
                   w.created_at AS createdAt,
                   w.updated_at AS updatedAt,
                   CONCAT('U', LPAD(w.user_id, 8, '0')) AS userNo,
                   u.nickname AS nickname,
                   CASE
                     WHEN u.phone IS NULL OR LENGTH(u.phone) &lt; 7 THEN u.phone
                       ELSE CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3))
                   END AS phoneMasked,
                   COALESCE(u.kyc_status, 'PENDING') AS kycStatus,
                   COALESCE(u.status, 'UNKNOWN') AS userStatus,
                   COALESCE(
                       k4o.override_score,
                       k4.model_score,
                       rd.risk_score,
                       (
                           SELECT rd2.risk_score
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       ),
                       0
                   ) AS riskScore,
                   COALESCE(
                       hit.hit_rules,
                       rd.rule_codes,
                       (
                           SELECT rd2.rule_codes
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       )
                   ) AS hitRules,
                   COALESCE(
                       rd.reason,
                       (
                           SELECT rd2.reason
                             FROM nx_risk_decision rd2
                            WHERE rd2.is_deleted = 0
                              AND rd2.biz_type = 'WITHDRAW_RULE'
                              AND (rd2.biz_no = w.withdrawal_no)
                            ORDER BY rd2.id DESC
                            LIMIT 1
                       ),
                       hit.hit_reasons
                   ) AS riskReason,
                   (
                       SELECT COUNT(1)
                         FROM nx_withdrawal_order w2
                        WHERE w2.user_id = w.user_id
                          AND w2.is_deleted = 0
                          AND w2.created_at >= DATE_SUB(w.created_at, INTERVAL 24 HOUR)
                         AND w2.created_at &lt;= w.created_at
                   ) AS withdrawalCount24h,
                   CONCAT(
                       'created:', DATE_FORMAT(w.created_at, '%Y-%m-%d %H:%i'),
                       '; status:', w.status,
                       IF(w.chain_submitted_at IS NULL, '', CONCAT('; chain:', DATE_FORMAT(w.chain_submitted_at, '%Y-%m-%d %H:%i'))),
                       IF(w.completed_at IS NULL, '', CONCAT('; completed:', DATE_FORMAT(w.completed_at, '%Y-%m-%d %H:%i'))),
                       IF(w.failed_at IS NULL, '', CONCAT('; failed:', DATE_FORMAT(w.failed_at, '%Y-%m-%d %H:%i'))),
                       IF(w.updated_at IS NULL, '', CONCAT('; updated:', DATE_FORMAT(w.updated_at, '%Y-%m-%d %H:%i')))
                   ) AS statusHistory,
                   aud.audit_trail AS auditTrail
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user k4 ON k4.user_no = CONCAT('U', LPAD(w.user_id, 8, '0')) AND k4.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_override k4o ON k4o.user_no = CONCAT('U', LPAD(w.user_id, 8, '0')) AND k4o.active = 1 AND k4o.is_deleted = 0
              LEFT JOIN (
                    SELECT withdrawal_no,
                           GROUP_CONCAT(CONCAT(h.rule_id, ':', h.action, '@', h.time_text) ORDER BY h.id DESC SEPARATOR ', ') AS hit_rules,
                           GROUP_CONCAT(
                               COALESCE(
                                   CONCAT(h.dimension, '规则命中:', r.condition_text, ' -> ', h.action),
                                   CONCAT(h.dimension, '规则命中:', h.rule_id, ' -> ', h.action)
                               )
                               ORDER BY h.id DESC SEPARATOR ' | '
                           ) AS hit_reasons
                      FROM nx_admin_risk_withdraw_hit h
                      LEFT JOIN nx_admin_risk_withdraw_rule r
                        ON r.rule_id = h.rule_id AND r.is_deleted = 0
                     WHERE h.is_deleted = 0
                     GROUP BY h.withdrawal_no
              ) hit ON (hit.withdrawal_no = w.withdrawal_no)
              LEFT JOIN (
                    SELECT resource_id,
                           GROUP_CONCAT(CONCAT(action, '@', DATE_FORMAT(created_at, '%m-%d %H:%i')) ORDER BY created_at DESC SEPARATOR ' | ') AS audit_trail
                      FROM nx_audit_log
                     WHERE is_deleted = 0 AND resource_type = 'WITHDRAWAL'
                     GROUP BY resource_id
              ) aud ON aud.resource_id = w.withdrawal_no
             WHERE w.withdrawal_no = #{withdrawalNo} AND w.is_deleted = 0
            LIMIT 1
            </script>
            """)
    WithdrawalOrderView findByWithdrawalNo(@Param("withdrawalNo") String withdrawalNo);

    @Select("""
            SELECT COALESCE(NULLIF(country_code, ''), NULLIF(region, ''))
              FROM nx_user
             WHERE id = #{userId} AND is_deleted = 0
             LIMIT 1
            """)
    String findUserCountryCode(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = #{status},
                   failure_reason = COALESCE(#{failureReason}, failure_reason),
                   updated_at = CURRENT_TIMESTAMP
             WHERE withdrawal_no = #{withdrawalNo} AND is_deleted = 0
            """)
    int updateStatus(@Param("withdrawalNo") String withdrawalNo, @Param("status") String status,
                     @Param("failureReason") String failureReason);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = 'FROZEN',
                   failure_reason = #{reason},
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND status IN ('PENDING','SUBMITTED','REVIEWING','DELAYED','PENDING_CHAIN','CHAIN_SUBMITTED','DEAD')
            """)
    int freezePendingByUserId(@Param("userId") Long userId, @Param("reason") String reason);
}
