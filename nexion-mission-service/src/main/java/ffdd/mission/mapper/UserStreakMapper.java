package ffdd.mission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.mission.domain.UserStreak;
import ffdd.mission.dto.StreakLeaderboardEntryResponse;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserStreakMapper extends BaseMapper<UserStreak> {
    @Select("""
            SELECT ROW_NUMBER() OVER (ORDER BY s.current_streak DESC, s.longest_streak DESC, s.last_check_in_date DESC, s.id ASC) AS `rank`,
                   s.user_id AS userId,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('Nexion_', RIGHT(CAST(s.user_id AS CHAR), 4))) AS displayName,
                   u.avatar_url AS avatarUrl,
                   u.country_code AS countryCode,
                   s.current_streak AS currentStreak,
                   s.longest_streak AS longestStreak,
                   s.last_check_in_date AS lastCheckInDate,
                   CASE WHEN s.last_check_in_date = #{asOfDate} THEN TRUE ELSE FALSE END AS checkedInToday
            FROM nx_user_streak s
            LEFT JOIN nx_user u
              ON u.id = s.user_id
             AND u.is_deleted = 0
            WHERE s.is_deleted = 0
              AND s.current_streak > 0
              AND s.last_check_in_date >= DATE_SUB(#{asOfDate}, INTERVAL 1 DAY)
            ORDER BY s.current_streak DESC, s.longest_streak DESC, s.last_check_in_date DESC, s.id ASC
            LIMIT #{limit}
            """)
    List<StreakLeaderboardEntryResponse> selectTopStreakers(
            @Param("asOfDate") LocalDate asOfDate,
            @Param("limit") int limit);
}
