package ffdd.opsconsole.content.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SupportPersonalPhraseMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_support_personal_phrase (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              admin_id BIGINT NOT NULL,
              content VARCHAR(500) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_support_personal_phrase_admin (admin_id, is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Advisor-private quick replies'
            """)
    void ensureTable();

    @Select("SELECT id,content,DATE_FORMAT(CONVERT_TZ(updated_at,'+08:00','+00:00'),'%Y-%m-%dT%H:%i:%s.%fZ') updatedAt FROM nx_support_personal_phrase WHERE admin_id=#{admin} AND is_deleted=0 ORDER BY id DESC")
    List<Map<String, Object>> list(@Param("admin") Long admin);

    @Select("SELECT COUNT(*) FROM nx_support_personal_phrase WHERE admin_id=#{admin} AND is_deleted=0")
    int count(@Param("admin") Long admin);

    @Insert("INSERT INTO nx_support_personal_phrase(admin_id,content) VALUES(#{admin},#{content})")
    int insert(@Param("admin") Long admin, @Param("content") String content);

    @Update("UPDATE nx_support_personal_phrase SET is_deleted=1 WHERE id=#{id} AND admin_id=#{admin} AND is_deleted=0")
    int delete(@Param("admin") Long admin, @Param("id") Long id);
}
