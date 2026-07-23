package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.infrastructure.PaymentRecordEntity;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface E4OrderRefundMapper extends BaseMapper<PaymentRecordEntity> {
    @Select("""
            SELECT user_id AS userId, usdt_available AS usdtAvailable,
                   cumulative_deposit_usdt AS cumulativeDepositUsdt, version
              FROM nx_user_wallet
             WHERE user_id = #{userId} AND is_deleted = 0
             FOR UPDATE
            """)
    TopupWalletSnapshot lockWallet(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available = #{available}, cumulative_deposit_usdt = #{cumulative},
                   version = version + 1, updated_at = NOW()
             WHERE user_id = #{userId} AND is_deleted = 0 AND version = #{version}
            """)
    int updateWallet(@Param("userId") Long userId,
                     @Param("available") BigDecimal available,
                     @Param("cumulative") BigDecimal cumulative,
                     @Param("version") Long version);

    @Insert("""
            INSERT INTO nx_wallet_ledger
              (user_id, biz_no, biz_type, asset, direction, amount, balance_after, status, remark,
               created_at, updated_at, is_deleted)
            VALUES
              (#{userId}, #{bizNo}, 'ORDER_REFUND', 'USDT', 'IN', #{amount}, #{balanceAfter},
               'SUCCESS', #{remark}, NOW(), NOW(), 0)
            """)
    int insertLedger(@Param("userId") Long userId,
                     @Param("bizNo") String bizNo,
                     @Param("amount") BigDecimal amount,
                     @Param("balanceAfter") BigDecimal balanceAfter,
                     @Param("remark") String remark);

    @Insert("""
            INSERT INTO nx_wallet_bill
              (id, user_id, bill_no, type, token, amount, direction, occurred_at, created_at, deleted)
            VALUES
              (UUID_SHORT(), #{userId}, #{billNo}, 'ORDER_REFUND', 'USDT', #{amount}, 'IN', NOW(), NOW(), 0)
            """)
    int insertBill(@Param("userId") Long userId,
                   @Param("billNo") String billNo,
                   @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE nx_payment_record
               SET payment_status = 'REFUNDED', updated_at = NOW()
             WHERE order_no = #{orderNo} AND user_id = #{userId} AND is_deleted = 0
               AND UPPER(payment_status) IN ('PAID','CONFIRMED','SUCCESS')
            """)
    int markPaymentRefunded(@Param("orderNo") String orderNo, @Param("userId") Long userId);
}
