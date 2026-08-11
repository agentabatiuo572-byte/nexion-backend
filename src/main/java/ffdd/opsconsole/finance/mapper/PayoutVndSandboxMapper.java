package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PayoutVndSandboxMapper extends BaseMapper<Object> {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_payout_vnd_sandbox_order
              (order_no,user_id,amount_vnd,bank_code,account_no_masked,account_name,status,source,idempotency_key,reason,created_at,updated_at)
            VALUES
              (#{orderNo},#{userId},#{amountVnd},#{bankCode},#{accountNoMasked},#{accountName},'PENDING','mock',#{idempotencyKey},#{reason},#{now},#{now})
            """)
    int insertOrder(@Param("orderNo") String orderNo, @Param("userId") Long userId,
                    @Param("amountVnd") BigDecimal amountVnd, @Param("bankCode") String bankCode,
                    @Param("accountNoMasked") String accountNoMasked, @Param("accountName") String accountName,
                    @Param("idempotencyKey") String idempotencyKey, @Param("reason") String reason,
                    @Param("now") LocalDateTime now);

    @Select("SELECT order_no orderNo,user_id userId,amount_vnd amountVnd,bank_code bankCode,account_no_masked accountNoMasked,account_name accountName,status,source,created_at createdAt,updated_at updatedAt FROM nx_payout_vnd_sandbox_order WHERE order_no=#{orderNo} LIMIT 1")
    Map<String, Object> order(@Param("orderNo") String orderNo);

    @Select("SELECT order_no orderNo,user_id userId,amount_vnd amountVnd,bank_code bankCode,account_no_masked accountNoMasked,account_name accountName,status,source,created_at createdAt,updated_at updatedAt FROM nx_payout_vnd_sandbox_order WHERE user_id=#{userId} ORDER BY id DESC LIMIT 100")
    List<Map<String, Object>> orders(@Param("userId") Long userId);

    @Update("UPDATE nx_payout_vnd_sandbox_order SET status=#{status},updated_at=#{now} WHERE order_no=#{orderNo} AND status='PENDING'")
    int complete(@Param("orderNo") String orderNo, @Param("status") String status, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_payout_vnd_sandbox_ledger(event_id,order_no,user_id,direction,amount_vnd,status,source,created_at)
            SELECT #{eventId},order_no,user_id,'OUT',amount_vnd,#{status},'mock',#{now}
              FROM nx_payout_vnd_sandbox_order WHERE order_no=#{orderNo}
            """)
    int insertLedger(@Param("eventId") String eventId, @Param("orderNo") String orderNo,
                     @Param("status") String status, @Param("now") LocalDateTime now);
}
