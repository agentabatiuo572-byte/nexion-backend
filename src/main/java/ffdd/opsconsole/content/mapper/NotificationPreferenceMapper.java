package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.NotificationPreferenceView;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationPreferenceMapper extends BaseMapper<Object> {
    @Select("""
            SELECT user_id AS userId,
                   notify_commission AS commission,
                   notify_team AS team,
                   notify_staking AS staking,
                   notify_market AS market,
                   notify_genesis AS genesis,
                   notify_system AS `system`
              FROM nx_user_preference
             WHERE user_id = #{userId} AND is_deleted = 0
             LIMIT 1
            """)
    NotificationPreferenceView findByUserId(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_preference
                (user_id, notify_commission, notify_team, notify_staking,
                 notify_market, notify_genesis, notify_system,
                 created_at, updated_at, is_deleted)
            VALUES
                (#{userId}, COALESCE(#{commission}, 1), COALESCE(#{team}, 1), COALESCE(#{staking}, 1),
                 COALESCE(#{market}, 1), COALESCE(#{genesis}, 1), COALESCE(#{system}, 1), NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                notify_commission = CASE WHEN #{commission} IS NULL THEN notify_commission ELSE #{commission} END,
                notify_team = CASE WHEN #{team} IS NULL THEN notify_team ELSE #{team} END,
                notify_staking = CASE WHEN #{staking} IS NULL THEN notify_staking ELSE #{staking} END,
                notify_market = CASE WHEN #{market} IS NULL THEN notify_market ELSE #{market} END,
                notify_genesis = CASE WHEN #{genesis} IS NULL THEN notify_genesis ELSE #{genesis} END,
                notify_system = CASE WHEN #{system} IS NULL THEN notify_system ELSE #{system} END,
                is_deleted = 0,
                updated_at = NOW()
            """)
    int upsert(
            @Param("userId") Long userId,
            @Param("commission") Boolean commission,
            @Param("team") Boolean team,
            @Param("staking") Boolean staking,
            @Param("market") Boolean market,
            @Param("genesis") Boolean genesis,
            @Param("system") Boolean system);
}
