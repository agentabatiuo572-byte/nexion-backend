package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaSocialPoolView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.domain.TrustedNovaSocialEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Insert("""
            INSERT INTO nx_nova_social_distribution
                (dist_key, dist_name, pct, color, operator, reason, created_at, updated_at, is_deleted)
            VALUES
                ('withdrawal', '提现到账', 30, 'var(--admin-cat-3)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0),
                ('vrank', 'V 等级晋升', 25, 'var(--admin-cat-5)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0),
                ('genesis', 'Genesis 成交', 25, 'var(--admin-cat-7)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0),
                ('aiClient', 'AI 客户消费', 0, 'var(--admin-cat-2)', 'system', '真实数据源未接入，默认不参与抽样', NOW(), NOW(), 0),
                ('newUsers', '每小时新增用户', 20, 'var(--admin-cat-4)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE dist_key = VALUES(dist_key)
            """)
    int seedSocialDistributionDefaults();

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

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_social_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              event_type VARCHAR(32) NOT NULL,
              source_event_id VARCHAR(160) NOT NULL,
              source_system VARCHAR(64) NOT NULL,
              source_table VARCHAR(64) NOT NULL,
              actor_display VARCHAR(64) NOT NULL DEFAULT '',
              city_display VARCHAR(64) NOT NULL DEFAULT '',
              amount_display VARCHAR(64) NOT NULL DEFAULT '',
              source_note VARCHAR(512) NOT NULL DEFAULT '',
              status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
              occurred_at DATETIME NOT NULL,
              expires_at DATETIME NOT NULL,
              verified_at DATETIME NOT NULL,
              last_dispatched_at DATETIME NULL,
              dispatch_count BIGINT NOT NULL DEFAULT 0,
              operator VARCHAR(128) NULL,
              reason VARCHAR(512) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_nova_social_source_event (event_type, source_system, source_event_id),
              KEY idx_nova_social_event_runtime (is_deleted, status, expires_at, event_type),
              KEY idx_nova_social_event_type_runtime (is_deleted, status, event_type, expires_at, occurred_at, id),
              KEY idx_nova_social_event_occurred (occurred_at),
              CONSTRAINT chk_nova_social_event_status CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED')),
              CONSTRAINT chk_nova_social_event_type CHECK (event_type IN ('withdrawal', 'vrank', 'genesis', 'aiClient', 'newUsers')),
              CONSTRAINT chk_nova_social_event_expiry CHECK (expires_at > occurred_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSocialEventTable();

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
                       SELECT SUM(e.dispatch_count)
                         FROM nx_nova_social_event e
                        WHERE e.is_deleted = 0
                          AND e.last_dispatched_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
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

    @Select("""
            SELECT id,
                   event_type AS eventType,
                   source_event_id AS sourceEventId,
                   actor_display AS actorDisplay,
                   city_display AS cityDisplay,
                   amount_display AS amountDisplay,
                   source_note AS sourceNote,
                   source_system AS sourceSystem,
                   source_table AS sourceTable,
                   status,
                   occurred_at AS occurredAt,
                   expires_at AS expiresAt,
                   verified_at AS verifiedAt,
                   last_dispatched_at AS lastDispatchedAt,
                   dispatch_count AS dispatchCount,
                   created_at AS createdAt,
                   updated_at AS updatedAt
              FROM nx_nova_social_event
             WHERE is_deleted = 0
             ORDER BY occurred_at DESC, id DESC
             LIMIT 500
            """)
    List<NovaSocialEventView> socialEvents();

    @Select("""
            <script>
            SELECT id, event_type AS eventType, source_event_id AS sourceEventId,
                   actor_display AS actorDisplay, city_display AS cityDisplay,
                   amount_display AS amountDisplay, source_note AS sourceNote,
                   source_system AS sourceSystem, source_table AS sourceTable, status,
                   occurred_at AS occurredAt, expires_at AS expiresAt, verified_at AS verifiedAt,
                   last_dispatched_at AS lastDispatchedAt, dispatch_count AS dispatchCount,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM nx_nova_social_event
             WHERE is_deleted = 0
               <if test="eventType != null and eventType != ''">AND event_type = #{eventType}</if>
               <if test="status != null and status != ''">AND status = #{status}</if>
             ORDER BY occurred_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<NovaSocialEventView> socialEventsFiltered(@Param("eventType") String eventType,
                                                    @Param("status") String status,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(1)
              FROM nx_nova_social_event
             WHERE is_deleted = 0
               <if test="eventType != null and eventType != ''">AND event_type = #{eventType}</if>
               <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countSocialEventsFiltered(@Param("eventType") String eventType,
                                   @Param("status") String status);

    @Select("""
            SELECT id, event_type AS eventType, source_event_id AS sourceEventId,
                   actor_display AS actorDisplay, city_display AS cityDisplay,
                   amount_display AS amountDisplay, source_note AS sourceNote,
                   source_system AS sourceSystem, source_table AS sourceTable, status,
                   occurred_at AS occurredAt, expires_at AS expiresAt, verified_at AS verifiedAt,
                   last_dispatched_at AS lastDispatchedAt, dispatch_count AS dispatchCount,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM nx_nova_social_event
             WHERE id = #{id} AND is_deleted = 0
             LIMIT 1
            """)
    NovaSocialEventView socialEvent(@Param("id") long id);

    @Select("""
            SELECT id, event_type AS eventType, source_event_id AS sourceEventId,
                   actor_display AS actorDisplay, city_display AS cityDisplay,
                   amount_display AS amountDisplay, source_note AS sourceNote,
                   source_system AS sourceSystem, source_table AS sourceTable, status,
                   occurred_at AS occurredAt, expires_at AS expiresAt, verified_at AS verifiedAt,
                   last_dispatched_at AS lastDispatchedAt, dispatch_count AS dispatchCount,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM nx_nova_social_event
             WHERE event_type = #{eventType}
               AND source_system = #{sourceSystem}
               AND source_event_id = #{sourceEventId}
               AND is_deleted = 0
             LIMIT 1
            """)
    NovaSocialEventView socialEventBySource(@Param("eventType") String eventType,
                                            @Param("sourceSystem") String sourceSystem,
                                            @Param("sourceEventId") String sourceEventId);

    @Select("""
            SELECT COUNT(1)
              FROM nx_nova_social_event
             WHERE event_type = #{eventType}
               AND source_system = #{sourceSystem}
               AND source_event_id = #{sourceEventId}
            """)
    int socialEventSourceCount(@Param("eventType") String eventType,
                               @Param("sourceSystem") String sourceSystem,
                               @Param("sourceEventId") String sourceEventId);

    @Insert("""
            INSERT IGNORE INTO nx_nova_social_event (
                event_type, source_event_id, source_system, source_table,
                actor_display, city_display, amount_display, source_note,
                status, occurred_at, expires_at, verified_at, operator, reason,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{source.eventType}, #{source.sourceEventId}, #{source.sourceSystem}, #{source.sourceTable},
                #{actorDisplay}, #{cityDisplay}, #{amountDisplay}, #{source.sourceNote},
                'ACTIVE', #{source.occurredAt}, #{expiresAt}, NOW(), #{operator}, #{reason},
                NOW(), NOW(), 0
            )
            """)
    int insertSocialEvent(@Param("source") TrustedNovaSocialEvent source,
                          @Param("actorDisplay") String actorDisplay,
                          @Param("cityDisplay") String cityDisplay,
                          @Param("amountDisplay") String amountDisplay,
                          @Param("expiresAt") LocalDateTime expiresAt,
                          @Param("operator") String operator,
                          @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_social_event
               SET status = #{status}, operator = #{operator}, reason = #{reason}, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0
            """)
    int updateSocialEventStatus(@Param("id") long id, @Param("status") String status,
                                @Param("operator") String operator, @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_social_event
               SET is_deleted = 1, operator = #{operator}, reason = #{reason}, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0
            """)
    int deleteSocialEvent(@Param("id") long id, @Param("operator") String operator, @Param("reason") String reason);

    @Update("""
            UPDATE nx_nova_social_event
               SET status = 'EXPIRED', operator = 'system', reason = '事件超过有效期自动过期', updated_at = NOW()
             WHERE is_deleted = 0 AND status = 'ACTIVE' AND expires_at <= #{now}
            """)
    int expireSocialEvents(@Param("now") LocalDateTime now);

    @Select("""
            SELECT id, event_type AS eventType, source_event_id AS sourceEventId,
                   actor_display AS actorDisplay, city_display AS cityDisplay,
                   amount_display AS amountDisplay, source_note AS sourceNote,
                   source_system AS sourceSystem, source_table AS sourceTable, status,
                   occurred_at AS occurredAt, expires_at AS expiresAt, verified_at AS verifiedAt,
                   last_dispatched_at AS lastDispatchedAt, dispatch_count AS dispatchCount,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM nx_nova_social_event
             WHERE is_deleted = 0 AND status = 'ACTIVE' AND expires_at > #{now}
             ORDER BY occurred_at DESC, id DESC
             LIMIT 1000
            """)
    List<NovaSocialEventView> activeSocialEvents(@Param("now") LocalDateTime now);

    @Select("""
            SELECT id, event_type AS eventType, source_event_id AS sourceEventId,
                   actor_display AS actorDisplay, city_display AS cityDisplay,
                   amount_display AS amountDisplay, source_note AS sourceNote,
                   source_system AS sourceSystem, source_table AS sourceTable, status,
                   occurred_at AS occurredAt, expires_at AS expiresAt, verified_at AS verifiedAt,
                   last_dispatched_at AS lastDispatchedAt, dispatch_count AS dispatchCount,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM nx_nova_social_event
             WHERE is_deleted = 0 AND status = 'ACTIVE' AND expires_at > #{now}
               AND event_type = #{eventType}
             ORDER BY occurred_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<NovaSocialEventView> activeSocialEventsByType(@Param("eventType") String eventType,
                                                       @Param("now") LocalDateTime now,
                                                       @Param("limit") int limit);

    @Select("""
            SELECT 'withdrawal' AS eventType,
                   CONCAT('withdrawal:', w.withdrawal_no) AS sourceEventId,
                   'NEXION_CORE' AS sourceSystem,
                   'nx_withdrawal_order' AS sourceTable,
                   COALESCE(u.nickname, '') AS actorName,
                   COALESCE(u.region, '') AS city,
                   w.amount AS amount,
                   w.asset AS amountUnit,
                   CASE WHEN w.status = 'COMPLETED' THEN 'LEGACY_COMPLETED' ELSE 'SUCCESS' END AS sourceNote,
                   w.completed_at AS occurredAt
              FROM nx_withdrawal_order w
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
             WHERE w.is_deleted = 0
               AND w.status IN ('SUCCESS', 'COMPLETED')
               AND w.completed_at IS NOT NULL
               AND w.completed_at >= #{since} AND w.completed_at < #{until}
             ORDER BY w.completed_at ASC
            """)
    List<TrustedNovaSocialEvent> withdrawalSourceEvents(@Param("since") LocalDateTime since,
                                                         @Param("until") LocalDateTime until);

    @Select("""
            SELECT 'vrank' AS eventType,
                   CONCAT('vrank:', l.id) AS sourceEventId,
                   'NEXION_CORE' AS sourceSystem,
                   'nx_user_level_log' AS sourceTable,
                   COALESCE(u.nickname, '') AS actorName,
                   COALESCE(u.region, '') AS city,
                   NULL AS amount,
                   '' AS amountUnit,
                   CONCAT(COALESCE(l.from_code, 'V0'), '→', l.to_code) AS sourceNote,
                   l.created_at AS occurredAt
              FROM nx_user_level_log l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0 AND l.level_type = 'V_RANK'
               AND CAST(SUBSTRING(UPPER(l.to_code), 2) AS UNSIGNED)
                   > CAST(SUBSTRING(UPPER(COALESCE(l.from_code, 'V0')), 2) AS UNSIGNED)
               AND l.created_at >= #{since} AND l.created_at < #{until}
             ORDER BY l.created_at ASC
            """)
    List<TrustedNovaSocialEvent> vrankSourceEvents(@Param("since") LocalDateTime since,
                                                   @Param("until") LocalDateTime until);

    @Select("""
            SELECT eventType, sourceEventId, sourceSystem, sourceTable, actorName, city,
                   amount, amountUnit, sourceNote, occurredAt
              FROM (
                    SELECT 'genesis' AS eventType,
                           CONCAT('genesis:', o.order_no) AS sourceEventId,
                           'NEXION_CORE' AS sourceSystem, 'nx_genesis_order' AS sourceTable,
                           COALESCE(u.nickname, '') AS actorName, COALESCE(u.region, '') AS city,
                           o.amount_usdt AS amount, 'USDT' AS amountUnit,
                           o.series_code AS sourceNote, o.completed_at AS occurredAt
                      FROM nx_genesis_order o
                      LEFT JOIN nx_user u ON u.id = o.user_id AND u.is_deleted = 0
                     WHERE o.is_deleted = 0 AND o.status IN ('COMPLETED', 'PAID', 'SUCCESS')
                       AND o.completed_at >= #{since} AND o.completed_at < #{until}
                    UNION ALL
                    SELECT 'genesis', CONCAT('genesis:', o.order_no),
                           'NEXION_CORE', 'nx_genesis_order',
                           COALESCE(u.nickname, ''), COALESCE(u.region, ''),
                           o.amount_usdt, 'USDT', o.series_code, o.paid_at
                      FROM nx_genesis_order o
                      LEFT JOIN nx_user u ON u.id = o.user_id AND u.is_deleted = 0
                     WHERE o.is_deleted = 0 AND o.status IN ('COMPLETED', 'PAID', 'SUCCESS')
                       AND o.completed_at IS NULL
                       AND o.paid_at >= #{since} AND o.paid_at < #{until}
                   ) source_events
             ORDER BY occurredAt ASC
            """)
    List<TrustedNovaSocialEvent> genesisSourceEvents(@Param("since") LocalDateTime since,
                                                     @Param("until") LocalDateTime until);

    @Select("""
            SELECT 'newUsers' AS eventType,
                   CONCAT('newUsers:', DATE_FORMAT(MIN(u.created_at), '%Y%m%d%H')) AS sourceEventId,
                   'NEXION_CORE' AS sourceSystem,
                   'nx_user' AS sourceTable,
                   '' AS actorName,
                   '全网' AS city,
                   COUNT(1) AS amount,
                   '人' AS amountUnit,
                   '完整小时注册用户聚合' AS sourceNote,
                   STR_TO_DATE(DATE_FORMAT(MIN(u.created_at), '%Y-%m-%d %H:00:00'), '%Y-%m-%d %H:%i:%s') AS occurredAt
              FROM nx_user u
             WHERE u.is_deleted = 0 AND u.created_at >= #{since} AND u.created_at < #{until}
             GROUP BY DATE_FORMAT(u.created_at, '%Y-%m-%d %H:00:00')
            HAVING COUNT(1) >= 10
             ORDER BY occurredAt ASC
            """)
    List<TrustedNovaSocialEvent> newUserSourceEvents(@Param("since") LocalDateTime since,
                                                     @Param("until") LocalDateTime until);

    @Update("""
            UPDATE nx_nova_social_event
               SET dispatch_count = dispatch_count + 1,
                   last_dispatched_at = #{dispatchedAt}, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0 AND status = 'ACTIVE' AND expires_at > #{dispatchedAt}
            """)
    int markSocialEventDispatched(@Param("id") long id, @Param("dispatchedAt") LocalDateTime dispatchedAt);
}
