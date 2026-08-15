package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppNetworkRankMapper extends BaseMapper<Object> {
    @Select("""
            SELECT u.id AS userId, COALESCE(SUM(d.hashrate),0) AS hashrate
              FROM nx_user u
              LEFT JOIN nx_user_device d ON d.user_id=u.id AND d.is_deleted=0
               AND UPPER(d.ownership_status)='OWNED'
               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.deactivated_at IS NULL AND d.pending_deactivate=0
             WHERE u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
             GROUP BY u.id
             HAVING COALESCE(SUM(d.hashrate),0) > 0
             ORDER BY hashrate DESC, u.id ASC
             """)
    List<RankRow> rankedUsers(@Param("sandbox") Integer sandbox);

    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    record UserScope(Integer sandbox) { }
    record RankRow(Long userId, BigDecimal hashrate) { }
}
