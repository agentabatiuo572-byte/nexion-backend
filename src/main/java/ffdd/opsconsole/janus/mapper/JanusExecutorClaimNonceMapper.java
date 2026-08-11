package ffdd.opsconsole.janus.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface JanusExecutorClaimNonceMapper {
    @Select("SELECT claim_hash FROM nx_janus_executor_claim_nonce WHERE executor_id=#{executorId} AND claim_nonce=#{nonce}")
    String findClaimHash(@Param("executorId") String executorId, @Param("nonce") String nonce);

    @Insert("""
            INSERT IGNORE INTO nx_janus_executor_claim_nonce(
              executor_id,claim_nonce,claim_hash,device_id,proof_timestamp,created_at)
            VALUES(#{executorId},#{nonce},#{claimHash},#{deviceId},FROM_UNIXTIME(#{proofTimestamp}/1000),NOW(3))
            """)
    int claim(@Param("executorId") String executorId, @Param("nonce") String nonce,
              @Param("claimHash") String claimHash, @Param("deviceId") String deviceId,
              @Param("proofTimestamp") long proofTimestamp);

    @Delete("DELETE FROM nx_janus_executor_claim_nonce WHERE created_at<FROM_UNIXTIME(#{cutoff}/1000) LIMIT 1000")
    int deleteExpired(@Param("cutoff") long cutoff);
}
