package ffdd.opsconsole.finance.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportFundJournalMapper {
    String WALLET = "SELECT id,user_id userId,usdt_available usdtAvailable,nex_available nexAvailable,"
        + "pending_withdraw pendingWithdraw,cregis_risk_held cregisRiskHeld,version,sandbox,"
        + "is_deleted isDeleted,created_at createdAt,fund_incarnation incarnation,fund_revision revision "
        + "FROM nx_user_wallet WHERE user_id=#{customerId} ORDER BY id FOR UPDATE";
    String JOURNAL = "SELECT id,wallet_incarnation incarnation,revision,observation,"
        + "old_id oldId,new_id newId,old_user_id oldUserId,new_user_id newUserId,"
        + "old_usdt_available oldUsdtAvailable,new_usdt_available newUsdtAvailable,"
        + "old_nex_available oldNexAvailable,new_nex_available newNexAvailable,"
        + "old_pending_withdraw oldPendingWithdraw,new_pending_withdraw newPendingWithdraw,"
        + "old_cregis_risk_held oldCregisRiskHeld,new_cregis_risk_held newCregisRiskHeld,"
        + "old_version oldVersion,new_version newVersion,old_sandbox oldSandbox,new_sandbox newSandbox,"
        + "old_is_deleted oldIsDeleted,new_is_deleted newIsDeleted,"
        + "old_created_at oldCreatedAt,new_created_at newCreatedAt,"
        + "recorded_db_utc recordedDbUtc,protocol_sha256 protocolSha256 "
        + "FROM nx_support_wallet_journal WHERE wallet_incarnation=#{incarnation} "
        + "AND revision>#{beforeRevision} AND revision<=#{afterRevision} ORDER BY revision FOR SHARE";
    String OPERATION = "SELECT id,op_key operationKey,business_type businessType,phase,source_table sourceTable,"
        + "source_key sourceKey,source_environment sourceEnvironment,ordered_events orderedEventsJson,"
        + "source_snapshot sourceSnapshotJson,source_proof sourceProofJson,receipt_sha256 receiptSha256,"
        + "protocol_version protocolVersion,recorded_db_utc recordedDbUtc "
        + "FROM nx_support_fund_operation WHERE op_key=#{operationKey} FOR SHARE";
    String LEGS = "SELECT operation_id operationId,wallet_incarnation incarnation,revision "
        + "FROM nx_support_fund_operation_leg WHERE operation_id=#{operationId} "
        + "ORDER BY wallet_incarnation,revision FOR SHARE";
    String PARTIES = "SELECT operation_id operationId,customer_id customerId "
        + "FROM nx_support_fund_operation_party WHERE operation_id=#{operationId} ORDER BY customer_id FOR SHARE";

    @Select(WALLET)
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> lockWallet(@Param("customerId") long customerId);

    @Select(JOURNAL)
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> journal(@Param("incarnation") String incarnation,
        @Param("beforeRevision") long beforeRevision, @Param("afterRevision") long afterRevision);

    @Select("SELECT wallet_incarnation incarnation,revision FROM nx_support_wallet_journal "
        + "WHERE old_id=#{walletId} OR new_id=#{walletId} OR old_user_id=#{customerId} OR new_user_id=#{customerId} "
        + "ORDER BY id FOR SHARE")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> birthCandidates(@Param("walletId") long walletId, @Param("customerId") long customerId);

    @Select(OPERATION)
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> operation(@Param("operationKey") String operationKey);

    @Select(LEGS)
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> legs(@Param("operationId") long operationId);

    @Select(PARTIES)
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> parties(@Param("operationId") long operationId);

    @Select("SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=#{customerId} ORDER BY operation_id FOR SHARE")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    List<Long> customerOperations(@Param("customerId") long customerId);

    @Insert("INSERT INTO nx_support_fund_operation_party (operation_id,customer_id) VALUES (#{operationId},#{customerId})")
    int insertParty(@Param("operationId") long operationId, @Param("customerId") long customerId);

    @Insert("INSERT INTO nx_support_fund_operation (op_key,business_type,phase,source_table,source_key,"
        + "source_environment,ordered_events,source_snapshot,source_proof,receipt_sha256,protocol_version) "
        + "VALUES (#{receipt.operationKey},#{receipt.businessType},#{receipt.phase},#{receipt.sourceTable},"
        + "#{receipt.sourceKey},#{receipt.sourceEnvironment},#{receipt.orderedEventsJson},"
        + "#{receipt.sourceSnapshotJson},#{receipt.sourceProofJson},#{receipt.receiptSha256},#{receipt.protocolVersion})")
    int insertOperation(@Param("receipt") Map<String,Object> receipt);

    @Insert("INSERT INTO nx_support_fund_operation_leg (operation_id,wallet_incarnation,revision) "
        + "VALUES (#{operationId},#{incarnation},#{revision})")
    int insertLeg(@Param("operationId") long operationId, @Param("incarnation") String incarnation,
        @Param("revision") long revision);
}
