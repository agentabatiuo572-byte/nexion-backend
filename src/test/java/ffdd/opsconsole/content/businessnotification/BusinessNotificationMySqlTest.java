package ffdd.opsconsole.content.businessnotification;

import ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper;
import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real MySQL, isolated schema; exercises the annotated SQL and projection together. */
@EnabledIfEnvironmentVariable(named="NEXION_BUSINESS_NOTIFICATION_IT",matches="true")
class BusinessNotificationMySqlTest {
    @Test void committedPurchaseResultsQueueExactlyOneOwnedStationAndPushIdentityEach() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_purchase_payment_result(notification_key,intent_no,outcome,user_id,order_no) VALUES('INTENT:paid','paid','DELIVERED',2,'ORDER-1'),('ORDER:ORDER-2','timeout','PAYMENT_TIMEOUT',2,'ORDER-2'),('INTENT:review','review','MANUAL_REVIEW',2,'ORDER-3')");
            scan(c);scan(c);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND type='FUNDS_RESULT' AND push_status='QUEUED'")).isEqualTo(3);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_notification_fact WHERE fact_key LIKE 'PURCHASE:%'")).isEqualTo(3);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE cta_href='/pages/store/order-detail?id=ORDER-1'")).isEqualTo(1);
        });
    }
    @Test void actualOutboxEnvelopeJsonNullUserDoesNotHideTargetUserSecurityFacts() throws Exception {
        inSchema(c->{
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var service=new ffdd.opsconsole.shared.outbox.EventOutboxService(null,json,new ffdd.opsconsole.shared.outbox.OutboxProperties(),null);
            for(String event:new String[]{"auth.login_locked","auth.refresh_token_reuse_detected"}) {
                var payload=json.createObjectNode().put("targetUserId",2).put("sessionChainId",event+"-server-chain");
                String envelope=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"toEnvelopeJson",
                    event,event,"SYSTEM",0,"2026-W44",true,0,payload,null);
                assertThat(json.readTree(envelope).get("user_id").isNull()).isTrue();
                try(var p=c.prepareStatement("INSERT INTO nx_event_outbox(id,event_id,aggregate_id,event_type,payload,created_at,is_deleted) VALUES(?,?,?,?,?,NOW(),0)")) {
                    p.setLong(1,event.endsWith("locked")?1:2);p.setString(2,event);p.setString(3,"2");p.setString(4,event);p.setString(5,envelope);p.executeUpdate();
                }
            }
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(2);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND priority='critical'")).isEqualTo(2);
        });
    }
    @Test void newSignInUsesServerSessionAndPeerAndDoesNotRepeatForRotationOrSamePeer() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_user_session(id,session_chain_id,user_id,client_ip,session_group,created_at,is_deleted) VALUES(1,'chain1',1,'203.0.113.1','APP',NOW(),0),"
                +"(2,'chain2',1,'203.0.113.1','APP',NOW(),0),(3,'chain3',1,'203.0.113.2','APP',NOW(),0),"
                +"(4,'chain3',1,'203.0.113.2','APP',NOW(),0)");
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(2);
        });
    }
    @Test void acceptedNewAppInstallationOnSameNetworkAlertsButSameInstallationAndH5PeerDoNotRepeat() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_user_session VALUES"
                +"(1,'chain1',1,'203.0.113.1','APP',NOW(),0,'accepted-app-install-a'),"
                +"(2,'chain2',1,'203.0.113.1','APP',NOW(),0,'accepted-app-install-b'),"
                +"(3,'chain3',1,'203.0.113.1','APP',NOW(),0,'accepted-app-install-b'),"
                +"(4,'chain3',1,'203.0.113.1','APP',NOW(),0,'accepted-app-install-b'),"
                +"(5,'chain5',1,'203.0.113.1','H5',NOW(),0,'ignored-h5-value-a'),"
                +"(6,'chain6',1,'203.0.113.1','H5',NOW(),0,'ignored-h5-value-b')");
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(3);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_notification_fact WHERE fact_key IN ('SESSION:chain1','SESSION:chain2','SESSION:chain5')")).isEqualTo(3);
        });
    }
    @Test void committedResultsAreScopedTrilingualDeduplicatedAndLateFactsAreNotLost() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted) VALUES('WD-W1',2,0)");
            sql(c,"INSERT INTO nx_event_outbox(id,event_id,aggregate_id,event_type,payload,created_at,is_deleted) VALUES(1,'a','WD-W1','withdraw.confirmed','{}',NOW(),0),"
                +"(2,'b','old','wallet.topup_confirmed','{\"user_id\":1}',DATE_SUB(NOW(),INTERVAL 3 DAY),0),"
                +"(3,'c','WD-W1','withdraw.confirmed','{}',NOW(),0)");
            sql(c,"INSERT INTO nx_audit_log VALUES(1,1,'USER_PASSWORD_CHANGED','USER_SECURITY','1','SUCCESS','{}',NOW(),0),"
                +"(2,2,'USER_PASSWORD_CHANGED','USER_SECURITY','2','FAILED','{}',NOW(),0)");
            sql(c,"INSERT INTO nx_conversation VALUES(1,'conversation1',3,'support',0)");
            sql(c,"INSERT INTO nx_conversation_message VALUES(1,1,'conversation1',99,'agent',0),"
                +"(2,1,'conversation1',3,'user',0),(3,1,'conversation1',99,'agent',0)");
            sql(c,"INSERT INTO nx_support_human_message VALUES(1,3,99,'ADMIN',UTC_TIMESTAMP()),"
                +"(2,3,3,'USER',UTC_TIMESTAMP()),(3,2,99,'ADMIN',UTC_TIMESTAMP())");
            scan(c); scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(3);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND title LIKE '%密码%' ")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND title LIKE 'Your withdrawal%' ")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=3 AND title LIKE 'Bộ phận%' ")).isEqualTo(1);
            // Insert after earlier scan with an earlier fact timestamp: no advancing MAX(id)/time cursor can lose it.
            sql(c,"INSERT INTO nx_order VALUES('refund1',1,0)");
            sql(c,"INSERT INTO nx_event_outbox(id,event_id,aggregate_id,event_type,payload,created_at,is_deleted) VALUES(4,'late','refund1','order.refunded','{\"userId\":1}',DATE_SUB(NOW(),INTERVAL 1 HOUR),0)");
            scan(c); scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(4);
            sql(c,"UPDATE nx_notification SET is_deleted=1"); scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(4);
            assertThat(count(c,"nx_business_notification_fact")).isEqualTo(4);
        });
    }

    @Test void firstSignInKeepsPeerClockDeletionLegacyAndLateCommitSemantics() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_user_session VALUES"
                +"(1,'known-before-baseline',1,'203.0.113.1','APP',DATE_SUB(NOW(),INTERVAL 3 DAY),0,'known'),"
                +"(2,'same-known',1,'203.0.113.1','APP',NOW(),0,'known'),"
                +"(3,'new-app',1,'203.0.113.1','APP',NOW(),0,'new-install'),"
                +"(4,'equal-time-peer',1,'203.0.113.1','APP',NOW(),0,'new-install'),"
                +"(5,'other-ip',1,'203.0.113.2','APP',NOW(),0,'new-install'),"
                +"(6,'h5-first',1,'203.0.113.1','H5',NOW(),0,'ignored-a'),"
                +"(7,'h5-same-peer',1,'203.0.113.1','H5',NOW(),0,'ignored-b'),"
                +"(8,'blank-install',1,'203.0.113.1','APP',NOW(),0,'   '),"
                +"(9,'legacy-first',1,'203.0.113.3','APP',NOW(),0,NULL),"
                +"(10,'legacy-same-peer',1,'203.0.113.3','APP',NOW(),0,''),"
                +"(11,'deleted-peer',1,'203.0.113.4','APP',DATE_SUB(NOW(),INTERVAL 3 DAY),1,'reused-install'),"
                +"(12,'deleted-peer-new',1,'203.0.113.4','APP',NOW(),0,'reused-install'),"
                +"(13,'deleted-chain',1,'203.0.113.5','APP',DATE_SUB(NOW(),INTERVAL 3 DAY),1,'old-install'),"
                +"(14,'deleted-chain',1,'203.0.113.6','APP',NOW(),0,'another-install')");
            scan(c);
            assertThat(count(c,"nx_business_notification_fact")).isEqualTo(5);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_notification_fact WHERE fact_key IN "
                +"('SESSION:new-app','SESSION:other-ip','SESSION:h5-first','SESSION:legacy-first','SESSION:deleted-peer-new')")).isEqualTo(5);
            sql(c,"INSERT INTO nx_user_session VALUES(100,'late-session',1,'203.0.113.7','APP',DATE_SUB(NOW(),INTERVAL 1 HOUR),0,'late-install')");
            scan(c);scan(c);
            assertThat(count(c,"nx_business_notification_fact")).isEqualTo(6);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_notification_fact WHERE fact_key='SESSION:late-session'")).isEqualTo(1);
        });
    }

    @Test void additiveSessionIndexesReduceRealScanReadsWithoutChangingRecipients() throws Exception {
        inSchema(c->{
            sql(c,"CREATE INDEX idx_user_session_user ON nx_user_session(user_id)");
            sql(c,"CREATE INDEX idx_user_session_chain ON nx_user_session(session_chain_id)");
            StringBuilder rows=new StringBuilder("INSERT INTO nx_user_session VALUES");
            for(int id=1;id<=3000;id++) rows.append(id==1?"":",").append("(").append(id)
                .append(",'old-").append(id).append("',1,'203.0.113.1','APP',DATE_SUB(NOW(),INTERVAL 3 DAY),0,'historic-")
                .append(id%50).append("')");
            for(int id=3001;id<=3030;id++) rows.append(",(").append(id).append(",'new-").append(id)
                .append("',1,'203.0.113.1','APP',NOW(),0,'new-install-").append(id).append("')");
            sql(c,rows.toString());
            Configuration cfg=new Configuration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(BusinessNotificationMapper.class);
            var tx=new org.apache.ibatis.transaction.managed.ManagedTransactionFactory();
            var properties=new java.util.Properties();properties.setProperty("closeConnection","false");tx.setProperties(properties);
            cfg.setEnvironment(new org.apache.ibatis.mapping.Environment("session-indexes",tx,new org.apache.ibatis.datasource.unpooled.UnpooledDataSource()));
            try(var session=new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                var mapper=session.getMapper(BusinessNotificationMapper.class);
                long before=sessionReads(c);var original=mapper.facts();long unindexed=sessionReads(c)-before;
                String migration=Files.readString(Path.of("scripts/migrations/20261030_business_notification_session_indexes.sql"));
                for(int run=0;run<2;run++) for(String statement:migration.split(";")) if(!statement.isBlank())sql(c,statement);
                session.clearCache();
                long after=sessionReads(c);var indexed=mapper.facts();long indexedReads=sessionReads(c)-after;
                assertThat(original).hasSize(30);
                assertThat(indexed).containsExactlyElementsOf(original);
                assertThat(indexedReads).as("index reads %s versus old-path reads %s",indexedReads,unindexed).isLessThan(unindexed);
                assertThat(scalar(c,"SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                    +"AND table_name='nx_user_session' AND index_name IN ('idx_user_session_notify_created','idx_user_session_notify_peer')")).isEqualTo(2);
                System.out.println("business notification session scan rows: before="+unindexed+", indexed="+indexedReads);
            }
        });
    }

    private static long sessionReads(Connection c) throws Exception {
        try(var statement=c.createStatement();var rows=statement.executeQuery("SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_key','Handler_read_next','Handler_read_rnd_next')")) {
            long reads=0;while(rows.next())reads+=rows.getLong(2);return reads;
        }
    }

    @Test void deviceDebounceRecoveryAndIntentionalDeactivationUseAuthoritativeRuntime() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_user_device VALUES(1,1,'PRODUCTION','','OWNED','ACTIVE',NOW(),NULL,0,0)");
            sql(c,"INSERT INTO nx_user_device_runtime VALUES(1,'OFFLINE',NOW(),0)");
            scan(c); assertThat(count(c,"nx_notification")).isZero();
            sql(c,"UPDATE nx_business_device_alert_state SET abnormal_since=DATE_SUB(NOW(),INTERVAL 16 MINUTE)");
            scan(c); scan(c); assertThat(count(c,"nx_notification")).isEqualTo(1);
            sql(c,"UPDATE nx_user_device_runtime SET online_status='ONLINE',heartbeat_at=NOW()");
            scan(c);
            sql(c,"UPDATE nx_user_device_runtime SET online_status='OFFLINE'"); scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(1);
            sql(c,"UPDATE nx_business_device_alert_state SET abnormal_since=DATE_SUB(NOW(),INTERVAL 16 MINUTE)");
            scan(c); assertThat(count(c,"nx_notification")).isEqualTo(2);
            sql(c,"UPDATE nx_user_device SET deactivated_at=NOW(),status='DEACTIVATED'"); scan(c);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_device_alert_state WHERE abnormal_since IS NOT NULL")).isZero();
        });
    }

    @Test void trialWindowsRetentionAndAutomaticClosureDoNotInventOwnedDeviceExpiry() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_trial_claim(id,user_id,status,claimed_at,expires_at,extended_at,cancel_reason,is_deleted) VALUES"
                +"(1,1,'ACTIVE',DATE_SUB(NOW(),INTERVAL 2 DAY),DATE_ADD(NOW(),INTERVAL 23 HOUR),NULL,NULL,0),"
                +"(2,2,'ACTIVE',DATE_SUB(NOW(),INTERVAL 2 DAY),DATE_ADD(NOW(),INTERVAL 30 MINUTE),NULL,NULL,0),"
                +"(3,3,'GRACE',DATE_SUB(NOW(),INTERVAL 3 DAY),DATE_SUB(NOW(),INTERVAL 1 MINUTE),NULL,NULL,0),"
                +"(4,1,'CANCELLED',DATE_SUB(NOW(),INTERVAL 10 DAY),DATE_SUB(NOW(),INTERVAL 7 DAY),NULL,'auto_end',0),"
                +"(5,2,'CANCELLED',DATE_SUB(NOW(),INTERVAL 10 DAY),DATE_SUB(NOW(),INTERVAL 7 DAY),NULL,'explicit',0),"
                +"(6,3,'GRACE',DATE_SUB(NOW(),INTERVAL 30 DAY),DATE_SUB(NOW(),INTERVAL 20 DAY),NULL,NULL,0),"
                +"(7,3,'EXTENDED',DATE_SUB(NOW(),INTERVAL 10 DAY),DATE_ADD(NOW(),INTERVAL 30 MINUTE),DATE_SUB(NOW(),INTERVAL 1 DAY),NULL,0)");
            sql(c,"UPDATE nx_trial_claim SET closed_at=NOW() WHERE status='CANCELLED'");
            scan(c); scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(5);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE type='DEVICE_EXPIRY' AND cta_href='/pages/me/trial'")).isEqualTo(5);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2")).isEqualTo(1);
        });
    }

    @Test void policyChangeCannotReopenTerminalRetentionOrDuplicateAlreadyEmittedEnd() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_trial_claim(id,user_id,status,claimed_at,expires_at,extended_at,cancel_reason,is_deleted,closed_at) VALUES"
                +"(1,1,'CANCELLED',DATE_SUB(NOW(),INTERVAL 10 DAY),DATE_SUB(NOW(),INTERVAL 7 DAY),NULL,'auto_end',0,DATE_SUB(NOW(),INTERVAL 10 MINUTE)),"
                +"(2,2,'GRACE',DATE_SUB(NOW(),INTERVAL 10 DAY),DATE_SUB(NOW(),INTERVAL 10081 MINUTE),NULL,NULL,0,NULL)");
            scan(c);assertThat(count(c,"nx_notification")).isEqualTo(2);
            sql(c,"UPDATE nx_trial_claim SET status='CANCELLED',cancel_reason='auto_end',closed_at=NOW() WHERE id=2");
            scan(c);assertThat(count(c,"nx_notification")).isEqualTo(2);
            sql(c,"INSERT INTO nx_growth_trial_policy VALUES('graceDays','8',0)");
            scan(c);assertThat(count(c,"nx_notification")).isEqualTo(2);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE title LIKE '%24小时%' OR title LIKE '%24 hours%'")).isZero();
        });
    }

    @Test void existingHdpayMessageIsAdoptedAndSecretsNeverCopied() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,title,body,is_deleted,read_flag) VALUES('HDPAY:topup1',1,'WALLET','old','old',0,1)");
            sql(c,"INSERT INTO nx_event_outbox(id,event_id,aggregate_id,event_type,payload,created_at,is_deleted) VALUES(1,'event','topup1','wallet.topup_confirmed','{\"user_id\":1,\"psp\":\"HDPAY\",\"secret\":\"do-not-copy\"}',NOW(),0)");
            scan(c); scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE biz_no='HDPAY:topup1' AND read_flag=1 AND type='FUNDS_RESULT' AND body NOT LIKE '%do-not-copy%'")).isEqualTo(1);
        });
    }

    @Test void scanningDoesNotKeepSourceAccountLockedUntilProjectionCommit() throws Exception {
        sourceWriteWhileProjectionIsOpen("nx_user", "UPDATE nx_user SET language=language WHERE id=1",false);
    }

    @Test void scanningDoesNotKeepSourceDeviceLockedAfterAlertStateUpdates() throws Exception {
        sourceWriteWhileProjectionIsOpen("nx_user_device", "UPDATE nx_user_device SET status=status WHERE id=1",false);
    }

    @Test void scanningDoesNotKeepSourceDeviceLockedAfterRecovery() throws Exception {
        sourceWriteWhileProjectionIsOpen("nx_user_device", "UPDATE nx_user_device SET status=status WHERE id=1",true);
    }

    @Test void scanningDoesNotKeepRuntimeHeartbeatLockedUntilProjectionCommit() throws Exception {
        sourceWriteWhileProjectionIsOpen("nx_user_device_runtime", "UPDATE nx_user_device_runtime SET heartbeat_at=NOW() WHERE user_device_id=1",false);
    }

    @Test void recoveryResetsEveryDeviceBeforeAHighIdDeviceStartsAnotherAbnormalCycle() throws Exception {
        inSchema(c->{
            for(int id=1;id<=101;id++) {
                sql(c,"INSERT INTO nx_user_device VALUES("+id+",1,'PRODUCTION','','OWNED','ACTIVE',NOW(),NULL,0,0)");
                sql(c,"INSERT INTO nx_user_device_runtime VALUES("+id+",'ONLINE',NOW(),0)");
            }
            sql(c,"INSERT INTO nx_business_device_alert_state(device_id,user_id,abnormal_since,cycle,alerted) "
                +"SELECT id,user_id,DATE_SUB(NOW(),INTERVAL 16 MINUTE),1,0 FROM nx_user_device");
            scan(c);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_device_alert_state WHERE abnormal_since IS NULL")).isEqualTo(101);
            sql(c,"UPDATE nx_user_device_runtime SET online_status='OFFLINE' WHERE user_device_id=101");
            scan(c);
            assertThat(count(c,"nx_notification")).isZero();
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_business_device_alert_state WHERE device_id=101 AND cycle=2 "
                +"AND abnormal_since>DATE_SUB(NOW(),INTERVAL 1 MINUTE)")).isEqualTo(1);
        });
    }

    @Test void ctaRepairDoesNotKeepTheWithdrawalLockedUntilProjectionCommits() throws Exception {
        sourceWriteWhileProjectionIsOpen("withdrawal","UPDATE nx_withdrawal_order SET updated_at=DATE_ADD(NOW(),INTERVAL 1 SECOND) WHERE withdrawal_no='WD-LOCK'",false,true);
    }
    @Test void ctaRepairDoesNotKeepItsOutboxSourceLockedUntilProjectionCommits() throws Exception {
        sourceWriteWhileProjectionIsOpen("withdrawal outbox","UPDATE nx_event_outbox SET payload='{}' WHERE id=2",false,true);
    }
    @Test void accountScopedRecoveryDoesNotHoldTheGlobalSchedulerControlLock() throws Exception {
        sourceWriteWhileProjectionIsOpen("scheduler control","UPDATE nx_business_notification_control SET baseline_at=baseline_at WHERE id=1",false,false,true);
    }
    private static void sourceWriteWhileProjectionIsOpen(String sourceTable,String sourceUpdate,boolean recovering) throws Exception {
        sourceWriteWhileProjectionIsOpen(sourceTable,sourceUpdate,recovering,false);
    }
    private static void sourceWriteWhileProjectionIsOpen(String sourceTable,String sourceUpdate,boolean recovering,boolean repairWithdrawal) throws Exception {
        sourceWriteWhileProjectionIsOpen(sourceTable,sourceUpdate,recovering,repairWithdrawal,false);
    }
    private static void sourceWriteWhileProjectionIsOpen(String sourceTable,String sourceUpdate,boolean recovering,boolean repairWithdrawal,boolean collecting) throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_user_device VALUES(1,1,'PRODUCTION','','OWNED','ACTIVE',NOW(),NULL,0,0)");
            sql(c,"INSERT INTO nx_user_device_runtime VALUES(1,'OFFLINE',NOW(),0)");
            if(recovering) {
                sql(c,"INSERT INTO nx_business_device_alert_state(device_id,user_id,abnormal_since,cycle,alerted) VALUES(1,1,DATE_SUB(NOW(),INTERVAL 16 MINUTE),1,0)");
                sql(c,"UPDATE nx_user_device_runtime SET online_status='ONLINE'");
            }
            if(repairWithdrawal) {
                sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted,status) VALUES('WD-LOCK',1,0,'FAILED')");
                sql(c,"INSERT INTO nx_event_outbox VALUES(2,'legacy-result','WD-LOCK','withdraw.refunded','{}',NOW(),0,'WITHDRAWAL')");
                sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,priority,cta_href,read_flag,push_status,created_at,is_deleted) VALUES('NATIVE1:legacy',1,'FUNDS_RESULT','critical','/pages/me/wallet-bills',1,'QUEUED',NOW(),0)");
                sql(c,"INSERT INTO nx_business_notification_fact(fact_key,notification_biz_no,user_id) SELECT CONCAT('EVENT:withdraw.refunded:',SHA2('WD-LOCK',256)),'NATIVE1:legacy',1");
            }
            if(collecting) sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted,status,failed_at) VALUES('WD-READ',1,0,'ADDRESS_INVALID',NOW())");
            sql(c,"INSERT INTO nx_event_outbox(id,event_id,aggregate_id,event_type,payload,created_at,is_deleted) VALUES(1,'event','topup1','wallet.topup_confirmed','{\"user_id\":1}',NOW(),0)");
            var ds=new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                c.getMetaData().getURL(),"root",System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD",""));
            Configuration cfg=new Configuration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(BusinessNotificationMapper.class);
            cfg.setEnvironment(new org.apache.ibatis.mapping.Environment("locks",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),ds));
            var template=new org.mybatis.spring.SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
            var delegate=template.getMapper(BusinessNotificationMapper.class);
            var projected=new java.util.concurrent.CountDownLatch(1);
            var resume=new java.util.concurrent.CountDownLatch(1);
            var pausing=(BusinessNotificationMapper)java.lang.reflect.Proxy.newProxyInstance(
                BusinessNotificationMapper.class.getClassLoader(),new Class<?>[]{BusinessNotificationMapper.class},(proxy,method,args)->{
                    Object value;
                    try {value=method.invoke(delegate,args);}
                    catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("notify")) {
                        projected.countDown();
                        if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("PROJECTION_TEST_NOT_RELEASED");
                    }
                    return value;
                });
            var proxy=new org.springframework.aop.framework.ProxyFactory(new BusinessNotificationService(pausing));
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var service=(BusinessNotificationService)proxy.getProxy();
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            var running=executor.submit(()->{if(collecting)service.collectWithdrawalAttention(1L);else service.scan();});
            try {
                assertThat(projected.await(10,java.util.concurrent.TimeUnit.SECONDS)).as("projection reached before source write").isTrue();
                sql(c,"SET SESSION innodb_lock_wait_timeout=1");
                try(var writer=c.createStatement()) {
                    assertThat(writer.executeUpdate(sourceUpdate)).as("concurrent writer of %s",sourceTable).isEqualTo(1);
                }
            } finally {
                resume.countDown();
                try {running.get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{executor.shutdownNow();}
            }
            assertThat(count(c,"nx_notification")).isEqualTo(repairWithdrawal?2:1);
        });
    }

    @Test void withdrawalLifecycleUsesOriginalOwnerAndEventTimeAndEveryFreezeCycle() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted) VALUES('WD-ABC123',2,0),('WD-DELETED',2,1)");
            String[] types={"confirmed","refunded","failed","rejected","frozen","delayed","review_due"};
            for(int i=0;i<types.length;i++) sql(c,"INSERT INTO nx_event_outbox(id,event_id,aggregate_id,event_type,payload,created_at,is_deleted) VALUES("
                +(i+1)+",'event-"+i+"','WD-ABC123','withdraw."+types[i]+"','{\"user_id\":1}',DATE_SUB(NOW(),INTERVAL 1 HOUR),0)");
            sql(c,"INSERT INTO nx_event_outbox VALUES(20,'unknown','WD-ABC123','withdraw.unknown','{}',NOW(),0,'WITHDRAWAL'),"
                +"(21,'other','WD-ABC123','withdraw.failed','{}',NOW(),0,'OTHER'),"
                +"(22,'deleted','WD-DELETED','withdraw.failed','{}',NOW(),0,'WITHDRAWAL')");
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(7);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND priority='critical' AND push_status='DELIVERED'"
                +" AND cta_href='/pages/me/wallet-withdraw-tracking?id=WD-ABC123' AND created_at<DATE_SUB(NOW(),INTERVAL 59 MINUTE)")).isEqualTo(7);
            sql(c,"INSERT INTO nx_event_outbox VALUES(23,'second-freeze','WD-ABC123','withdraw.frozen','{}',NOW(),0,'WITHDRAWAL')");
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(8);
            assertThat(count(c,"nx_event_outbox")).isEqualTo(11);
        });
    }

    @Test void repairsReceiptedWithdrawalRouteAndVisibilityWithoutResettingReadOrTime() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted) VALUES('WD-ABC123',2,0)");
            sql(c,"INSERT INTO nx_event_outbox VALUES(1,'result','WD-ABC123','withdraw.refunded','{}',NOW(),0,'WITHDRAWAL')");
            scan(c);
            sql(c,"UPDATE nx_notification SET cta_href='/pages/me/wallet-bills',push_status='QUEUED',read_flag=1,created_at='2026-10-16 16:51:13'");
            sql(c,"INSERT INTO nx_notification_action_receipt VALUES(1,2,'cta','/pages/me/wallet-bills',0)");
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE read_flag=1 AND created_at='2026-10-16 16:51:13'"
                +" AND push_status='DELIVERED' AND cta_href='/pages/me/wallet-withdraw-tracking?id=WD-ABC123'")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification_action_receipt WHERE route='/pages/me/wallet-withdraw-tracking?id=WD-ABC123'")).isEqualTo(1);
        });
    }

    @Test void onlyExplicitHeldSubmissionsCreateAttentionMessages() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted) VALUES('WD-ABC123',2,0)");
            String[] routes={"freeze","delay","manual","high-manual","escalated-manual","strong-review","fast-pass","pass","unknown"};
            for(int i=0;i<routes.length;i++) sql(c,"INSERT INTO nx_event_outbox VALUES("+(i+1)+",'submitted-"+i
                +"','WD-ABC123','withdraw.submitted','{\"risk_route\":\""+routes[i]+"\"}',NOW(),0,'WITHDRAWAL')");
            scan(c);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(6);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND push_status='DELIVERED' AND priority='critical'")).isEqualTo(6);
        });
    }

    @Test void latePublicationUsesCanonicalBusinessEventTimeInsteadOfScanOrCommitTime() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted) VALUES('WD-ABC123',2,0)");
            long occurredAt=java.time.LocalDateTime.of(2026,10,16,16,51,13)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            sql(c,"INSERT INTO nx_event_outbox VALUES(1,'late-result','WD-ABC123','withdraw.refunded','{\"ts\":"+occurredAt+"}',NOW(),0,'WITHDRAWAL')");
            scan(c);
            try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT created_at,push_status FROM nx_notification")) {
                assertThat(rows.next()).as("late event creates one station-visible notification").isTrue();
                assertThat(rows.getObject("created_at",java.time.LocalDateTime.class))
                    .as("persisted canonical event time").isEqualTo(java.time.LocalDateTime.of(2026,10,16,16,51,13));
                assertThat(rows.getString("push_status")).isEqualTo("DELIVERED");
                assertThat(rows.next()).isFalse();
            }
        });
    }

    @Test void currentUserRecoversRealHistoricalFailureAndStateReminderWithoutInventingEvents() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted,status,failed_at,updated_at,created_at) VALUES"
                +"('WD-7534DB8D9CF14CDB813092024C38AC65',2,0,'FAILED','2026-10-20 02:31:52','2026-10-20 02:31:52','2026-10-16 16:51:13'),"
                +"('WD-ADDRESS',2,0,'ADDRESS_INVALID','2026-10-20 03:00:00','2026-10-20 03:00:00','2026-10-16 17:00:00'),"
                +"('WD-OTHER',3,0,'FAILED',NOW(),NOW(),NOW()),('WD-UNKNOWN',2,0,'UNKNOWN',NOW(),NOW(),NOW()),"
                +"('WD-CONFIRMED',2,0,'CONFIRMED',NULL,NOW(),NOW())");
            sql(c,"INSERT INTO nx_event_outbox VALUES(1,'real-result','WD-7534DB8D9CF14CDB813092024C38AC65','withdraw.refunded','{\"user_id\":1}','2026-10-20 02:31:52',0,'WITHDRAWAL')");
            collect(c,1L);assertThat(count(c,"nx_notification")).isZero();
            collect(c,2L);collect(c,2L);
            assertThat(count(c,"nx_notification")).isEqualTo(2);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND biz_no LIKE 'STATION1:%' AND priority='critical' AND push_status='DELIVERED'")).isEqualTo(2);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE cta_href='/pages/me/wallet-withdraw-tracking?id=WD-7534DB8D9CF14CDB813092024C38AC65' AND created_at='2026-10-20 02:31:52'")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE cta_href='/pages/me/wallet-withdraw-tracking?id=WD-ADDRESS' AND created_at='2026-10-20 03:00:00' AND title LIKE '%status reminder%'")).isEqualTo(1);
            Configuration feed=new Configuration();feed.setMapUnderscoreToCamelCase(true);
            feed.addMapper(ffdd.opsconsole.content.mapper.NotificationCampaignMapper.class);
            var managed=new org.apache.ibatis.transaction.managed.ManagedTransactionFactory();
            var options=new java.util.Properties();options.setProperty("closeConnection","false");managed.setProperties(options);
            feed.setEnvironment(new org.apache.ibatis.mapping.Environment("feed",managed,new org.apache.ibatis.datasource.unpooled.UnpooledDataSource()));
            try(var session=new MybatisSqlSessionFactoryBuilder().build(feed).openSession(c)) {
                var inbox=session.getMapper(ffdd.opsconsole.content.mapper.NotificationCampaignMapper.class);
                assertThat(inbox.selectUserNotifications(2L,null,null,100)).hasSize(2);
                assertThat(inbox.selectUserNotifications(1L,null,null,100)).isEmpty();
            }
            sql(c,"UPDATE nx_notification SET is_deleted=1,read_flag=1");
            collect(c,2L);scan(c);
            assertThat(count(c,"nx_notification")).isEqualTo(2);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE is_deleted=1 AND read_flag=1")).isEqualTo(2);
            assertThat(count(c,"nx_event_outbox")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_withdrawal_order WHERE withdrawal_no='WD-7534DB8D9CF14CDB813092024C38AC65' AND status='FAILED'")).isEqualTo(1);
        });
    }

    @Test void historicalReviewAfterUnfreezeIsNotHiddenByItsEarlierFrozenEvent() throws Exception {
        inSchema(c->{
            sql(c,"INSERT INTO nx_withdrawal_order(withdrawal_no,user_id,is_deleted,status) VALUES('WD-REVIEW',2,0,'REVIEW_PENDING'),('WD-NOEVENT',2,0,'REVIEWING')");
            sql(c,"INSERT INTO nx_event_outbox VALUES(1,'manual','WD-REVIEW','withdraw.submitted','{\"risk_route\":\"manual\"}','2026-10-16 16:00:00',0,'WITHDRAWAL'),"
                +"(2,'freeze','WD-REVIEW','withdraw.frozen','{}','2026-10-16 17:00:00',0,'WITHDRAWAL'),"
                +"(3,'unfreeze','WD-REVIEW','withdraw.unfrozen','{}','2026-10-16 18:00:00',0,'WITHDRAWAL')");
            collect(c,2L);collect(c,2L);
            assertThat(count(c,"nx_notification")).isEqualTo(2);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE cta_href='/pages/me/wallet-withdraw-tracking?id=WD-REVIEW' AND title LIKE '%requires review%'")).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE cta_href='/pages/me/wallet-withdraw-tracking?id=WD-NOEVENT' AND title LIKE '%status reminder%'")).isEqualTo(1);
        });
    }

    private static void scan(Connection c) { project(c,null); }
    private static void collect(Connection c,Long userId) { project(c,userId); }
    private static void project(Connection c,Long userId) {
        Configuration cfg=new Configuration(); cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(BusinessNotificationMapper.class);
        var transactionFactory=new org.apache.ibatis.transaction.managed.ManagedTransactionFactory();
        var properties=new java.util.Properties();properties.setProperty("closeConnection","false");transactionFactory.setProperties(properties);
        cfg.setEnvironment(new org.apache.ibatis.mapping.Environment("test",transactionFactory,new org.apache.ibatis.datasource.unpooled.UnpooledDataSource()));
        try(var session=new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(c)) {
            var service=new BusinessNotificationService(session.getMapper(BusinessNotificationMapper.class));
            if(userId==null) service.scan();else service.collectWithdrawalAttention(userId);
            session.commit();
        }
    }
    private static void seed(Connection c) throws Exception {
        sql(c,"CREATE TABLE nx_user(id BIGINT PRIMARY KEY,language VARCHAR(32),is_deleted INT)");
        sql(c,"INSERT INTO nx_user VALUES(1,'zh',0),(2,'en',0),(3,'vi',0)");
        sql(c,"CREATE TABLE nx_user_preference(user_id BIGINT PRIMARY KEY,is_deleted INT,notify_commission INT,notify_team INT,notify_staking INT,notify_market INT,notify_genesis INT,notify_system INT)");
        sql(c,"INSERT INTO nx_user_preference VALUES(2,0,0,0,0,0,0,0)");
        sql(c,"CREATE TABLE nx_event_outbox(id BIGINT PRIMARY KEY,event_id VARCHAR(64),aggregate_id VARCHAR(128),event_type VARCHAR(96),payload JSON,created_at DATETIME,is_deleted INT,aggregate_type VARCHAR(32) DEFAULT 'WITHDRAWAL')");
        sql(c,"CREATE TABLE nx_withdrawal_order(withdrawal_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,is_deleted INT,status VARCHAR(32) DEFAULT 'REVIEW_PENDING',failed_at DATETIME,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,d2_k3_risk_route VARCHAR(32) DEFAULT 'pass')");
        sql(c,"CREATE TABLE nx_purchase_payment_result(notification_key VARCHAR(128),intent_no VARCHAR(48),outcome VARCHAR(24),user_id BIGINT,order_no VARCHAR(64),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(notification_key,outcome))");
        sql(c,"CREATE TABLE nx_order(order_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,is_deleted INT)");
        sql(c,"CREATE TABLE nx_deposit_order(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(32),ledger_id BIGINT,credited_at DATETIME,chain_name VARCHAR(32),is_deleted INT)");
        sql(c,"CREATE TABLE nx_audit_log(id BIGINT PRIMARY KEY,user_id BIGINT,action VARCHAR(96),resource_type VARCHAR(64),resource_id VARCHAR(128),result VARCHAR(32),detail_json JSON,created_at DATETIME,is_deleted INT)");
        sql(c,"CREATE TABLE nx_user_session(id BIGINT PRIMARY KEY,session_chain_id VARCHAR(64),user_id BIGINT,client_ip VARCHAR(64),session_group VARCHAR(32),created_at DATETIME,is_deleted INT,installation_id VARCHAR(128))");
        sql(c,"CREATE TABLE nx_conversation(id BIGINT PRIMARY KEY,conversation_no VARCHAR(40),user_id BIGINT,conversation_type VARCHAR(32),is_deleted INT)");
        sql(c,"CREATE TABLE nx_conversation_message(id BIGINT PRIMARY KEY,conversation_id BIGINT,conversation_no VARCHAR(40),sender_id BIGINT,sender_type VARCHAR(16),is_deleted INT)");
        sql(c,"CREATE TABLE nx_support_human_message(message_id BIGINT PRIMARY KEY,customer_id BIGINT,actor_id BIGINT,actor_type VARCHAR(16),committed_at DATETIME)");
        sql(c,"CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,source_environment VARCHAR(16),run_id VARCHAR(96),ownership_status VARCHAR(32),status VARCHAR(32),activated_at DATETIME,deactivated_at DATETIME,pending_deactivate INT,is_deleted INT)");
        sql(c,"CREATE TABLE nx_user_device_runtime(user_device_id BIGINT PRIMARY KEY,online_status VARCHAR(32),heartbeat_at DATETIME,is_deleted INT)");
        sql(c,"CREATE TABLE nx_trial_claim(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(32),claimed_at DATETIME,expires_at DATETIME,extended_at DATETIME,cancel_reason VARCHAR(64),is_deleted INT,closed_at DATETIME)");
        sql(c,"CREATE TABLE nx_growth_trial_policy(policy_key VARCHAR(128),current_value VARCHAR(128),is_deleted INT)");
        sql(c,"CREATE TABLE nx_notification(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_no VARCHAR(128),user_id BIGINT,type VARCHAR(32),priority VARCHAR(16),title VARCHAR(128),body VARCHAR(512),cta_label VARCHAR(64),cta_href VARCHAR(255),read_flag INT,push_status VARCHAR(32),push_attempts INT,next_push_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted INT,read_at DATETIME,UNIQUE KEY uk_biz_user(biz_no,user_id))");
        sql(c,"CREATE TABLE nx_notification_action_receipt(notification_id BIGINT,user_id BIGINT,action VARCHAR(32),route VARCHAR(255),is_deleted INT)");
        for(String statement:Files.readString(Path.of("scripts/migrations/20261029_business_notifications.sql")).split(";"))
            if(!statement.isBlank()) sql(c,statement);
        sql(c,"UPDATE nx_business_notification_control SET baseline_at=DATE_SUB(NOW(),INTERVAL 2 HOUR),baseline_utc_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 HOUR)");
    }
    private static void sql(Connection c,String text) throws Exception {try(var s=c.createStatement()){s.execute(text);}}
    private static long scalar(Connection c,String text) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getLong(1);}}
    private static long count(Connection c,String table) throws Exception{return scalar(c,"SELECT COUNT(*) FROM "+table);}
    private static Connection connect(String db) throws Exception {
        return DriverManager.getConnection("jdbc:mysql://"+System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")+"/"+db+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC","root",System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD",""));
    }
    private static void inSchema(Check check) throws Exception {
        String schema="outstanding_pay_notice_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=connect("")) {
            sql(admin,"CREATE DATABASE "+schema);
            try(Connection c=connect(schema)){seed(c);check.run(c);}finally{sql(admin,"DROP DATABASE "+schema);}
        }
    }
    @FunctionalInterface interface Check {void run(Connection c) throws Exception;}
}
