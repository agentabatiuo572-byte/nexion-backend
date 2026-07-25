package ffdd.opsconsole.janus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetCreateCommand;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.infrastructure.JanusDeviceRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface JanusRemoteTargetMapper extends BaseMapper<JanusDeviceRecord> {
    String COLUMNS = """
            t.catalog_version AS catalogVersion,
            t.remote_target_key AS remoteTargetKey,
            t.remote_target_version AS remoteTargetVersion,
            t.status,
            t.label,
            t.target_url AS url,
            t.target_origin AS origin,
            t.source,
            t.owner_id AS ownerId,
            CAST(UNIX_TIMESTAMP(t.created_at) * 1000 AS UNSIGNED) AS createdAt,
            CAST(UNIX_TIMESTAMP(t.updated_at) * 1000 AS UNSIGNED) AS updatedAt,
            t.updated_by AS updatedBy,
            t.change_reason AS changeReason,
            t.impact_note AS impact,
            t.lock_version AS lockVersion,
            (SELECT COUNT(1) FROM nx_janus_strategy s
              WHERE JSON_UNQUOTE(JSON_EXTRACT(s.action_json,'$.remoteUrlKey'))=t.remote_target_key
                AND CAST(JSON_UNQUOTE(JSON_EXTRACT(s.action_json,'$.remoteTargetVersion')) AS UNSIGNED)=t.remote_target_version
                AND CAST(JSON_UNQUOTE(JSON_EXTRACT(s.action_json,'$.remoteTargetCatalogVersion')) AS UNSIGNED)=t.catalog_version
                AND s.status IN ('draft','active','paused')) AS strategyCount,
            (SELECT COUNT(1) FROM nx_janus_device d
              WHERE d.remote_url_key=t.remote_target_key
                AND d.remote_target_version=t.remote_target_version
                AND d.remote_target_catalog_version=t.catalog_version
                AND d.command_state IN ('PENDING','PUBLISHED')) AS pendingCommandCount,
            0 AS cancelledCommandCount
            """;

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_remote_target (
              catalog_version BIGINT NOT NULL AUTO_INCREMENT,
              remote_target_key VARCHAR(64) NOT NULL,
              remote_target_version INT NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
              label VARCHAR(96) NOT NULL,
              target_url VARCHAR(1024) NOT NULL,
              target_origin VARCHAR(320) NOT NULL,
              source VARCHAR(16) NOT NULL DEFAULT 'ADMIN',
              owner_id VARCHAR(96) NOT NULL,
              change_reason VARCHAR(500) NOT NULL,
              impact_note VARCHAR(500) NOT NULL,
              updated_by VARCHAR(96) NOT NULL,
              lock_version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (catalog_version),
              UNIQUE KEY uk_janus_remote_target_version(remote_target_key,remote_target_version),
              KEY idx_janus_remote_target_status(remote_target_key,status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTable();

    @Select("SELECT " + COLUMNS + " FROM nx_janus_remote_target t ORDER BY t.catalog_version DESC")
    List<JanusRemoteTargetView> list();

    @Select("SELECT " + COLUMNS + """
            FROM nx_janus_remote_target t
            WHERE t.remote_target_key=#{key} AND t.remote_target_version=#{version}
            """)
    JanusRemoteTargetView find(@Param("key") String key, @Param("version") int version);

    @Insert("""
            INSERT INTO nx_janus_remote_target(
              remote_target_key,remote_target_version,status,label,target_url,target_origin,source,
              owner_id,change_reason,impact_note,updated_by)
            SELECT #{c.remoteTargetKey},#{c.expectedLatestVersion}+1,'ACTIVE',#{c.label},#{c.url},#{c.origin},
              'ADMIN',#{c.ownerId},#{c.reason},#{c.impact},#{c.operator}
            WHERE (SELECT COALESCE(MAX(x.remote_target_version),0)
                   FROM nx_janus_remote_target x WHERE x.remote_target_key=#{c.remoteTargetKey})
                  =#{c.expectedLatestVersion}
            """)
    int insertVersion(@Param("c") JanusRemoteTargetCreateCommand command);

    @Update("""
            UPDATE nx_janus_remote_target
            SET status='DISABLED',updated_by=#{operator},lock_version=lock_version+1
            WHERE remote_target_key=#{key} AND remote_target_version=#{version}
              AND catalog_version=#{catalogVersion}
              AND status='ACTIVE' AND lock_version=#{expectedVersion}
            """)
    int disableVersion(@Param("key") String key, @Param("version") int version,
                       @Param("catalogVersion") long catalogVersion,
                       @Param("expectedVersion") long expectedVersion, @Param("operator") String operator);

    @Update("""
            UPDATE nx_janus_command c
            SET c.state='CANCELLED',
                c.payload_json=JSON_SET(COALESCE(c.payload_json,JSON_OBJECT()),
                  '$.cancellationReason','TARGET_DISABLED')
            WHERE c.command_type='DEVICE_STATUS' AND c.state IN ('PENDING','PUBLISHED')
              AND c.remote_target_key=#{key}
              AND c.remote_target_version=#{version}
              AND c.remote_target_catalog_version=#{catalogVersion}
            """)
    int cancelCommandRecords(@Param("key") String key, @Param("version") int version,
                             @Param("catalogVersion") long catalogVersion);

    @Update("""
            UPDATE nx_janus_device
            SET acked_revision=GREATEST(acked_revision,desired_revision),
                desired_status=NULL,command_state='CANCELLED',remote_url_key=NULL,
                remote_target_version=NULL,remote_target_catalog_version=NULL,
                manual_override_json=JSON_SET(COALESCE(manual_override_json,JSON_OBJECT()),
                  '$.cancellationReason','TARGET_DISABLED'),
                last_operator_id='janus-target-guard',
                last_operation_reason='批准目标停用，未领取命令已取消',
                lock_version=lock_version+1
            WHERE remote_url_key=#{key}
              AND remote_target_version=#{version}
              AND remote_target_catalog_version=#{catalogVersion}
              AND command_state IN ('PENDING','PUBLISHED')
            """)
    int cancelUnclaimedDevices(@Param("key") String key, @Param("version") int version,
                               @Param("catalogVersion") long catalogVersion);
}
