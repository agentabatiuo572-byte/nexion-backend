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
            SELECT chain_name AS channel,
                   COUNT(1) AS providerCount,
                   COALESCE(SUM(amount), 0) AS providerAmount,
                   COALESCE(SUM(CASE WHEN status IN ('CONFIRMED', 'CREDITED', 'SUCCESS') THEN 1 ELSE 0 END), 0) AS ledgerCount,
                   COALESCE(SUM(CASE WHEN status IN ('CONFIRMED', 'CREDITED', 'SUCCESS') THEN amount ELSE 0 END), 0) AS ledgerAmount
              FROM nx_deposit_order
             WHERE is_deleted = 0
               AND created_at >= CURRENT_DATE
             GROUP BY chain_name
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
              channel_code, reconcile_date, operator, reason, idempotency_key, status,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{channelCode}, #{reconcileDate}, #{operator}, #{reason}, #{idempotencyKey}, 'RECONCILED',
              NOW(), NOW(), 0
            )
            """)
    int insertReconciliationWriteoff(@Param("channelCode") String channelCode,
                                     @Param("reconcileDate") LocalDate reconcileDate,
                                     @Param("operator") String operator,
                                     @Param("reason") String reason,
                                     @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_deposit_order
             WHERE is_deleted = 0
             <if test='statuses != null and statuses.size() > 0'>
               AND status IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (deposit_no LIKE CONCAT('%', #{keyword}, '%')
                    OR chain_tx_hash LIKE CONCAT('%', #{keyword}, '%')
                    OR asset LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countFlows(@Param("statuses") Collection<String> statuses, @Param("userId") Long userId,
                    @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT id,
                   user_id AS userId,
                   deposit_no AS depositNo,
                   chain_name AS channel,
                   asset,
                   amount,
                   amount AS providerReceived,
                   COALESCE(NULLIF(chain_tx_hash, ''), deposit_no) AS proof,
                   status,
                   CASE
                     WHEN status IN ('CONFIRMED', 'CREDITED', 'SUCCESS') THEN '已入账'
                     WHEN status IN ('FAILED', 'EXPIRED', 'REJECTED', 'ABNORMAL') THEN COALESCE(failure_reason, '异常')
                     ELSE '待确认'
                   END AS statusLabel,
                   created_at AS createdAt,
                   confirmed_at AS confirmedAt,
                   credited_at AS creditedAt
              FROM nx_deposit_order
             WHERE is_deleted = 0
             <if test='statuses != null and statuses.size() > 0'>
               AND status IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (deposit_no LIKE CONCAT('%', #{keyword}, '%')
                    OR chain_tx_hash LIKE CONCAT('%', #{keyword}, '%')
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
