package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.infrastructure.DepositOrderEntity;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DepositOrderMapper extends BaseMapper<DepositOrderEntity> {

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
