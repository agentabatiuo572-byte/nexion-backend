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
                   CASE a.leg WHEN 'LEFT' THEN 'A' WHEN 'RIGHT' THEN 'B' ELSE NULL END leg,
                   n.sponsor_user_id sponsorUserId,u.created_at joinedAt,
                   COALESCE(tm.volume,0) monthVolumeUsdt,NULL lifetimeVolumeUsdt,
                   CASE WHEN u.status='ACTIVE' THEN 'ACTIVE' ELSE 'OFFLINE' END status,u.region
              FROM network n JOIN nx_user u ON u.id=n.member_user_id
              LEFT JOIN nx_team_member tm ON tm.user_id=#{userId} AND tm.member_user_id=u.id AND tm.is_deleted=0
              LEFT JOIN nx_binary_leg_assignment a ON a.owner_user_id=#{userId} AND a.member_user_id=n.root_user_id
             ORDER BY n.level,u.created_at,u.id
             LIMIT 500
            """)
    List<MemberRow> members(@Param("userId") Long userId);

    record MemberRow(Long memberUserId, String nickname, String avatarUrl, String vRank, Integer level,
                     String leg, Long sponsorUserId, LocalDateTime joinedAt, BigDecimal monthVolumeUsdt,
                     BigDecimal lifetimeVolumeUsdt, String status, String region) { }
}
