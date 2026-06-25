package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.infrastructure.DepositOrderEntity;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DepositOrderMapper extends BaseMapper<DepositOrderEntity> {
    @Select("""
            SELECT COUNT(1)
              FROM nx_deposit_order
             WHERE is_deleted = 0
               AND deposit_no LIKE 'D1-SEED-%'
               AND created_at >= CURRENT_DATE
            """)
    long countD1SeedDepositsToday();

    @Insert("""
            INSERT INTO nx_deposit_order (
                deposit_no, user_id, chain_name, chain_tx_hash, asset, amount, confirmations, status,
                ledger_id, confirmed_at, credited_at, failed_at, failure_reason, created_at, updated_at, is_deleted
            )
            VALUES (
                #{depositNo}, #{userId}, #{chainName}, #{chainTxHash}, #{asset}, #{amount}, #{confirmations}, #{status},
                NULL,
                CASE WHEN #{confirmed} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                CASE WHEN #{credited} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                CASE WHEN #{failed} THEN DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE) ELSE NULL END,
                #{failureReason}, DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE), DATE_SUB(NOW(), INTERVAL #{minutesAgo} MINUTE), 0
            )
            ON DUPLICATE KEY UPDATE
                user_id = VALUES(user_id),
                chain_name = VALUES(chain_name),
                chain_tx_hash = VALUES(chain_tx_hash),
                asset = VALUES(asset),
                amount = VALUES(amount),
                confirmations = VALUES(confirmations),
                status = VALUES(status),
                confirmed_at = VALUES(confirmed_at),
                credited_at = VALUES(credited_at),
                failed_at = VALUES(failed_at),
                failure_reason = VALUES(failure_reason),
                created_at = VALUES(created_at),
                updated_at = VALUES(updated_at),
                is_deleted = 0
            """)
    int insertD1SeedDeposit(@Param("depositNo") String depositNo,
                            @Param("userId") Long userId,
                            @Param("chainName") String chainName,
                            @Param("chainTxHash") String chainTxHash,
                            @Param("asset") String asset,
                            @Param("amount") BigDecimal amount,
                            @Param("confirmations") int confirmations,
                            @Param("status") String status,
                            @Param("confirmed") boolean confirmed,
                            @Param("credited") boolean credited,
                            @Param("failed") boolean failed,
                            @Param("failureReason") String failureReason,
                            @Param("minutesAgo") int minutesAgo);

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
