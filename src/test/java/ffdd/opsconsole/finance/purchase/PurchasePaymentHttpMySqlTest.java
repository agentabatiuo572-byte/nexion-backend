package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.auth.facade.ExternalPurchaseCommandQuery;
import ffdd.opsconsole.auth.mapper.*;
import ffdd.opsconsole.auth.web.AppPaymentPasswordController;
import ffdd.opsconsole.commerce.application.*;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.commerce.web.AppOrderCommandController;
import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.finance.application.*;
import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.finance.web.*;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.*;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.data.redis.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real loopback Tomcat + production SecurityConfig/JWT/session/PIN/payment/order services and real MySQL.
 * The vendor gateway is synthetic and never contacts an external financial system. */
@EnabledIfEnvironmentVariable(named="PAYMENT_PIN_TEST_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3334[38]/(payment_pin_backend_test|outstanding_pay_test).*")
class PurchasePaymentHttpMySqlTest {
    static Fixture fixture;
    static ServletWebServerApplicationContext server;
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final List<Map<String,Object>> EVIDENCE=new ArrayList<>();

    @BeforeAll static void start() throws Exception {
        fixture=new Fixture();fixture.install();fixture.services();
        server=(ServletWebServerApplicationContext)new SpringApplicationBuilder(Wire.class).properties(
                "server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off","spring.profiles.active=dev",
                "spring.cloud.discovery.enabled=false","spring.cloud.nacos.config.enabled=false","spring.cloud.nacos.discovery.enabled=false",
                "spring.config.location=optional:classpath:/no-fixture-config.yml","server.forward-headers-strategy=none").run("--spring.profiles.active=dev");
        fixture.seed();
    }
    @AfterAll static void close() throws Exception {
        if(server!=null)server.close();
        if(fixture!=null)fixture.close();
    }
    record Response(int status,Map<String,Object> body,String text){}
    static Response request(String method,String path,String token,String key,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(20));
        if(token!=null)builder.header("Authorization","Bearer "+token);if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json(body)));
        var response=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        Map<String,Object> parsed=response.body().startsWith("{")?JSON.readValue(response.body(),new com.fasterxml.jackson.core.type.TypeReference<>(){}):Map.of();
        return new Response(response.statusCode(),parsed,response.body());
    }
    static Map<String,Object> data(Response response){assertThat(response.status()).as(response.text()).isEqualTo(200);assertThat(response.body().get("code")).isEqualTo(0);return map(response.body().get("data"));}
    static String token(long user){return fixture.jwt.createUserToken(user,"http-synthetic-user",List.of(),"http-session-"+user,Duration.ofHours(1),UserAuthEnvironment.PRODUCTION);}

    @Test void authenticatedExternalPurchaseSurvivesUnavailableAndLockedPinAndSignedCallbackDeliversExactlyOnce() throws Exception {
        int providerCreatesBefore=fixture.provider.creates.get();
        String user=token(7),other=token(8);
        var body=values("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",values("orderNo","HTTP-ORDER-1"));
        assertThat(request("POST","/api/app/purchase-payment-quotes",null,null,body).status()).isEqualTo(401);
        assertThat(request("POST","/api/app/purchase-payment-quotes","invalid-jwt",null,body).status()).isEqualTo(401);
        var foreign=request("POST","/api/app/purchase-payment-quotes",other,null,body);assertThat(foreign.status()).as(foreign.text()).isEqualTo(404);
        fixture.jdbc.update("DELETE FROM nx_payment_password_policy WHERE id=1");
        assertThat(request("GET","/api/app/security/payment-password",user,null,null).status()).isEqualTo(503);
        assertThat(request("POST","/api/app/orders/HTTP-ORDER-1/pay",user,"http-wallet-unavailable",values("paymentPassword","018290","credentialVersion",1,"attemptNo","http-unavailable-attempt")).body())
                .containsEntry("code",503);
        clearInvocations(fixture.pin);
        var quote=data(request("POST","/api/app/purchase-payment-quotes",user,null,body));
        var pending=data(request("POST","/api/app/purchase-payment-intents",user,"http-external-original-command",values("paymentQuoteId",quote.get("paymentQuoteId"))));
        String intent=text(pending.get("intentNo"));assertThat(pending).containsEntry("status","SUBMIT_UNKNOWN");
        assertThat(data(request("GET","/api/app/payment-commands/order-pay/http-external-original-command",user,null,null))).containsEntry("status","PROCESSING");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_password_attempt",Integer.class)).isZero();
        verifyNoInteractions(fixture.pin);
        fixture.migrate("20261030_payment_password.sql");
        assertThat(map(data(request("POST","/api/app/security/payment-password/setup",user,"http-setup",values("newPassword","018290","confirmPassword","018290"))).get("state"))).containsEntry("credentialVersion",1);
        for(int i=0;i<5;i++){
            var failed=request("POST","/api/app/orders/HTTP-ORDER-2/pay",user,"http-wrong-pin-command-"+i,
                    values("paymentPassword","999998","credentialVersion",1,"attemptNo","http-wrong-pin-attempt-"+i));
            assertThat(failed.body().get("code")).as(failed.text()).isEqualTo(i<4?422:429);
        }
        var state=data(request("GET","/api/app/security/payment-password",user,null,null));assertThat(state.get("lockedUntil")).isNotNull();
        long attempts=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_password_attempt",Long.class);
        clearInvocations(fixture.pin);
        var wrongRoute=request("POST","/api/app/orders/HTTP-ORDER-2/pay",user,"http-external-wrong-route",values("paymentMethod","HDPAY_BANK","paymentPassword","018290","credentialVersion",1,"attemptNo","http-must-not-read-pin"));
        assertThat(wrongRoute.body()).containsEntry("code",400).containsEntry("message","REQUEST_BODY_INVALID");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_password_attempt",Long.class)).isEqualTo(attempts);
        verifyNoInteractions(fixture.pin);
        fixture.jdbc.update("DELETE FROM nx_payment_password_policy WHERE id=1");
        assertThat(request("GET","/api/app/security/payment-password",user,null,null).status()).isEqualTo(503);
        clearInvocations(fixture.pin);
        fixture.provider.paid.add(intent);
        Map<String,String> callback=new LinkedHashMap<>();callback.put("merchantId","123456");callback.put("merchantOrderId",intent);callback.put("orderId","SYNTH-"+intent);
        callback.put("transAmt",text(quote.get("amountVnd")));callback.put("orderStatus","3");callback.put("signType","MD5");callback.put("sign",HdPaySigner.sign(callback,fixture.hd.getMd5Key()));
        Map<String,Object> callbackBody=new LinkedHashMap<>(callback);callbackBody.put("orderStatus",3);
        var forged=new LinkedHashMap<>(callbackBody);forged.put("sign","0".repeat(32));
        assertThat(request("POST",HdPayProperties.PAY_IN_CALLBACK_PATH,null,null,forged).status()).isEqualTo(400);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no='HTTP-ORDER-1'",Integer.class)).isZero();
        var first=request("POST",HdPayProperties.PAY_IN_CALLBACK_PATH,null,null,callbackBody);assertThat(first.status()).as(first.text()).isEqualTo(200);assertThat(first.text()).isEqualTo("success");
        var delivered=data(request("GET","/api/app/purchase-payment-intents/"+intent,user,null,null));
        if(!"DELIVERED".equals(delivered.get("status"))) {
            // The retained manual command joins one transaction, so a hidden SQL failure is surfaced with its real cause.
            fixture.payments.revalidate(intent,"http-delivery-failure-diagnostic",values("expectedVersion",delivered.get("version"),"reason","Expose isolated HTTP delivery failure cause"));
            fail("Authoritative HTTP settlement was blocked: "+delivered);
        }
        assertThat(delivered).containsEntry("status","DELIVERED");
        assertThat(map(delivered.get("receipt"))).containsEntry("amountUsdt","19.995000").containsEntry("paymentSubtotalUsdt","20.00").containsEntry("walletDebitUsdt","0.00");
        assertThat(data(request("GET","/api/app/payment-commands/order-pay/http-external-original-command",user,null,null))).containsEntry("status","SUCCEEDED");
        assertThat(request("GET","/api/app/purchase-payment-intents/"+intent,other,null,null).status()).isEqualTo(404);
        assertThat(request("POST",HdPayProperties.PAY_IN_CALLBACK_PATH,null,null,callbackBody).text()).isEqualTo("success");
        data(request("POST","/api/app/purchase-payment-intents",user,"http-external-original-command",values("paymentQuoteId",quote.get("paymentQuoteId"))));
        assertThat(fixture.provider.creates.get()-providerCreatesBefore).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no='HTTP-ORDER-1' AND status='ACTIVE'",Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT price_usdt_snapshot FROM nx_user_device WHERE source_order_no='HTTP-ORDER-1'",BigDecimal.class)).isEqualByComparingTo("19.995000");
        assertThat(fixture.jdbc.queryForObject("SELECT stock FROM nx_product WHERE id=18",Integer.class)).isEqualTo(9);
        assertThat(fixture.jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=7",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=7",Integer.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record WHERE payment_no=?",Integer.class,intent)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE aggregate_id='HTTP-ORDER-1' AND event_type='checkout.completed'",Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_password_attempt",Long.class)).isEqualTo(attempts);
        EVIDENCE.add(values("case","actual-http-auth-pin-503-lock-callback-exactly-once","status","PASS","provider","synthetic in-process HDPay gateway","intentNo",intent,
                "pinAttempts",attempts,"walletPrincipalLedgers",0,"devices",1,"providerCreates",1,"schema",fixture.schema,"httpPort",server.getWebServer().getPort()));
        var bscQuote=data(request("POST","/api/app/purchase-payment-quotes",user,null,values("kind","ORDER_PAY","paymentMethod","CREGIS_USDT_BSC","payload",values("orderNo","HTTP-ORDER-2"))));
        var bscPending=data(request("POST","/api/app/purchase-payment-intents",user,"http-bsc-original-command",values("paymentQuoteId",bscQuote.get("paymentQuoteId"))));
        String bscIntent=text(bscPending.get("intentNo"));assertThat(bscPending).containsEntry("status","AWAITING_PAYMENT").containsEntry("address",fixture.bscAddress);
        assertThat(data(request("GET","/api/app/payment-commands/order-pay/http-bsc-original-command",user,null,null))).containsEntry("status","PROCESSING");
        fixture.publishSyntheticBscTransfer();
        var bscDelivered=data(request("GET","/api/app/purchase-payment-intents/"+bscIntent,user,null,null));assertThat(bscDelivered).containsEntry("status","DELIVERED");
        assertThat(map(bscDelivered.get("receipt"))).containsEntry("paymentMethod","CREGIS_USDT_BSC").containsEntry("amountUsdt","19.995000").containsEntry("paymentSubtotalUsdt","20.00").containsEntry("walletDebitUsdt","0.00");
        assertThat(data(request("GET","/api/app/payment-commands/order-pay/http-bsc-original-command",user,null,null))).containsEntry("status","SUCCEEDED");
        data(request("GET","/api/app/purchase-payment-intents/"+bscIntent,user,null,null));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no='HTTP-ORDER-2' AND status='ACTIVE'",Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_arrival WHERE intent_no=?",Integer.class,bscIntent)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT stock FROM nx_product WHERE id=18",Integer.class)).isEqualTo(8);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=7",Integer.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_password_attempt",Long.class)).isEqualTo(attempts);
        assertThat(fixture.jdbc.queryForObject("SELECT purpose FROM nx_cregis_deposit_address WHERE address=?",String.class,fixture.bscAddress)).isEqualTo("PURCHASE_DIRECT");
        verify(fixture.bscProvider,never()).createAddress(anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(fixture.pin);
        EVIDENCE.add(values("case","actual-http-locked-pin-bsc-pool-confirmed-delivery","status","PASS","provider","synthetic provider membership/history and BSC chain proof","intentNo",bscIntent,"walletPrincipalLedgers",0,"devices",1,"schema",fixture.schema));
        Files.writeString(Path.of("D:/workspace/artifacts/pay1718-20261031/BACKEND-HTTP-RESULTS.json"),json(EVIDENCE));
    }

    @Test void bothCancellationHttpAliasesUseTheReadCommittedBusinessEntry() throws Exception {
        for(String prefix:List.of("/api/orders/","/api/app/orders/")){
            String no="HTTP-CANCEL-"+(prefix.contains("/app/")?"APP":"LEGACY");
            fixture.jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,amount_usdt,payment_status,order_status,activation_status,inventory_reserved) VALUES(7,?,18,1,'SINGLE',1,19.995,'PENDING','PENDING_PAYMENT','WAITING_PAYMENT',0)",no);
            var quote=fixture.payments.quote(7,values("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",values("orderNo",no)));
            fixture.payments.create(7,"other-tab-"+no,text(quote.get("paymentQuoteId")));
            var rejected=request("POST",prefix+no+"/cancel",token(7),"original-cancel-"+no,null);
            assertThat(rejected.status()).as(rejected.text()).isEqualTo(409);
            assertThat(rejected.body()).containsEntry("code",409).containsEntry("message","ORDER_EXTERNAL_PAYMENT_IN_PROGRESS");
            fixture.jdbc.update("UPDATE nx_purchase_payment_intent SET status='EXPIRED' WHERE quote_id=?",quote.get("paymentQuoteId"));
            var cancelled=data(request("POST",prefix+no+"/cancel",token(7),"original-cancel-"+no,null));
            assertThat(cancelled).containsEntry("orderNo",no).containsEntry("paymentStatus","CANCELLED").containsEntry("serverCanonical",true);
            assertThat(data(request("POST",prefix+no+"/cancel",token(7),"original-cancel-"+no,null))).containsEntry("paymentStatus","CANCELLED");
        }
    }

    static final class SyntheticHdPay implements HdPayGateway {
        final AtomicInteger creates=new AtomicInteger();final Map<String,BigDecimal> amounts=new ConcurrentHashMap<>();final Set<String> paid=ConcurrentHashMap.newKeySet();
        public PayPage createPayOrder(CreatePayOrder order){creates.incrementAndGet();amounts.put(order.merchantOrderId(),order.transAmt());throw new HdPayGatewayException("SYNTHETIC_CREATE_RESPONSE_LOST",true);}
        public PayOrder queryPayOrder(String no){if(!amounts.containsKey(no)||!paid.contains(no))throw new HdPayGatewayException("SYNTHETIC_QUERY_OUTCOME_UNKNOWN",true);return new PayOrder(no,"SYNTH-"+no,3,amounts.get(no),"BANKQR",null);}
    }

    static class Fixture implements AutoCloseable {
        final Clock clock=spy(Clock.systemUTC());
        final String schema="outstanding_pay_http_"+UUID.randomUUID().toString().replace("-","");
        DataSource source;JdbcTemplate jdbc,admin;SqlSessionTemplate session;DataSourceTransactionManager tx;AuthSessionMapper authSessions;
        final ObjectMapper json=JSON;final SyntheticHdPay provider=new SyntheticHdPay();final HdPayProperties hd=new HdPayProperties();
        final CregisGateway bscProvider=mock(CregisGateway.class);final BscDepositProof bscChain=mock(BscDepositProof.class);
        final String bscAddress="0x"+"1".repeat(40),allocationHash="0x"+"a".repeat(64);
        AuditLogService audit;PlatformConfigFacadeAdapter configs;AppPaymentPasswordService pin;AdminIdempotencyService commands;
        AppOrderCommandService orders;PurchasePaymentService payments;PurchasePaymentPolicyService policy;JwtTokenProvider jwt;
        void install() throws Exception {
            String original=System.getenv("PAYMENT_PIN_TEST_DB_URL"),username=System.getenv("PAYMENT_PIN_TEST_DB_USERNAME"),password=System.getenv("PAYMENT_PIN_TEST_DB_PASSWORD");
            admin=new JdbcTemplate(new DriverManagerDataSource(original,username,password));assertThat(admin.queryForObject("SELECT @@port",Integer.class)).isIn(33343,33348);admin.execute("CREATE DATABASE "+schema);
            String url=original.replaceFirst("/(?:payment_pin_backend_test|outstanding_pay_test)","/"+schema);
            source=new DriverManagerDataSource(url,username,password);jdbc=new JdbcTemplate(source);tx=new DataSourceTransactionManager(source);
            String base=Files.readString(Path.of("scripts/schema.sql")).replaceFirst("CREATE DATABASE[^;]+;","").replaceFirst("USE nexion;","");
            sql(base,"base-schema");
            sql(Files.readString(Path.of("scripts/seed.sql")).replaceFirst("USE nexion;",""),"source-system-seed");
            sql(Files.readString(Path.of("scripts/fresh_db/00_app_support_schema.sql")),"support-fresh-bootstrap");
            for(String file:List.of("20260929_support_binding_s3_preflight.sql","20260929_support_binding_rules_schema_20261015.sql","20260929_support_binding_s3.sql","20260929_support_ticket_source_s3.sql"))migrate(file);
            var prereqs=Files.readAllLines(Path.of("scripts/fresh_db/prereqs.txt"));
            var manifest=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
            int first=manifest.indexOf("$migrations = @("),last=manifest.indexOf("\n)",first);
            var matcher=java.util.regex.Pattern.compile("scripts\\\\migrations\\\\([^\"]+\\.sql)").matcher(manifest.substring(first,last));
            while(matcher.find()){
                String file=matcher.group(1);
                // This source-provided fresh-bootstrap rule is a DML postcondition requiring a console-created S1.
                if(file.equals("20260921_h2_trial_product_daily_yield.sql"))continue;
                for(String line:prereqs)if(!line.startsWith("#")&&line.startsWith(file+"|")){
                    Path prerequisite=Path.of("scripts",line.split("\\|",2)[1]);sql(Files.readString(prerequisite),prerequisite.toString());
                }
                migrate(file);
            }
            for(String line:Files.readAllLines(Path.of("scripts/fresh_db/post.txt")))if(!line.isBlank()&&!line.startsWith("#")){
                Path post=Path.of("scripts",line);sql(Files.readString(post),post.toString());
            }
            // Existing upgraded deployments own this historical E5 event contract; fresh_db does not restore it.
            migrate("20260721_e5_device_operations.sql");
            migrate("20260721_e4_order_state_machine.sql");
            // Cancellation also reads the historical H7 voucher contract, absent from the fresh startup list.
            migrate("20260722_h7_voucher_grant.sql");
            // The network-aware service also requires its separately versioned schema contract.
            migrate("20261031_z_cregis_erc20_network.sql");
        }
        void migrate(String file) throws Exception {sql(Files.readString(Path.of("scripts/migrations",file)),file);}
        void sql(String sql,String phase) throws Exception {
            if(!schema.matches("outstanding_pay_http_[0-9a-f]{32}")||!"outstanding_pay_http_".equals(System.getenv("HTTP_SETUP_DB_ALLOWED_SCHEMA_PREFIX")))throw new IllegalStateException("Owned HTTP schema setup is required");
            var command=new ProcessBuilder("D:/software/MySQL/MySQL Server 8.0/bin/mysql.exe","--no-defaults","--host=127.0.0.1","--port="+java.net.URI.create(System.getenv("PAYMENT_PIN_TEST_DB_URL").substring(5)).getPort(),"--user="+System.getenv("HTTP_SETUP_DB_USERNAME"),"--database="+schema,"--default-character-set=utf8mb4");
            command.environment().put("MYSQL_PWD",System.getenv("HTTP_SETUP_DB_PASSWORD"));command.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            // Local admin is confined to this initializer connection; runtime stays the restricted pin_test user.
            Process process=command.start();process.getOutputStream().write(("SET SESSION sql_log_bin=0;\n"+sql).getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();String error=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
            if(process.waitFor()!=0)throw new IllegalStateException("Isolated schema phase "+phase+": "+error);
        }
        @SuppressWarnings("unchecked") <T>T proxy(T value){var factory=new ProxyFactory(value);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
        <T>T mapper(Class<T> type){return session.getMapper(type);}
        void services(){
            var config=new MybatisConfiguration(new Environment("purchase-http",new SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());global.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC()));GlobalConfigUtils.setGlobalConfig(config,global);
            for(Class<?> type:List.of(PurchasePaymentMapper.class,AppOrderCommandMapper.class,PaymentPasswordMapper.class,AdminIdempotencyRecordMapper.class,
                    PlatformConfigItemMapper.class,AuditLogMapper.class,AdminMapper.class,EventOutboxMapper.class,AuthSessionMapper.class,UserOpsMapper.class,AdminAccountStateMapper.class,VietnamPaymentMapper.class,CregisDepositPolicyMapper.class,ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper.class))config.addMapper(type);
            session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var sessionProxy=new ProxyFactory(session.getMapper(AuthSessionMapper.class));sessionProxy.setInterfaces(AuthSessionMapper.class);
            sessionProxy.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));authSessions=(AuthSessionMapper)sessionProxy.getProxy();
            var repository=new MybatisPlatformConfigRepository(mapper(PlatformConfigItemMapper.class));configs=new PlatformConfigFacadeAdapter(repository);
            audit=new AuditLogService(mapper(AuditLogMapper.class),new AuditLogSanitizer(json),new ApplicationNameProperties(),new AuditProperties(),mapper(AdminMapper.class),new A2RuntimePolicy(repository));
            var expiry=proxy(new AdminIdempotencyExpiryTransitionExecutor(mapper(AdminIdempotencyRecordMapper.class)));
            commands=new AdminIdempotencyService(proxy(new AdminIdempotencyTransactionExecutor(mapper(AdminIdempotencyRecordMapper.class),json,expiry)),Clock.systemUTC());
            pin=spy(new AppPaymentPasswordService(mapper(PaymentPasswordMapper.class),json,Clock.systemUTC(),mock(UserOtpDeliveryService.class),configs,audit,tx,"synthetic-http-pepper-material-32-bytes","v1",5,900,900));
            var env=new MockEnvironment();env.setActiveProfiles("dev");
            var attribution=mock(ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.class,invocation->{throw new AssertionError("External principal reached wallet attribution");});
            var outbox=new EventOutboxService(mapper(EventOutboxMapper.class),json,new OutboxProperties(),new A4RuntimePolicyService(repository));
            orders=proxy(new AppOrderCommandService(mapper(AppOrderCommandMapper.class),commands,audit,new FundsSandboxProfileGuard(new FundsSandboxProperties(),env),null,null,null,outbox,30,null,attribution,pin, org.mockito.Mockito.mock(ffdd.opsconsole.device.application.TestCloudActivationAdmission.class)));
            hd.setMode(HdPayProperties.Mode.PROVIDER);hd.setBaseUrl("http://127.0.0.1:18999/api/order");hd.setAllowInsecureBaseUrlForTests(true);hd.setMerchantId("123456");hd.setMd5Key("synthetic-hdpay-key-32-characters");hd.setServerIp("8.8.8.8");hd.setCallbackBaseUrl("https://callback.synthetic.invalid");hd.setCallbackHosts(List.of("callback.synthetic.invalid"));hd.setPaymentPageHosts(List.of("pay.synthetic.invalid"));
            var cr=new CregisProperties();cr.setMode(CregisProperties.Mode.PROVIDER);cr.setDepositEnabled(true);cr.setDepositCreditEnabled(true);cr.setProjectId(99);cr.setDepositConfirmations(15);cr.setBscRpcUrl("https://bsc.synthetic.invalid");
            var router=mock(CregisGatewayRouter.class);when(router.provider()).thenReturn(bscProvider);
            when(bscProvider.projectCoins()).thenReturn(List.of(new CregisGateway.Coin(CregisNetwork.BEP20.currency(),"USDT-BEP20","2510",CregisNetwork.BEP20.tokenId(),true,true)));
            when(bscProvider.addressBelongs("2510",bscAddress)).thenReturn(true);when(bscProvider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY,bscAddress)).thenReturn(true);
            when(bscChain.zeroUsdtBalance(CregisNetwork.BEP20,bscAddress)).thenReturn(true);when(bscChain.head(CregisNetwork.BEP20)).thenReturn(new BscDepositProof.Head(100,allocationHash));when(bscChain.blockHash(CregisNetwork.BEP20,100)).thenReturn(allocationHash);
            policy=new PurchasePaymentPolicyService(mapper(PurchasePaymentMapper.class),new CregisDepositPolicyService(mapper(CregisDepositPolicyMapper.class),audit,cr),commands,audit,json);
            payments=new PurchasePaymentService(mapper(PurchasePaymentMapper.class),policy,orders,mock(AppTradeinService.class),mock(AppTrialLifecycleService.class),hd,provider,cr,router,bscChain,mapper(VietnamPaymentMapper.class),tx,json,audit,commands, org.mockito.Mockito.mock(ffdd.opsconsole.device.application.TestCloudActivationAdmission.class),clock);
            var properties=new JwtProperties();properties.setSecret("synthetic-http-jwt-signing-material-only-32-bytes");jwt=new JwtTokenProvider(properties);
        }
        void seed(){
            // Model an explicitly published A4 checkout schema: the historical L1 migration advances
            // its revision but leaves the original order_id property on the earlier revision.
            jdbc.update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id SET p.registry_revision=s.current_revision WHERE s.event_name='checkout.completed' AND p.property_name='order_id' AND p.is_deleted=0");
            for(long id:List.of(7L,8L)){
                jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES(?,'+84',?,'127.0.0.1','synthetic-login-unused',? ,?,'ACTIVE',0)",id,"91234567"+id,"Synthetic HTTP "+id,"HTTP-"+id);
                jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available) VALUES(?,0,0)",id);
                jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,session_group,expires_at,last_active_at) VALUES(?,?,?,'H5',DATE_ADD(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR))",id,"http-session-"+id,"http-chain-"+id);
                jdbc.update("INSERT INTO nx_onboarding_calibration(user_id,device_id,signal_json,derived_json,comparison_json,config_revision,idempotency_key,request_hash,activation_status) VALUES(?,?,'{}','{}','{}',1,?,?,'DEFERRED')",id,"HTTP-DEVICE-"+id,"http-onboarding-"+id,"a".repeat(64));
            }
            jdbc.update("INSERT INTO nx_product(id,product_no,name,tier,product_type,inventory_mode,status,price_usdt,hashrate,estimated_daily_usdt,daily_nex,stock,gpu_model,vram_total_gb,generation,store_visible) VALUES(18,'HTTP-SKU','Synthetic HTTP GPU','S1','DEVICE','FINITE','ACTIVE',19.995,100,2,3,10,'GPU',24,1,1)");
            jdbc.update("INSERT INTO nx_admin_device_sku(sku_id,name,power_text,datacenter,status,purchase_gate_json) VALUES('HTTP-SKU','Synthetic HTTP GPU','100 W','Synthetic DC','ACTIVE',NULL)");
            for(String no:List.of("HTTP-ORDER-1","HTTP-ORDER-2")){
                jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status,activation_status,inventory_reserved) VALUES(7,?,18,1,'SINGLE',1,25,5.005,19.995,'PENDING','PENDING_PAYMENT','WAITING_PAYMENT',0)",no);
                jdbc.update("INSERT INTO nx_order_item(order_no,product_id,product_no,product_name,quantity,unit_price_usdt,line_amount_usdt,sort_order) VALUES(?,18,'HTTP-SKU','Synthetic HTTP GPU',1,25,19.995,0)",no);
            }
            jdbc.update("INSERT INTO nx_finance_fx_quote_config(config_code,base_rate_vnd_per_usdt,buy_spread_pct,lock_window_minutes,version) VALUES('VND_USDT',25000,0,30,1) ON DUPLICATE KEY UPDATE base_rate_vnd_per_usdt=25000,buy_spread_pct=0,is_deleted=0");
            jdbc.update("INSERT INTO nx_cregis_provision_gate(id,state,assign_enabled,credit_enabled) VALUES(1,'IDLE',1,1) ON DUPLICATE KEY UPDATE state='IDLE',assign_enabled=1,credit_enabled=1");
            jdbc.update("INSERT INTO nx_cregis_deposit_address(project_id,chain_id,address,request_id,state,purpose) VALUES(99,'2510',?,'synthetic-http-pool-original','UNASSIGNED','WALLET_TOPUP')",bscAddress);
            policy.update("http-fee-config",values("expectedVersion",1,"reason","Explicit synthetic HTTP fee configuration", "channels",List.of(
                    values("paymentMethod","HDPAY_BANK","channelFeeRatePct","0.00","channelFeeFixed","0.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"),
                    values("paymentMethod","CREGIS_USDT_BSC","channelFeeRatePct","0.00","channelFeeFixed","0.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal",policy.channel("CREGIS_USDT_BSC").get("minTopupPrincipal")))));
            assertThat(hd.ready()).isTrue();assertThat(mapper(UserOpsMapper.class).isOnboardingComplete(7L)).isTrue();
            assertThat(authSessions.touchActiveUserSession("http-session-7",7L,7)).isEqualTo(1);
            assertThat(mapper(EventOutboxMapper.class).findActiveSchema("admin.device_activated")).isNotNull();
            assertThat(mapper(EventOutboxMapper.class).findActiveSchema("checkout.completed")).isNotNull();
        }
        void publishSyntheticBscTransfer(){
            String txid="0x"+"2".repeat(64),block="0x"+"3".repeat(64);var amount=new BigDecimal("20.00");
            when(bscChain.head(CregisNetwork.BEP20)).thenReturn(new BscDepositProof.Head(120,"0x"+"b".repeat(64)));
            when(bscChain.scan(CregisNetwork.BEP20,101,106,List.of(bscAddress))).thenReturn(List.of(new BscDepositProof.Observation(txid,1,bscAddress,amount,105)));
            when(bscChain.verifyExact(eq(CregisNetwork.BEP20),eq(txid),eq(1),eq(bscAddress),argThat(value->value!=null&&value.compareTo(amount)==0),eq(105L)))
                    .thenReturn(new BscDepositProof.TransferProof(1,105,block,16,"0x"+"4".repeat(40),bscAddress,amount,true));
            when(bscProvider.depositsByTxid(CregisNetwork.BEP20,txid)).thenReturn(List.of(new CregisGateway.DepositTrade(44,"2510",CregisConstants.USDT_BEP20_TOKEN_ID,bscAddress,amount,txid,1)));
        }
        public void close(){if(schema.matches("outstanding_pay_http_[0-9a-f]{32}"))admin.execute("DROP DATABASE "+schema);}
    }

    @TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,RedisAutoConfiguration.class,RedisReactiveAutoConfiguration.class,RedisRepositoriesAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
    @Import({SecurityConfig.class,GlobalExceptionHandler.class})
    static class Wire {
        @Bean AuditLogService audit(){return fixture.audit;}
        @Bean AppOrderCommandService orders(){return fixture.orders;}
        @Bean PurchasePaymentService payments(){return fixture.payments;}
        @Bean PurchasePaymentPolicyService policy(){return fixture.policy;}
        @Bean AppPaymentPasswordService pin(){return fixture.pin;}
        @Bean ExternalPurchaseCommandQuery external(){return new ExternalPurchaseCommandQueryService(fixture.mapper(PurchasePaymentMapper.class),JSON);}
        @Bean AppPurchasePaymentController purchases(PurchasePaymentService payments,PurchasePaymentPolicyService policy){return new AppPurchasePaymentController(payments,policy);}
        @Bean AppOrderCommandController orderController(AppOrderCommandService orders){return new AppOrderCommandController(orders);}
        @Bean AppPaymentPasswordController pinController(AppPaymentPasswordService pin,ObjectProvider<ExternalPurchaseCommandQuery> external){return new AppPaymentPasswordController(pin,external);}
        @Bean HdPayPayInCallbackController callback(ObjectProvider<PurchasePaymentService> direct){return new HdPayPayInCallbackController(new HdPayCallbackService(fixture.provider,new HdPayCallbackVerifier(fixture.hd),mock(HdPayCallbackSettlementService.class),direct));}
        @Bean AdminRbacAuthorizationFilter rbac(){return new AdminRbacAuthorizationFilter(fixture.audit,fixture.mapper(AdminAccountStateMapper.class));}
        @Bean UserBusinessWriteGateFilter businessGate(){return new UserBusinessWriteGateFilter(fixture.mapper(UserOpsMapper.class));}
        @Bean JwtAuthenticationFilter jwt(org.springframework.core.env.Environment environment){return new JwtAuthenticationFilter(fixture.jwt,fixture.authSessions,fixture.mapper(UserOpsMapper.class),environment,new GatewaySecurityProperties(),mock(AdminSessionRegistry.class),mock(AdminPermissionCache.class),mock(ImpersonationSessionVerifier.class),fixture.configs,user->false,mock(UserSessionRevocationService.class));}
    }
}
