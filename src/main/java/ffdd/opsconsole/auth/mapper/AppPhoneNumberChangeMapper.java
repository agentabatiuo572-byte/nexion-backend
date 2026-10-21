package ffdd.opsconsole.auth.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AppPhoneNumberChangeMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_phone_number_change (
              change_no VARCHAR(64) PRIMARY KEY,
              user_id BIGINT NOT NULL,
              old_country_code VARCHAR(8) NULL,
              old_phone VARCHAR(32) NULL,
              new_country_code VARCHAR(8) NULL,
              new_phone VARCHAR(32) NULL,
              old_code_hash CHAR(64) NOT NULL,
              new_code_hash CHAR(64) NOT NULL,
              expires_at DATETIME NOT NULL,
              attempt_count INT NOT NULL DEFAULT 0,
              completed_at DATETIME NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              KEY idx_phone_number_change_user_completed (user_id, completed_at),
              KEY idx_phone_number_change_user_created (user_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """)
    void createTable();

    @Select("""
            SELECT COUNT(*) FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND completed_at > DATE_SUB(NOW(), INTERVAL #{windowDays} DAY)
            """)
    int countCompleted(@Param("userId") long userId, @Param("windowDays") int windowDays);

    @Select("""
            SELECT completed_at FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND completed_at > DATE_SUB(NOW(), INTERVAL #{windowDays} DAY)
             ORDER BY completed_at DESC LIMIT 1 OFFSET #{maxChangesMinusOne}
            """)
    java.time.LocalDateTime thresholdCompleted(@Param("userId") long userId,
            @Param("windowDays") int windowDays, @Param("maxChangesMinusOne") int maxChangesMinusOne);

    @Select("""
            SELECT COUNT(*) FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND created_at >= DATE_SUB(NOW(), INTERVAL 1 MINUTE)
            """)
    int recentStarts(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
            """)
    int dailyStarts(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user
             WHERE REPLACE(country_code,'+','')=#{countryCode}
               AND phone IN (#{phone},#{alternatePhone}) AND id<>#{userId}
            """)
    int targetOwners(@Param("userId") long userId, @Param("countryCode") String countryCode,
            @Param("phone") String phone, @Param("alternatePhone") String alternatePhone);

    @Select("""
            SELECT change_no AS changeNo,old_country_code AS oldCountryCode,old_phone AS oldPhone,
                   new_country_code AS newCountryCode,new_phone AS newPhone,
                   GREATEST(TIMESTAMPDIFF(SECOND,NOW(),expires_at),1) AS expiresInSeconds
              FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND completed_at IS NULL AND expires_at>NOW() AND attempt_count<5
             ORDER BY created_at DESC LIMIT 1 FOR UPDATE
            """)
    Map<String, Object> latestOpen(@Param("userId") long userId);

    @Update("""
            UPDATE nx_user_phone_number_change
               SET expires_at=NOW(),old_country_code=NULL,old_phone=NULL,
                   new_country_code=NULL,new_phone=NULL,old_code_hash='',new_code_hash=''
             WHERE user_id=#{userId} AND completed_at IS NULL AND expires_at>NOW()
            """)
    int expirePending(@Param("userId") long userId);

    @Update("""
            UPDATE nx_user_phone_number_change
               SET old_country_code=NULL,old_phone=NULL,new_country_code=NULL,new_phone=NULL,
                   old_code_hash='',new_code_hash=''
             WHERE completed_at IS NULL AND expires_at<=NOW()
               AND (old_phone IS NOT NULL OR new_phone IS NOT NULL)
            """)
    int scrubExpired();

    @Insert("""
            INSERT INTO nx_user_phone_number_change (
              change_no,user_id,old_country_code,old_phone,new_country_code,new_phone,
              old_code_hash,new_code_hash,expires_at)
            VALUES (#{changeNo},#{userId},#{oldCountryCode},#{oldPhone},#{newCountryCode},#{newPhone},
              SHA2(CONCAT(#{oldCode},':',#{changeNo},':OLD'),256),
              SHA2(CONCAT(#{newCode},':',#{changeNo},':NEW'),256),
              DATE_ADD(NOW(),INTERVAL 10 MINUTE))
            """)
    int insert(@Param("changeNo") String changeNo, @Param("userId") long userId,
            @Param("oldCountryCode") String oldCountryCode, @Param("oldPhone") String oldPhone,
            @Param("newCountryCode") String newCountryCode, @Param("newPhone") String newPhone,
            @Param("oldCode") String oldCode, @Param("newCode") String newCode);

    @Select("""
            SELECT change_no AS changeNo,old_country_code AS oldCountryCode,old_phone AS oldPhone,
                   new_country_code AS newCountryCode,new_phone AS newPhone,
                   expires_at AS expiresAt,attempt_count AS attemptCount,completed_at AS completedAt
              FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND change_no=#{changeNo} FOR UPDATE
            """)
    Map<String, Object> lock(@Param("userId") long userId, @Param("changeNo") String changeNo);

    @Select("""
            SELECT COUNT(*) FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND change_no=#{changeNo}
               AND expires_at>NOW() AND completed_at IS NULL AND attempt_count<5
            """)
    int open(@Param("userId") long userId, @Param("changeNo") String changeNo);

    @Select("""
            SELECT COUNT(*) FROM nx_user_phone_number_change
             WHERE user_id=#{userId} AND change_no=#{changeNo}
               AND old_code_hash=SHA2(CONCAT(#{oldCode},':',change_no,':OLD'),256)
               AND new_code_hash=SHA2(CONCAT(#{newCode},':',change_no,':NEW'),256)
               AND expires_at>NOW() AND completed_at IS NULL AND attempt_count<5
            """)
    int codesMatch(@Param("userId") long userId, @Param("changeNo") String changeNo,
            @Param("oldCode") String oldCode, @Param("newCode") String newCode);

    @Update("""
            UPDATE nx_user_phone_number_change SET attempt_count=attempt_count+1
             WHERE user_id=#{userId} AND change_no=#{changeNo}
               AND expires_at>NOW() AND completed_at IS NULL AND attempt_count<5
            """)
    int recordFailedAttempt(@Param("userId") long userId, @Param("changeNo") String changeNo);

    @Update("""
            UPDATE nx_user SET country_code=#{countryCode},phone=#{phone},updated_at=NOW()
             WHERE id=#{userId} AND country_code=#{oldCountryCode} AND phone=#{oldPhone}
               AND status='ACTIVE' AND is_deleted=0
            """)
    int changeUserPhone(@Param("userId") long userId, @Param("oldCountryCode") String oldCountryCode,
            @Param("oldPhone") String oldPhone, @Param("countryCode") String countryCode,
            @Param("phone") String phone);

    @Update("""
            UPDATE nx_user_phone_number_change
               SET completed_at=NOW(),old_country_code=NULL,old_phone=NULL,
                   new_country_code=NULL,new_phone=NULL,old_code_hash='',new_code_hash=''
             WHERE user_id=#{userId} AND change_no=#{changeNo} AND completed_at IS NULL
            """)
    int complete(@Param("userId") long userId, @Param("changeNo") String changeNo);
}
