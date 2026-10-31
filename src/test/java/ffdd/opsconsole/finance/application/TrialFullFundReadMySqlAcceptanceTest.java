package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.Authorization;
import ffdd.opsconsole.auth.mapper.*;
import ffdd.opsconsole.content.application.SupportPaymentHistoryBirthService;
import ffdd.opsconsole.content.application.SupportPaymentCaptureHistoryService;
import ffdd.opsconsole.content.application.SupportPaymentAttributionService;
import ffdd.opsconsole.content.mapper.SupportPaymentHistoryBirthMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentCaptureHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.device.application.*;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.infrastructure.MybatisDeviceCatalogRepository;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.Outcome;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.growth.application.*;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.market.application.*;
import ffdd.opsconsole.market.infrastructure.MybatisNexMarketRepository;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.web.OpsNexMarketController;
import ffdd.opsconsole.market.mapper.*;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.platform.dto.*;
import ffdd.opsconsole.platform.domain.*;
import ffdd.opsconsole.platform.web.OpsAuditController;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.*;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.config.*;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.team.application.SupportDirectReferralReadService;
import ffdd.opsconsole.team.mapper.SupportDirectReferralReadMapper;
import ffdd.opsconsole.treasury.application.*;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.*;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.web.OpsTreasuryController;
import ffdd.opsconsole.user.application.UserRegistrationWriteAdapter;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One real service/SQL chain. No migrations, HTTP server, provider network, proof SQL or financial mocks.
 * The six earlier seeded-claim tests, including their UNKNOWN assertion, are deliberately untouched. */
@EnabledIfEnvironmentVariable(named="TRIAL_ACCEPTANCE_MYSQL_URL",
        matches="jdbc:mysql://127\\.0\\.0\\.1:33343/outstanding_trial_e01_[a-z0-9_]+(?:\\?.*)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrialFullFundReadMySqlAcceptanceTest {
    private final TrialRefundMySqlAcceptanceTest f=new TrialRefundMySqlAcceptanceTest();
    private final Clock clock=Clock.system(DateTimeFormatConfig.BUSINESS_ZONE);
    private final List<Map<String,Object>> stages=new ArrayList<>();
    private final String run=UUID.randomUUID().toString().replace("-","").substring(0,12);
    private final BigDecimal principal=new BigDecimal("1000");
    private Path evidencePath;
    private String phase="CONNECT";
    private long customer,admin,checker;
    private AuthNewAccountCreator accounts;
    private SupportPaymentHistoryBirthService paymentBirth;
    private EventOutboxService outbox;
    private AuditLogService audit;
    private PlatformConfigFacadeAdapter configs;
    private TreasuryCoverageFacadeAdapter coverage;
    private OpsTreasuryService treasury;
    private AdminPermissionCache permissions;
    private AnnotationConfigApplicationContext adminContext;
    private AppPaymentPasswordService pin;
    private AppTrialLifecycleService trials;
    private OpsDeviceService devices;
    private SupportFundReadService reader;
    private HdPayHostedDepositService hosted;
    private HdPayCallbackService callback;
    private final LocalHdPay gateway=new LocalHdPay();
    private HdPayProperties hd;
    private String product;

    @BeforeAll void reuseExistingRealSqlFixtureAndWireOnlyTheRequiredServices() {
        evidencePath=Path.of(Objects.requireNonNull(System.getenv("E01_FULL_READ_EVIDENCE"),"E01_FULL_READ_EVIDENCE"));
        f.connectAndWireProductionServices();
        // Reuse the fixture's session/transaction setup, then run all business SQL with the isolated APP grants.
        var dataSource=(org.springframework.jdbc.datasource.DriverManagerDataSource)f.source;
        dataSource.setUsername(Objects.requireNonNull(System.getenv("E01_FULL_READ_DB_USERNAME"),"E01_FULL_READ_DB_USERNAME"));
        dataSource.setPassword(Objects.requireNonNull(System.getenv("E01_FULL_READ_DB_PASSWORD"),"E01_FULL_READ_DB_PASSWORD"));
        var configuration=f.sessions.getConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(new MybatisMetaObjectHandler(clock));
        for(var type:List.of(UserOpsMapper.class,AuthRegistrationBirthMapper.class,PaymentPasswordMapper.class,
                AppTrialLifecycleMapper.class,CanonicalStateMapper.class,DeviceCatalogMapper.class,
                TestCloudRequestMapper.class,AuditObjectLockMapper.class,AppVietQrIntentMapper.class,
                VietnamPaymentMapper.class,HdPayOrderMapper.class,SupportGenesisSourceMapper.class,
                SupportExchangeSourceMapper.class,SupportStakingSourceMapper.class,SupportDeviceReadMapper.class,
                SupportCommissionReadMapper.class,SupportDirectReferralReadMapper.class,AdminRolePermissionMapper.class,
                AdminRoleRelationMapper.class,NexMarketMapper.class,ExchangeOrderMapper.class,StakingMapper.class,
                GenesisMapper.class,AuditOperationTicketMapper.class,AuditOperationHistoryMapper.class,
                AuditConfirmCategoryMapper.class)) configuration.addMapper(type);
        var repository=new MybatisPlatformConfigRepository(f.mapper(PlatformConfigItemMapper.class));
        configs=f.proxy(new PlatformConfigFacadeAdapter(repository));
        var a2=new A2RuntimePolicy(repository);
        audit=new AuditLogService(f.mapper(AuditLogMapper.class),new AuditLogSanitizer(f.json),
                new ApplicationNameProperties(),new AuditProperties(),f.mapper(AdminMapper.class),a2);
        outbox=new EventOutboxService(f.mapper(EventOutboxMapper.class),f.json,new OutboxProperties(),new A4RuntimePolicyService(repository));
        var genesis=new SupportGenesisReadService(f.mapper(SupportGenesisSourceMapper.class));
        var history=new SupportPaymentCaptureHistoryService(f.mapper(SupportPaymentCaptureHistoryMapper.class),f.mapper(SupportPaymentHistoryBirthMapper.class));
        f.finance=f.proxy(new SupportPaymentSourceService(f.mapper(SupportPaymentSourceMapper.class),
                new SupportPaymentFactService(f.mapper(SupportPaymentFactMapper.class),genesis),f.source,f.json,history,
                f.binder,f.mapper(SupportFundJournalMapper.class),f.mapper(EarningsReleaseMapper.class),genesis));
        f.attribution=f.proxy(new SupportPaymentAttributionService(f.mapper(SupportPaymentAttributionMapper.class),f.finance,audit,f.source,f.json));
        var ledger=f.proxy(new MybatisTreasuryLedgerRepository(f.mapper(TreasuryLedgerMapper.class),outbox,null));
        var seed=OpsReadTimeSeedPolicy.disabledForDirectConstruction();
        // dualLedger/reserve only: no emergency mutation or tamper-signal path is entered.
        treasury=f.proxy(new OpsTreasuryService(ledger,configs,null,null,audit,f.commands,outbox,clock,
                new TreasuryDualLedgerProperties(),f.json,seed,a2));
        coverage=new TreasuryCoverageFacadeAdapter(treasury);
        permissions=new AdminPermissionCache(null,f.mapper(AdminRolePermissionMapper.class));
        var roles=new AdminOperatorRoleResolver(f.mapper(AdminMapper.class),f.mapper(AdminRoleRelationMapper.class));
        var access=new A2AccessPolicy(roles,f.mapper(AuditOperationTicketMapper.class),permissions);
        // G3 override/overview never enters emergency, exchange or staking command paths.
        var market=new OpsNexMarketService(configs,coverage,new MybatisNexMarketRepository(f.mapper(NexMarketMapper.class),
                f.mapper(ExchangeOrderMapper.class),f.mapper(StakingMapper.class),f.mapper(GenesisMapper.class)),
                null,null,f.ledger,audit,f.json,clock,seed,permissions,f.mapper(AuditObjectLockMapper.class),
                f.mapper(ExchangeOrderMapper.class),null);
        var a2Commands=new OpsAuditCenterService(repository,audit,seed,f.mapper(AuditOperationTicketMapper.class),
                f.mapper(AuditOperationHistoryMapper.class),f.mapper(AuditConfirmCategoryMapper.class),
                f.mapper(AuditObjectLockMapper.class),new AuditReplayBusinessPermissionGuard(null,roles,null,null,configs),
                new AuditReplayDispatcher(List.of(market)),access,f.json,f.commands);
        // Original admin controllers and method security; no server or application component scan.
        adminContext=new AnnotationConfigApplicationContext();adminContext.register(AdminMethodSecurity.class);
        adminContext.registerBean("a2AccessPolicy",A2AccessPolicy.class,()->access);
        adminContext.registerBean("superAdminAuthorization",SuperAdminAuthorization.class,()->new SuperAdminAuthorization(f.mapper(AdminMapper.class)));
        adminContext.registerBean(OpsTreasuryController.class,()->new OpsTreasuryController(treasury));
        adminContext.registerBean(OpsNexMarketController.class,()->new OpsNexMarketController(market,
                f.proxy(new G2G3AdminCommandService(market,f.commands,outbox,null,audit)),null,null,null));
        adminContext.registerBean(OpsAuditController.class,()->new OpsAuditController(audit,a2Commands,roles,access,
                f.commands,f.json,a2,null));
        adminContext.refresh();
        var environment=new MockEnvironment();environment.setActiveProfiles("dev");
        var admission=new TestCloudActivationAdmission(f.mapper(TestCloudRequestMapper.class),environment,clock);
        var risk=new RiskReleaseParamsService(configs,f.commands,audit,coverage);
        f.earnings=f.proxy(new EarningsReleaseService(f.mapper(EarningsReleaseMapper.class),risk,f.commands,audit,
                new FundsSandboxProfileGuard(new FundsSandboxProperties(),environment),
                new SupportFundEarningsAdapter(f.binder,f.mapper(SupportFundJournalMapper.class),f.mapper(EarningsReleaseMapper.class),f.json)));
        var reversal=f.proxy(new TrialConversionRewardReversal(f.mapper(E4OrderRefundMapper.class),f.mapper(EarningsReleaseMapper.class),
                f.earnings,f.ledger,audit,f.manager));
        f.refunds=f.proxy(new E4OrderRefundSettlementFacadeAdapter(f.attribution,f.mapper(E4OrderRefundMapper.class),reversal));
        accounts=f.proxy(new AuthNewAccountCreator(f.proxy(new UserRegistrationWriteAdapter(f.mapper(UserOpsMapper.class))),
                f.mapper(AuthRegistrationBirthMapper.class),f.proxy(new SupportFundAuthBirthAdapter(
                f.mapper(SupportFundJournalMapper.class),f.binder,f.json)),f.source,f.json));
        paymentBirth=f.proxy(new SupportPaymentHistoryBirthService(f.mapper(SupportPaymentHistoryBirthMapper.class),f.source));
        // setup/verify use no OTP transport. No recovery/phone reset is exercised.
        pin=new AppPaymentPasswordService(f.mapper(PaymentPasswordMapper.class),f.json,clock,null,configs,audit,f.manager,
                "e01-local-fixture-pin-pepper-32-bytes", "v1",5,900,900);
        var catalog=new MybatisDeviceCatalogRepository(f.mapper(DeviceCatalogMapper.class),f.json);
        var release=new StorefrontProductReleasePolicy(catalog,new GrowthRhythmFacadeAdapter(configs,seed));
        trials=f.proxy(new AppTrialLifecycleService(pin,f.attribution,f.mapper(AppTrialLifecycleMapper.class),f.earnings,
                f.commands,coverage,audit,outbox,release,f.mapper(CanonicalStateMapper.class),environment,clock,admission));
        // Ordinary trial order: unrelated device-management/storage/promotion commands are not invoked.
        devices=f.proxy(new OpsDeviceService(f.attribution,null,catalog,configs,f.ledger,f.refunds,coverage,audit,
                f.commands,outbox,null,clock,seed,f.mapper(AuditObjectLockMapper.class),null,admission));
        hd=new HdPayProperties();hd.setMode(HdPayProperties.Mode.PROVIDER);
        hd.setBaseUrl("https://api.hdpayadmin.com/api/order");hd.setCallbackBaseUrl("https://payments.example.com");
        hd.setCallbackHosts(List.of("payments.example.com"));hd.setMerchantId("1234567890123456789");
        hd.setPaymentPageHosts(List.of("payments.example.com"));
        hd.setMd5Key("e01-local-signature-fixture-key-only");
        var intents=f.proxy(new AppVietQrIntentService(f.mapper(AppVietQrIntentMapper.class),
                new FinanceSensitiveDataCipher("e01-local-finance-cipher-material-32-bytes"),clock,environment,configs));
        hosted=new HdPayHostedDepositService(intents,hd,gateway,f.mapper(HdPayOrderMapper.class),audit,f.json);
        var settlement=f.proxy(new HdPayCallbackSettlementService(f.mapper(HdPayOrderMapper.class),
                f.mapper(AppVietQrIntentMapper.class),f.mapper(VietnamPaymentMapper.class),outbox,audit,ledger,clock,f.attribution));
        callback=new HdPayCallbackService(gateway,new HdPayCallbackVerifier(hd),settlement);
        var earningsEvidence=new SupportFundEarningsEvidence(f.binder,f.json);
        var exchanges=new SupportExchangeReadService(f.mapper(SupportExchangeSourceMapper.class));
        reader=new SupportFundReadService(f.mapper(SupportFundReadMapper.class),
                new SupportFundBirthReadService(f.mapper(SupportFundBirthReadMapper.class)),f.finance,f.binder,f.source,
                earningsEvidence,genesis,exchanges,
                new SupportFundGenesisEvidence(f.json,f.binder),new SupportFundExchangeEvidence(f.binder,f.json,exchanges),
                new SupportDeviceReadService(f.mapper(SupportDeviceReadMapper.class)),new SupportStakingReadService(f.mapper(SupportStakingSourceMapper.class)),
                new SupportFundDeviceEvidence(f.binder,f.json),new SupportFundStakingEvidence(f.binder,f.json,earningsEvidence),
                new SupportCommissionReadService(f.mapper(SupportCommissionReadMapper.class)),
                new SupportDirectReferralReadService(f.mapper(SupportDirectReferralReadMapper.class)),
                new SupportFundCommissionEvidence(f.binder,f.json),new SupportFundDirectReferralEvidence(f.binder,f.json));
    }

    @AfterAll void saveActualLastStage() throws Exception {
        SecurityContextHolder.clearContext();
        if(adminContext!=null) adminContext.close();
        if(evidencePath!=null) {
            Files.createDirectories(evidencePath.toAbsolutePath().getParent());
            Files.writeString(evidencePath,f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "run",run,"lastPhase",phase,"customer",customer,"stages",stages,
                    "scope","Real services/MySQL/method security/A2; isolated synthetic HDPay, D3 capital and G3 price; no HTTP/UI or real funds")));
        }
    }

    @Test void realBirthTopupPinConversionAndE4RefundMustKeepTheCompleteReaderReady() throws Exception {
        phase="ORDINARY_FIXTURE";ordinaryProductConfigAndActor();
        phase="SYNTHETIC_D3_G3_PREREQUISITES";installSyntheticCapitalAndPublishPrice();
        phase="AUTH_BIRTH";
        customer=f.tx.execute(status->{
            var birth=accounts.insertNew(new NewAccountCommand(null,null,"127.0.0.1",
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(run),
                    "E01 full chain", "E01"+run.toUpperCase(Locale.ROOT),null,null,"L1","V0","ACTIVE",0,"en-US"));
            paymentBirth.registerNewAccount(birth.customerId());accounts.ensureWallet(birth);
            var event=outbox.publish("USER_REGISTRATION",String.valueOf(birth.customerId()),"auth.register_completed",Map.of("userId",birth.customerId()));
            assertThat(accounts.complete(birth,event)).isEqualTo(Outcome.BOUND);
            return birth.customerId();
        });
        ready("auth-zero",BigDecimal.ZERO,BigDecimal.ZERO);
        phase="LOCAL_SIGNED_HDPAY_PRINCIPAL";authenticate(customer,"USER","user:"+customer);
        var intent=ok(hosted.create(customer,"e01-topup-"+run,principal,"127.0.0.1"));
        String intentNo=String.valueOf(intent.get("intentNo"));
        var fields=new LinkedHashMap<String,String>();fields.put("merchantId",hd.getMerchantId());fields.put("merchantOrderId",intentNo);
        fields.put("orderId",gateway.providerId(intentNo));fields.put("orderStatus","3");
        fields.put("transAmt",gateway.amounts.get(intentNo).setScale(2).toPlainString());fields.put("signType","MD5");
        var body=f.json.valueToTree(fields);((com.fasterxml.jackson.databind.node.ObjectNode)body)
                .put("orderStatus",3).put("sign",HdPaySigner.sign(fields,hd.getMd5Key()));
        assertThat(callback.accept(body)).isEqualTo("success");
        var topupWitness=operations();assertThat(callback.accept(body)).isEqualTo("success");assertThat(operations()).isEqualTo(topupWitness);
        ready("topup-principal",principal,BigDecimal.ZERO);
        phase="PIN_AND_TRIAL_START";
        ok(pin.setup(customer,"e01-pin-"+run,new AppPaymentPasswordService.Setup("246810","246810")));
        ok(trials.start(customer,null,"E01 full chain","e01-start-"+run));
        var quote=ok(trials.externalPaymentPricing(customer,clock.instant()));
        stage("authoritative-trial-quote",quote);
        // Match the App's two-decimal confirmed input; preserve the authoritative pricing snapshot.
        var amount=number(quote.get("amountUsdt")).setScale(2,java.math.RoundingMode.HALF_UP);
        String quoteVersion=String.valueOf(quote.get("quoteVersion"));
        phase="REAL_PIN_TRIAL_CONVERSION";
        var conversion=ok(trials.convert(customer,product,amount,quoteVersion,"e01-convert-"+run,
                new Authorization("246810",pin.state(customer).credentialVersion(),"e01-attempt-"+run)));
        String order=String.valueOf(conversion.get("orderNo"));
        String claim=f.jdbc.queryForObject("SELECT claim_no FROM nx_trial_claim WHERE user_id=?",String.class,customer);
        var fixture=new TrialRefundMySqlAcceptanceTest.Claim(customer,claim,order);
        phase="COMPLETE_READER_BEFORE_REFUND";
        f.assertBalance(customer,principal.subtract(amount).add(number(quote.get("remainderUsdt"))).toPlainString(),number(quote.get("rewardNex")).toPlainString());
        stage("real-conversion-committed",Map.of("receipt",conversion,"wallet",f.wallet(customer)));
        // NetCustomer is net contributed principal, separately from wallet cash and reward balances.
        ready("conversion-before-refund",principal,BigDecimal.ZERO);
        var originalOperations=operations();
        var originalRaw=f.jdbc.queryForList("SELECT * FROM nx_support_wallet_journal WHERE new_user_id=? ORDER BY revision",customer);
        var coverageBefore=coverage.snapshot();stage("actual-coverage-before-refund",coverageBefore);
        assertThat(coverageBefore.reliable()).as("actual reserve/liability/UVT valuation prerequisite").isTrue();
        phase="FORMAL_E4_REFUND";authenticate(admin,"ADMIN","e01-refund-"+run);
        String refundReason="E01 isolated synthetic acceptance "+"r".repeat(150);
        var request=new DeviceOrderActionRequest(null,"WALLET",refundReason,"e01-refund-"+run);
        ok(devices.refundOrder(order,"e01-refund-"+run,request));
        stage("e4-committed",Map.of("wallet",f.wallet(customer),"recoveries",f.recoveries(fixture),"actions",f.actions(fixture)));
        assertThat(f.actions(fixture)).containsExactly("H2_TRIAL_REFUND_REWARD_PENDING","H2_TRIAL_REFUND_REWARD_REVERSED");
        f.assertBalance(customer,principal.toPlainString(),"0");
        assertThat(f.recoveries(fixture)).hasSize(2).allSatisfy(row->{
            assertThat(number(row.get("outstanding"))).isZero();
            assertThat(number(row.get("recovered"))).isEqualByComparingTo(number(row.get("requested")));
        });
        f.assertJournalBindings(fixture,2);
        assertThat(f.jdbc.queryForObject("SELECT payment_status FROM nx_order WHERE order_no=?",String.class,order)).isEqualTo("REFUNDED");
        assertThat(f.jdbc.queryForMap("SELECT status,ownership_status FROM nx_user_device WHERE source_order_no=?",order))
                .containsEntry("status","DEACTIVATED").containsEntry("ownership_status","REFUNDED");
        assertThat(f.jdbc.queryForObject("SELECT stock FROM nx_product WHERE product_no=?",Integer.class,product)).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_order_state_history WHERE order_no=? AND to_state='refunded'",Integer.class,order)).isOne();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE biz_no=? AND action='E4_ORDER_REFUNDED' AND result='SUCCESS'",Integer.class,order)).isOne();
        String remark=f.jdbc.queryForObject("SELECT remark FROM nx_wallet_ledger WHERE biz_no=? AND biz_type='ORDER_REFUND'",String.class,"E4-REFUND-"+order);
        String keyHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("e01-refund-"+run).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(remark.codePointCount(0,remark.length())).isEqualTo(255);
        assertThat(remark).startsWith("E4 order refund | key_hash_v2="+keyHash+" | orderNo="+order+" | operator=");
        stage("e4-key-marker-survives-long-reason",Map.of("remark",remark,"reasonCodePoints",refundReason.length(),"keyHash",keyHash));
        assertThat(operations()).containsAll(originalOperations);
        assertThat(f.jdbc.queryForList("SELECT * FROM nx_support_wallet_journal WHERE new_user_id=? ORDER BY revision",customer)).containsAll(originalRaw);
        var committed=f.witness(fixture);ok(devices.refundOrder(order,"e01-refund-"+run,request));assertThat(f.witness(fixture)).isEqualTo(committed);
        phase="COMPLETE_READER_AFTER_REFUND";
        // Expected to expose the frozen-trial-source/current-REFUNDED lifecycle conflict on 8fc85346.
        // Do not replace READY with UNKNOWN or edit raw snapshots/digests to make this test pass.
        ready("refund-and-afterCommit-recovery",principal,BigDecimal.ZERO);
        phase="PASS";
    }

    private void ordinaryProductConfigAndActor() {
        product="e01-trial-"+run;
        f.tx.executeWithoutResult(status->{
            // Only ordinary configuration and this run's product/actor are seeded; no wallet/proof/receipt SQL.
            configs.upsertAdminValue("finance.topup.channel.vietqr.enabled","true","BOOLEAN","finance","E01 isolated fixture");
            configs.upsertAdminValue("H1.rhythm.currentMonth","1","INTEGER","growth","E01 isolated fixture");
            configs.upsertAdminValue("growth.phase.current_month","1","INTEGER","growth","E01 isolated fixture");
            f.jdbc.update("INSERT INTO nx_admin_phase_config(scope,label,sort_order,status) SELECT 'E1',?,COALESCE(MIN(sort_order),0)-1,'active' FROM nx_admin_phase_config WHERE scope='E1'",product);
            long phaseId=f.jdbc.queryForObject("SELECT id FROM nx_admin_phase_config WHERE scope='E1' AND label=?",Long.class,product);
            f.jdbc.update("INSERT INTO nx_product(product_no,name,product_type,tier,status,price_usdt,estimated_daily_usdt,daily_nex,stock,inventory_mode,trial_eligible,store_status,unlock_phase,gpu_model,vram_total_gb) VALUES(?,?,'DEVICE','Entry','ACTIVE',260,38.52,10,2,'FINITE',1,'on',?,'Synthetic GPU',24)",product,product,String.valueOf(phaseId));
            f.jdbc.update("INSERT INTO nx_admin_device_sku(sku_id,name,power_text,datacenter,status,purchase_gate_json) VALUES(?,?,'100 W','Synthetic isolated DC','ACTIVE',NULL)",product,product);
            int seats=f.jdbc.queryForObject("SELECT COALESCE(MAX(claimed_count),0)+1 FROM nx_growth_trial_daily_quota WHERE quota_date=CURRENT_DATE()",Integer.class);
            for(var entry:Map.of("trialProductId",product,"trialDays","3","trialOffsetCapUSD","50","earlyPurchaseDiscountUSD","10","seatsLeftToday",String.valueOf(seats),"phaseOpen","true").entrySet())
                f.jdbc.update("INSERT INTO nx_growth_trial_policy(policy_key,policy_name,current_value) VALUES(?,?,?) ON DUPLICATE KEY UPDATE current_value=VALUES(current_value),is_deleted=0",entry.getKey(),entry.getKey(),entry.getValue());
            f.jdbc.update("INSERT INTO nx_emergency_control_setting(setting_key,setting_value,remark) VALUES('killswitch.trial','enabled','E01 isolated fixture') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),is_deleted=0");
            // These are the ordinary defaults from the original Vietnam payment configuration migration.
            f.jdbc.update("INSERT IGNORE INTO nx_vietqr_config(id,tolerance_vnd,grace_minutes,per_tx_limit_usd,trc20_confirmations,erc20_confirmations,bep20_confirmations,rotation_strategy) VALUES(1,1000,10,5000,20,12,15,'ROUND_ROBIN')");
            f.jdbc.update("INSERT IGNORE INTO nx_finance_fx_quote_config(config_code,base_rate_vnd_per_usdt,buy_spread_pct,lock_window_minutes) VALUES('VND_USDT',26000,1.50,30)");
            f.jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname) VALUES(?,'fixture-unused','E01 isolated refund')","e01-refund-"+run);
            admin=f.jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,"e01-refund-"+run);
            f.jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname) VALUES(?,'fixture-unused','E01 isolated checker')","e01-checker-"+run);
            checker=f.jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,"e01-checker-"+run);
            for(long actor:List.of(admin,checker)) {
                String role="E01_"+actor+"_"+run;
                f.jdbc.update("INSERT INTO nx_admin_role(role_code,role_name,remark) VALUES(?,?,'isolated synthetic acceptance')",role,role);
                long roleId=f.jdbc.queryForObject("SELECT id FROM nx_admin_role WHERE role_code=?",Long.class,role);
                f.jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES(?,?)",actor,roleId);
                for(String permission:List.of("finance_d3_injection_create","finprod_g3_override_price_write","platform_a2_read","platform_a2_write","platform_a2_operation_approve"))
                    assertThat(f.jdbc.update("INSERT INTO nx_admin_role_permission(role_id,permission_id) SELECT ?,id FROM nx_admin_permission WHERE permission_code=? AND status=1 AND is_deleted=0 AND resource_type='API'",roleId,permission)).as(permission).isOne();
            }
        });
        // Existing official PIN/risk policy, reserve and market prerequisites must be real.
        // Missing values fail at their actual owning service; this test never seeds monetary evidence.
    }

    private void installSyntheticCapitalAndPublishPrice() {
        var controller=adminContext.getBean(OpsTreasuryController.class);
        var request=new TreasuryInjectionRequest(BigDecimal.ONE,"DEV-E01-DENIED-"+run,"isolated synthetic acceptance permission denial",null);
        authenticate(admin,"ADMIN","e01-refund-"+run);
        var reserveBefore=f.jdbc.queryForList("SELECT * FROM nx_treasury_reserve_ledger ORDER BY id");
        assertThatThrownBy(()->controller.createInjection("e01-denied-"+run,request))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(f.jdbc.queryForList("SELECT * FROM nx_treasury_reserve_ledger ORDER BY id")).isEqualTo(reserveBefore);
        authenticateAdmin(admin,"e01-refund-"+run);
        var dual=ok(treasury.dualLedger());
        @SuppressWarnings("unchecked") var values=(Map<String,Object>)dual.get("snapshot");
        stage("native-b1-before-synthetic-capital",dual);
        // Retain the approved legacy opening; fund healthy coverage plus at most 1000 of this case's rewards.
        var capital=number(values.get("liabilitiesUsd")).add(principal).add(new BigDecimal("1000"))
                .multiply(number(values.get("healthyPct"))).divide(new BigDecimal("100"))
                .subtract(number(values.get("reserveUsd"))).max(BigDecimal.ZERO).setScale(0,java.math.RoundingMode.CEILING);
        assertThat(capital).isNotNegative().isLessThanOrEqualTo(new BigDecimal("500000"));
        if(capital.signum()>0) {
            var injection=new TreasuryInjectionRequest(capital,"DEV-E01-ISOLATED-"+run,"isolated synthetic acceptance capital; no real funds",null);
            stage("formal-d3-capital",ok(controller.createInjection("e01-capital-"+run,injection)));
            var reserveCommitted=f.jdbc.queryForList("SELECT * FROM nx_treasury_reserve_ledger ORDER BY id");
            ok(controller.createInjection("e01-capital-"+run,injection));
            assertThat(f.jdbc.queryForList("SELECT * FROM nx_treasury_reserve_ledger ORDER BY id")).isEqualTo(reserveCommitted);
        } else stage("native-capital-already-sufficient",coverage.snapshot());
        phase="FORMAL_G3_A2_PRICE";
        var market=adminContext.getBean(OpsNexMarketController.class);
        var oldPrice=Optional.ofNullable(f.mapper(NexMarketMapper.class).latestNexUsdtPrice()).orElse(BigDecimal.ZERO);
        String price=oldPrice.compareTo(BigDecimal.ONE)==0?"1.01":"1";
        var update=new NexMarketValueUpdateRequest(price,"isolated synthetic acceptance price; no market claim",null,oldPrice.toPlainString());
        assertThatThrownBy(()->market.updateOverride("e01-stale-price-"+run,"currentPrice",
                new NexMarketValueUpdateRequest("1",update.reason(),null,"-1")))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessageContaining("G3_STATE_CONFLICT");
        var a2=adminContext.getBean(OpsAuditController.class);
        var proposal=new AuditOperationProposalRequest("E01 isolated synthetic G3 price","currentPrice",oldPrice.toPlainString(),price,null,null,
                "param",true,false,"门槛者",update.reason(),"G",new AuditReplayCommand("G","g3_override",Map.of("overrideKey","currentPrice","value",price)),
                new AuditLockTarget("G","nex_market_override","currentPrice"),null);
        var ticket=ok(a2.createOperation("e01-price-propose-"+run,proposal));stage("g3-a2-proposed",ticket);
        assertThat(f.mapper(AuditObjectLockMapper.class).countActiveByTarget("G","nex_market_override","currentPrice")).isOne();
        assertThat(a2.approveOperation("e01-price-same-maker-"+run,ticket.id(),new AuditOperationDecisionRequest(update.reason(),null)).getCode()).isEqualTo(403);
        authenticateAdmin(checker,"e01-checker-"+run);
        stage("g3-a2-approved",ok(a2.approveOperation("e01-price-approve-"+run,ticket.id(),new AuditOperationDecisionRequest(update.reason(),null))));
        assertThat(f.mapper(AuditObjectLockMapper.class).countActiveByTarget("G","nex_market_override","currentPrice")).isZero();
        stage("formal-g3-price-rows",f.jdbc.queryForList("SELECT * FROM nx_price_index ORDER BY id"));
        stage("native-b1-after-synthetic-capital-and-price",coverage.snapshot());
    }

    private void authenticateAdmin(long actor,String username) {
        var auth=new TestingAuthenticationToken(String.valueOf(actor),null,permissions.getPermissionCodes(actor).toArray(String[]::new));
        auth.setDetails(Map.of("subjectType","ADMIN","username",username));SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class AdminMethodSecurity { }

    private void ready(String name,BigDecimal usdt,BigDecimal uvt) {
        var read=new TransactionTemplate(f.manager);read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.executeWithoutResult(status->{
            assertThat(f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            var snapshot=reader.read(List.of(customer));
            stage(name,Map.of("connectionId",f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class),"snapshot",snapshot));
            assertThat(snapshot.customers()).singleElement().satisfies(row->{
                assertThat(row.state()).as(name+" complete reader: "+row.reasons()).isEqualTo(EvidenceState.READY);
                assertThat(row.usdt()).isEqualByComparingTo(usdt);assertThat(row.nex()).isEqualByComparingTo(uvt);
            });
        });
    }
    private List<Map<String,Object>> operations() {
        return f.jdbc.queryForList("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",customer);
    }
    private void stage(String name,Object actual) {stages.add(Map.of("stage",name,"actual",actual));}
    private static BigDecimal number(Object value) {return new BigDecimal(value.toString());}
    private static <T>T ok(ApiResult<T> result) {assertThat(result.getCode()).as(result.getMessage()).isZero();return result.getData();}
    private static void authenticate(long id,String type,String username) {
        var auth=new TestingAuthenticationToken(String.valueOf(id),null,"ADMIN".equals(type)?"device_e4_write":"ROLE_USER");
        auth.setDetails(Map.of("subjectType",type,"username",username));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private static final class LocalHdPay implements HdPayGateway {
        final Map<String,BigDecimal> amounts=new HashMap<>();
        String providerId(String no) {return "E01-"+no;}
        public PayPage createPayOrder(CreatePayOrder request) {
            amounts.put(request.merchantOrderId(),request.transAmt());return new PayPage("https://payments.example.com/pay/"+request.merchantOrderId());
        }
        public PayOrder queryPayOrder(String no) {return new PayOrder(no,providerId(no),3,amounts.get(no),"BANKQR",null);}
    }
}
