package ffdd.opsconsole.growth.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppEarningGoalMapper extends BaseMapper<Object> {
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Long activeUser(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id AS userId,target_usdt AS targetUsdt,deadline_at AS deadlineAt,
                   achieved,achieved_at AS achievedAt,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_earning_goal
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY achieved ASC, deadline_at ASC, id DESC
            """)
    List<GoalRow> list(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id AS userId,target_usdt AS targetUsdt,deadline_at AS deadlineAt,
                   achieved,achieved_at AS achievedAt,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_earning_goal
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY id DESC LIMIT 1
            """)
    GoalRow latest(@Param("userId") Long userId);

    @Select("""
            SELECT id,user_id AS userId,target_usdt AS targetUsdt,deadline_at AS deadlineAt,
                   achieved,achieved_at AS achievedAt,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_earning_goal
             WHERE id=#{goalId} AND user_id=#{userId} AND is_deleted=0
             """)
    GoalRow findById(@Param("userId") Long userId, @Param("goalId") Long goalId);

    @Select("""
            SELECT COALESCE(SUM(reward_usdt),0)
              FROM nx_compute_receipt
             WHERE user_id=#{userId} AND is_deleted=0
               AND UPPER(earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
            """)
    BigDecimal lifetimeEarnings(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_earning_goal
              (user_id,target_usdt,deadline_at,achieved,created_at,updated_at,is_deleted)
            VALUES (#{userId},#{targetUsdt},#{deadlineAt},0,NOW(),NOW(),0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(GoalInsert goal);

    @Update("""
            UPDATE nx_earning_goal SET achieved=#{achieved},
                   achieved_at=CASE WHEN #{achieved}=1 THEN COALESCE(achieved_at,NOW()) ELSE NULL END,
                   updated_at=NOW()
             WHERE id=#{goalId} AND user_id=#{userId} AND is_deleted=0
            """)
    int updateStatus(@Param("userId") Long userId, @Param("goalId") Long goalId,
                     @Param("achieved") boolean achieved);

    @Update("""
            UPDATE nx_earning_goal SET is_deleted=1, deleted_at=NOW(), updated_at=NOW()
             WHERE id=#{goalId} AND user_id=#{userId} AND is_deleted=0
            """)
    int softDelete(@Param("userId") Long userId, @Param("goalId") Long goalId);

    record GoalRow(Long id, Long userId, BigDecimal targetUsdt, LocalDateTime deadlineAt,
                    boolean achieved, LocalDateTime achievedAt, LocalDateTime createdAt,
                    LocalDateTime updatedAt) { }

    class GoalInsert {
        private Long id;
        private final Long userId;
        private final BigDecimal targetUsdt;
        private final LocalDateTime deadlineAt;

        public GoalInsert(Long userId, BigDecimal targetUsdt, LocalDateTime deadlineAt) {
            this.userId = userId;
            this.targetUsdt = targetUsdt;
            this.deadlineAt = deadlineAt;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getUserId() { return userId; }
        public BigDecimal getTargetUsdt() { return targetUsdt; }
        public LocalDateTime getDeadlineAt() { return deadlineAt; }
    }
}
