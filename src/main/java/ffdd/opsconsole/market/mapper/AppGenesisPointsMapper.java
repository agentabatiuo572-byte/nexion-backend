package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only Genesis points projection. The account environment is a hard SQL boundary. */
@Mapper
public interface AppGenesisPointsMapper extends BaseMapper<Object> {
    @Select("""
            SELECT u.id AS userId,
                   COALESCE(NULLIF(TRIM(u.nickname),''), CONCAT('User ', u.id)) AS nickname,
                   COUNT(h.id) AS holdings
              FROM nx_genesis_holding h
              JOIN nx_user u ON u.id=h.user_id
                             AND u.status='ACTIVE'
                             AND u.is_deleted=0
                             AND u.sandbox=#{sandbox}
             WHERE h.is_deleted=0
               AND UPPER(h.status) IN ('ACTIVE','LISTED')
             GROUP BY u.id, u.nickname
             ORDER BY COUNT(h.id) DESC, u.id ASC
             LIMIT 100
            """)
    List<PointsRow> leaderboard(@Param("sandbox") Integer sandbox);

    @Select("""
            SELECT u.id AS userId,
                   COALESCE(NULLIF(TRIM(u.nickname),''), CONCAT('User ', u.id)) AS nickname,
                   COUNT(h.id) AS holdings
              FROM nx_genesis_holding h
              JOIN nx_user u ON u.id=h.user_id
             WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
               AND h.is_deleted=0 AND UPPER(h.status) IN ('ACTIVE','LISTED')
             GROUP BY u.id, u.nickname
            """)
    PointsRow currentUser(@Param("userId") Long userId, @Param("sandbox") Integer sandbox);

    @Select("""
            SELECT COUNT(*) + 1
              FROM (
                    SELECT u.id, COUNT(h.id) AS holdings
                      FROM nx_genesis_holding h
                      JOIN nx_user u ON u.id=h.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
                     WHERE h.is_deleted=0 AND UPPER(h.status) IN ('ACTIVE','LISTED')
                     GROUP BY u.id
                   ) ranked
              JOIN (
                    SELECT COUNT(h.id) AS holdings
                      FROM nx_genesis_holding h
                      JOIN nx_user u ON u.id=h.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
                     WHERE u.id=#{userId} AND h.is_deleted=0 AND UPPER(h.status) IN ('ACTIVE','LISTED')
                     GROUP BY u.id
                   ) mine
                ON ranked.holdings > mine.holdings
                OR (ranked.holdings = mine.holdings AND ranked.id < #{userId})
            """)
    Integer currentRank(@Param("userId") Long userId, @Param("sandbox") Integer sandbox);

    @Select("SELECT sandbox,v_rank AS vRank FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    record UserScope(Integer sandbox, String vRank) { }

    record PointsRow(Long userId, String nickname, Long holdings) { }
}
