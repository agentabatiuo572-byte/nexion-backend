package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SupportUnboundPolicyMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_support_unbound_policy (
              id TINYINT NOT NULL PRIMARY KEY,
              alert_minutes INT NOT NULL,
              fallback_minutes INT NOT NULL,
              default_group_id BIGINT NULL,
              version BIGINT NOT NULL DEFAULT 1,
              updated_by VARCHAR(64) NOT NULL,
              reason VARCHAR(200) NOT NULL,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='How long an unassigned inquiry may wait (D-3) and where unrouted customers go (M-18)'
            """)
    void ensureTable();

    @Insert("INSERT IGNORE INTO nx_support_unbound_policy(id,alert_minutes,fallback_minutes,default_group_id,version,updated_by,reason) VALUES(1,#{alert},#{fallback},NULL,1,'system','系统默认待分配策略')")
    int insertDefault(@Param("alert") int alert, @Param("fallback") int fallback);

    @Select("SELECT p.alert_minutes alertMinutes,p.fallback_minutes fallbackMinutes,p.default_group_id defaultGroupId,g.name defaultGroupName,p.version,p.updated_by updatedBy,p.reason,DATE_FORMAT(CONVERT_TZ(p.updated_at,'+08:00','+00:00'),'%Y-%m-%dT%H:%i:%s.%fZ') updatedAt FROM nx_support_unbound_policy p LEFT JOIN nx_support_group g ON g.id=p.default_group_id WHERE p.id=1")
    Map<String, Object> read();

    @Update("UPDATE nx_support_unbound_policy SET alert_minutes=#{alert},fallback_minutes=#{fallback},default_group_id=#{group},version=version+1,updated_by=#{actor},reason=#{reason} WHERE id=1 AND version=#{version}")
    int update(@Param("alert") int alert, @Param("fallback") int fallback, @Param("group") Long group,
            @Param("actor") String actor, @Param("reason") String reason, @Param("version") long version);
}
