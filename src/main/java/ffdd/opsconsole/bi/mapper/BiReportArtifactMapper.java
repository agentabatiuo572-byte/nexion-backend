package ffdd.opsconsole.bi.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface BiReportArtifactMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_bi_report_artifact (
              report_id VARCHAR(64) NOT NULL,
              object_key VARCHAR(255) NOT NULL,
              content_type VARCHAR(128) NOT NULL,
              size_bytes BIGINT NOT NULL,
              content_sha256 CHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (report_id),
              UNIQUE KEY uk_bi_report_artifact_object (object_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createArtifactTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_bi_report_download_grant (
              id BIGINT NOT NULL AUTO_INCREMENT,
              report_id VARCHAR(64) NOT NULL,
              token_hash CHAR(64) NOT NULL,
              issued_to_admin_id BIGINT NOT NULL,
              expires_at DATETIME NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY uk_bi_report_download_token (token_hash),
              KEY idx_bi_report_download_lookup
                (report_id, issued_to_admin_id, expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGrantTable();

    @Insert("""
            INSERT INTO nx_bi_report_artifact
              (report_id, object_key, content_type, size_bytes, content_sha256)
            VALUES
              (#{reportId}, #{objectKey}, #{contentType}, #{sizeBytes}, #{sha256})
            ON DUPLICATE KEY UPDATE
              object_key = VALUES(object_key),
              content_type = VALUES(content_type),
              size_bytes = VALUES(size_bytes),
              content_sha256 = VALUES(content_sha256),
              updated_at = NOW()
            """)
    int upsertArtifact(
            @Param("reportId") String reportId,
            @Param("objectKey") String objectKey,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256);

    @Select("""
            SELECT report_id AS reportId,
                   object_key AS objectKey,
                   content_type AS contentType,
                   size_bytes AS sizeBytes,
                   content_sha256 AS sha256
              FROM nx_bi_report_artifact
             WHERE report_id = #{reportId}
             LIMIT 1
            """)
    ArtifactRow findArtifact(@Param("reportId") String reportId);

    @Insert("""
            INSERT INTO nx_bi_report_download_grant
              (report_id, token_hash, issued_to_admin_id, expires_at)
            VALUES
              (#{reportId}, #{tokenHash}, #{adminId}, #{expiresAt})
            """)
    int insertGrant(
            @Param("reportId") String reportId,
            @Param("tokenHash") String tokenHash,
            @Param("adminId") long adminId,
            @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT COUNT(*)
              FROM nx_bi_report_download_grant
             WHERE report_id = #{reportId}
               AND token_hash = #{tokenHash}
               AND issued_to_admin_id = #{adminId}
               AND expires_at > #{now}
            """)
    int countValidGrant(
            @Param("reportId") String reportId,
            @Param("tokenHash") String tokenHash,
            @Param("adminId") long adminId,
            @Param("now") LocalDateTime now);

    @Update("DELETE FROM nx_bi_report_download_grant WHERE expires_at <= #{now}")
    int deleteExpiredGrants(@Param("now") LocalDateTime now);

    record ArtifactRow(
            String reportId,
            String objectKey,
            String contentType,
            long sizeBytes,
            String sha256) {
    }
}
