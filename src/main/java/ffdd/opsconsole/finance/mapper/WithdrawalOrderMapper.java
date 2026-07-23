package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.infrastructure.WithdrawalOrderEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WithdrawalOrderMapper extends BaseMapper<WithdrawalOrderEntity> {

    @Select("""
            SELECT COUNT(1)
              FROM nx_withdrawal_order
             WHERE is_deleted = 0
               AND status IN ('REVIEW_PENDING', 'EXTENDED_HOLD', 'FROZEN', 'REVIEW_PASSED', 'PROCESSING', 'SENT', 'TX_ORPHANED',
                              'REVIEWING', 'DELAYED', 'PENDING_CHAIN', 'CHAIN_SUBMITTED', 'DEAD')
            """)
    long countD2ActionableWithdrawals();

    @Select("""
            <script>
            SELECT COUNT(DISTINCT w.id)
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user k4
                ON k4.user_no = CONCAT('U', LPAD(w.user_id, 8, '0'))
               AND k4.is_deleted = 0
               AND k4.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
              LEFT JOIN nx_admin_risk_score_model k4m
                ON k4m.state = 'active' AND k4m.is_deleted = 0
               AND k4.model_version = CONCAT('k4-v', k4m.model_version)
              LEFT JOIN nx_admin_risk_score_override k4o ON k4o.user_no = k4.user_no AND k4o.active = 1 AND k4o.is_deleted = 0
              LEFT JOIN (
                    SELECT withdrawal_no,
                           GROUP_CONCAT(CONCAT(rule_id, ':', action) ORDER BY id DESC SEPARATOR ', ') AS hit_rules,
                           GROUP_CONCAT(CONCAT(dimension, '规则命中:', rule_id, ' -> ', action) ORDER BY id DESC SEPARATOR ' | ') AS hit_reasons
                      FROM nx_admin_risk_withdraw_hit
                     WHERE is_deleted = 0
                     GROUP BY withdrawal_no
              ) hit ON (hit.withdrawal_no = w.withdrawal_no)
             WHERE w.is_deleted = 0
             <if test='status != null and status != ""'>
               AND (CASE w.status
                 WHEN 'PENDING' THEN 'SUBMITTED'
                 WHEN 'REVIEWING' THEN 'REVIEW_PENDING'
                 WHEN 'DELAYED' THEN 'EXTENDED_HOLD'
                 WHEN 'PENDING_CHAIN' THEN 'REVIEW_PASSED'
                 WHEN 'CHAIN_SUBMITTED' THEN 'SENT'
                 WHEN 'SUCCESS' THEN 'CONFIRMED'
                 WHEN 'REJECTED' THEN 'REVIEW_REJECTED'
                 WHEN 'FAILED' THEN 'TX_FAILED'
                 WHEN 'DEAD' THEN 'TX_ORPHANED'
                 ELSE w.status END) = #{status}
             </if>
             <if test='userId != null'>AND w.user_id = #{userId}</if>
             <if test='minAmount != null'>AND w.amount &gt;= #{minAmount}</if>
             <if test='maxAmount != null'>AND w.amount &lt;= #{maxAmount}</if>
             <if test='minRiskScore != null'>
               AND COALESCE(k4o.override_score, k4.model_score) &gt;= #{minRiskScore}
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
             <if test='ipSegment != null and ipSegment != ""'>
               AND EXISTS (
                 SELECT 1 FROM nx_user_session ips
                  WHERE ips.user_id=w.user_id AND ips.is_deleted=0
                    AND ips.client_ip LIKE CONCAT(#{ipSegment}, '%')
               )
             </if>
            </script>
            """)
    long countPage(@Param("status") String status, @Param("userId") Long userId,
                   @Param("keyword") String keyword, @Param("minAmount") BigDecimal minAmount,
                   @Param("maxAmount") BigDecimal maxAmount,
                   @Param("minRiskScore") Integer minRiskScore,
                   @Param("ipSegment") String ipSegment);

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
                   COALESCE(kyc.status, 'PENDING') AS kycStatus,
                   COALESCE(u.status, 'UNKNOWN') AS userStatus,
                   COALESCE(k4o.override_score, k4.model_score) AS riskScore,
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
                   aud.audit_trail AS auditTrail,
                   u.user_level AS userLevel,
                   CONCAT(
                     '总设备=', (SELECT COUNT(1) FROM nx_user_device ud WHERE ud.user_id=w.user_id AND ud.is_deleted=0),
                     '; 活跃=', (SELECT COUNT(1) FROM nx_user_device ud WHERE ud.user_id=w.user_id AND ud.is_deleted=0 AND ud.status IN ('ONLINE','BUSY','ACTIVE','RUNNING')),
                     '; 最近=', COALESCE((SELECT CONCAT(ud.name,'/',ud.status,'/',COALESCE(ud.dc_location,'未知地域')) FROM nx_user_device ud WHERE ud.user_id=w.user_id AND ud.is_deleted=0 ORDER BY COALESCE(ud.last_seen_at,ud.updated_at) DESC LIMIT 1),'无')
                   ) AS deviceSummary,
                   CONCAT('邀请码=',COALESCE(u.referral_code,'—'),'; 上级=',COALESCE(u.sponsor_code,'—')) AS referralPosition,
                   CASE WHEN k4.model_score IS NULL THEN 'K4评分不可用'
                        ELSE CONCAT('模型=',k4.model_score,'; 人工覆盖=',COALESCE(CAST(k4o.override_score AS CHAR),'无'),'; 决策=',COALESCE(rd.decision,'无'),'; 规则=',COALESCE(rd.rule_codes,hit.hit_rules,'无'))
                   END AS riskScoreBreakdown,
                   (SELECT GROUP_CONCAT(CONCAT(w3.withdrawal_no,'/',w3.status,'/',w3.amount,' ',w3.asset,'@',DATE_FORMAT(w3.created_at,'%Y-%m-%d %H:%i')) ORDER BY w3.created_at DESC SEPARATOR ' | ')
                      FROM nx_withdrawal_order w3 WHERE w3.user_id=w.user_id AND w3.is_deleted=0) AS withdrawalHistory,
                   w.d2_penalty_fee_rate AS penaltyFeeRate,
                   w.d2_gross_fee AS grossFee,
                   w.d2_nex_burned AS nexBurned,
                   w.d2_nex_fee_offset_rate AS nexFeeOffsetRate,
                   w.d2_fee_waived AS feeWaived,
                   w.d2_actual_fee AS actualFee,
                   w.d2_net_receive AS netReceive,
                   (SELECT CASE WHEN ips.client_ip IS NULL OR ips.client_ip='' THEN '—'
                                WHEN LOCATE('.',REVERSE(ips.client_ip)) &gt; 0 THEN CONCAT(SUBSTRING(ips.client_ip,1,LENGTH(ips.client_ip)-LOCATE('.',REVERSE(ips.client_ip))),'.*')
                                ELSE ips.client_ip END
                      FROM nx_user_session ips WHERE ips.user_id=w.user_id AND ips.is_deleted=0 ORDER BY COALESCE(ips.last_active_at,ips.created_at) DESC LIMIT 1) AS ipSegment,
                   w.d2_hold_until AS holdUntil,
                   w.d2_lifecycle_owner AS lifecycleOwner,
                   w.d2_freeze_period AS freezePeriod,
                   w.d2_previous_status AS previousStatus,
                   CASE WHEN COALESCE(k4o.override_score, k4.model_score) IS NULL THEN 'UNAVAILABLE'
                        WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.auto_escalate_score THEN 'ESCALATED'
                        WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.band_high_min THEN 'HIGH'
                        WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.band_low_max THEN 'NORMAL'
                        ELSE 'LOW' END AS routingPriority,
                   k4m.band_low_max AS k4BandLowMax,
                   k4m.band_high_min AS k4BandHighMin,
                   k4m.auto_escalate_score AS k4AutoEscalateScore,
                   w.d2_k3_risk_route AS k3RiskRoute
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=w.user_id AND kyc.is_deleted=0
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user k4
                ON k4.user_no = CONCAT('U', LPAD(w.user_id, 8, '0'))
               AND k4.is_deleted = 0
               AND k4.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
              LEFT JOIN nx_admin_risk_score_model k4m
                ON k4m.state = 'active' AND k4m.is_deleted = 0
               AND k4.model_version = CONCAT('k4-v', k4m.model_version)
              LEFT JOIN nx_admin_risk_score_override k4o ON k4o.user_no = k4.user_no AND k4o.active = 1 AND k4o.is_deleted = 0
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
             <if test='status != null and status != ""'>
               AND (CASE w.status
                 WHEN 'PENDING' THEN 'SUBMITTED' WHEN 'REVIEWING' THEN 'REVIEW_PENDING'
                 WHEN 'DELAYED' THEN 'EXTENDED_HOLD' WHEN 'PENDING_CHAIN' THEN 'REVIEW_PASSED'
                 WHEN 'CHAIN_SUBMITTED' THEN 'SENT' WHEN 'SUCCESS' THEN 'CONFIRMED'
                 WHEN 'REJECTED' THEN 'REVIEW_REJECTED' WHEN 'FAILED' THEN 'TX_FAILED'
                 WHEN 'DEAD' THEN 'TX_ORPHANED' ELSE w.status END) = #{status}
             </if>
             <if test='userId != null'>AND w.user_id = #{userId}</if>
             <if test='minAmount != null'>AND w.amount &gt;= #{minAmount}</if>
             <if test='maxAmount != null'>AND w.amount &lt;= #{maxAmount}</if>
             <if test='minRiskScore != null'>
               AND COALESCE(k4o.override_score, k4.model_score) &gt;= #{minRiskScore}
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
             <if test='ipSegment != null and ipSegment != ""'>
               AND EXISTS (SELECT 1 FROM nx_user_session ips WHERE ips.user_id=w.user_id AND ips.is_deleted=0 AND ips.client_ip LIKE CONCAT(#{ipSegment}, '%'))
             </if>
             ORDER BY
             <choose>
               <when test='sortBy == "amount"'>w.amount</when>
               <when test='sortBy == "riskScore"'>riskScore</when>
               <when test='sortBy == "status"'>w.status</when>
               <otherwise>CASE
                 WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.auto_escalate_score THEN 0
                 WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.band_high_min THEN 1
                 WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.band_low_max THEN 2
                 WHEN COALESCE(k4o.override_score, k4.model_score) IS NOT NULL THEN 3 ELSE 4 END ASC,
                 w.created_at</otherwise>
             </choose>
             <choose><when test='sortDirection == "asc"'>ASC</when><otherwise>DESC</otherwise></choose>, w.id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<WithdrawalOrderView> page(@Param("status") String status, @Param("userId") Long userId,
                                   @Param("keyword") String keyword, @Param("minAmount") BigDecimal minAmount,
                                   @Param("maxAmount") BigDecimal maxAmount,
                                   @Param("minRiskScore") Integer minRiskScore,
                                   @Param("ipSegment") String ipSegment,
                                   @Param("sortBy") String sortBy,
                                   @Param("sortDirection") String sortDirection,
                                   @Param("pageSize") int pageSize,
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
                   COALESCE(kyc.status, 'PENDING') AS kycStatus,
                   COALESCE(u.status, 'UNKNOWN') AS userStatus,
                   COALESCE(k4o.override_score, k4.model_score) AS riskScore,
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
                   aud.audit_trail AS auditTrail,
                   u.user_level AS userLevel,
                   CONCAT(
                     '总设备=', (SELECT COUNT(1) FROM nx_user_device ud WHERE ud.user_id=w.user_id AND ud.is_deleted=0),
                     '; 活跃=', (SELECT COUNT(1) FROM nx_user_device ud WHERE ud.user_id=w.user_id AND ud.is_deleted=0 AND ud.status IN ('ONLINE','BUSY','ACTIVE','RUNNING')),
                     '; 最近=', COALESCE((SELECT CONCAT(ud.name,'/',ud.status,'/',COALESCE(ud.dc_location,'未知地域')) FROM nx_user_device ud WHERE ud.user_id=w.user_id AND ud.is_deleted=0 ORDER BY COALESCE(ud.last_seen_at,ud.updated_at) DESC LIMIT 1),'无')
                   ) AS deviceSummary,
                   CONCAT('邀请码=',COALESCE(u.referral_code,'—'),'; 上级=',COALESCE(u.sponsor_code,'—')) AS referralPosition,
                   CASE WHEN k4.model_score IS NULL THEN 'K4评分不可用'
                        ELSE CONCAT('模型=',k4.model_score,'; 人工覆盖=',COALESCE(CAST(k4o.override_score AS CHAR),'无'),'; 决策=',COALESCE(rd.decision,'无'),'; 规则=',COALESCE(rd.rule_codes,hit.hit_rules,'无'))
                   END AS riskScoreBreakdown,
                   (SELECT GROUP_CONCAT(CONCAT(w3.withdrawal_no,'/',w3.status,'/',w3.amount,' ',w3.asset,'@',DATE_FORMAT(w3.created_at,'%Y-%m-%d %H:%i')) ORDER BY w3.created_at DESC SEPARATOR ' | ')
                      FROM nx_withdrawal_order w3 WHERE w3.user_id=w.user_id AND w3.is_deleted=0) AS withdrawalHistory,
                   w.d2_penalty_fee_rate AS penaltyFeeRate,
                   w.d2_gross_fee AS grossFee,
                   w.d2_nex_burned AS nexBurned,
                   w.d2_nex_fee_offset_rate AS nexFeeOffsetRate,
                   w.d2_fee_waived AS feeWaived,
                   w.d2_actual_fee AS actualFee,
                   w.d2_net_receive AS netReceive,
                   (SELECT CASE WHEN ips.client_ip IS NULL OR ips.client_ip='' THEN '—'
                                WHEN LOCATE('.',REVERSE(ips.client_ip)) &gt; 0 THEN CONCAT(SUBSTRING(ips.client_ip,1,LENGTH(ips.client_ip)-LOCATE('.',REVERSE(ips.client_ip))),'.*')
                                ELSE ips.client_ip END
                      FROM nx_user_session ips WHERE ips.user_id=w.user_id AND ips.is_deleted=0 ORDER BY COALESCE(ips.last_active_at,ips.created_at) DESC LIMIT 1) AS ipSegment,
                   w.d2_hold_until AS holdUntil,
                   w.d2_lifecycle_owner AS lifecycleOwner,
                   w.d2_freeze_period AS freezePeriod,
                   w.d2_previous_status AS previousStatus,
                   CASE WHEN COALESCE(k4o.override_score, k4.model_score) IS NULL THEN 'UNAVAILABLE'
                        WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.auto_escalate_score THEN 'ESCALATED'
                        WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.band_high_min THEN 'HIGH'
                        WHEN COALESCE(k4o.override_score, k4.model_score) &gt;= k4m.band_low_max THEN 'NORMAL'
                        ELSE 'LOW' END AS routingPriority,
                   k4m.band_low_max AS k4BandLowMax,
                   k4m.band_high_min AS k4BandHighMin,
                   k4m.auto_escalate_score AS k4AutoEscalateScore,
                   w.d2_k3_risk_route AS k3RiskRoute
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=w.user_id AND kyc.is_deleted=0
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user k4
                ON k4.user_no = CONCAT('U', LPAD(w.user_id, 8, '0'))
               AND k4.is_deleted = 0
               AND k4.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
              LEFT JOIN nx_admin_risk_score_model k4m
                ON k4m.state = 'active' AND k4m.is_deleted = 0
               AND k4.model_version = CONCAT('k4-v', k4m.model_version)
              LEFT JOIN nx_admin_risk_score_override k4o ON k4o.user_no = k4.user_no AND k4o.active = 1 AND k4o.is_deleted = 0
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
               SET status = 'REVIEW_PENDING',
                   failure_reason = NULL,
                   d2_version = d2_version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE withdrawal_no = #{withdrawalNo}
               AND status = #{expectedStatus}
               AND is_deleted = 0
            """)
    int transitionStatus(
            @Param("withdrawalNo") String withdrawalNo,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("failureReason") String failureReason);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = #{newStatus},
                   failure_reason = #{failureReason},
                   d2_hold_until = #{holdUntil},
                   d2_lifecycle_owner = #{owner},
                   d2_freeze_period = #{period},
                   d2_previous_status = #{previousStatus},
                   d2_version = d2_version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE withdrawal_no = #{withdrawalNo}
               AND status = #{expectedStatus}
               AND is_deleted = 0
            """)
    int transitionStatusWithLifecycle(
            @Param("withdrawalNo") String withdrawalNo,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("failureReason") String failureReason,
            @Param("holdUntil") LocalDateTime holdUntil,
            @Param("owner") String owner,
            @Param("period") String period,
            @Param("previousStatus") String previousStatus);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = 'REVIEW_PENDING',
                   d2_hold_until = NULL,
                   d2_lifecycle_owner = NULL,
                   d2_freeze_period = NULL,
                   d2_previous_status = NULL,
                   failure_reason = 'scheduled-review-due',
                   d2_version = d2_version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE status IN ('EXTENDED_HOLD', 'FROZEN')
               AND d2_hold_until IS NOT NULL
               AND d2_hold_until <= #{now}
               AND is_deleted = 0
            """)
    int releaseExpiredHolds(@Param("now") LocalDateTime now);

    @Select("""
            SELECT withdrawal_no
              FROM nx_withdrawal_order
             WHERE status IN ('EXTENDED_HOLD', 'FROZEN')
               AND d2_hold_until IS NOT NULL
               AND d2_hold_until <= #{now}
               AND is_deleted = 0
             ORDER BY d2_hold_until, id
             LIMIT 500
            """)
    List<String> findExpiredLifecycleNos(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = #{newStatus},
                   d2_hold_until = NULL,
                   d2_lifecycle_owner = NULL,
                   d2_freeze_period = NULL,
                   d2_previous_status = NULL,
                   failure_reason = #{failureReason},
                   d2_version = d2_version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE withdrawal_no = #{withdrawalNo}
               AND status = #{expectedStatus}
               AND d2_hold_until IS NOT NULL
               AND d2_hold_until <= #{now}
               AND (#{newStatus} != 'REVIEW_PASSED'
                    OR (d2_previous_status = 'REVIEW_PASSED' AND d2_lifecycle_owner = 'H1_PHASE_COOLDOWN'))
               AND is_deleted = 0
            """)
    int releaseExpiredLifecycle(
            @Param("withdrawalNo") String withdrawalNo,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("failureReason") String failureReason,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = #{status},
                   failure_reason = #{failureReason},
                   updated_at = CURRENT_TIMESTAMP
             WHERE withdrawal_no = #{withdrawalNo}
               AND status = 'FROZEN'
               AND failure_reason = CONCAT('K5_REVIEW:', #{ticketId})
               AND is_deleted = 0
            """)
    int transitionK5FrozenStatus(
            @Param("withdrawalNo") String withdrawalNo,
            @Param("ticketId") String ticketId,
            @Param("status") String status,
            @Param("failureReason") String failureReason);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = 'FROZEN',
                   failure_reason = CONCAT('K5_REVIEW:', #{ticketId}),
                   updated_at = CURRENT_TIMESTAMP
             WHERE withdrawal_no = #{withdrawalNo}
               AND status = #{expectedStatus}
               AND is_deleted = 0
            """)
    int freezeForK5Review(@Param("withdrawalNo") String withdrawalNo,
                          @Param("expectedStatus") String expectedStatus,
                          @Param("ticketId") String ticketId);

    @Update("""
            UPDATE nx_withdrawal_order
               SET c2_previous_status = status,
                   status = 'FROZEN',
                   c2_frozen_by_user_status = 1,
                   failure_reason = #{reason},
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND status IN ('PENDING','SUBMITTED','REVIEWING','REVIEW_PENDING','DELAYED','EXTENDED_HOLD',
                              'PENDING_CHAIN','REVIEW_PASSED','PROCESSING','CHAIN_SUBMITTED','SENT','DEAD','TX_ORPHANED')
            """)
    int freezePendingByUserId(@Param("userId") Long userId, @Param("reason") String reason);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status = COALESCE(NULLIF(c2_previous_status, ''), 'REVIEW_PENDING'),
                   c2_previous_status = NULL,
                   c2_frozen_by_user_status = 0,
                   failure_reason = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND status = 'FROZEN'
               AND c2_frozen_by_user_status = 1
            """)
    int restoreFrozenByUserStatus(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_withdrawal_order
             WHERE withdrawal_no = #{withdrawalNo}
               AND is_deleted = 0
               AND status = 'FROZEN'
               AND c2_frozen_by_user_status = 1
            """)
    int countFrozenByUserStatus(@Param("withdrawalNo") String withdrawalNo);
}
