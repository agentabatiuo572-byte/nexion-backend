package ffdd.opsconsole.finance.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface B140TestWalletResetMapper {
    String SOURCE = "SELECT entry_no entryNo,customer_id customerId,wallet_id walletId,"
            + "original_operation_id originalOperationId,original_receipt_sha256 originalReceiptSha256,"
            + "server_uuid serverUuid,amount,recorded_db_utc recordedDbUtc FROM nx_b140_test_wallet_reset";
    String OPERATION_PREFIX = "B140:WALLET_RESET:";

    @Select("SELECT @@server_uuid serverUuid,DATABASE() databaseName")
    Map<String, Object> serverIdentity();

    @Select(SOURCE + " WHERE entry_no=#{entryNo} FOR SHARE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Map<String, Object> lockSource(@Param("entryNo") String entryNo);

    @Insert("""
            INSERT INTO nx_b140_test_wallet_reset
                (entry_no,customer_id,wallet_id,original_operation_id,original_receipt_sha256,server_uuid,amount)
            VALUES (#{entryNo},#{customerId},#{walletId},#{originalOperationId},#{receiptSha256},#{serverUuid},60)
            """)
    int insertSource(@Param("entryNo") String entryNo, @Param("customerId") long customerId,
            @Param("walletId") long walletId, @Param("originalOperationId") long originalOperationId,
            @Param("receiptSha256") String receiptSha256, @Param("serverUuid") String serverUuid);

    @Update("""
            UPDATE nx_user_wallet
               SET nex_available=0,lifetime_earned=0,version=version+1,updated_at=NOW()
             WHERE id=#{walletId} AND user_id=#{customerId} AND is_deleted=0 AND sandbox=0
               AND fund_revision=1 AND version=1 AND nex_available=60 AND lifetime_earned=60
               AND usdt_available=0 AND pending_withdraw=0 AND cregis_risk_held=0
               AND cumulative_deposit_usdt=0 AND earnings_usdt_debited=0 AND earnings_nex_debited=0
            """)
    int resetAvailableReward(@Param("walletId") long walletId, @Param("customerId") long customerId);
}
