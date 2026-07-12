package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import ffdd.opsconsole.content.domain.LearningProgressRow;
import ffdd.opsconsole.content.infrastructure.HelpArticleEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AppLearningMapper extends BaseMapper<HelpArticleEntity> {
    @Insert("""
            INSERT IGNORE INTO nx_learning_event (
                user_id, course_id, course_version, event_type, event_payload,
                created_at, updated_at, is_deleted
            ) VALUES (#{userId}, #{courseId}, #{courseVersion}, #{eventType}, #{payload}, NOW(), NOW(), 0)
            """)
    int insertLearningEvent(@Param("userId") Long userId,
                            @Param("courseId") String courseId,
                            @Param("courseVersion") String courseVersion,
                            @Param("eventType") String eventType,
                            @Param("payload") String payload);


    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion,
                   progress_pct AS progressPct, attempts, completed_at AS completedAt
              FROM nx_learning_progress
             WHERE user_id = #{userId} AND is_deleted = 0
            """)
    List<LearningProgressRow> listProgress(@Param("userId") Long userId);

    @Select("""
            SELECT course_id AS courseId, course_version AS courseVersion,
                   progress_pct AS progressPct, attempts, completed_at AS completedAt
              FROM nx_learning_progress
             WHERE user_id = #{userId} AND course_id = #{courseId}
               AND course_version = #{courseVersion} AND is_deleted = 0
             LIMIT 1
            """)
    LearningProgressRow findProgress(@Param("userId") Long userId,
                                     @Param("courseId") String courseId,
                                     @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT INTO nx_learning_progress (
                user_id, course_id, course_version, progress_pct, attempts,
                last_score, started_at, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, #{courseId}, #{courseVersion}, 1, 0, 0, NOW(), NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE updated_at = NOW(), is_deleted = 0
            """)
    int startCourse(@Param("userId") Long userId,
                    @Param("courseId") String courseId,
                    @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT INTO nx_learning_progress (
                user_id, course_id, course_version, progress_pct, attempts,
                last_score, started_at, completed_at, created_at, updated_at, is_deleted
            ) VALUES (
                #{userId}, #{courseId}, #{courseVersion}, #{progressPct}, 1,
                #{score}, NOW(), IF(#{progressPct} = 100, NOW(), NULL), NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                progress_pct = GREATEST(progress_pct, VALUES(progress_pct)),
                attempts = attempts + 1,
                last_score = VALUES(last_score),
                completed_at = IF(VALUES(progress_pct) = 100, COALESCE(completed_at, NOW()), completed_at),
                updated_at = NOW(), is_deleted = 0
            """)
    int recordQuiz(@Param("userId") Long userId,
                   @Param("courseId") String courseId,
                   @Param("courseVersion") String courseVersion,
                   @Param("score") int score,
                   @Param("progressPct") int progressPct);

    @Select("""
            SELECT COUNT(*) FROM nx_learning_reward_ledger
             WHERE user_id = #{userId} AND course_id = #{courseId}
               AND course_version = #{courseVersion} AND status = 'GRANTED' AND is_deleted = 0
            """)
    int countGrantedReward(@Param("userId") Long userId,
                           @Param("courseId") String courseId,
                           @Param("courseVersion") String courseVersion);

    @Insert("""
            INSERT IGNORE INTO nx_learning_reward_ledger (
                reward_no, user_id, course_id, course_version, amount_nex, status,
                created_at, updated_at, is_deleted
            ) VALUES (#{rewardNo}, #{userId}, #{courseId}, #{courseVersion}, #{amount}, 'GRANTED', NOW(), NOW(), 0)
            """)
    int grantReward(@Param("rewardNo") String rewardNo,
                    @Param("userId") Long userId,
                    @Param("courseId") String courseId,
                    @Param("courseVersion") String courseVersion,
                    @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO nx_user_wallet (
                user_id, usdt_available, nex_available, pending_withdraw, lifetime_earned,
                version, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, 0, #{amount}, 0, #{amount}, 0, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                nex_available = nex_available + VALUES(nex_available),
                lifetime_earned = lifetime_earned + VALUES(lifetime_earned),
                version = version + 1, updated_at = NOW(), is_deleted = 0
            """)
    int creditWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Select("""
            SELECT COALESCE(SUM(amount_nex), 0) FROM nx_learning_reward_ledger
             WHERE user_id = #{userId} AND status = 'GRANTED' AND is_deleted = 0
            """)
    BigDecimal sumGrantedReward(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(amount_nex), 0) FROM nx_learning_reward_ledger
             WHERE status = 'GRANTED' AND is_deleted = 0
               AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """)
    BigDecimal sumGrantedRewardThisWeek();
}
