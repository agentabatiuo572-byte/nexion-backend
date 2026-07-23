package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.infrastructure.DepositOrderEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DepositOrderMapper extends BaseMapper<DepositOrderEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_deposit_reconciliation_writeoff (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              channel_code VARCHAR(64) NOT NULL,
              reconcile_date DATE NOT NULL,
              operator VARCHAR(64) NOT NULL,
              reason VARCHAR(255) NOT NULL,
              method VARCHAR(32) NOT NULL DEFAULT 'CONFIRM_EXCEPTION',
              evidence_ref VARCHAR(128) NOT NULL DEFAULT 'LEGACY',
              idempotency_key VARCHAR(128) NOT NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'RECONCILED',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_deposit_reconcile_writeoff (channel_code, reconcile_date),
              UNIQUE KEY uk_deposit_reconcile_idem (idempotency_key),
              KEY idx_deposit_reconcile_status (status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createReconciliationWriteoffTable();

    @Select("""
            WITH provider_side AS (
                SELECT channel_code AS channel, COUNT(1) AS provider_count,
                       COALESCE(SUM(amount_usdt), 0) AS provider_amount
                 FROM nx_topup_provider_statement
                 WHERE is_deleted = 0 AND ingestion_event_id IS NOT NULL AND payload_hash IS NOT NULL
                   AND observed_at >= CURRENT_DATE
                   AND statement_status IN ('PAID', 'CONFIRMED', 'SETTLED')
                 GROUP BY channel_code
            ), ledger_side AS (
                SELECT COALESCE(d.chain_name,
                         CASE WHEN p.provider IN ('Checkout.com', 'Stripe', 'Card') THEN 'Card' ELSE p.provider END) AS channel,
                       COUNT(1) AS ledger_count,
                       COALESCE(SUM(CASE WHEN p.id IS NOT NULL
                                         THEN l.amount + p.fee_amount_usdt ELSE l.amount END), 0) AS ledger_amount
                  FROM nx_wallet_ledger l
                  LEFT JOIN nx_deposit_order d
                    ON d.ledger_id=l.id AND d.is_deleted=0 AND d.user_id=l.user_id
                   AND d.deposit_no=l.biz_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
                   AND d.asset=l.asset AND d.amount=l.amount
                  LEFT JOIN nx_payment_record p
                    ON p.wallet_ledger_id=l.id AND p.is_deleted=0 AND p.user_id=l.user_id
                   AND p.amount_usdt=l.amount AND l.biz_type='CARD_TOPUP' AND l.biz_no=p.payment_no
                 WHERE l.is_deleted = 0 AND l.created_at >= CURRENT_DATE
                   AND l.asset = 'USDT' AND l.direction = 'IN' AND l.status = 'SUCCESS'
                   AND (d.id IS NOT NULL OR p.id IS NOT NULL)
                 GROUP BY COALESCE(d.chain_name,
                         CASE WHEN p.provider IN ('Checkout.com', 'Stripe', 'Card') THEN 'Card' ELSE p.provider END)
            ), channels AS (
                SELECT channel FROM provider_side UNION SELECT channel FROM ledger_side
            )
            SELECT c.channel AS channel,
                   COALESCE(p.provider_count, 0) AS providerCount,
                   COALESCE(p.provider_amount, 0) AS providerAmount,
                   COALESCE(l.ledger_count, 0) AS ledgerCount,
                   COALESCE(l.ledger_amount, 0) AS ledgerAmount
              FROM channels c
              LEFT JOIN provider_side p ON p.channel = c.channel
              LEFT JOIN ledger_side l ON l.channel = c.channel
             ORDER BY c.channel
            """)
    List<DepositAggregateView> aggregateToday();

    @Select("""
            SELECT COUNT(1)
              FROM nx_deposit_reconciliation_writeoff
             WHERE is_deleted = 0
               AND status = 'RECONCILED'
               AND channel_code = #{channelCode}
               AND reconcile_date = #{reconcileDate}
            """)
    long countReconciliationWriteoff(@Param("channelCode") String channelCode,
                                     @Param("reconcileDate") LocalDate reconcileDate);

    @Insert("""
            INSERT INTO nx_deposit_reconciliation_writeoff (
              channel_code, reconcile_date, method, evidence_ref, operator, reason, idempotency_key, status,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{channelCode}, #{reconcileDate}, #{method}, #{evidenceRef}, #{operator}, #{reason}, #{idempotencyKey}, 'RECONCILED',
              NOW(), NOW(), 0
            )
            """)
    int insertReconciliationWriteoff(@Param("channelCode") String channelCode,
                                     @Param("reconcileDate") LocalDate reconcileDate,
                                     @Param("method") String method,
                                     @Param("evidenceRef") String evidenceRef,
                                     @Param("operator") String operator,
                                     @Param("reason") String reason,
                                     @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            <script>
            WITH all_flows AS (
              SELECT d.user_id AS user_id, d.deposit_no AS flow_no,
                     COALESCE(NULLIF(d.chain_tx_hash, ''), d.deposit_no) AS proof,
                     d.asset,
                     CASE WHEN d.status IN ('CONFIRMED','CREDITED','SUCCESS') AND l.id IS NULL
                          THEN 'ABNORMAL' ELSE d.status END AS status
                FROM nx_deposit_order d
                LEFT JOIN nx_wallet_ledger l
                  ON l.id=d.ledger_id AND l.is_deleted=0 AND l.user_id=d.user_id
                 AND l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
                 AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
               WHERE d.is_deleted = 0
              UNION ALL
              SELECT p.user_id, p.payment_no,
                     COALESCE(NULLIF(p.provider_payment_id, ''), p.payment_no),
                     p.currency, p.payment_status
                FROM nx_payment_record p
               WHERE p.is_deleted = 0
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_deposit_order d
                    WHERE d.is_deleted = 0
                      AND d.deposit_no IN (p.payment_no, p.order_no)
                 )
              UNION ALL
              SELECT a.user_id, a.order_no, a.admission_event_id, 'USDT',
                      CASE WHEN a.decision!='ALLOWED' THEN 'REJECTED'
                          WHEN a.expires_at &lt;= NOW() THEN 'EXPIRED' ELSE 'PENDING' END
                FROM nx_topup_card_admission a
               WHERE a.is_deleted=0 AND a.settlement_event_id IS NULL AND a.failure_event_id IS NULL
                 AND NOT EXISTS (SELECT 1 FROM nx_payment_record p
                                  WHERE p.is_deleted=0 AND p.order_no=a.order_no)
                 AND NOT EXISTS (SELECT 1 FROM nx_deposit_order d
                                  WHERE d.is_deleted=0 AND d.deposit_no=a.order_no)
            )
            SELECT COUNT(1) FROM all_flows
             WHERE 1 = 1
             <if test='statuses != null and statuses.size() > 0'>
               AND status IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (flow_no LIKE CONCAT('%', #{keyword}, '%')
                    OR proof LIKE CONCAT('%', #{keyword}, '%')
                    OR asset LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countFlows(@Param("statuses") Collection<String> statuses, @Param("userId") Long userId,
                    @Param("keyword") String keyword);

    @Select("""
            <script>
            WITH all_flows AS (
              SELECT d.id, d.user_id, d.deposit_no AS flow_no, d.chain_name AS channel, d.asset,
                     d.amount, d.amount AS provider_received,
                     COALESCE(NULLIF(d.chain_tx_hash, ''), d.deposit_no) AS proof,
                     CASE WHEN d.status IN ('CONFIRMED','CREDITED','SUCCESS') AND l.id IS NULL
                          THEN 'ABNORMAL' ELSE d.status END AS status,
                     CASE WHEN d.status IN ('CONFIRMED','CREDITED','SUCCESS') AND l.id IS NULL
                          THEN 'D4充值分录绑定不一致' ELSE d.failure_reason END AS failure_reason,
                     d.created_at, d.confirmed_at, d.credited_at
                FROM nx_deposit_order d
                LEFT JOIN nx_wallet_ledger l
                  ON l.id=d.ledger_id AND l.is_deleted=0 AND l.user_id=d.user_id
                 AND l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
                 AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
               WHERE d.is_deleted = 0
              UNION ALL
              SELECT -p.id, p.user_id, p.payment_no,
                     CASE WHEN UPPER(p.provider) IN ('CHECKOUT.COM','STRIPE','CARD')
                          THEN 'Card' ELSE p.provider END,
                     p.currency, p.amount_usdt,
                     CASE WHEN p.payment_status IN ('CONFIRMED','CREDITED','SUCCESS','CHARGEBACK',
                                                     'DISPUTED','CHARGEBACK_REVIEW','CHARGEBACK_REFUNDED',
                                                     'CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
                          THEN p.amount_usdt + p.fee_amount_usdt ELSE 0 END,
                     COALESCE(NULLIF(p.provider_payment_id, ''), p.payment_no),
                     p.payment_status, p.failure_reason, p.created_at, p.paid_at, p.paid_at
                FROM nx_payment_record p
               WHERE p.is_deleted = 0
                 AND NOT EXISTS (
                   SELECT 1 FROM nx_deposit_order d
                    WHERE d.is_deleted = 0
                      AND d.deposit_no IN (p.payment_no, p.order_no)
                 )
              UNION ALL
              SELECT -1000000000000-a.id, a.user_id, a.order_no, 'Card', 'USDT',
                     a.amount_usdt, CAST(0 AS DECIMAL(18,6)), a.admission_event_id,
                      CASE WHEN a.decision!='ALLOWED' THEN 'REJECTED'
                          WHEN a.expires_at &lt;= NOW() THEN 'EXPIRED' ELSE 'PENDING' END,
                     CASE WHEN a.decision='ALLOWED' AND a.expires_at &lt;= NOW()
                          THEN '入金授权已过期' ELSE a.reason END,
                     a.created_at, NULL, NULL
                FROM nx_topup_card_admission a
               WHERE a.is_deleted=0 AND a.settlement_event_id IS NULL AND a.failure_event_id IS NULL
                 AND NOT EXISTS (SELECT 1 FROM nx_payment_record p
                                  WHERE p.is_deleted=0 AND p.order_no=a.order_no)
                 AND NOT EXISTS (SELECT 1 FROM nx_deposit_order d
                                  WHERE d.is_deleted=0 AND d.deposit_no=a.order_no)
            )
            SELECT id, user_id AS userId, flow_no AS depositNo, channel, asset, amount,
                   provider_received AS providerReceived, proof, status,
                   CASE
                     WHEN status IN ('CONFIRMED', 'CREDITED', 'SUCCESS') THEN '已入账'
                     WHEN status = 'CHARGEBACK_RECOVERED' THEN '拒付已追回'
                     WHEN status = 'CHARGEBACK_PARTIAL' THEN '拒付部分追回'
                     WHEN status = 'CHARGEBACK_REVIEW' THEN '拒付复核中'
                     WHEN status = 'CHARGEBACK_REFUNDED' THEN '拒付已退款'
                     WHEN status = 'DISPUTED' THEN '拒付争议中'
                     WHEN status = 'CHARGEBACK' THEN '拒付待处理'
                     WHEN status = 'FAILED' THEN '处理失败'
                     WHEN status = 'DECLINED' THEN '支付被拒绝'
                     WHEN status = 'EXPIRED' THEN '已过期'
                     WHEN status = 'REJECTED' THEN '已拒绝'
                     WHEN status = 'ABNORMAL' THEN '账务异常'
                     ELSE '待确认'
                   END AS statusLabel,
                   created_at AS createdAt,
                   confirmed_at AS confirmedAt,
                   credited_at AS creditedAt
              FROM all_flows
             WHERE 1 = 1
             <if test='statuses != null and statuses.size() > 0'>
               AND status IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (flow_no LIKE CONCAT('%', #{keyword}, '%')
                    OR proof LIKE CONCAT('%', #{keyword}, '%')
                    OR asset LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY created_at DESC, id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<DepositFlowView> pageFlows(@Param("statuses") Collection<String> statuses, @Param("userId") Long userId,
                                    @Param("keyword") String keyword, @Param("pageSize") int pageSize,
                                    @Param("offset") int offset);
}
