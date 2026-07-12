package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialPoolView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NovaMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_channel (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              channel_key VARCHAR(64) NOT NULL,
              channel_name VARCHAR(128) NOT NULL,
              trigger_rule VARCHAR(255) NOT NULL,
              tick_rule VARCHAR(64) NOT NULL,
              cooldown_rule VARCHAR(64) NOT NULL,
              phase_keyed VARCHAR(128) NOT NULL DEFAULT '',
              ctr_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
              target_ctr_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
              enabled TINYINT NOT NULL DEFAULT 1,
              sort_order INT NOT NULL DEFAULT 1000,
              operator VARCHAR(128) NULL,
              reason VARCHAR(512) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_nova_channel_key (channel_key),
              KEY idx_nova_channel_order (is_deleted, sort_order, channel_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createChannelTable();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_nova_channel'
               AND COLUMN_NAME = 'target_ctr_pct'
            """)
    int targetCtrColumnCount();

    @Update("ALTER TABLE nx_nova_channel ADD COLUMN target_ctr_pct DECIMAL(8,4) NOT NULL DEFAULT 0 AFTER ctr_pct")
    void addTargetCtrColumn();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_template (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              channel_key VARCHAR(64) NOT NULL,
              template_name VARCHAR(128) NOT NULL,
              cta VARCHAR(255) NOT NULL,
              version VARCHAR(32) NOT NULL,
              title_zh VARCHAR(255) NOT NULL DEFAULT '',
              body_zh TEXT NOT NULL,
              title_vi VARCHAR(255) NOT NULL DEFAULT '',
              body_vi TEXT NOT NULL,
              title_en VARCHAR(255) NOT NULL DEFAULT '',
              body_en TEXT NOT NULL,
              status VARCHAR(32) NOT NULL,
              operator VARCHAR(128) NULL,
              reason VARCHAR(512) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_nova_template_channel (channel_key),
              KEY idx_nova_template_status (is_deleted, status, channel_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTemplateTable();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_nova_template'
               AND COLUMN_NAME = 'title_zh'
            """)
    int templateContentColumnCount();

    @Update("""
            ALTER TABLE nx_nova_template
              ADD COLUMN title_zh VARCHAR(255) NOT NULL DEFAULT '' AFTER version,
              ADD COLUMN body_zh TEXT NOT NULL AFTER title_zh,
              ADD COLUMN title_vi VARCHAR(255) NOT NULL DEFAULT '' AFTER body_zh,
              ADD COLUMN body_vi TEXT NOT NULL AFTER title_vi,
              ADD COLUMN title_en VARCHAR(255) NOT NULL DEFAULT '' AFTER body_vi,
              ADD COLUMN body_en TEXT NOT NULL AFTER title_en
            """)
    void addTemplateContentColumns();

    @Update("""
            UPDATE nx_nova_template t
            LEFT JOIN nx_nova_channel c
              ON c.channel_key = t.channel_key AND c.is_deleted = 0
               SET t.status = 'DRAFT',
                   c.enabled = 0,
                   t.updated_at = NOW(),
                   c.updated_at = NOW()
             WHERE t.is_deleted = 0
               AND (TRIM(t.title_zh) = '' OR TRIM(t.body_zh) = ''
                    OR TRIM(t.title_vi) = '' OR TRIM(t.body_vi) = '')
               AND (UPPER(t.status) <> 'DRAFT' OR COALESCE(c.enabled, 0) <> 0)
            """)
    int quarantineIncompleteTemplates();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_social_distribution (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              dist_key VARCHAR(64) NOT NULL,
              dist_name VARCHAR(128) NOT NULL,
              pct INT NOT NULL DEFAULT 0,
              color VARCHAR(64) NOT NULL DEFAULT '',
              operator VARCHAR(128) NULL,
              reason VARCHAR(512) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_nova_social_dist_key (dist_key),
              KEY idx_nova_social_dist_order (is_deleted, dist_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSocialDistributionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_social_pool (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              pool_key VARCHAR(64) NOT NULL,
              pool_name VARCHAR(128) NOT NULL,
              description VARCHAR(512) NOT NULL DEFAULT '',
              item_count INT NOT NULL DEFAULT 0,
              operator VARCHAR(128) NULL,
              reason VARCHAR(512) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_nova_social_pool_key (pool_key),
              KEY idx_nova_social_pool_order (is_deleted, pool_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSocialPoolTable();

    @Select("""
            SELECT channel_key AS `key`,
                   channel_name AS name,
                   trigger_rule AS `trigger`,
                   tick_rule AS tick,
                   cooldown_rule AS cooldown,
                   phase_keyed AS phaseKeyed,
                   ctr_pct AS ctr,
                   enabled
              FROM nx_nova_channel
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, channel_key ASC
            """)
    List<NovaChannelView> channels();

    @Select("""
            SELECT COALESCE((
                       SELECT COUNT(1)
                         FROM nx_notification n
                        WHERE n.is_deleted = 0
                          AND DATE(COALESCE(n.pushed_at, n.updated_at, n.created_at)) = CURRENT_DATE()
                          AND UPPER(COALESCE(n.push_status, '')) IN ('SENT', 'DELIVERED', 'READ', 'SUCCESS')
                   ), 0) AS todayDelivered,
                   COALESCE(AVG(CASE WHEN c.is_deleted = 0 THEN c.ctr_pct END), 0) AS avgCtr,
                   COALESCE(AVG(CASE WHEN c.is_deleted = 0 THEN c.target_ctr_pct END), 0) AS ctrTarget,
                   COALESCE(SUM(CASE WHEN c.is_deleted = 0 AND c.enabled = 1 THEN 1 ELSE 0 END), 0) AS onlineChannels,
                   COALESCE(SUM(CASE WHEN c.is_deleted = 0 THEN 1 ELSE 0 END), 0) AS totalChannels,
                   COALESCE((
                       SELECT SUM(p.item_count)
                         FROM nx_nova_social_pool p
                        WHERE p.is_deleted = 0
                   ), 0) AS weeklySocial
              FROM nx_nova_channel c
            """)
    Map<String, Object> stats();

    @Select("""
            SELECT channel_key AS `key`,
                   channel_name AS name,
                   trigger_rule AS `trigger`,
                   tick_rule AS tick,
                   cooldown_rule AS cooldown,
                   phase_keyed AS phaseKeyed,
                   ctr_pct AS ctr,
                   enabled
              FROM nx_nova_channel
             WHERE is_deleted = 0
               AND channel_key = #{key}
             LIMIT 1
            """)
    NovaChannelView channel(@Param("key") String key);

    @Select("SELECT COALESCE(MAX(sort_order), 0) FROM nx_nova_channel WHERE is_deleted = 0")
    int maxChannelOrder();

    @Insert("""
            INSERT INTO nx_nova_channel (
                channel_key, channel_name, trigger_rule, tick_rule, cooldown_rule,
                phase_keyed, ctr_pct, enabled, sort_order, operator, reason,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{key}, #{name}, #{trigger}, #{tick}, #{cooldown},
                '', #{ctr}, #{enabled}, #{sortOrder}, #{operator}, #{reason},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                channel_name = VALUES(channel_name),
                trigger_rule = VALUES(trigger_rule),
                tick_rule = VALUES(tick_rule),
                cooldown_rule = VALUES(cooldown_rule),
                ctr_pct = VALUES(ctr_pct),
                enabled = VALUES(enabled),
                sort_order = VALUES(sort_order),
                operator = VALUES(operator),
                reason = VALUES(reason),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertChannel(@Param("key") String key,
                      @Param("name") String name,
                      @Param("trigger") String trigger,
                      @Param("tick") String tick,
                      @Param("cooldown") String cooldown,
                      @Param("ctr") BigDecimal ctr,
                      @Param("enabled") boolean enabled,
                      @Param("sortOrder") int sortOrder,
                      @Param("operator") String operator,
                      @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_channel
               SET channel_name = #{name},
                   trigger_rule = #{trigger},
                   tick_rule = #{tick},
                   cooldown_rule = #{cooldown},
                   ctr_pct = #{ctr},
                   enabled = #{enabled},
                   operator = #{operator},
                   reason = #{reason},
                   updated_at = NOW()
             WHERE channel_key = #{key}
               AND is_deleted = 0
            """)
    int updateChannel(@Param("key") String key,
                      @Param("name") String name,
                      @Param("trigger") String trigger,
                      @Param("tick") String tick,
                      @Param("cooldown") String cooldown,
                      @Param("ctr") BigDecimal ctr,
                      @Param("enabled") boolean enabled,
                      @Param("operator") String operator,
                      @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_channel
               SET enabled = #{enabled},
                   operator = #{operator},
                   reason = #{reason},
                   updated_at = NOW()
             WHERE channel_key = #{key}
               AND is_deleted = 0
            """)
    int updateChannelStatus(@Param("key") String key,
                            @Param("enabled") boolean enabled,
                            @Param("operator") String operator,
                            @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_channel
               SET is_deleted = 1,
                   operator = #{operator},
                   reason = #{reason},
                   updated_at = NOW()
             WHERE channel_key = #{key}
               AND is_deleted = 0
            """)
    int deleteChannel(@Param("key") String key, @Param("operator") String operator, @Param("reason") String reason);

    @Select("""
            SELECT channel_key AS channel,
                   template_name AS name,
                   cta,
                   version,
                   title_zh AS titleZh,
                   body_zh AS bodyZh,
                   title_vi AS titleVi,
                   body_vi AS bodyVi,
                   title_en AS titleEn,
                   body_en AS bodyEn,
                   status
              FROM nx_nova_template
             WHERE is_deleted = 0
             ORDER BY channel_key ASC
            """)
    List<NovaTemplateView> templates();

    @Select("""
            SELECT channel_key AS channel,
                   template_name AS name,
                   cta,
                   version,
                   title_zh AS titleZh,
                   body_zh AS bodyZh,
                   title_vi AS titleVi,
                   body_vi AS bodyVi,
                   title_en AS titleEn,
                   body_en AS bodyEn,
                   status
              FROM nx_nova_template
             WHERE is_deleted = 0
               AND channel_key = #{channel}
             LIMIT 1
            """)
    NovaTemplateView template(@Param("channel") String channel);

    @Insert("""
            INSERT INTO nx_nova_template (
                channel_key, template_name, cta, version,
                title_zh, body_zh, title_vi, body_vi, title_en, body_en,
                status, operator, reason,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{channel}, #{name}, #{cta}, #{version},
                #{titleZh}, #{bodyZh}, #{titleVi}, #{bodyVi}, #{titleEn}, #{bodyEn},
                #{status}, #{operator}, #{reason},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                template_name = VALUES(template_name),
                cta = VALUES(cta),
                version = VALUES(version),
                title_zh = VALUES(title_zh),
                body_zh = VALUES(body_zh),
                title_vi = VALUES(title_vi),
                body_vi = VALUES(body_vi),
                title_en = VALUES(title_en),
                body_en = VALUES(body_en),
                status = VALUES(status),
                operator = VALUES(operator),
                reason = VALUES(reason),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertTemplate(@Param("channel") String channel,
                       @Param("name") String name,
                       @Param("cta") String cta,
                       @Param("version") String version,
                       @Param("titleZh") String titleZh,
                       @Param("bodyZh") String bodyZh,
                       @Param("titleVi") String titleVi,
                       @Param("bodyVi") String bodyVi,
                       @Param("titleEn") String titleEn,
                       @Param("bodyEn") String bodyEn,
                       @Param("status") String status,
                       @Param("operator") String operator,
                       @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_template
               SET status = #{status},
                   operator = #{operator},
                   reason = #{reason},
                   updated_at = NOW()
             WHERE channel_key = #{channel}
               AND is_deleted = 0
            """)
    int updateTemplateStatus(@Param("channel") String channel,
                             @Param("status") String status,
                             @Param("operator") String operator,
                             @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_template
               SET is_deleted = 1,
                   operator = #{operator},
                   reason = #{reason},
                   updated_at = NOW()
             WHERE channel_key = #{channel}
               AND is_deleted = 0
            """)
    int deleteTemplate(@Param("channel") String channel,
                       @Param("operator") String operator,
                       @Param("reason") String reason);

    @Select("""
            SELECT dist_key AS `key`,
                   dist_name AS name,
                   pct,
                   color
              FROM nx_nova_social_distribution
             WHERE is_deleted = 0
             ORDER BY dist_key ASC
            """)
    List<NovaSocialDistributionItem> socialDistribution();

    @Insert("""
            INSERT INTO nx_nova_social_distribution (
                dist_key, dist_name, pct, color, operator, reason,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{key}, #{name}, #{pct}, #{color}, #{operator}, #{reason},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                dist_name = VALUES(dist_name),
                pct = VALUES(pct),
                color = VALUES(color),
                operator = VALUES(operator),
                reason = VALUES(reason),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertDistribution(@Param("key") String key,
                           @Param("name") String name,
                           @Param("pct") int pct,
                           @Param("color") String color,
                           @Param("operator") String operator,
                           @Param("reason") String reason);

    @Select("""
            SELECT pool_key AS `key`,
                   pool_name AS name,
                   description,
                   item_count AS count
              FROM nx_nova_social_pool
             WHERE is_deleted = 0
             ORDER BY pool_key ASC
            """)
    List<NovaSocialPoolView> socialPools();

    @Select("""
            SELECT pool_key AS `key`,
                   pool_name AS name,
                   description,
                   item_count AS count
              FROM nx_nova_social_pool
             WHERE is_deleted = 0
               AND pool_key = #{key}
             LIMIT 1
            """)
    NovaSocialPoolView socialPool(@Param("key") String key);

    @Insert("""
            INSERT INTO nx_nova_social_pool (
                pool_key, pool_name, description, item_count, operator, reason,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{key}, #{name}, #{description}, #{count}, #{operator}, #{reason},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                pool_name = VALUES(pool_name),
                description = VALUES(description),
                item_count = VALUES(item_count),
                operator = VALUES(operator),
                reason = VALUES(reason),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertPool(@Param("key") String key,
                   @Param("name") String name,
                   @Param("description") String description,
                   @Param("count") int count,
                   @Param("operator") String operator,
                   @Param("reason") String reason);
}
