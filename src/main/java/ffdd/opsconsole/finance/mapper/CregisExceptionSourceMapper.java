package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.cregis.CregisExceptionSourceService.Source;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisExceptionSourceMapper extends BaseMapper<Object> {
    @Select("SELECT EXISTS(SELECT 1 FROM nx_cregis_deposit_address WHERE project_id=#{s.projectId} AND LOWER(address)=LOWER(#{s.address}) AND state='DIRECT_PURCHASE')")
    boolean directPurchaseAddress(@Param("s") Source source);
    @Insert("INSERT IGNORE INTO nx_cregis_exception_source(project_id,txid,log_index,cid,address,gross_amount) VALUES(#{s.projectId},#{s.txid},#{s.logIndex},#{s.cid},#{s.address},#{s.grossAmount})")
    int ensure(@Param("s") Source source);
    @Select("SELECT cid,address,gross_amount grossAmount,state,operation_kind operationKind,operation_ref operationRef,version FROM nx_cregis_exception_source WHERE project_id=#{s.projectId} AND txid=#{s.txid} AND log_index=#{s.logIndex} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lock(@Param("s") Source source);
    @Update("UPDATE nx_cregis_exception_source SET state=#{state},operation_kind=#{kind},operation_ref=#{ref},version=version+1,updated_at=NOW() WHERE project_id=#{s.projectId} AND txid=#{s.txid} AND log_index=#{s.logIndex} AND version=#{version}")
    int transition(@Param("s") Source source,@Param("state") String state,@Param("kind") String kind,@Param("ref") String ref,@Param("version") long version);
    @Select("SELECT id,cid,txid,log_index logIndex,address,gross_amount grossAmount,status,ledger_id ledgerId FROM nx_cregis_deposit_event WHERE project_id=#{s.projectId} AND ((txid=#{s.txid} AND log_index=#{s.logIndex}) OR cid=#{s.cid}) FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> sourceEvent(@Param("s") Source source);
    @Update("UPDATE nx_cregis_exception_source SET state='CREDITED',operation_kind='REORG',operation_ref=#{ref},alias_of_log_index=#{origin},version=version+1,updated_at=NOW() WHERE project_id=#{s.projectId} AND txid=#{s.txid} AND log_index=#{s.logIndex} AND version=#{version} AND state='AVAILABLE'")
    int consumeAlias(@Param("s") Source source,@Param("origin") int originalIndex,@Param("ref") String reference,@Param("version") long version);
    @Update("UPDATE nx_cregis_deposit_event SET status='REFUNDED' WHERE project_id=#{s.projectId} AND txid=#{s.txid} AND log_index=#{s.logIndex} AND cid=#{s.cid} AND ledger_id IS NULL AND status IN ('DUST_HOLD','REVIEW_HOLD')")
    int refundEvent(@Param("s") Source source);
    @Update("UPDATE nx_cregis_chain_observation SET status=#{state},checked_at=NOW(),last_error=NULL WHERE project_id=#{s.projectId} AND txid=#{s.txid} AND log_index=#{s.logIndex} AND address=#{s.address} AND CAST(raw_amount AS DECIMAL(18,6))=#{s.grossAmount}")
    int finishObservation(@Param("s") Source source,@Param("state") String state);
    @Update("UPDATE nx_cregis_deposit_delivery SET processed_at=NOW(),reason=#{state} WHERE accepted=1 AND cid=#{s.cid} AND txid=#{s.txid} AND address=#{s.address} AND gross_amount=#{s.grossAmount} AND processed_at IS NULL")
    int finishDeliveries(@Param("s") Source source,@Param("state") String state);
    @Update("UPDATE nx_cregis_risk_alert SET resolved_at=NOW() WHERE project_id=#{s.projectId} AND alert_key=CONCAT('orphan-cid-',#{s.cid}) AND kind='PROVIDER_ORPHAN' AND resolved_at IS NULL")
    int resolveOwnAlert(@Param("s") Source source);
    @Update("UPDATE nx_cregis_deposit_address a SET state='RETIRED',updated_at=NOW() WHERE a.project_id=#{s.projectId} AND a.chain_id='2510' AND a.address=#{s.address} AND a.user_id IS NULL AND a.state IN ('UNATTRIBUTED_HOLD','RETIRED_HOLD') AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o WHERE o.project_id=a.project_id AND o.address=a.address AND o.status NOT IN ('MATCHED','REFUNDED'))")
    int retireResolvedAddress(@Param("s") Source source);
}
