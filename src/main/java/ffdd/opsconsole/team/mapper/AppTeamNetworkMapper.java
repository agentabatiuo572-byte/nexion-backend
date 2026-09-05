package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppTeamNetworkMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
             WHERE u.id=#{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code,'')),'+','')=REPLACE(#{countryCode},'+','')
               AND u.phone=#{phone} AND u.sandbox=1
               AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId,
                             @Param("countryCode") String countryCode,
                             @Param("phone") String phone);

    @Select("""
            WITH RECURSIVE network AS (
              SELECT child.id member_user_id,child.sponsor_user_id,1 level,child.id root_user_id,
                     CAST(CONCAT(',',#{userId},',',child.id,',') AS CHAR(2048)) path
                FROM nx_user owner JOIN nx_user child
                  ON child.sponsor_user_id=owner.id AND child.sandbox=owner.sandbox
               WHERE owner.id=#{userId} AND owner.status='ACTIVE' AND owner.is_deleted=0
                 AND child.status='ACTIVE' AND child.is_deleted=0 AND child.id<>owner.id
              UNION ALL
              SELECT child.id,child.sponsor_user_id,n.level+1,n.root_user_id,
                     CONCAT(n.path,child.id,',')
                FROM network n
                JOIN nx_user parent ON parent.id=n.member_user_id AND parent.is_deleted=0
                JOIN nx_user child ON child.sponsor_user_id=parent.id AND child.sandbox=parent.sandbox
               WHERE n.level<7 AND child.status='ACTIVE' AND child.is_deleted=0
                 AND child.id<>child.sponsor_user_id
                 AND LOCATE(CONCAT(',',child.id,','),n.path)=0
            )
            SELECT u.id memberUserId,u.nickname,u.avatar_url avatarUrl,u.v_rank vRank,n.level,
                   CASE UPPER(a.leg)
                     WHEN 'A' THEN 'A' WHEN 'LEFT' THEN 'A'
                     WHEN 'B' THEN 'B' WHEN 'RIGHT' THEN 'B'
                     ELSE NULL
                   END leg,
                   n.sponsor_user_id sponsorUserId,u.created_at joinedAt,
                   COALESCE(tm.volume,0) monthVolumeUsdt,NULL lifetimeVolumeUsdt,
                   CASE WHEN u.status='ACTIVE' THEN 'ACTIVE' ELSE 'OFFLINE' END status,u.region
              FROM network n JOIN nx_user u ON u.id=n.member_user_id
              LEFT JOIN nx_team_member tm ON tm.user_id=#{userId} AND tm.member_user_id=u.id AND tm.is_deleted=0
              LEFT JOIN nx_binary_leg_assignment a ON a.owner_user_id=#{userId} AND a.member_user_id=n.root_user_id
             WHERE u.id>#{afterId}
             ORDER BY u.id
             LIMIT #{limit}
            """)
    List<MemberRow> membersPage(@Param("userId") Long userId, @Param("afterId") long afterId,
                                @Param("limit") int limit);

    default List<MemberRow> members(Long userId) { return membersPage(userId, 0, 501); }

    record MemberRow(Long memberUserId, String nickname, String avatarUrl, String vRank, Integer level,
                     String leg, Long sponsorUserId, LocalDateTime joinedAt, BigDecimal monthVolumeUsdt,
                     BigDecimal lifetimeVolumeUsdt, String status, String region) { }
    record UserScope(Integer sandbox) { }
}
