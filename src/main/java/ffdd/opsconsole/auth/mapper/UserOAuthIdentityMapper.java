package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserOAuthIdentityMapper extends BaseMapper<UserOAuthIdentityEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_oauth_identity (
              id BIGINT NOT NULL AUTO_INCREMENT,
              provider VARCHAR(16) NOT NULL,
              external_subject VARCHAR(191) NOT NULL,
              user_id BIGINT NOT NULL,
              source_environment VARCHAR(16) NOT NULL,
              display_name VARCHAR(128) NULL,
              created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
              updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
              is_deleted TINYINT NOT NULL DEFAULT 0,
              PRIMARY KEY (id),
              UNIQUE KEY uk_oauth_identity (provider, external_subject, source_environment),
              KEY idx_oauth_identity_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTable();

    @Select("""
            SELECT id,provider,external_subject,user_id,source_environment,display_name,
                   created_at,updated_at,is_deleted
              FROM nx_user_oauth_identity
             WHERE provider=#{provider} AND external_subject=#{externalSubject}
               AND source_environment=#{sourceEnvironment} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    UserOAuthIdentityEntity findForUpdate(
            @Param("provider") String provider,
            @Param("externalSubject") String externalSubject,
            @Param("sourceEnvironment") String sourceEnvironment);

    @Insert("""
            INSERT INTO nx_user_oauth_identity(provider,external_subject,user_id,source_environment,display_name,
                                               created_at,updated_at,is_deleted)
            VALUES(#{provider},#{externalSubject},#{userId},#{sourceEnvironment},#{displayName},NOW(6),NOW(6),0)
            """)
    int insertIdentity(UserOAuthIdentityEntity identity);
}
