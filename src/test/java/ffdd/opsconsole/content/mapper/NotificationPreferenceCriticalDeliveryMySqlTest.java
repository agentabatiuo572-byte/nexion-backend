package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Exercises the annotated notification mappers against a private disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_NOTIFICATION_DELIVERY_IT", matches = "true")
class NotificationPreferenceCriticalDeliveryMySqlTest {
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    @Test
    void criticalDisclosureIgnoresDisabledPreferencesAndRemainsUserScopedAndIdempotent() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
            try (var session = session(connection)) {
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                assertThat(campaign.insertDisclosureReackNotifications(
                        "DISCLOSURE-VN-20260911", "VN", "2026.09", List.of("VN"), now)).isEqualTo(1);
                // Replaying the same dispatch keeps the unique (biz_no, user_id) notification singular.
                campaign.insertDisclosureReackNotifications("DISCLOSURE-VN-20260911", "VN", "2026.09", List.of("VN"), now);
                assertThat(rows(connection, "DISCLOSURE-VN-20260911")).isEqualTo(1L);

                assertThat(campaign.markCampaignNotificationsDelivered("DISCLOSURE-VN-20260911", now)).isEqualTo(1);
                assertThat(campaign.markCampaignNotificationsDelivered("DISCLOSURE-VN-20260911", now)).isZero();
                assertThat(campaign.countNotificationsByBizNo("DISCLOSURE-VN-20260911")).isEqualTo(1);
                assertThat(campaign.selectUserNotifications(1L, null, null, 20)).extracting(view -> view.id()).hasSize(1);
                assertThat(campaign.selectUserNotifications(2L, null, null, 20)).isEmpty();
                assertThat(campaign.countUnreadByKindForUser(1L).stream().mapToLong(count -> count.unread()).sum()).isEqualTo(1L);
                assertThat(campaign.countUnreadByKindForUser(2L).stream().mapToLong(count -> count.unread()).sum()).isZero();

                long notificationId = scalar(connection, "SELECT id FROM nx_notification WHERE biz_no='DISCLOSURE-VN-20260911'");
                assertThat(campaign.markUserNotificationRead(2L, notificationId)).isZero();
                assertThat(campaign.countUnreadByKindForUser(1L).stream().mapToLong(count -> count.unread()).sum()).isEqualTo(1L);
                assertThat(campaign.markUserNotificationRead(1L, notificationId)).isEqualTo(1);
                assertThat(campaign.countUnreadByKindForUser(1L).stream().mapToLong(count -> count.unread()).sum()).isZero();
                assertThat(campaign.selectUserNotifications(1L, null, null, 20)).singleElement()
                        .satisfies(view -> assertThat(view.readAt()).isNotNull());
            }
        });
    }

    @Test
    void welcomeNotificationUsesRegisteredLanguageForDeliveredTitleAndBody() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO nx_user VALUES (3,'zh','CN','ACTIVE','2026-09-01',0)");
                sql.execute("UPDATE nx_user_preference SET notify_system=1");
                sql.execute("INSERT INTO nx_nova_channel VALUES ('welcome',1,0)");
                sql.execute("INSERT INTO nx_nova_template VALUES ('welcome',0,'PUBLISHED')");
            }
            LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);
            Map<Long, List<String>> expected = Map.of(
                    1L, List.of("Chào mừng đến NexGrid", "Bắt đầu với nhiệm vụ đầu tiên."),
                    2L, List.of("Welcome to NexGrid", "Start with your first earning task."),
                    3L, List.of("欢迎来到 NexGrid", "从第一项收益任务开始。"));
            try (var session = session(connection)) {
                var nova = session.getMapper(NovaSocialRuntimeMapper.class);
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                for (var entry : expected.entrySet()) {
                    String bizNo = "NOVA-WELCOME-IT-" + entry.getKey();
                    assertThat(nova.enqueueBusinessNotifications(
                            "welcome", "NOVA_WELCOME", "registration-" + entry.getKey(),
                            entry.getKey(), bizNo,
                            "欢迎来到 NexGrid", "从第一项收益任务开始。",
                            "Chào mừng đến NexGrid", "Bắt đầu với nhiệm vụ đầu tiên.",
                            "Welcome to NexGrid", "Start with your first earning task.",
                            "/earn", now.minusHours(1), now)).isEqualTo(1);
                    assertThat(nova.markNotificationsDelivered(bizNo, now)).isEqualTo(1);
                    assertThat(campaign.selectUserNotifications(entry.getKey(), null, null, 20))
                            .singleElement().satisfies(notification -> {
                                assertThat(notification.title()).isEqualTo(entry.getValue().get(0));
                                assertThat(notification.body()).isEqualTo(entry.getValue().get(1));
                            });
                }
            }
        });
    }

    @Test
    void welcomeUvelMigrationChangesOnlyOldDefaultTitlesAndIsReplaySafe() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("ALTER TABLE nx_nova_template ADD title_zh VARCHAR(255), "
                        + "ADD title_vi VARCHAR(255), ADD title_en VARCHAR(255), ADD updated_at DATETIME");
                sql.execute("INSERT INTO nx_nova_template VALUES "
                        + "('welcome',0,'PUBLISHED','欢迎来到 NexGrid','Chào mừng đến Nexion','Welcome to NexGrid',NULL),"
                        + "('market',0,'PUBLISHED','NexGrid 市场','NexGrid market','NexGrid market',NULL),"
                        + "('welcome',1,'PUBLISHED','欢迎来到 NexGrid','Chào mừng đến NexGrid','Welcome to NexGrid',NULL)");
                sql.execute("INSERT INTO nx_notification (biz_no,user_id,type,title,body,is_deleted) "
                        + "VALUES ('HISTORICAL-WELCOME',1,'NOVA_WELCOME','Chào mừng đến NexGrid','old',0)");

                String migration = Files.readString(Path.of(
                        "scripts/migrations/20260929_nova_welcome_uvel_title.sql"));
                assertThat(sql.executeUpdate(migration)).isEqualTo(1);
                assertThat(sql.executeUpdate(migration)).isZero();
                try (var rows = sql.executeQuery("SELECT channel_key,is_deleted,title_zh,title_vi,title_en "
                        + "FROM nx_nova_template ORDER BY channel_key,is_deleted")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("channel_key")).isEqualTo("market");
                    assertThat(rows.getString("title_zh")).isEqualTo("NexGrid 市场");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("title_zh")).isEqualTo("欢迎来到 UVEL");
                    assertThat(rows.getString("title_vi")).isEqualTo("Chào mừng đến UVEL");
                    assertThat(rows.getString("title_en")).isEqualTo("Welcome to UVEL");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("is_deleted")).isEqualTo(1);
                    assertThat(rows.getString("title_zh")).isEqualTo("欢迎来到 NexGrid");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("SELECT title FROM nx_notification WHERE biz_no='HISTORICAL-WELCOME'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("Chào mừng đến NexGrid");
                }
            }
        });
    }

    @Test
    void optionalCampaignAndNovaRemainFilteredWhenAllPreferencesAreDisabled() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO nx_nova_channel VALUES ('system',1,0)");
                sql.execute("INSERT INTO nx_nova_template VALUES ('system',0,'PUBLISHED')");
            }
            LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
            try (var session = session(connection)) {
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                var nova = session.getMapper(NovaSocialRuntimeMapper.class);
                assertThat(campaign.insertCampaignNotifications(
                        "CAMPAIGN-NORMAL-20260911", "commission", "normal", "all", 0,
                        "zh", "zh", "vi", "vi", "en", "en", "open", "/normal", now)).isZero();
                assertThat(nova.enqueueBusinessNotifications(
                        "system", "NOVA_COMMISSION", "event-1", 1L, "NOVA-NORMAL-20260911",
                        "zh", "zh", "vi", "vi", "en", "en", "/nova", now.minusHours(1), now)).isZero();
                assertThat(rows(connection, "CAMPAIGN-NORMAL-20260911")).isZero();
                assertThat(rows(connection, "NOVA-NORMAL-20260911")).isZero();
            }
        });
    }

    @Test
    void criticalCampaignKeepsItsFactAndBulkReadPathWhileAnAlreadyQueuedNormalRowStaysUndelivered() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);
            try (var session = session(connection); var sql = connection.createStatement()) {
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                assertThat(campaign.insertCampaignNotifications(
                        "CAMPAIGN-CRITICAL-20260911", "system", "critical", "vi", 0,
                        "zh", "zh", "vi", "vi", "en", "en", "open", "/critical", now)).isEqualTo(1);
                assertThat(campaign.markCampaignNotificationsDelivered("CAMPAIGN-CRITICAL-20260911", now)).isEqualTo(1);
                long criticalId = scalar(connection, "SELECT id FROM nx_notification WHERE biz_no='CAMPAIGN-CRITICAL-20260911'");
                assertThat(campaign.selectNotificationEventFactsByBizNo(
                        "CAMPAIGN-CRITICAL-20260911", "P1", now))
                        .extracting(fact -> fact.notificationId()).containsExactly(criticalId);
                assertThat(campaign.lockUnreadNotificationEventFacts(1L))
                        .extracting(fact -> fact.notificationId()).containsExactly(criticalId);
                assertThat(campaign.markAllUserNotificationsRead(1L, List.of(criticalId))).isEqualTo(1);
                assertThat(campaign.lockUnreadNotificationEventFacts(1L)).isEmpty();

                sql.execute("INSERT INTO nx_notification (biz_no,user_id,type,priority,title,body,read_flag,push_status,push_attempts,next_push_at,created_at,updated_at,is_deleted) "
                        + "VALUES ('PREQUEUED-NORMAL-20260911',1,'COMMISSION','normal','normal','normal',0,'QUEUED',0,'2026-09-11 12:00:00','2026-09-11 12:00:00','2026-09-11 12:00:00',0)");
                assertThat(campaign.markCampaignNotificationsDelivered("PREQUEUED-NORMAL-20260911", now)).isZero();
                assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE biz_no='PREQUEUED-NORMAL-20260911' AND push_status='QUEUED'"))
                        .isEqualTo(1L);
            }
        });
    }

    @Test
    void deliveredBusinessRowsAreDurableIdempotentMandatoryAndRollbackTogether() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            connection.setAutoCommit(false);
            try (var session = session(connection)) {
                var notices = session.getMapper(BusinessNotificationMapper.class);
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                notices.deliver("STATE-1",1L,"WITHDRAWAL","high","Đã hoàn tất rút tiền","safe","Xem chi tiết","/pages/me/wallet");
                session.commit();
                notices.deliver("STATE-1",1L,"WITHDRAWAL","high","duplicate","safe","View details","/pages/me/wallet");
                session.commit();
                assertThat(rows(connection,"STATE-1")).isEqualTo(1);
                assertThat(campaign.countUnreadByKindForUser(1L).stream().mapToLong(count -> count.unread()).sum()).isEqualTo(1);
                assertThat(campaign.countUnreadByKindForUser(2L).stream().mapToLong(count -> count.unread()).sum()).isZero();
                assertThat(campaign.selectUserNotifications(1L,null,null,20)).singleElement()
                        .satisfies(n -> assertThat(n.title()).isEqualTo("Đã hoàn tất rút tiền"));
                notices.deliver("ROLLED-BACK",1L,"WITHDRAWAL","high","failed","safe","View details","/pages/me/wallet");
                session.rollback();
                assertThat(rows(connection,"ROLLED-BACK")).isZero();
                assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification WHERE pushed_at IS NOT NULL AND push_status='DELIVERED'")).isEqualTo(1);
                notices.deliver("CRITICAL",1L,"SYSTEM","critical","keep","safe","View details","/pages/me/security");
                session.commit();
                var facts = campaign.lockUnreadNotificationEventFacts(1L);
                assertThat(campaign.markAllUserNotificationsRead(1L,facts.stream().map(f -> f.notificationId()).toList())).isEqualTo(2);
                assertThat(campaign.clearReadUserNotifications(1L)).isEqualTo(1);
                session.commit();
                assertThat(campaign.selectUserNotifications(1L,null,null,20)).singleElement()
                        .satisfies(n -> assertThat(n.priority()).isEqualTo("critical"));
            }
        });
    }

    @Test
    void paymentProducersDeliverThreeLanguagesDespiteHistoricalSystemMute() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            connection.createStatement().execute("INSERT INTO nx_user VALUES (3,'zh','CN','ACTIVE','2026-09-01',0)");
            try (var session = session(connection)) {
                var topup = session.getMapper(ffdd.opsconsole.finance.hdpay.HdPayOrderMapper.class);
                var card = session.getMapper(ffdd.opsconsole.user.mapper.UserPaymentMethodMapper.class);
                var campaign = session.getMapper(NotificationCampaignMapper.class);
                for (long userId : List.of(1L,2L,3L)) {
                    topup.insertDepositNotification("HDPAY:IT-"+userId,userId,new java.math.BigDecimal("12.50"));
                    card.queueNotification(userId,"PAYMENT_METHOD_UNBOUND:"+userId,"支付方式已解绑","支付方式已从账户解绑。","/pages/me/wallet-cards");
                    assertThat(campaign.countUnreadByKindForUser(userId).stream().mapToLong(count -> count.unread()).sum()).isEqualTo(2);
                    assertThat(campaign.selectUserNotifications(userId,null,null,20)).allSatisfy(n -> assertThat(n.ctaHref()).startsWith("/pages/me/"));
                }
                assertThat(campaign.selectUserNotifications(1L,null,null,20).get(0).title()).isEqualTo("Đã gỡ phương thức thanh toán");
                assertThat(campaign.selectUserNotifications(2L,null,null,20).get(0).title()).isEqualTo("Payment method removed");
                assertThat(campaign.selectUserNotifications(3L,null,null,20).get(0).title()).isEqualTo("支付方式已解绑");
                assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification WHERE pushed_at IS NOT NULL AND push_status='DELIVERED'")).isEqualTo(6);
            }
        });
    }

    @Test
    void legacyRepairTouchesOnlyKnownUnreadPendingRowsAndCanBeReplayed() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try (var sql=connection.createStatement()) {
                sql.execute("ALTER TABLE nx_user_preference ADD updated_at DATETIME");
                sql.execute("INSERT INTO nx_notification(biz_no,user_id,type,priority,title,body,read_flag,push_status,is_deleted) VALUES "
                        +"('HDPAY:old',1,'WALLET','high','old','old',0,'PENDING',0),"
                        +"('PAYMENT_METHOD_REBIND:old',2,'PAYMENT_METHOD','high','old','old',0,'PENDING',0),"
                        +"('HDPAY:read',1,'WALLET','high','old','old',1,'PENDING',0),"
                        +"('HDPAY:deleted',1,'WALLET','high','old','old',0,'PENDING',1),"
                        +"('UNRELATED',1,'WALLET','high','old','old',0,'PENDING',0)");
                String migration=Files.readString(Path.of("scripts/migrations/20261002_app_business_notifications.sql"));
                String repair=migration.substring(0,migration.indexOf("INSERT INTO nx_event_schema_registry"));
                for(int run=0;run<2;run++) for(String statement:repair.split(";")) if(!statement.isBlank()) sql.execute(statement);
                assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification WHERE push_status='DELIVERED'")).isEqualTo(2);
                assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification WHERE push_status='PENDING'")).isEqualTo(3);
                assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_user_preference WHERE notify_system=0")).isZero();
            }
        });
    }

    @Test
    void realConsumerRollsBackNoticeOnReceiptFailureThenRetriesOnce() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try(var sql=connection.createStatement()) {
                sql.execute("CREATE TABLE nx_withdrawal_order(withdrawal_no VARCHAR(64) PRIMARY KEY,user_id BIGINT,is_deleted INT)");
                sql.execute("INSERT INTO nx_withdrawal_order VALUES('WD-1',1,0)");
                sql.execute("CREATE TABLE nx_event_consumer_delivery(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(128),consumer_group VARCHAR(128),topic VARCHAR(128),msg_id VARCHAR(128),event_type VARCHAR(128),aggregate_type VARCHAR(64),aggregate_id VARCHAR(128),status VARCHAR(32),attempt_count INT,rocketmq_reconsume_times INT,next_retry_at DATETIME,processed_at DATETIME,dead_at DATETIME,created_commissions INT,last_error VARCHAR(512),first_seen_at DATETIME,last_seen_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_delivery(event_id,consumer_group)) ENGINE=InnoDB");
                sql.execute("CREATE TRIGGER reject_success BEFORE UPDATE ON nx_event_consumer_delivery FOR EACH ROW BEGIN IF NEW.status='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='receipt unavailable'; END IF; END");
            }
            var dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:mysql://"+System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")+"/"+connection.getCatalog()+OPTIONS,
                    "root",System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD",""));
            Configuration cfg=new Configuration();
            cfg.setMapUnderscoreToCamelCase(true);
            cfg.setEnvironment(new org.apache.ibatis.mapping.Environment("spring-it",
                    new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),dataSource));
            cfg.addMapper(BusinessNotificationMapper.class);
            cfg.addMapper(ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper.class);
            var sqlSession=new org.mybatis.spring.SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
            var delivery=new ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService(
                    sqlSession.getMapper(ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper.class),
                    new ffdd.opsconsole.shared.outbox.EventConsumerDeliveryProperties());
            var consumer=new ffdd.opsconsole.content.application.BusinessNotificationEventConsumer(delivery,
                    sqlSession.getMapper(BusinessNotificationMapper.class),new com.fasterxml.jackson.databind.ObjectMapper(),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            var event=new ffdd.opsconsole.shared.outbox.EventOutboxMessage();
            event.setEventId("event-rollback");event.setEventType("withdraw.confirmed");event.setAggregateType("WITHDRAWAL");
            event.setAggregateId("WD-1");event.setServerAuthoritative(true);event.setPayload("{\"user_id\":1}");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onOutboxMessage(event)).isInstanceOf(RuntimeException.class);
            assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification")).isZero();
            assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_event_consumer_delivery WHERE status='FAILED'")).isEqualTo(1);
            connection.createStatement().execute("DROP TRIGGER reject_success");
            consumer.onOutboxMessage(event);
            consumer.onOutboxMessage(event);
            assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification WHERE push_status='DELIVERED'")).isEqualTo(1);
            assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_event_consumer_delivery WHERE status='SUCCESS' AND attempt_count=2")).isEqualTo(1);
            event.setEventId("different-transport-id");
            consumer.onOutboxMessage(event);
            assertThat(scalar(connection,"SELECT COUNT(*) FROM nx_notification")).isEqualTo(1);
        });
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void actualControllerReadAndCtaCommitOnlyWithProductionSchemasAndOwnedIdentity() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            String ddl = Files.readString(Path.of("scripts/schema.sql"));
            try (var sql = connection.createStatement()) {
                for (String table : List.of("nx_event_schema_revision", "nx_event_schema_registry",
                        "nx_event_schema_property", "nx_event_domain_extension", "nx_event_outbox")) {
                    var create = java.util.regex.Pattern.compile(
                            "CREATE TABLE IF NOT EXISTS " + table + "\\s*\\([\\s\\S]*?;").matcher(ddl);
                    assertThat(create.find()).as(table).isTrue();
                    sql.execute(create.group());
                }
                sql.execute("CREATE TABLE nx_admin_event_lifecycle(event_name VARCHAR(128) PRIMARY KEY,lifecycle_state VARCHAR(32),is_deleted INT DEFAULT 0)");
                sql.execute("UPDATE nx_user_preference SET notify_commission=1");
                sql.execute("DROP TABLE nx_nova_business_event_receipt");
                sql.execute("INSERT INTO nx_notification(id,biz_no,user_id,type,priority,title,body,cta_href,read_flag,push_status,created_at,is_deleted) VALUES "
                        + "(1,'NOVA-team_event-source-1',1,'NOVA_COMMISSION','normal','one','one','/pages/team/team',0,'DELIVERED',NOW(),0),"
                        + "(2,'BUSINESS:two',1,'WITHDRAWAL','high','two','two','/pages/me/wallet',0,'DELIVERED',NOW(),0),"
                        + "(3,'BUSINESS:other',2,'WITHDRAWAL','high','other','other','/pages/me/wallet',0,'DELIVERED',NOW(),0)");
            }
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:mysql://" + System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT") + "/" + connection.getCatalog() + OPTIONS,
                    "root", System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
            Configuration cfg = new Configuration(new org.apache.ibatis.mapping.Environment("notification-http-it",
                    new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), source));
            cfg.setMapUnderscoreToCamelCase(true);
            cfg.addMapper(NotificationCampaignMapper.class);
            cfg.addMapper(NotificationCapRuleMapper.class);
            cfg.addMapper(NovaSocialRuntimeMapper.class);
            cfg.addMapper(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class);
            cfg.addMapper(ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper.class);
            var session = new org.mybatis.spring.SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
            session.getMapper(NovaSocialRuntimeMapper.class).createBusinessEventReceiptTable();
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO nx_nova_business_event_receipt(channel_key,source_event_id,event_name,status) VALUES('team_event','source-1','commission.paid','DELIVERED')");
            }
            var events = new ffdd.opsconsole.shared.outbox.EventOutboxService(
                    session.getMapper(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class),
                    new com.fasterxml.jackson.databind.ObjectMapper(), new ffdd.opsconsole.shared.outbox.OutboxProperties(),
                    new ffdd.opsconsole.platform.application.A4RuntimePolicyService(
                            new ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository(
                                    session.getMapper(ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper.class))));
            var novaFactory = new org.springframework.aop.framework.ProxyFactory(
                    new ffdd.opsconsole.content.infrastructure.MybatisNovaSocialRuntimeRepository(
                            session.getMapper(NovaSocialRuntimeMapper.class)));
            novaFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var nova = (ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository) novaFactory.getProxy();
            var target = new ffdd.opsconsole.content.application.AppNotificationService(
                    new ffdd.opsconsole.content.infrastructure.MybatisNotificationCampaignRepository(
                            session.getMapper(NotificationCampaignMapper.class), session.getMapper(NotificationCapRuleMapper.class)),
                    events, nova);
            var factory = new org.springframework.aop.framework.ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var service = (ffdd.opsconsole.content.application.AppNotificationService) factory.getProxy();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new ffdd.opsconsole.content.web.AppNotificationController(service))
                    .setControllerAdvice(new ffdd.opsconsole.shared.exception.GlobalExceptionHandler(null),
                            new ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice()).build();
            var owner = identity(1, "USER");

            // Reproduce the observed HTTP 422 and prove that its transaction rolls back the read flag.
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/notifications/1/read").principal(owner))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                            .value("A4_SCHEMA_NOT_REGISTERED"));
            assertThat(scalar(connection, "SELECT read_flag FROM nx_notification WHERE id=1")).isZero();
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_event_outbox")).isZero();

            for (int replay = 0; replay < 2; replay++) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.FileSystemResource("scripts/migrations/20260722_i3_a4_event_closure.sql"));
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/notifications/1/read").principal(identity(2, "USER")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/notifications/1/read").principal(identity(1, "ADMIN")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            for (int replay = 0; replay < 2; replay++) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/notifications/1/read").principal(owner))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            }
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification WHERE id=1 AND read_flag=1 AND read_at IS NOT NULL")).isEqualTo(1);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='notification.read'")).isEqualTo(1);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/notifications/read-all").principal(owner))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").value(1));
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='notification.read'")).isEqualTo(2);
            assertThat(scalar(connection, "SELECT read_flag FROM nx_notification WHERE id=3")).isZero();

            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_nova_click BEFORE INSERT ON nx_event_outbox FOR EACH ROW BEGIN IF NEW.event_name='nova.push_clicked' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='click outbox unavailable'; END IF; END");
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/notifications/1/actions").principal(owner)
                    .header("Idempotency-Key", "real-notification-cta-1")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"action\":\"cta\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError());
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification_action_receipt")).isZero();
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='notification.swipe_action_taken'")).isZero();
            try (var sql = connection.createStatement()) { sql.execute("DROP TRIGGER reject_nova_click"); }

            for (int replay = 0; replay < 2; replay++) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/notifications/1/actions").principal(owner)
                        .header("Idempotency-Key", "real-notification-cta-1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"action\":\"cta\"}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.route").value("/pages/team/team"));
            }
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_notification_action_receipt")).isEqualTo(1);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='notification.swipe_action_taken'")).isEqualTo(1);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='nova.push_clicked' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.channel'))='team_event'")).isEqualTo(1);
        });
    }

    private static org.springframework.security.core.Authentication identity(long id, String type) {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(Long.toString(id), null, List.of());
        authentication.setDetails(Map.of("subjectType", type));
        return authentication;
    }

    @Test
    void novaCategoryChangesKeepExactChannelCooldownAndClickAttribution() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            LocalDateTime now=LocalDateTime.of(2026,10,2,12,0);
            try(var sql=connection.createStatement()) {
                sql.execute("UPDATE nx_user_preference SET notify_commission=1,notify_market=1");
                sql.execute("INSERT INTO nx_nova_channel VALUES('team_event',1,0),('market',1,0)");
                sql.execute("INSERT INTO nx_nova_template VALUES('team_event',0,'PUBLISHED'),('market',0,'PUBLISHED')");
                sql.execute("INSERT INTO nx_nova_business_event_receipt VALUES('teamXevent','shared'),('market-extra','shared'),('team_event','fresh'),('market','fresh'),('market','batch')");
                sql.execute("INSERT INTO nx_notification(biz_no,user_id,type,priority,title,body,read_flag,push_status,created_at,is_deleted) VALUES "
                        +"('NOVA-teamXevent-shared',1,'NOVA_COMMISSION','normal','wrong','wrong',0,'DELIVERED','2026-10-02 12:00:00',0),"
                        +"('NOVA-market-extra-shared',1,'NOVA_MARKET','normal','wrong','wrong',0,'DELIVERED','2026-10-02 12:00:00',0)");
            }
            try(var session=session(connection)) {
                var nova=session.getMapper(NovaSocialRuntimeMapper.class);
                assertThat(nova.latestNotificationAtByType("NOVA_TEAM_EVENT")).isNull();
                assertThat(nova.latestNotificationAtByType("NOVA_MARKET")).isNull();
                assertThat(nova.enqueueBusinessNotifications("team_event","NOVA_COMMISSION","fresh",1L,"NOVA-team_event-fresh",
                        "zh","zh","vi","vi","en","en","/pages/team/team",now.minusHours(1),now)).isEqualTo(1);
                assertThat(nova.enqueueBusinessNotificationBatch("market","NOVA_MARKET","NOVA-market-batch-B2",0L,2L,
                        "zh","zh","vi","vi","en","en","/pages/market/market",now.minusHours(1),now)).isEqualTo(2);
                assertThat(nova.latestNotificationAtByType("NOVA_TEAM_EVENT")).isEqualTo(now);
                assertThat(nova.latestNotificationAtByType("NOVA_MARKET")).isEqualTo(now);
                assertThat(nova.enqueueBusinessNotifications("team_event","NOVA_TEAM","another",1L,"NOVA-team_event-another",
                        "zh","zh","vi","vi","en","en","/pages/team/team",now.minusHours(1),now)).isZero();
                long notificationId=scalar(connection,"SELECT id FROM nx_notification WHERE biz_no='NOVA-team_event-fresh'");
                assertThat(nova.notificationChannel(1L,notificationId)).isEqualTo("team_event");
                assertThat(nova.notificationChannel(2L,notificationId)).isNull();
            }
        });
    }

    @Test
    void clearReadPreservesAnExplicitlyLockedNoncriticalTier() throws Exception {
        inSchema(connection -> {
            seedUsersAndPreferences(connection);
            try(var sql=connection.createStatement()) {
                sql.execute("INSERT INTO nx_notification_cap_rule VALUES('high',1,1,0)");
                sql.execute("INSERT INTO nx_notification(biz_no,user_id,type,priority,title,body,read_flag,push_status,created_at,is_deleted) VALUES('LOCKED-HIGH',1,'WALLET','high','keep','keep',1,'READ',NOW(),0)");
            }
            try(var session=session(connection)) {
                var campaign=session.getMapper(NotificationCampaignMapper.class);
                assertThat(campaign.clearReadUserNotifications(1L)).isZero();
                assertThat(campaign.selectUserNotifications(1L,null,null,20)).hasSize(1);
            }
        });
    }

    private static org.apache.ibatis.session.SqlSession session(Connection connection) {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("isolated",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                new org.apache.ibatis.datasource.unpooled.UnpooledDataSource()));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NotificationCampaignMapper.class);
        configuration.addMapper(BusinessNotificationMapper.class);
        configuration.addMapper(ffdd.opsconsole.finance.hdpay.HdPayOrderMapper.class);
        configuration.addMapper(ffdd.opsconsole.user.mapper.UserPaymentMethodMapper.class);
        configuration.addMapper(NovaSocialRuntimeMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(connection);
    }

    private static void seedUsersAndPreferences(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,language VARCHAR(32),country_code VARCHAR(8),status VARCHAR(32),created_at DATETIME,is_deleted INT)");
            sql.execute("CREATE TABLE nx_user_preference (user_id BIGINT PRIMARY KEY,notify_commission INT,notify_team INT,notify_staking INT,notify_market INT,notify_genesis INT,notify_system INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_notification (id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_no VARCHAR(128),user_id BIGINT,type VARCHAR(32),priority VARCHAR(16),title VARCHAR(128),body VARCHAR(512),cta_label VARCHAR(128),cta_href VARCHAR(256),read_flag INT,read_at DATETIME NULL,push_status VARCHAR(32),pushed_at DATETIME NULL,push_attempts INT,next_push_at DATETIME NULL,created_at DATETIME,updated_at DATETIME,is_deleted INT,UNIQUE KEY uk_notification_biz_user (biz_no,user_id)) ENGINE=InnoDB");
            sql.execute("CREATE TABLE nx_notification_cap_rule(tier VARCHAR(16),locked INT,status INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_nova_business_event_receipt(channel_key VARCHAR(64),source_event_id VARCHAR(64))");
            sql.execute("CREATE TABLE nx_config_item (config_key VARCHAR(128),config_value VARCHAR(128),status INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_nova_channel (channel_key VARCHAR(32) PRIMARY KEY,enabled INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_nova_template (channel_key VARCHAR(32),is_deleted INT,status VARCHAR(32))");
            sql.execute("INSERT INTO nx_user VALUES (1,'vi','VN','ACTIVE','2026-09-01',0),(2,'en','CA','ACTIVE','2026-09-01',0)");
            sql.execute("INSERT INTO nx_user_preference VALUES (1,0,0,0,0,0,0,0),(2,0,0,0,0,0,0,0)");
        }
    }

    private static long rows(Connection connection, String bizNo) throws Exception {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM nx_notification WHERE biz_no=?")) {
            statement.setString(1, bizNo);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Connection connect(String schema) throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).as("explicit local test-server endpoint").matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}");
        assertThat(endpoint).as("business MySQL port is excluded").doesNotEndWith(":3306");
        return DriverManager.getConnection("jdbc:mysql://" + endpoint + "/" + schema + OPTIONS, "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = "nexion_notification_delivery_it_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("nexion_notification_delivery_it_[a-f0-9]{32}")) throw new IllegalStateException("unsafe test schema");
        try (Connection admin = connect("")) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection connection = connect(schema)) {
                test.run(connection);
            } finally {
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(Connection connection) throws Exception; }
}
