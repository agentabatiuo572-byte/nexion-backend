package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserLoginGuardMapper extends BaseMapper<UserLoginGuardRecord> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_login_guard (
              login_key CHAR(64) PRIMARY KEY,
              failed_count INT NOT NULL DEFAULT 0,
              window_started_at DATETIME(3) NOT NULL,
              locked_until DATETIME(3) DEFAULT NULL,
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              KEY idx_user_login_guard_lock (locked_until),
              KEY idx_user_login_guard_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTable();

    @Select("SELECT COUNT(1) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND INDEX_NAME='idx_user_login_guard_updated'")
    int countUpdatedAtIndex();

    @Update("ALTER TABLE nx_user_login_guard ADD KEY idx_user_login_guard_updated (updated_at)")
    void addUpdatedAtIndex();

    @Insert("""
            INSERT IGNORE INTO nx_user_login_guard(login_key,failed_count,window_started_at)
            VALUES(#{loginKey},0,#{now})
            """)
    void initialize(@Param("loginKey") String loginKey, @Param("now") LocalDateTime now);

    @Select("""
            SELECT failed_count AS failedCount,window_started_at AS windowStartedAt,locked_until AS lockedUntil
            FROM nx_user_login_guard WHERE login_key=#{loginKey} FOR UPDATE
            """)
    UserLoginGuardRecord lock(@Param("loginKey") String loginKey);

    @Update("""
            UPDATE nx_user_login_guard
            SET failed_count=#{failedCount},window_started_at=#{windowStartedAt},locked_until=#{lockedUntil}
            WHERE login_key=#{loginKey}
            """)
    void recordFailure(@Param("loginKey") String loginKey, @Param("failedCount") int failedCount,
                       @Param("windowStartedAt") LocalDateTime windowStartedAt,
                       @Param("lockedUntil") LocalDateTime lockedUntil);

    @Delete("DELETE FROM nx_user_login_guard WHERE login_key=#{loginKey}")
    void clear(@Param("loginKey") String loginKey);

    @Delete("""
            DELETE FROM nx_user_login_guard
            WHERE updated_at<#{before} AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(3))
            LIMIT 1000
            """)
    int deleteExpired(@Param("before") LocalDateTime before);
}
