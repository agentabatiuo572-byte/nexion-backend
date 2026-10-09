package ffdd.opsconsole.finance.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Two ordinary batch reads. No writes, ownership inference, or whole-platform empty-scope fallback. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportFundsReadMapper {
    String CUSTOMER_SCOPE="""
        <choose><when test="customerIds != null and !customerIds.isEmpty()">
          user_id IN <foreach collection="customerIds" item="customer" open="(" separator="," close=")">#{customer}</foreach>
        </when><otherwise>1=0</otherwise></choose>
        """;

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>SELECT id walletId,user_id customerId,version,usdt_available usdtAvailable,
          nex_available nexAvailable,updated_at updatedAt,NOW(6) evaluatedDbAt
        FROM nx_user_wallet WHERE is_deleted=0 AND
        """+CUSTOMER_SCOPE+" ORDER BY user_id,id</script>")
    List<WalletRow> wallets(@Param("customerIds") List<Long> customerIds);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>SELECT id withdrawalId,user_id customerId,asset currency,amount principal,
          d2_actual_fee actualFee,d2_net_receive net,status,completed_at completedAt,
          updated_at updatedAt,NOW(6) evaluatedDbAt
        FROM nx_withdrawal_order WHERE is_deleted=0 AND
        """+CUSTOMER_SCOPE+" ORDER BY user_id,id</script>")
    List<WithdrawalRow> withdrawals(@Param("customerIds") List<Long> customerIds);

    record WalletRow(Long walletId,Long customerId,Long version,BigDecimal usdtAvailable,
            BigDecimal nexAvailable,LocalDateTime updatedAt,LocalDateTime evaluatedDbAt) { }
    record WithdrawalRow(Long withdrawalId,Long customerId,String currency,BigDecimal principal,
            BigDecimal actualFee,BigDecimal net,String status,LocalDateTime completedAt,
            LocalDateTime updatedAt,LocalDateTime evaluatedDbAt) { }
}
