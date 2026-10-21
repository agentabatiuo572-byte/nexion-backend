package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.OAuthProviderFlow;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OAuthProviderFlowMapper extends BaseMapper<OAuthProviderFlow> {
    @Update("""
        CREATE TABLE IF NOT EXISTS nx_oauth_provider_flow (
          id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
          proof_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
          receipt_hash VARCHAR(64) NULL,
          state VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
          provider VARCHAR(16) NOT NULL, nonce VARCHAR(64) NOT NULL, verifier VARCHAR(64) NOT NULL,
          browser_hash VARCHAR(64) NULL, link_user_id BIGINT NULL, sponsor_code VARCHAR(32) NULL,
          status VARCHAR(16) NOT NULL, subject VARCHAR(191) NULL, display_name VARCHAR(128) NULL,
          client_hash VARCHAR(64) NOT NULL, expires_at DATETIME(6) NOT NULL,
          UNIQUE KEY uk_oauth_flow_state(state), KEY idx_oauth_flow_expiry(expires_at),
          KEY idx_oauth_flow_client(client_hash,expires_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createTable();

    @Insert("""
        INSERT INTO nx_oauth_provider_flow
        (id,proof_hash,state,provider,nonce,verifier,link_user_id,sponsor_code,status,client_hash,expires_at)
        VALUES (#{id},#{proofHash},#{state},#{provider},#{nonce},#{verifier},#{linkUserId},#{sponsorCode},'PENDING',
                #{clientHash},DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE))
        """)
    void insertFlow(OAuthProviderFlow flow);

    @Select("SELECT * FROM nx_oauth_provider_flow WHERE id=#{id}")
    OAuthProviderFlow find(String id);

    @Select("SELECT * FROM nx_oauth_provider_flow WHERE id=#{id} FOR UPDATE")
    OAuthProviderFlow lock(String id);

    @Select("SELECT * FROM nx_oauth_provider_flow WHERE state=#{state} AND expires_at>UTC_TIMESTAMP(6)")
    OAuthProviderFlow byState(String state);

    @Update("""
        UPDATE nx_oauth_provider_flow SET browser_hash=#{browserHash},status='AUTHORIZING'
        WHERE id=#{id} AND status='PENDING' AND expires_at>UTC_TIMESTAMP(6)
        """)
    int openBrowser(@Param("id") String id, @Param("browserHash") String browserHash);

    @Update("""
        UPDATE nx_oauth_provider_flow SET status='EXCHANGING'
        WHERE id=#{id} AND status='AUTHORIZING' AND expires_at>UTC_TIMESTAMP(6)
        """)
    int claimCallback(String id);

    @Update("""
        UPDATE nx_oauth_provider_flow SET status=#{status},subject=#{subject},display_name=#{displayName},receipt_hash=#{receiptHash},
          verifier='',nonce=''
        WHERE id=#{id} AND status='EXCHANGING' AND expires_at>UTC_TIMESTAMP(6)
        """)
    int finishCallback(@Param("id") String id, @Param("status") String status,
                       @Param("subject") String subject, @Param("displayName") String displayName,
                       @Param("receiptHash") String receiptHash);

    @Update("UPDATE nx_oauth_provider_flow SET status='CONSUMED' WHERE id=#{id} AND status='VERIFIED'")
    int consume(String id);

    @Select("SELECT COUNT(*) FROM nx_oauth_provider_flow WHERE client_hash=#{clientHash} AND expires_at>UTC_TIMESTAMP(6)")
    int countForClient(String clientHash);

    @Delete("DELETE FROM nx_oauth_provider_flow WHERE expires_at<DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) LIMIT 1000")
    void cleanup();
}
