package ffdd.opsconsole.promotion;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated SQL, audit, authority resolution and retained commands; never seeds approval state. */
public final class PromotionRuntimeHarness {
    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final PromotionMapper db;
    public final SqlSessionTemplate session;
    public final PlatformConfigFacadeAdapter config;
    public final A2RuntimePolicy a2;
    public final AuditLogService audit;
    public final AdminIdempotencyService idempotency;
    public final RiskReleaseParamsService earningsPolicy;
    public final PromotionNativeContractResolver natives;
    public final PromotionPolicyResolver resolver;
    public final PromotionPolicyService policies;
    public final PromotionContractValidator validator;
    public final PromotionAdminService admin;
    public final String run="PGR-"+UUID.randomUUID().toString().substring(0,12);
    public final long maker=790000000001L,checker=790000000002L,publisher=790000000003L;
    public String evidence;

    public PromotionRuntimeHarness() throws Exception {
        dataSource=RuntimePool.INSTANCE;jdbc=new JdbcTemplate(dataSource);
        assertEquals("growth_promotions_20261007",jdbc.queryForObject("SELECT DATABASE()",String.class));assertEquals(33339,jdbc.queryForObject("SELECT @@port",Integer.class));
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();var mybatis=new MybatisConfiguration(new Environment(run,new SpringManagedTransactionFactory(),dataSource));
        var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());global.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC()));GlobalConfigUtils.setGlobalConfig(mybatis,global);mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.setCallSettersOnNulls(true);mybatis.setReturnInstanceForEmptyRow(true);mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        for(Class<?> mapper:List.of(PlatformConfigItemMapper.class,AdminMapper.class,AdminIdempotencyRecordMapper.class,AuditLogMapper.class,
                ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.class,ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper.class,
                ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class,ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.class,PromotionMapper.class))mybatis.addMapper(mapper);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis));
        db=session.getMapper(PromotionMapper.class);
        var repository=new MybatisPlatformConfigRepository(session.getMapper(PlatformConfigItemMapper.class));config=new PlatformConfigFacadeAdapter(repository);a2=new A2RuntimePolicy(repository);
        audit=new AuditLogService(session.getMapper(AuditLogMapper.class),new AuditLogSanitizer(json),new ApplicationNameProperties(),new AuditProperties(),session.getMapper(AdminMapper.class),a2);
        var idemMapper=session.getMapper(AdminIdempotencyRecordMapper.class);var expiry=proxy(new AdminIdempotencyExpiryTransitionExecutor(idemMapper));
        idempotency=new AdminIdempotencyService(proxy(new AdminIdempotencyTransactionExecutor(idemMapper,json,expiry)),Clock.systemUTC());
        earningsPolicy=new RiskReleaseParamsService(config,idempotency,audit,null,false);
        natives=new PromotionNativeContractResolver(db,config,earningsPolicy,a2,new MockEnvironment().withProperty("spring.profiles.active","test"));ReflectionTestUtils.setField(natives,"orderTtlMinutes",30);
        validator=new PromotionContractValidator(json);resolver=new PromotionPolicyResolver(db,validator,natives);policies=new PromotionPolicyService(db,validator,resolver,natives);
        admin=new PromotionAdminService(db,validator,resolver,policies,natives,null,null,null,null,idempotency,audit,a2,null);
        authenticate(maker);
        var request=values("reason","隔离治理契约真实验收说明","draft",values("category","PROMOTION","template","SKU_GIFT"));
        admin.command("createPromotion","promotions",run+"-evidence",request,()->admin.create(request));
        evidence=text(db.requiredRow("SELECT id FROM nx_audit_log WHERE actor_id=? AND action='CREATEPROMOTION' ORDER BY id DESC LIMIT 1",maker).get("id"));
    }
    public void authenticate(long actor,String... selected){
        List<String> authorities=selected.length>0?Arrays.asList(selected):db.list("SELECT permission_code FROM nx_admin_permission WHERE permission_code LIKE 'growth_promotion_%' AND status=1 AND is_deleted=0").stream().map(r->text(r.get("permission_code"))).toList();
        var auth=new UsernamePasswordAuthenticationToken(actor,"",authorities.stream().map(SimpleGrantedAuthority::new).toList());auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private static final class RuntimePool {
        private static final HikariDataSource INSTANCE=create();
        private static HikariDataSource create(){
            Map<String,String> credentials=new HashMap<>();
            try{
                for(String line:Files.readAllLines(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/mysql/client.private.ini"))){
                    if(!line.contains("=")||line.trim().startsWith("#"))continue;
                    String[] pair=line.split("=",2);String value=pair[1].trim();
                    if(value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);
                    credentials.put(pair[0].trim(),value);
                }
            }catch(java.io.IOException failure){throw new IllegalStateException("Isolated promotion database credentials unavailable",failure);}
            var pool=new HikariDataSource();
            pool.setJdbcUrl("jdbc:mysql://127.0.0.1:33339/growth_promotions_20261007?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&sessionVariables=time_zone='%2B08:00'");
            pool.setUsername(credentials.get("user"));pool.setPassword(credentials.get("password"));
            // Reuse bounded physical sockets across all scenarios, including the 51-obligation queue race.
            pool.setMaximumPoolSize(8);pool.setMinimumIdle(0);pool.setPoolName("promotion-isolated-test");
            Runtime.getRuntime().addShutdownHook(new Thread(pool::close,"promotion-test-pool-close"));
            return pool;
        }
    }
    @SuppressWarnings("unchecked") public <T>T proxy(T target){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    public ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade paymentAttribution(){
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        var mybatis=new MybatisConfiguration(new Environment(run+"-payment-attribution",new SpringManagedTransactionFactory(),dataSource));
        mybatis.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper.class,
                ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.class,
                ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper.class))mybatis.addMapper(mapper);
        var attributionSession=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis));
        var history=new ffdd.opsconsole.finance.application.SupportPaymentFactService(attributionSession.getMapper(ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper.class));
        var sources=proxy(new ffdd.opsconsole.finance.application.SupportPaymentSourceService(
                attributionSession.getMapper(ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.class),history,dataSource,json));
        return proxy(new ffdd.opsconsole.content.application.SupportPaymentAttributionService(
                attributionSession.getMapper(ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper.class),sources,audit,dataSource,json));
    }
    public Map<String,Object> nativeRef(String system,String resource){var value=natives.nativeContract(system,resource);value.remove("content");return value;}
    public Map<String,Object> approvePolicy(Map<String,Object> content){
        authenticate(maker);var request=values("reason","隔离治理政策真实审批依据","content",content,"evidenceRefs",List.of(evidence));
        String key=run+"-"+id("policy");var receipt=admin.command("createPolicy","promotion-policies",key,request,()->policies.create(null,request));String target=text(map(receipt.get("resource")).get("id"));String id=target.substring(0,target.lastIndexOf(':'));
        // Policy approval validates real evidence from its creator; no fixture field can authorize it.
        var action=values("expectedRevision",1,"reason","依据原合同批准隔离验收政策","evidenceRefs",List.of(evidence));
        admin.command("approvePolicy",target,key+"approve",action,()->policies.transition(id,1,"approve",action,maker));
        var actual=policies.get(id,1);assertEquals("APPROVED",actual.get("state"));return values("policyId",id,"version",1,"contentHash",actual.get("contentHash"));
    }
    public Map<String,Object> commonPolicies(){
        var order=nativeRef("ORDER_CONTRACT","WALLET_ORDER_V1");
        var settlement=approvePolicy(values("kind","SETTLEMENT","executorCode","EXISTING_ORDER_MATURITY_V1","nativeContract",order,"requiresPaid",true,"requiresNoRefundHold",true,"requiresNoFreeze",true));
        var stacking=approvePolicy(values("kind","STACKING","executorCode","PROMOTION_STACKING_V1","voucher","ALLOW","bundleDiscount","ALLOW","existingH8","ALLOW","existingReferral","ALLOW","samePurchaseUnit","EXCLUSIVE"));
        var refund=approvePolicy(values("kind","REFUND","executorCode","PROMOTION_WALLET_REFUND_V1","principalChannel","WALLET","scope","WHOLE_ORDER","insufficientRecovery","MANUAL_REVIEW","negativeBalance",false,"crossAssetDeduction",false,"deviceRecoveryPolicy",order,"lossOwner",run,"terms",localized("隔离真实审批的退款依据")));
        var quote=approvePolicy(values("kind","QUOTE","executorCode","PROMOTION_QUOTE_V1","ttlSeconds",300,"payByRule","MIN_ORDER_DEADLINE_ACTIVITY_END"));
        var auth=approvePolicy(values("kind","AUTHORIZATION","executorCode","PROMOTION_AUTHORIZATION_V1","approvalCapability","growth_promotion_approve","publishCapability","growth_promotion_publish","separateMakerChecker",true,"scope","PROMOTION_ONLY","nativeContract",nativeRef("A2_POLICY","admin.a2.schema_version")));
        return values("firstPurchase",null,"settlement",settlement,"stacking",stacking,"refund",refund,"quote",quote,"authorization",auth);
    }
    public Map<String,Object> assetPolicy(String asset){return approvePolicy(values("kind","ASSET","executorCode","EXISTING_EARNINGS_RELEASE_V1","asset",asset,"nativeContract",nativeRef("EARNINGS_RELEASE","risk.k1.release.version"),"riskBucketRouting",true,"availabilityDescription",localized("依据当前真实风险释放规则")));}
    public Map<String,Object> devicePolicy(String product){return approvePolicy(values("kind","DEVICE_RIGHTS","executorCode","COMMERCE_GIFT_FROM_PRODUCT_V1","productNo",product,"productContract",nativeRef("E1_PRODUCT",product),"taskEligibility","EXISTING_PRODUCT_RULES","rightsDescription",localized("隔离验收复用现行商品权益")));}
    public String publish(Map<String,Object> draft){
        authenticate(maker);var request=values("reason","建立隔离真实活动验收配置","draft",draft);String key=run+id("create");
        var receipt=admin.command("createPromotion","promotions",key,request,()->admin.create(request));String id=text(map(receipt.get("resource")).get("id"));
        transition(id,"submit",maker,1);transition(id,"approve",checker,2);transition(id,"publish",publisher,3);assertEquals("ACTIVE",admin.get(id).get("state"));return id;
    }
    public void transition(String id,String action,long actor,long revision){authenticate(actor);var request=values("expectedRevision",revision,"version",1,"reason","执行隔离真实活动审批验收","evidenceRefs",List.of(evidence));admin.command(action+"Promotion",id,run+id(action),request,()->admin.versionAction(id,action,request));}
    public static Map<String,Object> localized(String text){return values("zh",text,"en",text,"vi",text);}
    public Map<String,Object> contract(String product,Map<String,Object> reward,Map<String,Object> policies){
        var limit=values("mode","LIMITED","value",100);return values("name",run,"category","PROMOTION","template","SKU_GIFT","startsAt",Instant.now().minusSeconds(60).toString(),"endsAt",Instant.now().plusSeconds(3600).toString(),"displayTimezone","Asia/Tokyo","title",localized(run),"terms",localized("仅隔离验收真实批准合同"),"placement",null,"minimumClientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1"),
            "buyerAudience",values("registrationAge",values("minDays",null,"maxDays",null),"purchaseHistory","ANY","devicePresence","ANY","deviceAudiencePolicy",null,"rankIds",List.of(),"lastValidPurchaseAge",values("minDays",null,"maxDays",null),"neverPurchased",false,"markets",List.of(),"sponsor","ANY"),"inviterAudience",null,
            "rules",List.of(values("ruleId","r1","productNo",product,"minBuyQty",1,"repeatMode","PER_GROUP","maxGroups",limit,"maxGroupsPerPerson",limit,"priority",1,"buyerReward",reward,"inviterReward",null)),"combinationMatch","ANY","perPersonLimit",values("buyer",limit,"directInviter",limit),"maxRewardUnitsPerOrder",limit,
            "budgets",List.of("DEVICE".equals(reward.get("type"))?values("asset","DEVICE","productNo",reward.get("giftProductNo"),"total",100):values("asset",reward.get("type"),"total","10000.000000")),"shortagePolicy","RULE_STOP","policies",policies,"activityLimit",limit);
    }
}
