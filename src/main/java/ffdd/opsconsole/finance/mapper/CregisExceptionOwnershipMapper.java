package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisExceptionOwnershipMapper extends BaseMapper<Object> {
    @Select("SELECT id,user_id userId,cid,txid,log_index logIndex,address,gross_amount grossAmount,block_number blockNumber,block_hash blockHash,status FROM nx_cregis_deposit_event WHERE project_id=#{project} AND id=#{id}")
    Map<String,Object> event(@Param("project") long project,@Param("id") long id);
    @Select("SELECT id,txid,log_index logIndex,address,raw_amount grossAmount,block_number blockNumber,status FROM nx_cregis_chain_observation WHERE project_id=#{project} AND id=#{id}")
    Map<String,Object> observation(@Param("project") long project,@Param("id") long id);
    @Select("SELECT id,user_id userId,cid,status FROM nx_cregis_deposit_event WHERE project_id=#{project} AND txid=#{txid} AND log_index=#{logIndex}")
    Map<String,Object> eventByLog(@Param("project") long project,@Param("txid") String txid,@Param("logIndex") int index);
    @Select("SELECT id,user_id userId,cid,status FROM nx_cregis_deposit_event WHERE project_id=#{project} AND cid=#{cid}")
    Map<String,Object> eventByCid(@Param("project") long project,@Param("cid") long cid);
    @Select("SELECT id,user_id userId,state,creation_block creationBlock,allocation_block allocationBlock,allocation_hash allocationHash FROM nx_cregis_deposit_address WHERE project_id=#{project} AND chain_id='2510' AND address=#{address}")
    Map<String,Object> address(@Param("project") long project,@Param("address") String address);
    @Select("SELECT COUNT(*)=1 FROM nx_user WHERE id=#{id} AND status='ACTIVE' AND sandbox=0 AND is_deleted=0")
    boolean activeTarget(@Param("id") long id);
    @Select("SELECT * FROM nx_cregis_ownership_challenge WHERE challenge_id=#{id}")
    Map<String,Object> challenge(@Param("id") String id);
    @Select("SELECT * FROM nx_cregis_ownership_challenge WHERE challenge_id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockChallenge(@Param("id") String id);
    @Insert("INSERT INTO nx_cregis_ownership_challenge(challenge_id,project_id,source_type,source_id,action,target_user_id,message,evidence_hash,expires_at,created_by) VALUES(#{id},#{project},#{type},#{sourceId},#{action},#{target},#{message},#{hash},#{expires},#{actor})")
    int insert(@Param("id") String id,@Param("project") long project,@Param("type") String type,@Param("sourceId") long sourceId,
               @Param("action") String action,@Param("target") Long target,@Param("message") String message,
               @Param("hash") String hash,@Param("expires") LocalDateTime expires,@Param("actor") long actor);
    @Update("UPDATE nx_cregis_ownership_challenge SET consumed_ref=#{ref},consumed_signature=#{signature},signer=#{signer},consumed_at=UTC_TIMESTAMP() WHERE challenge_id=#{id} AND consumed_ref IS NULL AND consumed_signature IS NULL AND signer IS NULL AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP()")
    int consume(@Param("id") String id,@Param("ref") String ref,@Param("signature") String signature,@Param("signer") String signer);
}
