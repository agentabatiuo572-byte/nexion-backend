package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NovaSocialRuntimeMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_social_runtime_slot (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              slot_key VARCHAR(128) NOT NULL,
              lease_owner VARCHAR(128) NOT NULL,
              lease_until DATETIME NOT NULL,
              completed_at DATETIME NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              UNIQUE KEY uk_nova_social_runtime_slot (slot_key),
              KEY idx_nova_social_runtime_lease (completed_at, lease_until)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRuntimeSlotTable();

    @Insert("""
            INSERT IGNORE INTO nx_nova_social_runtime_slot (
                slot_key, lease_owner, lease_until, completed_at, created_at, updated_at)
            VALUES (#{slotKey}, #{leaseOwner}, #{leaseUntil}, NULL, #{now}, #{now})
            """)
    int insertSlotClaim(@Param("slotKey") String slotKey,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("leaseUntil") LocalDateTime leaseUntil,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_social_runtime_slot
               SET lease_owner = #{leaseOwner}, lease_until = #{leaseUntil}, updated_at = #{now}
             WHERE slot_key = #{slotKey}
               AND completed_at IS NULL
               AND lease_until <= #{now}
            """)
    int takeoverExpiredSlot(@Param("slotKey") String slotKey,
                            @Param("leaseOwner") String leaseOwner,
                            @Param("leaseUntil") LocalDateTime leaseUntil,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_social_runtime_slot
               SET completed_at = #{now}, lease_until = #{now}, updated_at = #{now}
             WHERE slot_key = #{slotKey}
               AND lease_owner = #{leaseOwner}
               AND completed_at IS NULL
            """)
    int completeSlot(@Param("slotKey") String slotKey,
                     @Param("leaseOwner") String leaseOwner,
                     @Param("now") LocalDateTime now);

    @Select("""
            SELECT MAX(n.created_at)
              FROM nx_notification n
             WHERE n.is_deleted = 0
               AND n.type = 'NOVA_SOCIAL'
            """)
    LocalDateTime latestNotificationAt();

    @Insert("""
            INSERT IGNORE INTO nx_notification (
                biz_no, user_id, type, priority, title, body, cta_label, cta_href,
                read_flag, push_status, push_attempts, next_push_at,
                created_at, updated_at, is_deleted)
            SELECT #{bizNo}, u.id, 'NOVA_SOCIAL', 'normal',
                   LEFT(CASE
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{titleVi}
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{titleZh}
                       ELSE #{titleEn}
                   END, 128),
                   LEFT(CASE
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{bodyVi}
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{bodyZh}
                       ELSE #{bodyEn}
                   END, 512),
                   NULL, NULLIF(#{ctaHref}, ''),
                   0, 'QUEUED', 0, #{now}, #{now}, #{now}, 0
              FROM nx_user u
              JOIN nx_nova_social_event e
                ON e.id = #{eventId}
               AND e.is_deleted = 0
               AND e.status = 'ACTIVE'
               AND e.expires_at > #{now}
              JOIN nx_nova_channel c
                ON c.channel_key = 'social'
               AND c.is_deleted = 0
               AND c.enabled = 1
              JOIN nx_nova_template t
                ON t.channel_key = c.channel_key
               AND t.is_deleted = 0
               AND t.status = 'PUBLISHED'
             WHERE u.is_deleted = 0
               AND UPPER(u.status) = 'ACTIVE'
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_notification previous
                    WHERE previous.user_id = u.id
                      AND previous.is_deleted = 0
                      AND previous.type = 'NOVA_SOCIAL'
                      AND previous.created_at > #{cooldownSince}
               )
            """)
    int enqueueNotifications(
            @Param("eventId") long eventId,
            @Param("bizNo") String bizNo,
            @Param("titleZh") String titleZh,
            @Param("bodyZh") String bodyZh,
            @Param("titleVi") String titleVi,
            @Param("bodyVi") String bodyVi,
            @Param("titleEn") String titleEn,
            @Param("bodyEn") String bodyEn,
            @Param("ctaHref") String ctaHref,
            @Param("cooldownSince") LocalDateTime cooldownSince,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_social_event
               SET dispatch_count = dispatch_count + 1,
                   last_dispatched_at = #{now},
                   updated_at = #{now}
             WHERE id = #{eventId}
               AND is_deleted = 0
               AND status = 'ACTIVE'
               AND expires_at > #{now}
            """)
    int markDispatchedIfStillActive(@Param("eventId") long eventId, @Param("now") LocalDateTime now);
}
