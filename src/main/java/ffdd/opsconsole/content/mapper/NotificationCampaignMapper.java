package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.NotificationCampaignEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface NotificationCampaignMapper extends BaseMapper<NotificationCampaignEntity> {
    @Insert("""
            INSERT INTO nx_notification (biz_no, user_id, type, title, body, read_flag, push_status, push_attempts, next_push_at, created_at, updated_at, is_deleted)
            VALUES (#{bizNo}, 0, 'SYSTEM', LEFT(#{title}, 128), LEFT(#{body}, 512), 0, 'PENDING', 0, #{now}, #{now}, #{now}, 0)
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                body = VALUES(body),
                read_flag = VALUES(read_flag),
                push_status = VALUES(push_status),
                next_push_at = VALUES(next_push_at),
                updated_at = VALUES(updated_at),
                is_deleted = 0
            """)
    int insertCampaignNotification(
            @Param("bizNo") String bizNo,
            @Param("title") String title,
            @Param("body") String body,
            @Param("now") LocalDateTime now);
}
