package ffdd.opsconsole.content.push;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Installation;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Private disposable schema only. No application server, real accounts, CID or external send. */
@EnabledIfEnvironmentVariable(named="NEXION_NATIVE_PUSH_MYSQL_URL", matches="jdbc:mysql:.*")
class NativePushMySqlTest {
    static final String NOW = "DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR)";
    static final String INSTALL = "installation-test";

    @Test void cutoverDiscoveryIsIdempotentAndPreservesInboxStatusAndPreferences() throws Exception {
        inSchema(c -> {
            try (var s = mapperSession(c)) {
                var m=s.getMapper(NativePushMapper.class);
                m.insert(installation(7,"chain",1));
                sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,cta_href,created_at,is_deleted,priority,push_status) VALUES "
                        +"('NATIVE1:new',7,'FUNDS_RESULT','/pages/me/wallet-bills',"+NOW+",0,'normal','DELIVERED'),"
                        +"('NATIVE1:other',8,'FUNDS_RESULT','/pages/me/wallet-bills',"+NOW+",0,'critical','DELIVERED'),"
                        +"('CAMPAIGN:new',7,'MARKET','https://example.com',"+NOW+",0,'critical','DELIVERED')");
                long cut=m.lockCursor(); assertThat(cut).isEqualTo(1);
                assertThat(m.discover(cut,Long.MAX_VALUE,0)).isEqualTo(1);
                assertThat(m.discover(cut,Long.MAX_VALUE,0)).isZero();
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery")).isEqualTo(1);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE push_status='DELIVERED'")).isEqualTo(4);
                sql(c,"UPDATE nx_user_preference SET notify_system=0 WHERE user_id=7");
                sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,cta_href,created_at,is_deleted,priority,push_status) VALUES "
                        +"('NATIVE1:disabled',7,'FUNDS_RESULT','/pages/me/wallet-bills',"+NOW+",0,'normal','DELIVERED'),"
                        +"('NATIVE1:critical',7,'ACCOUNT_SECURITY','/pages/me/security',"+NOW+",0,'critical','DELIVERED'),"
                        +"('HDPAY:new',7,'FUNDS_RESULT','/pages/me/wallet-bills',"+NOW+",0,'critical','DELIVERED')");
                sql(c,"INSERT INTO nx_business_notification_fact VALUES('topup','HDPAY:new',7)");
                assertThat(m.discover(cut,Long.MAX_VALUE,0)).isEqualTo(2);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery")).isEqualTo(3);
                assertThat(m.ownedNotification(2,8)).isNull();
                assertThat(m.ownedNotification(2,7).type()).isEqualTo("FUNDS_RESULT");
                m.cancelPending(INSTALL); m.update(new Installation(INSTALL,"fcm:"+"x".repeat(150),"FCM","hash",8L,"other","android","granted","1",1,true),1);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE status='CANCELLED'")).isEqualTo(3);
                assertThat(m.lockInstallation(INSTALL).bindingEpoch()).isEqualTo(2);
                // Existing queued old-user notifications cannot be recreated for a new binding.
                assertThat(m.discover(cut,Long.MAX_VALUE,0)).isZero();
                s.commit();
            }
        });
    }

    @Test void claimsSkipLockedRecoverStaleLeaseAndFenceOldWorkerAndOpenedReceipt() throws Exception {
        inSchema(c -> {
            String catalog=c.getCatalog();
            try (var s=mapperSession(c)) {
                var m=s.getMapper(NativePushMapper.class); m.insert(installation(7,"chain",1));
                sql(c,"INSERT INTO nx_native_push_delivery(notification_id,installation_id,user_id,binding_epoch,status,attempts,next_attempt_at,created_at,updated_at) VALUES"
                        +"(2,'"+INSTALL+"',7,1,'QUEUED',0,"+NOW+","+NOW+","+NOW+")");
                s.commit(); c.setAutoCommit(false);
                var d=m.lockNext(); assertThat(d).isNotNull(); m.claim(d.id(),"first");
                try (var other=connect()) {
                    other.setCatalog(catalog); other.setAutoCommit(false);
                    try (var second=mapperSession(other)) { assertThat(second.getMapper(NativePushMapper.class).lockNext()).isNull(); second.rollback(); }
                }
                s.commit();
                sql(c,"UPDATE nx_native_push_delivery SET lease_until=DATE_SUB("+NOW+",INTERVAL 1 SECOND)"); c.commit();
                s.clearCache(); assertThat(m.expireAmbiguousClaims()).isEqualTo(1); assertThat(m.lockNext()).isNull();
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE status='UNKNOWN'")).isEqualTo(1);
                // An explicit operator-reviewed retry can establish a new fenced claim.
                sql(c,"UPDATE nx_native_push_delivery SET status='RETRY'"); m.claim(d.id(),"second"); s.commit();
                assertThat(m.finish(d.id(),"first","ACCEPTED",null,"bad-task",0)).isZero();
                assertThat(m.finish(d.id(),"second","ACCEPTED",null,"task",0)).isEqualTo(1);
                assertThat(m.opened(2,installation(8,"other",1))).isZero();
                assertThat(m.opened(2,installation(7,"chain",2))).isZero();
                assertThat(m.opened(2,installation(7,"chain",1))).isEqualTo(1);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE accepted_at IS NOT NULL AND opened_at IS NOT NULL")).isEqualTo(1);
                s.commit();
            }
        });
    }

    @Test void activeSessionChecksRefreshChainFixedContextAudienceRevocationAndNewerAccountLogin() throws Exception {
        inSchema(c -> {
            try (var s=mapperSession(c)) {
                var m=s.getMapper(NativePushMapper.class); var i=installation(7,"chain",1); m.insert(i);
                sql(c,"INSERT INTO nx_app_session_state VALUES(7,1,'chain','"+INSTALL+"',0)");
                sql(c,"INSERT INTO nx_user_session VALUES(1,7,'chain','APP','"+INSTALL+"',1,0,0,NULL,NULL,DATE_ADD("+NOW+",INTERVAL 1 DAY),"+NOW+","+NOW+")");
                assertThat(m.activeSession(i,0,30)).isEqualTo(1);
                assertThat(m.activeSession(i,1,30)).isZero();
                sql(c,"UPDATE nx_user_session SET revoked_at="+NOW+" WHERE id=1"); s.clearCache(); assertThat(m.activeSession(i,0,30)).isZero();
                sql(c,"INSERT INTO nx_user_session VALUES(2,7,'chain','APP','"+INSTALL+"',1,0,0,NULL,NULL,DATE_ADD("+NOW+",INTERVAL 1 DAY),"+NOW+","+NOW+")");
                s.clearCache(); assertThat(m.activeSession(i,0,30)).isEqualTo(1);
                sql(c,"INSERT INTO nx_user_session VALUES(3,8,'other-chain','APP','"+INSTALL+"',1,0,0,NULL,NULL,DATE_ADD("+NOW+",INTERVAL 1 DAY),"+NOW+","+NOW+")");
                s.clearCache(); assertThat(m.activeSession(i,0,30)).isZero();
                s.commit();
            }
        });
    }

    @Test void recentCriticalSecurityCanReachNewCurrentBindingButNeverAnOldRevokedInstallation() throws Exception {
        inSchema(c -> {
            try (var s=mapperSession(c)) {
                var m=s.getMapper(NativePushMapper.class);
                sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,cta_href,created_at,is_deleted,priority,push_status) VALUES "
                        +"('NATIVE1:password-before-bind',7,'ACCOUNT_SECURITY','/pages/me/security',DATE_SUB("+NOW+",INTERVAL 1 MINUTE),0,'critical','DELIVERED'),"
                        +"('NATIVE1:funds-before-bind',7,'FUNDS_RESULT','/pages/me/wallet-bills',DATE_SUB("+NOW+",INTERVAL 1 MINUTE),0,'critical','DELIVERED'),"
                        +"('NATIVE1:security-expired',7,'ACCOUNT_SECURITY','/pages/me/security',DATE_SUB("+NOW+",INTERVAL 25 HOUR),0,'critical','DELIVERED')");
                var current=installation(7,"current-chain",1); m.insert(current);
                sql(c,"INSERT INTO nx_app_session_state VALUES(7,2,'current-chain','"+INSTALL+"',0)");
                sql(c,"INSERT INTO nx_user_session VALUES(1,7,'removed-chain','APP','old-installation',1,0,0,"+NOW+",'SECURITY_REVOKED',DATE_ADD("+NOW+",INTERVAL 1 DAY),"+NOW+","+NOW+"),"
                        +"(2,7,'current-chain','APP','"+INSTALL+"',2,0,0,NULL,NULL,DATE_ADD("+NOW+",INTERVAL 1 DAY),"+NOW+","+NOW+")");
                assertThat(m.discover(m.lockCursor(),Long.MAX_VALUE,0)).isEqualTo(1);
                assertThat(m.discover(m.lockCursor(),Long.MAX_VALUE,0)).isZero();
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE notification_id=2")).isEqualTo(1);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE notification_id IN (3,4)")).isZero();
                assertThat(m.activeSession(current,0,30)).isEqualTo(1);
                var removed=new Installation("old-installation","fcm:"+"old".repeat(50),"FCM","hash",7L,"removed-chain","android","granted","1",1,true);
                assertThat(m.activeSession(removed,0,30)).isZero();
                s.commit();
            }
        });
    }

    @Test void actualOpenRecordsEvidenceForUnknownAttemptWithoutInventingProviderAcceptance() throws Exception {
        inSchema(c -> {
            try (var s=mapperSession(c)) {
                var m=s.getMapper(NativePushMapper.class); var current=installation(7,"current-chain",1); m.insert(current);
                sql(c,"INSERT INTO nx_native_push_delivery(notification_id,installation_id,user_id,binding_epoch,status,attempts,next_attempt_at,created_at,updated_at) VALUES"
                        +"(2,'"+INSTALL+"',7,1,'UNKNOWN',1,"+NOW+","+NOW+","+NOW+"),"
                        +"(3,'"+INSTALL+"',7,1,'QUEUED',0,"+NOW+","+NOW+","+NOW+")");
                assertThat(m.opened(2,installation(7,"current-chain",2))).isZero();
                assertThat(m.opened(2,installation(8,"other-chain",1))).isZero();
                assertThat(m.opened(2,current)).isEqualTo(1);
                assertThat(m.opened(2,current)).isZero();
                assertThat(m.opened(3,current)).isZero();
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE notification_id=2 AND status='UNKNOWN' AND accepted_at IS NULL AND opened_at IS NOT NULL")).isEqualTo(1);
                s.commit();
            }
        });
    }

    @Test void longTokensDigestUniquenessProviderReplacementAndLegacyMigrationAreRealSql() throws Exception {
        inSchema(c -> {
            sql(c,"INSERT INTO nx_native_push_installation(installation_id,client_id,provider,token_digest,binding_token_hash,user_id,session_chain_id,platform,permission,app_version,binding_epoch,active,bound_at,updated_at) VALUES"
                    +"('legacy-install','oldcid','UNIPUSH',SHA2(CONCAT('UNIPUSH',CHAR(0),'oldcid'),256),'proof',7,'chain','android','granted','1',1,1,"+NOW+","+NOW+")");
            applyDirectMigration(c); applyDirectMigration(c);
            try (var s=mapperSession(c)) {
                var m=s.getMapper(NativePushMapper.class);
                assertThat(m.lockInstallation("legacy-install").active()).isFalse();
                assertThat(m.lockInstallation("legacy-install").bindingEpoch()).isEqualTo(2);
                String token="fcm:"+"x".repeat(4092);
                var current=new Installation(INSTALL,token,"FCM","proof",7L,"chain","android","granted","1",1,true);
                m.insert(current); s.commit();
                assertThat(m.lockInstallation(INSTALL).clientId()).hasSize(4096);
                assertThat(m.tokenOwner("FCM",token)).isEqualTo(INSTALL);
                assertThat(m.tokenOwner("APNS",token)).isNull();
                assertThatThrownBy(() -> m.insert(new Installation("another-install",token,"FCM","proof",8L,"other","android","granted","1",1,true)))
                        .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
                s.rollback();
                m.update(new Installation(INSTALL,"a".repeat(64),"APNS","proof",7L,"chain","ios","granted","1",1,true),1);
                s.commit(); s.clearCache(); assertThat(m.lockInstallation(INSTALL).bindingEpoch()).isEqualTo(2);
                sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,cta_href,created_at,is_deleted,priority,push_status) VALUES ('NATIVE1:legacy-check',7,'ACCOUNT_SECURITY','/pages/me/security',"+NOW+",0,'critical','DELIVERED')");
                assertThat(m.discover(m.lockCursor(),Long.MAX_VALUE,0)).isEqualTo(1);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_delivery WHERE installation_id='legacy-install'")).isZero();
                s.commit();
            }
        });
    }

    private Installation installation(long user,String chain,long epoch) {
        return new Installation(INSTALL,"fcm:"+"x".repeat(150),"FCM","hash",user,chain,"android","granted","1",epoch,true);
    }
    private SqlSession mapperSession(Connection c) {
        Configuration config=new Configuration(); config.setMapUnderscoreToCamelCase(true); config.addMapper(NativePushMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(config).openSession(c);
    }
    private static Connection connect() throws Exception {
        return DriverManager.getConnection(System.getenv("NEXION_NATIVE_PUSH_MYSQL_URL"),System.getenv("NEXION_NATIVE_PUSH_MYSQL_USER"),System.getenv("NEXION_NATIVE_PUSH_MYSQL_PASSWORD"));
    }
    private void inSchema(Checked work) throws Exception {
        String schema="native_push_it_"+UUID.randomUUID().toString().replace("-","");
        try (Connection owner=connect()) {
            sql(owner,"CREATE DATABASE "+schema);
            try (Connection c=connect()) {
                c.setCatalog(schema);
                sql(c,"CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(16),is_deleted INT,sandbox INT,language VARCHAR(16))");
                sql(c,"INSERT INTO nx_user VALUES(7,'ACTIVE',0,0,'en'),(8,'ACTIVE',0,0,'zh')");
                sql(c,"CREATE TABLE nx_user_preference(user_id BIGINT PRIMARY KEY,is_deleted INT,notify_system INT)");
                sql(c,"INSERT INTO nx_user_preference VALUES(7,0,1),(8,0,1)");
                sql(c,"CREATE TABLE nx_user_security(user_id BIGINT PRIMARY KEY,is_deleted INT,password_reset_required INT)");
                sql(c,"CREATE TABLE nx_notification(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_no VARCHAR(128),user_id BIGINT,type VARCHAR(64),cta_href VARCHAR(256),created_at DATETIME(6),is_deleted INT,priority VARCHAR(16),push_status VARCHAR(16))");
                sql(c,"INSERT INTO nx_notification VALUES(1,'NATIVE1:historical',7,'FUNDS_RESULT','/pages/me/wallet-bills',DATE_SUB("+NOW+",INTERVAL 1 DAY),0,'critical','DELIVERED')");
                sql(c,"CREATE TABLE nx_business_notification_fact(fact_key VARCHAR(128) PRIMARY KEY,notification_biz_no VARCHAR(128),user_id BIGINT)");
                sql(c,"CREATE TABLE nx_app_session_state(user_id BIGINT PRIMARY KEY,app_session_version BIGINT,session_chain_id VARCHAR(128),installation_id VARCHAR(128),execution_supported INT)");
                sql(c,"CREATE TABLE nx_user_session(id BIGINT PRIMARY KEY,user_id BIGINT,session_chain_id VARCHAR(128),session_group VARCHAR(16),installation_id VARCHAR(128),app_session_version BIGINT,execution_supported INT,is_deleted INT,revoked_at DATETIME,session_ended_reason VARCHAR(64),expires_at DATETIME,last_active_at DATETIME,created_at DATETIME)");
                String migration=Files.readString(Path.of("scripts/migrations/20261029_native_push_transport.sql"));
                for(String statement:migration.split(";")) if(!statement.isBlank()) sql(c,statement);
                sql(c,"INSERT INTO nx_native_push_installation(installation_id,client_id,binding_token_hash,user_id,session_chain_id,platform,permission,app_version,binding_epoch,active,bound_at,updated_at) VALUES"
                        +"('pre-migration-install','originalcid','proof',7,'chain','android','granted','1',1,1,"+NOW+","+NOW+")");
                for (int run=0;run<2;run++) applyDirectMigration(c);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_native_push_installation WHERE installation_id='pre-migration-install' AND provider='UNIPUSH' AND active=0 AND binding_epoch=2 AND token_digest=SHA2(CONCAT('UNIPUSH',CHAR(0),'originalcid'),256)")).isEqualTo(1);
                work.run(c);
            } finally { sql(owner,"DROP DATABASE "+schema); }
        }
    }
    private static void applyDirectMigration(Connection c) throws Exception {
        for (String statement:Files.readString(Path.of("scripts/migrations/20261030_native_push_direct_providers.sql")).split(";"))
            if (!statement.isBlank()) sql(c,statement);
    }
    private static void sql(Connection c,String query) throws SQLException { try(var statement=c.createStatement()) { statement.execute(query); } }
    private static long scalar(Connection c,String query) throws SQLException { try(var statement=c.createStatement();var rows=statement.executeQuery(query)) { rows.next(); return rows.getLong(1); } }
    interface Checked { void run(Connection c) throws Exception; }
}
