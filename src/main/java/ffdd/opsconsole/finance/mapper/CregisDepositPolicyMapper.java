package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.cregis.CregisDepositPolicy;
import ffdd.opsconsole.finance.cregis.CregisNetwork;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.CregisDepositPolicyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CregisDepositPolicyMapper extends BaseMapper<CregisDepositPolicyEntity> {
    @Select("SELECT EXISTS(SELECT 1 FROM nx_cregis_deposit_policy_snapshot s JOIN nx_purchase_payment_policy p ON p.id=1 WHERE s.project_id=#{projectId} AND s.chain_id=#{network.policyChainId} AND s.token_id=#{network.policyTokenId} AND s.txid=#{txid} AND s.created_at>=p.created_at)")
    boolean integerPrincipalRequired(@Param("network") CregisNetwork network,@Param("projectId") long projectId,@Param("txid") String txid);
    @Select("SELECT id FROM nx_cregis_deposit_policy_lock WHERE id=1 FOR UPDATE")
    Integer lockPolicy();
    @Select("SELECT version,min_deposit_usdt AS minDepositUsdt,fee_usdt AS feeUsdt,confirmations FROM nx_cregis_deposit_policy ORDER BY version DESC LIMIT 1")
    CregisDepositPolicy current();
    @Select("SELECT version,min_deposit_usdt AS minDepositUsdt,fee_usdt AS feeUsdt,confirmations FROM nx_cregis_deposit_policy ORDER BY version DESC LIMIT 1 FOR UPDATE")
    CregisDepositPolicy lockCurrent();
    @Select("SELECT p.version,p.min_deposit_usdt AS minDepositUsdt,p.fee_usdt AS feeUsdt,s.required_confirmations AS confirmations FROM nx_cregis_deposit_policy_snapshot s JOIN nx_cregis_deposit_policy p ON p.version=s.policy_version WHERE s.project_id=#{projectId} AND s.chain_id=#{network.policyChainId} AND s.token_id=#{network.policyTokenId} AND s.txid=#{txid}")
    CregisDepositPolicy snapshot(@Param("network") CregisNetwork network,@Param("projectId") long projectId,@Param("txid") String txid);
    @Select("SELECT p.version,p.min_deposit_usdt AS minDepositUsdt,p.fee_usdt AS feeUsdt,s.required_confirmations AS confirmations FROM nx_cregis_deposit_policy_snapshot s JOIN nx_cregis_deposit_policy p ON p.version=s.policy_version WHERE s.project_id=#{projectId} AND s.chain_id=#{network.policyChainId} AND s.token_id=#{network.policyTokenId} AND s.txid=#{txid} FOR UPDATE")
    CregisDepositPolicy lockSnapshot(@Param("network") CregisNetwork network,@Param("projectId") long projectId,@Param("txid") String txid);
    @Insert("INSERT IGNORE INTO nx_cregis_deposit_policy_snapshot(project_id,chain_id,token_id,txid,policy_version,required_confirmations) VALUES(#{projectId},#{network.policyChainId},#{network.policyTokenId},#{txid},#{version},#{confirmations})")
    int insertSnapshot(@Param("network") CregisNetwork network,@Param("projectId") long projectId,@Param("txid") String txid,@Param("version") long version,@Param("confirmations") int confirmations);
    @Insert("INSERT INTO nx_cregis_deposit_policy(version,min_deposit_usdt,fee_usdt,confirmations,operation_id,reason) VALUES(#{policy.version},#{policy.minDepositUsdt},#{policy.feeUsdt},#{policy.confirmations},#{operationId},#{reason})")
    int insertPolicy(@Param("policy") CregisDepositPolicy policy,@Param("operationId") String operationId,@Param("reason") String reason);
    default boolean integerPrincipalRequired(long projectId,String txid) { return integerPrincipalRequired(CregisNetwork.BEP20 ,projectId,txid); }
    default CregisDepositPolicy snapshot(long projectId,String txid) { return snapshot(CregisNetwork.BEP20 ,projectId,txid); }
    default CregisDepositPolicy lockSnapshot(long projectId,String txid) { return lockSnapshot(CregisNetwork.BEP20 ,projectId,txid); }
    default int insertSnapshot(long projectId,String txid,long version,int confirmations) { return insertSnapshot(CregisNetwork.BEP20 ,projectId,txid,version,confirmations); }
}
