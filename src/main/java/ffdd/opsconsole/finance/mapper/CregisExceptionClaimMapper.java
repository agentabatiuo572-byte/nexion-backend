package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisExceptionClaimMapper extends BaseMapper<Object> {
    @Insert("INSERT INTO nx_cregis_exception_claim(case_no,project_id,source_type,source_id,cid,txid,log_index,address,from_address,gross_amount,target_user_id,challenge_id,evidence_hash,maker_id,reason) VALUES(#{r.caseNo},#{r.projectId},#{r.sourceType},#{r.sourceId},#{r.cid},#{r.txid},#{r.logIndex},#{r.address},#{r.fromAddress},#{r.grossAmount},#{r.targetUserId},#{r.challengeId},#{r.evidenceHash},#{r.makerId},#{r.reason})")
    int insertCase(@Param("r") Map<String,Object> row);
    @Select("SELECT LAST_INSERT_ID()") Long lastId();
    @Select("SELECT * FROM nx_cregis_exception_claim WHERE project_id=#{project} AND id=#{id}")
    Map<String,Object> snapshot(@Param("project") long project,@Param("id") long id);
    @Select("SELECT * FROM nx_cregis_exception_claim WHERE project_id=#{project} AND id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lock(@Param("project") long project,@Param("id") long id);
    @Select("SELECT * FROM nx_cregis_exception_claim WHERE project_id=#{project} AND (#{before}=0 OR id<#{before}) ORDER BY id DESC LIMIT #{limit}")
    List<Map<String,Object>> page(@Param("project") long project,@Param("before") long before,@Param("limit") int limit);
    @Update("UPDATE nx_cregis_exception_claim SET state=#{state},checker_id=#{actor},decision_reason=#{reason},version=version+1,resolved_at=NOW() WHERE project_id=#{project} AND id=#{id} AND state='MAKER_DONE' AND version=#{version} AND maker_id<>#{actor}")
    int decide(@Param("project") long project,@Param("id") long id,@Param("version") long version,@Param("actor") long actor,@Param("state") String state,@Param("reason") String reason);
    @Select("SELECT COUNT(*) FROM nx_cregis_risk_alert WHERE project_id=#{project} AND resolved_at IS NULL AND severity IN ('P0','P1') AND NOT(kind='UNRESOLVED_EXPOSURE' AND alert_key='unresolved-exposure-500') AND NOT(kind='PROVIDER_ORPHAN' AND alert_key=CONCAT('orphan-cid-',#{cid}))")
    int otherCritical(@Param("project") long project,@Param("cid") long cid);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_delivery WHERE accepted=1 AND processed_at IS NULL AND NOT(cid<=>#{cid} AND txid<=>#{txid} AND address<=>#{address} AND gross_amount<=>#{amount} AND signature_valid<=>1 AND timestamp_valid<=>1 AND ip_valid<=>1)")
    int otherPending(@Param("cid") long cid,@Param("txid") String txid,@Param("address") String address,@Param("amount") java.math.BigDecimal amount);
    @Select("SELECT id,txid,log_index logIndex,address,raw_amount grossAmount,block_number blockNumber,status FROM nx_cregis_chain_observation WHERE project_id=#{project} AND id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockObservation(@Param("project") long project,@Param("id") long id);
}
