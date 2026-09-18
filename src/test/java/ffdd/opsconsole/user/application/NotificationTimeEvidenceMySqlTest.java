package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicitly opt-in; only a fresh schema on the task's loopback 18362 instance. */
@EnabledIfEnvironmentVariable(named="NEXION_C1_AUDIT_MYSQL", matches="true")
class NotificationTimeEvidenceMySqlTest {
    private static final String SCHEMA="bug3_evidence_"+UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory factory;
    private static DriverManagerDataSource source(String schema) {
        if (!schema.isEmpty() && !schema.equals(SCHEMA)) throw new IllegalArgumentException("UNOWNED_SCHEMA");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:18362/"+schema+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "root", System.getenv("NEXION_C1_AUDIT_MYSQL_PASSWORD"));
    }
    @BeforeAll static void create() {
        new JdbcTemplate(source("")).execute("CREATE DATABASE "+SCHEMA);
        var dataSource=source(SCHEMA); jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE nx_notification(id BIGINT PRIMARY KEY,user_id BIGINT,biz_no VARCHAR(96),type VARCHAR(32),push_status VARCHAR(32),created_at DATETIME,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_event_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64),aggregate_type VARCHAR(64),aggregate_id VARCHAR(96),event_name VARCHAR(96),is_server_authoritative BOOLEAN,payload JSON,event_ts DATETIME(3),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_nova_business_event_receipt(channel_key VARCHAR(64),source_event_id VARCHAR(64),event_name VARCHAR(96),status VARCHAR(32),notification_count INT)");
        var config=new Configuration(new Environment("bug3-evidence-only",new JdbcTransactionFactory(),dataSource));
        config.addMapper(NotificationTimeEvidenceMapper.class); factory=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup() {
        if(SCHEMA.matches("bug3_evidence_[a-f0-9]{32}")) new JdbcTemplate(source("")).execute("DROP DATABASE IF EXISTS "+SCHEMA);
    }
    @Test void realMapperMapsRecordsAndPreviewLeavesPersistedNotificationUntouched() throws Exception {
        String sourceId="b".repeat(32),biz="NOVA-welcome-"+sourceId;
        long ts=Instant.parse("2026-09-18T03:38:00Z").toEpochMilli();
        jdbc.update("INSERT INTO nx_notification VALUES(99,7,?,'NOVA_WELCOME','DELIVERED','2026-09-18 03:38:00',0)",biz);
        insert(sourceId,"USER_REGISTRATION","7","auth.register_completed",ts-1000,Map.of());
        insert("c".repeat(32),"NOVA_NOTIFICATION","99","nova.push_sent",ts,Map.of("notification_id",99,"channel","welcome"));
        insert("d".repeat(32),"NOTIFICATION","99","notification.delivered",ts+100,Map.of("notification_id",99,"campaign_id",biz,"kind","nova_welcome"));
        jdbc.update("INSERT INTO nx_nova_business_event_receipt VALUES('welcome',?,'auth.register_completed','DELIVERED',1)",sourceId);
        try(var session=factory.openSession()) {
            var mapper=session.getMapper(NotificationTimeEvidenceMapper.class);
            assertThat(mapper.notification(8L,99L)).isNull();
            var users=mock(UserOpsRepository.class); when(users.findUserIdByLookupKey("U7")).thenReturn(Optional.of(7L));
            var roles=mock(AdminOperatorRoleResolver.class); when(roles.resolveCode()).thenReturn("SUPER_ADMIN");
            var service=new NotificationTimeEvidenceService(users,mapper,roles,new ObjectMapper(),Clock.fixed(Instant.parse("2026-09-18T04:00:00Z"),DateTimeFormatConfig.BUSINESS_ZONE));
            assertThat(service.preview("U7",99L).getData().status()).isEqualTo("MATCHED");
            assertThat(service.preview("U7",99L).getData().deliveryFactTime()).isEqualTo(LocalDateTime.of(2026,9,18,11,38));
        }
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM nx_notification WHERE id=99",String.class)).isEqualTo("2026-09-18 03:38:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class)).isEqualTo(3);
    }
    @Test void receiptChannelCollationCannotTurnDifferentChannelIntoWelcome() {
        String sourceId="e".repeat(32);
        for(String channel:List.of("WELCOME", "welcóme", "welcome ")) {
            jdbc.update("INSERT INTO nx_nova_business_event_receipt VALUES(?,?,'auth.register_completed','DELIVERED',1)",channel,sourceId);
        }
        try(var session=factory.openSession()) {
            assertThat(session.getMapper(NotificationTimeEvidenceMapper.class).receipts(sourceId)).isEmpty();
        }
    }
    private void insert(String id,String aggregate,String aggregateId,String name,long ts,Map<String,Object> fields) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>(fields);
        payload.put("event_id",id); payload.put("event_name",name); payload.put("ts",ts); payload.put("user_id",7);
        payload.put("is_server_authoritative",true);
        jdbc.update("INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_name,is_server_authoritative,payload,event_ts) VALUES(?,?,?,?,true,?,?)",
                id,aggregate,aggregateId,name,new ObjectMapper().writeValueAsString(payload),LocalDateTime.ofInstant(Instant.ofEpochMilli(ts),DateTimeFormatConfig.BUSINESS_ZONE));
    }
}

