package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.content.businessnotification.BusinessNotificationFact;
import ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.infrastructure.MybatisNotificationCampaignRepository;
import ffdd.opsconsole.content.infrastructure.NotificationCapRuleEntity;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.content.mapper.NotificationCapRuleMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual page transaction and all retention SQL, against an isolated MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXION_NOTIFICATION_RETENTION_IT",matches="true")
class AppNotificationRetentionMySqlTest {
    @Test void pageRetentionDoesNotBlockBusinessInsertAndKeepsAllUserScopedRules() throws Exception {
        String schema="notification_retention_it_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=connect("")) {
            sql(admin,"CREATE DATABASE "+schema);
            try(Connection c=connect(schema)) {
                seed(c);
                var ds=new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                    c.getMetaData().getURL(),"root",System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD",""));
                Configuration cfg=new Configuration();cfg.setMapUnderscoreToCamelCase(true);
                cfg.addMapper(NotificationCampaignMapper.class);cfg.addMapper(BusinessNotificationMapper.class);
                cfg.setEnvironment(new org.apache.ibatis.mapping.Environment("retention",
                    new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),ds));
                var template=new org.mybatis.spring.SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
                var caps=mock(NotificationCapRuleMapper.class);
                when(caps.selectList(any())).thenReturn(List.of(cap("high",2),cap("normal",2),cap("low",3),cap("critical",1)));
                var delegate=new MybatisNotificationCampaignRepository(template.getMapper(NotificationCampaignMapper.class),caps);
                var retained=new CountDownLatch(1);var resume=new CountDownLatch(1);
                var repository=(NotificationCampaignRepository)java.lang.reflect.Proxy.newProxyInstance(
                    NotificationCampaignRepository.class.getClassLoader(),new Class<?>[]{NotificationCampaignRepository.class},
                    (proxy,method,args)->{
                        Object value;
                        try {value=method.invoke(delegate,args);}
                        catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        if(method.getName().equals("applyRetentionForUser")) {
                            retained.countDown();
                            if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("RETENTION_TEST_NOT_RELEASED");
                        }
                        return value;
                    });
                var proxy=new org.springframework.aop.framework.ProxyFactory(new AppNotificationService(repository,
                    mock(EventOutboxService.class),mock(NovaSocialRuntimeRepository.class),mock(org.springframework.beans.factory.ObjectProvider.class)));
                proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                var service=(AppNotificationService)proxy.getProxy();
                var executor=Executors.newSingleThreadExecutor();
                var page=executor.submit(()->service.page(1L,null,null,100));
                try {
                    assertThat(retained.await(10,TimeUnit.SECONDS)).as("all four retention SQL statements completed").isTrue();
                    try(var statement=c.createStatement();var locks=statement.executeQuery("SELECT INDEX_NAME,LOCK_MODE,COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=DATABASE() AND OBJECT_NAME='nx_notification' GROUP BY INDEX_NAME,LOCK_MODE")) {
                        while(locks.next())System.out.println("retention lock "+locks.getString(1)+" "+locks.getString(2)+" count="+locks.getLong(3));
                    }
                    sql(c,"SET SESSION innodb_lock_wait_timeout=1");
                    var managed=new org.apache.ibatis.transaction.managed.ManagedTransactionFactory();
                    var properties=new java.util.Properties();properties.setProperty("closeConnection","false");managed.setProperties(properties);
                    Configuration writerCfg=new Configuration();writerCfg.addMapper(BusinessNotificationMapper.class);
                    writerCfg.setEnvironment(new org.apache.ibatis.mapping.Environment("business-writer",managed,ds));
                    try(var writer=new MybatisSqlSessionFactoryBuilder().build(writerCfg).openSession(c)) {
                        assertThat(writer.getMapper(BusinessNotificationMapper.class).notify(
                            new BusinessNotificationFact("SUPPORT:999",1L,"SUPPORT_REPLY","support","/pages/support/chat?cid=test",null),
                            "NATIVE1:concurrent-support","zh","en","vi")).isEqualTo(1);
                    }
                } finally {
                    resume.countDown();
                    try {
                        var result=page.get(10,TimeUnit.SECONDS);
                        assertThat(result.getCode()).isZero();
                        assertThat(result.getData().items()).hasSize(9);
                        assertThat(result.getData().unread()).isEqualTo(9);
                    } finally {executor.shutdownNow();}
                }
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND is_deleted=1")).isEqualTo(4);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=2 AND is_deleted=0")).isEqualTo(13);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE priority='critical' AND is_deleted=0")).isEqualTo(6);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND priority='high' AND is_deleted=0")).isEqualTo(3);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND priority='normal' AND is_deleted=0")).isEqualTo(2);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND priority='low' AND is_deleted=0")).isEqualTo(2);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE biz_no='NATIVE1:concurrent-support'")).isEqualTo(1);
                assertThat(scalar(c,"SELECT COUNT(*) FROM nx_notification WHERE user_id=1 AND biz_no IN ('1-high-3','1-normal-3','1-low-3','1-low-4') AND is_deleted=0")).isEqualTo(4);
            } finally {sql(admin,"DROP DATABASE "+schema);}
        }
    }

    private static NotificationCapRuleEntity cap(String tier,int maximum) {
        var rule=new NotificationCapRuleEntity();rule.setTier(tier);rule.setCapLabel(Integer.toString(maximum));rule.setLocked(0);
        return rule;
    }
    private static void seed(Connection c) throws Exception {
        sql(c,"CREATE TABLE nx_user(id BIGINT PRIMARY KEY,language VARCHAR(32),is_deleted INT)");
        sql(c,"INSERT INTO nx_user VALUES(1,'en',0),(2,'en',0)");
        sql(c,"CREATE TABLE nx_user_preference(user_id BIGINT PRIMARY KEY,notify_commission INT,notify_team INT,notify_staking INT,notify_market INT,notify_genesis INT,notify_system INT,is_deleted INT)");
        sql(c,"CREATE TABLE nx_notification(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_no VARCHAR(128),user_id BIGINT,type VARCHAR(32),priority VARCHAR(16),title VARCHAR(128),body VARCHAR(512),cta_label VARCHAR(64),cta_href VARCHAR(255),read_flag INT DEFAULT 0,read_at DATETIME,push_status VARCHAR(32),push_attempts INT,next_push_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0,UNIQUE KEY uk_notification_biz_user(biz_no,user_id),KEY idx_notification_user_time(user_id,created_at),KEY idx_notification_push(push_status,created_at),KEY idx_notification_push_due(push_status,next_push_at,created_at))");
        for(int user=1;user<=2;user++)for(String priority:new String[]{"high","normal","low","critical"}) {
            int count=priority.equals("low")?4:3;
            for(int row=1;row<=count;row++) {
                int hours=priority.equals("low")?(row==1?80:row==2?49:4-row):4-row;
                sql(c,"INSERT INTO nx_notification(biz_no,user_id,type,priority,title,body,push_status,created_at,updated_at) VALUES('"
                    +user+"-"+priority+"-"+row+"',"+user+",'system','"+priority+"','fixture','fixture','DELIVERED',DATE_SUB(NOW(),INTERVAL "+hours+" HOUR),NOW())");
            }
        }
    }
    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection("jdbc:mysql://"+System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")+"/"+schema
            +"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC","root",System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD",""));
    }
    private static void sql(Connection c,String text) throws Exception {try(var s=c.createStatement()){s.execute(text);}}
    private static long scalar(Connection c,String text) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getLong(1);}}
}
