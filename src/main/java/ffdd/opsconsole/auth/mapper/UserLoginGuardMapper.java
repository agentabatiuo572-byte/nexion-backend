package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
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
              user_id BIGINT DEFAULT NULL,
              failed_count INT NOT NULL DEFAULT 0,
              window_started_at DATETIME(3) NOT NULL,
              locked_until DATETIME(3) DEFAULT NULL,
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              KEY idx_user_login_guard_lock (locked_until),
              KEY idx_user_login_guard_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_otp_send_guard (
              login_key CHAR(64) PRIMARY KEY,
              last_sent_at DATETIME(3) DEFAULT NULL,
              window_started_at DATETIME(3) NOT NULL,
              window_send_count INT NOT NULL DEFAULT 0,
              day_started_at DATETIME(3) NOT NULL,
              day_send_count INT NOT NULL DEFAULT 0,
              legacy_window_until DATETIME(3) DEFAULT NULL,
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              KEY idx_user_otp_send_guard_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createOtpSendGuardTable();

    @Insert("""
            INSERT IGNORE INTO nx_user_otp_send_guard(
                login_key,window_started_at,window_send_count,day_started_at,day_send_count)
            VALUES(#{loginKey},#{now},0,#{now},0)
            """)
    void initializeOtpSendGuard(@Param("loginKey") String loginKey, @Param("now") LocalDateTime now);

    @Select("""
            SELECT login_key AS loginKey,last_sent_at AS lastSentAt,
                   window_started_at AS windowStartedAt,window_send_count AS windowSendCount,
                   day_started_at AS dayStartedAt,day_send_count AS daySendCount,
                   legacy_window_until AS legacyWindowUntil
            FROM nx_user_otp_send_guard WHERE login_key=#{loginKey} FOR UPDATE
            """)
    UserOtpSendGuardRecord lockOtpSendGuard(@Param("loginKey") String loginKey);

    @Update("""
            UPDATE nx_user_otp_send_guard
               SET last_sent_at=#{now},window_started_at=#{windowStartedAt},
                   window_send_count=#{windowSendCount},
                   day_started_at=CASE WHEN legacy_window_until IS NULL OR legacy_window_until<=#{now}
                                       THEN #{dayStartedAt} ELSE day_started_at END,
                   day_send_count=CASE WHEN legacy_window_until IS NULL OR legacy_window_until<=#{now}
                                       THEN #{daySendCount} ELSE day_send_count END,
                   legacy_window_until=CASE WHEN legacy_window_until<=#{now} THEN NULL ELSE legacy_window_until END
             WHERE login_key=#{loginKey}
            """)
    int recordOtpSend(@Param("loginKey") String loginKey, @Param("now") LocalDateTime now,
                      @Param("windowStartedAt") LocalDateTime windowStartedAt,
                      @Param("windowSendCount") int windowSendCount,
                      @Param("dayStartedAt") java.time.LocalDateTime dayStartedAt,
                      @Param("daySendCount") int daySendCount);

    @Select("SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_otp_send_guard' AND COLUMN_NAME='day_started_at' AND DATA_TYPE='date'")
    int countOtpSendGuardDateWindowColumn();

    @Update("ALTER TABLE nx_user_otp_send_guard MODIFY COLUMN day_started_at DATETIME(3) NOT NULL")
    void migrateOtpSendGuardWindowToTimestamp();

    @Select("SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_otp_send_guard' AND COLUMN_NAME='legacy_window_until'")
    int countOtpSendGuardLegacyWindowColumn();

    @Update("ALTER TABLE nx_user_otp_send_guard ADD COLUMN legacy_window_until DATETIME(3) NULL AFTER day_send_count")
    void addOtpSendGuardLegacyWindowColumn();

    @Update("UPDATE nx_user_otp_send_guard SET legacy_window_until=DATE_ADD(day_started_at, INTERVAL 2 DAY) WHERE legacy_window_until IS NULL AND day_send_count>0")
    int preserveLegacyOtpSendGuardQuota();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_otp_send_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              login_key CHAR(64) NOT NULL,
              sent_at DATETIME(3) NOT NULL,
              KEY idx_user_otp_send_event_key_time (login_key,sent_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createOtpSendEventTable();

    @Select("SELECT COUNT(1) FROM nx_user_otp_send_event WHERE login_key=#{loginKey} AND sent_at>=#{since}")
    int countRecentOtpSendEvents(@Param("loginKey") String loginKey, @Param("since") LocalDateTime since);

    @Insert("INSERT INTO nx_user_otp_send_event(login_key,sent_at) VALUES(#{loginKey},#{sentAt})")
    int insertOtpSendEvent(@Param("loginKey") String loginKey, @Param("sentAt") LocalDateTime sentAt);

    @Delete("DELETE FROM nx_user_otp_send_event WHERE sent_at<#{before} LIMIT 10000")
    int deleteExpiredOtpSendEvents(@Param("before") LocalDateTime before);

    @Select("SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND COLUMN_NAME='user_id'")
    int countUserIdColumn();

    @Update("ALTER TABLE nx_user_login_guard ADD COLUMN user_id BIGINT NULL AFTER login_key, ADD KEY idx_user_login_guard_user (user_id, locked_until)")
    void addUserIdColumn();

    @Select("SELECT COUNT(1) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND INDEX_NAME='idx_user_login_guard_updated'")
    int countUpdatedAtIndex();

    @Update("ALTER TABLE nx_user_login_guard ADD KEY idx_user_login_guard_updated (updated_at)")
    void addUpdatedAtIndex();

    @Insert("""
            INSERT IGNORE INTO nx_user_login_guard(login_key,failed_count,window_started_at)
            VALUES(#{loginKey},0,#{now})
            """)
    void initialize(@Param("loginKey") String loginKey, @Param("now") LocalDateTime now);

    @Update("UPDATE nx_user_login_guard SET user_id=#{userId} WHERE login_key=#{loginKey}")
    int bindUser(@Param("loginKey") String loginKey, @Param("userId") Long userId);

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

    @Delete("DELETE FROM nx_user_login_guard WHERE user_id=#{userId}")
    int clearByUserId(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM nx_user_login_guard
            WHERE updated_at<#{before} AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(3))
            LIMIT 1000
            """)
    int deleteExpired(@Param("before") LocalDateTime before);
}
