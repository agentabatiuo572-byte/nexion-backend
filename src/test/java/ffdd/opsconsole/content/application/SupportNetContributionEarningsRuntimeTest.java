package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.application.TestComputeWorkerService;
import ffdd.opsconsole.device.application.TestComputeWorkerService.CompleteRequest;
import ffdd.opsconsole.device.application.TestComputeWorkerService.Grant;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.TaskConfigRow;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/** Two retained cases only. No cash/task reward seeds, external payout or reserve injection. */
@Import(SupportNetContributionEarningsRuntimeTest.FundOnlyStartup.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CS_NET_CONTRIBUTION_COMMITTED_ENABLED",matches="true")
class SupportNetContributionEarningsRuntimeTest extends SupportNetContributionSettlementRuntimeTest {
    static final long RETAINED_EARNINGS_CUSTOMER=6281;
    static final String RETAINED_EARNINGS_NAME="an12_e61c85d608";
    static final String RETAINED_EARNINGS_SCENE="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/earnings-principal-pathfix-20261013/SupportNetContributionEarningsRuntimeTest-actualTaskEarningsConsumptionRefundThenMixedWithdrawalRespectOriginalSources.json";
    static final String RETAINED_EARNINGS_SHA="91293f138b1c2bbe72dc6f745210f7fcade370a128e3cc55dc6a52874defda1b";
    static final String RETAINED_PAID_SCENE="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/earnings-principal-amount-20261013/SupportNetContributionEarningsRuntimeTest-actualTaskEarningsConsumptionRefundThenMixedWithdrawalRespectOriginalSources.json";
    static final String RETAINED_PAID_SHA="5b755d2735b1eb0cf90a0227ef4ad3e4a1176ffa436f8c8c42622e0484df8690";
    static final String RETAINED_PAID_ORDER="ORD-CF3090261F3843D8907A18480334E3A4";
    static final BigDecimal RETAINED_PAID_NET=new BigDecimal("649");
    Map<String,Object> retainedEarningsIdentity;

    @TestConfiguration(proxyBeanMethods=false)
    static class FundOnlyStartup {
        @Bean static BeanFactoryPostProcessor omitUnrelatedRiskBackfill() {
            return factory -> {
                assertThat("true".equals(System.getenv("CS_NET_CONTRIBUTION_COMMITTED_ENABLED"))).isTrue();
                ((BeanDefinitionRegistry)factory).removeBeanDefinition("k4ScoreBackfillInitializer");
            };
        }
    }
    @Autowired AppTaskAssignmentService tasks;
    @Autowired AppTaskAssignmentMapper taskMapper;
    @Autowired TestComputeWorkerService worker;
    @Autowired ConfigurableEnvironment workerEnvironment;
    @Autowired Clock workerClock;

    @Test void actualTaskEarningsConsumptionRefundThenMixedWithdrawalRespectOriginalSources() throws Exception {
        assertThat(applicationContext.containsBean("k4ScoreBackfillInitializer")).isFalse();
        assertThat(applicationContext.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
        long customer=retainedEarningsCustomer();
        var policy=withdrawals.policy(customer);assertThat(policy.getCode()).as(policy.getMessage()).isZero();
        assertThat(policy.getData().get("withdrawalEnabled")).isEqualTo(true);
        assertThat(((Collection<?>)policy.getData().get("enabledNetworks")).contains(TEST_NETWORK)).isTrue();
        BigDecimal gross=amount(policy.getData().get("minAmount")),ratio=amount(policy.getData().get("balanceMaxRatio"));
        assertThat(gross).isPositive();assertThat(ratio).isPositive().isLessThanOrEqualTo(BigDecimal.ONE);
        BigDecimal requiredBalance=gross.divide(ratio,6,RoundingMode.CEILING);
        var share=product(customer,"cloud-share","SHARE");BigDecimal cloudRecharge=decimal(share,"price_usdt").multiply(BigDecimal.valueOf(2));
        BigDecimal originalRecharge=RETAINED_PAID_NET.add(cloudRecharge);
        var selectedTask=selectActualTask();
        var capacity=taskMapper.e3CapacityConfig().stream().collect(Collectors.toMap(r->r.configKey(),r->r.configValue()));
        assertThat(ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy.validConfig(capacity)).isTrue();
        assertThat(capacity.get("capacityApplyToCloudShare")).isEqualTo("false");
        assertThat(midpoint(selectedTask)).isPositive().isLessThan(gross);
        BigDecimal cardMin=new BigDecimal(config.activeValue("finance.topup.channel.card.min_amount").orElse("30"));
        BigDecimal cardMax=new BigDecimal(config.activeValue("finance.topup.channel.card.max_amount").orElse("5000"));
        assertThat(cardMin).isPositive();assertThat(cardMax).isGreaterThanOrEqualTo(cardMin);
        var consumption=product(customer,"stellarbox-s1","DEVICE");BigDecimal price=decimal(consumption,"price_usdt");
        assertThat(cloudRecharge).isBetween(cardMin,cardMax);
        assertThat(price).isBetween(cardMin,cardMax).isGreaterThanOrEqualTo(requiredBalance);
        assertThat(price.subtract(selectedTask.maxReward())).as("Both planned deposits must satisfy the existing card minimum").isGreaterThanOrEqualTo(cardMin);
        deposit(customer,cloudRecharge,"earnings-cloud-pair");
        String deviceOrder=createOrder(customer,share,2);pay(customer,deviceOrder,key());
        assertThat(decimal(walletRow(customer),"usdt_available")).isZero();
        assertNet(customer,originalRecharge,"REAL_PAID_DEVICE_EXHAUSTS_RECHARGE_WITHOUT_DOUBLE_COUNT");
        var devices=physical("SELECT id,instance_no FROM nx_user_device WHERE user_id=? AND source_order_no=? AND source_environment='PRODUCTION' AND run_id='' AND is_deleted=0 ORDER BY id",customer,deviceOrder);
        assertThat(devices).hasSize(2);long device=number(devices.get(0),"id");String instance=String.valueOf(devices.get(0).get("instance_no"));
        BigDecimal earned=completeActualTask(customer,device,instance,selectedTask);
        assertNet(customer,originalRecharge,"ACTUAL_UNSPENT_EARNINGS_HAVE_NO_CONTRIBUTION");
        assertThat(earned).as("This case must withdraw both actual earnings and credited principal").isLessThan(gross);
        var firstBefore=analytics(agent,"view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run).path("records").get(0).path("first").deepCopy();
        BigDecimal available=decimal(walletRow(customer),"usdt_available");
        assertThat(available).isEqualByComparingTo(earned);
        assertThat(price).as("A real eligible single order must exceed the remaining wallet; no price/ratio/reward edits").isGreaterThan(available);
        BigDecimal consumptionTopup=price.subtract(available);assertThat(consumptionTopup).isBetween(cardMin,cardMax);deposit(customer,consumptionTopup,"earnings-consumption-gap");
        BigDecimal expectedBeforeConsumption=originalRecharge.add(consumptionTopup);
        assertNet(customer,expectedBeforeConsumption,"REAL_CONSUMPTION_TOPUP_IS_COUNTED_ONCE");
        String order=createOrder(customer,consumption);String payKey=key();pay(customer,order,payKey);
        assertThat(decimal(walletRow(customer),"usdt_available")).isZero();
        assertNet(customer,expectedBeforeConsumption.add(earned),"EXHAUSTED_WALLET_CONSUMPTION_COUNTS_ONLY_ACTUAL_EARNINGS");
        var afterPay=orderWitness(customer,order);pay(customer,order,payKey);
        assertThat(orderWitness(customer,order)).isEqualTo(afterPay);
        assertNet(customer,expectedBeforeConsumption.add(earned),"EARNINGS_CONSUMPTION_REPLAY_ADDS_NOTHING");
        refundActualOrder(customer,order,price,earned,expectedBeforeConsumption);
        assertThat(analytics(agent,"view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run).path("records").get(0).path("first")).isEqualTo(firstBefore);

        BigDecimal withdrawalTopup=requiredBalance.subtract(decimal(walletRow(customer),"usdt_available")).max(BigDecimal.ZERO);
        assertThat(withdrawalTopup).as("The planned refund already covers the withdrawal balance; do not add another deposit").isZero();
        BigDecimal beforeWithdrawalNet=expectedBeforeConsumption.add(withdrawalTopup);
        assertNet(customer,beforeWithdrawalNet,"REFUND_RESTORES_ORIGINAL_EARNINGS_AND_RECHARGE_SOURCES");
        prepareOwnedNonFinancialMetadata(customer);
        var settled=settleMixedWithdrawal(customer,gross,String.valueOf(policy.getData().get("policyVersion")));
        BigDecimal creditedTaken=gross.subtract(earned);assertThat(creditedTaken).isPositive();
        assertNet(customer,beforeWithdrawalNet.subtract(creditedTaken),"SUCCESS_GROSS_INCLUDING_FEE_REVERSES_ONLY_ORIGINAL_RECHARGE");
        var negatives=fundsSnapshot(customer).changes().stream().filter(c->c.sourceReference().equals(settled)&&c.amount().signum()<0).toList();
        assertThat(negatives).isNotEmpty();
        assertThat(negatives.stream().map(c->c.amount().negate()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(creditedTaken);
        assertThat(negatives).allSatisfy(c->{assertThat(c.currency()).isEqualTo("USDT");assertThat(c.originCustomerId()).isEqualTo(customer);});
        assertThat(analytics(agent,"view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run).path("records").get(0).path("first")).isEqualTo(firstBefore);
        checked("One actual frozen-price compute result credited USDT/UVT through the production device writer. Actual earnings consumption forms one contribution; original WALLET refund and replay reverse it and restore the original earnings source. Subsequent mixed-source minimum withdrawal deducts only original recharge including its fee share. This is not a pure-earnings-wallet or external payout claim. Provider/risk/dispatch inputs are explicit isolated test prerequisites; no reward/price/policy/reserve edits.");
    }

    long retainedEarningsCustomer() throws Exception {
        requireNetClone();var scene=readRetainedEarningsScene();
        assertThat(scene.path("suite").asText()).isEqualTo(getClass().getSimpleName());
        assertThat(scene.path("testcase").asText()).isEqualTo(testcase);
        assertThat(scene.path("status").asText()).isEqualTo("not-completed");
        assertThat(scene.path("committedCommands").size()).isEqualTo(1);
        var birth=scene.path("committedCommands").get(0);
        assertThat(birth.path("event").asText()).isEqualTo("AUTH_COMMITTED_NEW_ZERO_READBACK");
        assertThat(principalTree(physical("SELECT * FROM nx_event_outbox WHERE event_id=?",birth.path("registrationEventId").asText()))).isEqualTo(principalTree(birth.path("outbox")));
        var paid=readRetainedPaidScene();assertThat(paid.path("status").asText()).isEqualTo("not-completed");
        assertThat(paid.path("suite").asText()).isEqualTo(getClass().getSimpleName());
        assertThat(paid.path("testcase").asText()).isEqualTo(testcase);
        var events=new ArrayList<String>();paid.path("committedCommands").forEach(c->events.add(c.path("event").asText()));
        assertThat(events).containsExactly("COMMITTED_CARD_ADMIT","COMMITTED_CARD_SETTLE","COMMITTED_CARD_SETTLEMENT_REPLAY","COMMITTED_CREATE_ORDER","COMMITTED_PAY_ORDER","REAL_PAID_DEVICE_EXHAUSTS_RECHARGE_WITHOUT_DOUBLE_COUNT","FAILED_OR_UNKNOWN_REAL_RESTRICTED_COMPUTE_CLAIM");
        var checkpoint=paid.path("committedCommands").get(5);assertThat(checkpoint.path("customerId").asLong()).isEqualTo(RETAINED_EARNINGS_CUSTOMER);
        var wallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",RETAINED_EARNINGS_CUSTOMER);assertThat(wallet).hasSize(1);
        assertThat(principalTree(wallet)).isEqualTo(principalTree(checkpoint.path("wallet")));
        assertThat(number(wallet.get(0),"version")).isEqualTo(2);assertThat(number(wallet.get(0),"fund_revision")).isEqualTo(2);
        for(String field:List.of("usdt_available","nex_available","pending_withdraw","cregis_risk_held","lifetime_earned","earnings_nex_debited"))assertThat(decimal(wallet.get(0),field)).isZero();
        assertThat(decimal(wallet.get(0),"earnings_usdt_debited")).isEqualByComparingTo(RETAINED_PAID_NET);
        assertThat(wallet.get(0).get("fund_incarnation")).isEqualTo(birth.path("wallet").get(0).path("fund_incarnation").asText());
        String incarnation=wallet.get(0).get("fund_incarnation").toString();
        assertThat(principalTree(physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=? ORDER BY revision",incarnation))).isEqualTo(principalTree(checkpoint.path("journal")));
        var operations=physical("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",RETAINED_EARNINGS_CUSTOMER);
        assertThat(principalTree(operations)).isEqualTo(principalTree(checkpoint.path("operations")));assertThat(operations).hasSize(3);
        assertThat(principalTree(List.of(operations.get(0)))).isEqualTo(principalTree(birth.path("birthOperation")));
        long operation=number(operations.get(0),"id");
        assertThat(principalTree(physical("SELECT * FROM nx_support_fund_operation_party WHERE operation_id=?",operation))).isEqualTo(principalTree(birth.path("party")));
        assertThat(principalTree(physical("SELECT p.* FROM nx_support_fund_operation_party p WHERE p.operation_id IN (SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=?) ORDER BY operation_id,customer_id",RETAINED_EARNINGS_CUSTOMER))).isEqualTo(principalTree(checkpoint.path("parties")));
        assertThat(principalTree(physical("SELECT * FROM nx_support_fund_operation_leg WHERE wallet_incarnation=? ORDER BY revision",incarnation))).isEqualTo(principalTree(checkpoint.path("legs")));
        var registration=principalJson().readTree(operations.get(0).get("source_snapshot").toString());
        var identity=physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",RETAINED_EARNINGS_CUSTOMER);assertThat(identity).hasSize(1);
        var row=identity.get(0);assertThat(row.get("referral_code")).isEqualTo(birth.path("referralCode").asText());
        assertThat(row.get("nickname")).isEqualTo(RETAINED_EARNINGS_NAME);
        assertThat(row.get("created_at").toString().replace(' ','T')).isEqualTo(registration.path("customerCreatedAt").asText());
        var order=physical("SELECT * FROM nx_order WHERE user_id=? AND order_no=?",RETAINED_EARNINGS_CUSTOMER,RETAINED_PAID_ORDER);assertThat(order).hasSize(1);
        assertThat(number(order.get(0),"quantity")).isEqualTo(1);assertThat(decimal(order.get(0),"amount_usdt")).isEqualByComparingTo(RETAINED_PAID_NET);
        assertThat(order.get(0).get("payment_status")).isEqualTo("PAID");assertThat(order.get(0).get("order_status")).isEqualTo("COMPLETED");assertThat(order.get(0).get("activation_status")).isEqualTo("ACTIVATED");
        var paidWitness=paid.path("nativeObservations").path("paid-"+RETAINED_PAID_ORDER+"-5");
        assertThat(principalTree(orderWitness(RETAINED_EARNINGS_CUSTOMER,RETAINED_PAID_ORDER))).isEqualTo(principalTree(paidWitness));
        var deposit=paid.path("nativeObservations").path("deposit-earnings-paid-device");
        for(var source:Map.of("nx_payment_record","payment","nx_wallet_ledger","ledger").entrySet()){
            var expected=principalJson().createArrayNode();deposit.path(source.getValue()).forEach(expected::add);paidWitness.path(source.getValue()).forEach(expected::add);
            assertThat(principalTree(physical("SELECT * FROM "+source.getKey()+" WHERE user_id=? ORDER BY id",RETAINED_EARNINGS_CUSTOMER))).isEqualTo(principalTree(expected));
        }
        assertThat(physical("SELECT id FROM nx_order WHERE user_id=?",RETAINED_EARNINGS_CUSTOMER)).hasSize(1);
        assertThat(principalTree(fundsSnapshot(RETAINED_EARNINGS_CUSTOMER))).isEqualTo(principalTree(checkpoint.path("actualReader-0")));
        for(String table:List.of("nx_withdrawal_order","nx_withdrawal_attempt_control","nx_compute_receipt","nx_compute_task"))
            assertThat(physical("SELECT * FROM "+table+" WHERE user_id=?",RETAINED_EARNINGS_CUSTOMER)).isEmpty();
        var binding=physical("SELECT id,agent_admin_id,version FROM nx_support_agent_user_assignment WHERE user_id=? AND ends_at IS NULL AND is_deleted=0",RETAINED_EARNINGS_CUSTOMER);
        assertThat(binding).singleElement().satisfies(r->{assertThat(number(r,"id")).isEqualTo(4764);assertThat(number(r,"agent_admin_id")).isEqualTo(5460);assertThat(number(r,"version")).isEqualTo(1);});
        retainedEarningsIdentity=Collections.unmodifiableMap(new LinkedHashMap<>(row));customerIdentities.put(RETAINED_EARNINGS_CUSTOMER,retainedEarningsIdentity);
        nativeObservations.put("retainedPaidEarningsCustomer",Map.of("scenePath",RETAINED_PAID_SCENE,"sceneSha256",RETAINED_PAID_SHA,"identity",retainedEarningsIdentity,"newAuthOrRebinding",false,"originalPaymentReplayed",false));saveIntent();
        var page=analytics(agent,"view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run);
        assertThat(page.path("records").size()).isEqualTo(1);
        assertThat(page.path("records").get(0).path("first")).isEqualTo(checkpoint.path("httpAnalytics").path("records").get(0).path("first"));
        return RETAINED_EARNINGS_CUSTOMER;
    }

    JsonNode readRetainedEarningsScene() throws Exception {return reference(json.createObjectNode().put("path",RETAINED_EARNINGS_SCENE).put("sha256",RETAINED_EARNINGS_SHA),RETAINED_EARNINGS_SCENE);}
    JsonNode readRetainedPaidScene() throws Exception {return reference(json.createObjectNode().put("path",RETAINED_PAID_SCENE).put("sha256",RETAINED_PAID_SHA),RETAINED_PAID_SCENE);}
    @Override void requireCustomer(long customer) {
        if(customer!=RETAINED_EARNINGS_CUSTOMER){super.requireCustomer(customer);return;}
        require(retainedEarningsIdentity!=null,"Retained earnings customer must be verified first");
        try {
            readRetainedEarningsScene();
            readRetainedPaidScene();
            assertThat(principalTree(physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer))).isEqualTo(principalTree(List.of(retainedEarningsIdentity)));
        } catch(Exception failure){throw new IllegalStateException("Retained earnings evidence recheck failed",failure);}
    }
    @Override JsonNode analytics(long actor,String query) throws Exception {
        if(retainedEarningsIdentity==null || actor!=agent)return super.analytics(actor,query);
        assertThat(query).contains("keyword="+run);
        return super.analytics(boss,query.replace("keyword="+run,"keyword="+RETAINED_EARNINGS_NAME)+"&agentId=5460&groupId=707");
    }

    TaskConfigRow selectActualTask() {
        var candidates=taskMapper.eligibleTasks(8);
        assertThat(candidates).isNotEmpty().allSatisfy(t->assertThat(t.minVram()).isNotNull());
        TaskConfigRow selected=candidates.stream().filter(t->t.minVram()<=8).filter(t->midpoint(t).signum()>0)
            .max(Comparator.comparing(t->midpoint(t).divide(BigDecimal.valueOf(seconds(t.taskClass())),12,RoundingMode.HALF_UP))).orElseThrow();
        assertThat(seconds(selected.taskClass())).as("Existing chosen task must fit the restricted worker; never change its price/class").isLessThanOrEqualTo(60);
        assertThat(selected.maxReward()).isPositive();return selected;
    }

    BigDecimal completeActualTask(long customer,long device,String instance,TaskConfigRow selected) throws Exception {
        long issued=workerClock.millis();String executor="earnings",runId="TEST-NET-"+UUID.randomUUID();
        var provisional=new Grant(executor,customer,device,instance,"pending",selected.taskId(),runId,issued,issued+300_000);
        String taskNo=worker.newTaskNo(provisional);var grant=new Grant(executor,customer,device,instance,taskNo,selected.taskId(),runId,issued,issued+300_000);
        byte[] credential=new byte[32];new SecureRandom().nextBytes(credential);
        String prefix="nexion.compute-task.test-worker.",propertySource="owned-net-earnings-"+UUID.randomUUID();
        var properties=new HashMap<String,Object>();properties.put(prefix+"deployment-scope","TEST");properties.put(prefix+"enabled","true");
        properties.put(prefix+"credential-sha256",sha(credential));properties.put(prefix+"executor-id",executor);properties.put(prefix+"owner-id",Long.toString(customer));
        properties.put(prefix+"device-id",Long.toString(device));properties.put(prefix+"instance-no",instance);properties.put(prefix+"task-no",taskNo);
        properties.put(prefix+"task-config-id",selected.taskId());properties.put(prefix+"run-id",runId);properties.put(prefix+"issued-at",Long.toString(issued));properties.put(prefix+"expires-at",Long.toString(grant.expiresAt()));
        assertThat(workerEnvironment.getPropertySources().contains(propertySource)).isFalse();
        var before=walletRow(customer);
        var previousLedgerIds=physical("SELECT id FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",customer).stream().map(row->number(row,"id")).collect(Collectors.toSet());
        workerEnvironment.getPropertySources().addFirst(new MapPropertySource(propertySource,properties));
        try {
            assertThat(worker.authenticate("Bearer tw1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(credential))).isEqualTo(grant);
            var claimed=command(customer,"REAL_RESTRICTED_COMPUTE_CLAIM",Map.of("taskNo",taskNo,"originalConfig",selected),
                ()->tasks.testWorkerClaim(grant,taskNo,"TEST355-CLAIM-"+TestComputeWorkerService.sha256(taskNo.getBytes(StandardCharsets.UTF_8))));
            assertThat(claimed.getCode()).as(claimed.getMessage()).isZero();
            var task=command(customer,"READ_REAL_FROZEN_TASK",Map.of("taskNo",taskNo),()->taskMapper.lockAssignment(customer,taskNo,"PRODUCTION"));
            assertThat(task).isNotNull();assertThat(task.rewardUsdt()).isPositive();
            LocalDateTime due=task.startedAt().plusSeconds(task.requiredSeconds());long deadline=workerClock.millis()+65_000;
            while(LocalDateTime.now(workerClock).isBefore(due)){assertThat(workerClock.millis()).isLessThan(deadline);Thread.sleep(100);}
            var input=worker.input(grant,task);int[] sorted=input.values().clone();Arrays.sort(sorted);
            BigInteger sum=BigInteger.ZERO,squares=BigInteger.ZERO;for(int value:sorted){var n=BigInteger.valueOf(value);sum=sum.add(n);squares=squares.add(n.multiply(n));}
            String csv=Arrays.stream(sorted).mapToObj(Integer::toString).collect(Collectors.joining(","));
            byte[] result=("UVEL_TEST_VECTOR_STATS_RESULT_V1\ninput="+input.hash()+"\ncount=32\nsum="+sum+"\nsumSquares="+squares+"\nsorted="+csv+"\n").getBytes(StandardCharsets.UTF_8);
            var body=new CompleteRequest(TestComputeWorkerService.SPEC,input.hash(),sha(result),Base64.getEncoder().encodeToString(result),task.completionNonce(),workerClock.millis());
            String completeKey=key();var completed=command(customer,"REAL_COMPUTE_ARTIFACT_COMPLETE",Map.of("taskNo",taskNo,"artifact",body),()->tasks.testWorkerComplete(grant,taskNo,completeKey,body));
            assertThat(completed.getCode()).as(completed.getMessage()).isZero();String receipt=String.valueOf(completed.getData().get("receiptNo"));
            var rows=physical("SELECT * FROM nx_compute_receipt WHERE receipt_no=? AND user_id=?",receipt,customer);assertThat(rows).hasSize(1);
            BigDecimal usdt=decimal(rows.get(0),"reward_usdt"),nex=decimal(rows.get(0),"reward_nex");assertThat(usdt).isEqualByComparingTo(task.rewardUsdt()).isPositive();assertThat(nex).isNotNegative();
            var after=walletRow(customer);assertThat(decimal(after,"usdt_available")).isEqualByComparingTo(decimal(before,"usdt_available").add(usdt));
            assertThat(decimal(after,"nex_available")).isEqualByComparingTo(decimal(before,"nex_available").add(nex));
            assertThat(physical("SELECT * FROM nx_audit_log WHERE action='TASK_ASSIGNMENT_COMPLETED' AND resource_id=?",taskNo)).hasSize(1);
            assertThat(physical("SELECT * FROM nx_earning_event WHERE user_id=? AND receipt_no=?",customer,receipt)).hasSize(nex.signum()>0?2:1);
            var replay=command(customer,"REAL_COMPUTE_SAME_KEY_REPLAY",Map.of("taskNo",taskNo,"artifact",body),()->tasks.testWorkerComplete(grant,taskNo,completeKey,body));
            assertThat(replay.getCode()).isZero();assertThat(walletRow(customer)).isEqualTo(after);
            var newLedgers=physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",customer).stream().filter(row->!previousLedgerIds.contains(number(row,"id"))).toList();
            nativeObservations.put("actualEarnings",Map.of("task",physical("SELECT * FROM nx_compute_task WHERE task_no=? AND user_id=?",taskNo,customer),"receipt",rows,
                "ledger",newLedgers,"actualUsdt",usdt,"actualNex",nex));saveIntent();return usdt;
        } finally {workerEnvironment.getPropertySources().remove(propertySource);Arrays.fill(credential,(byte)0);}
    }

    String settleMixedWithdrawal(long customer,BigDecimal gross,String policyVersion) throws Exception {
        String submitKey=key();var before=walletRow(customer);
        var submitted=command(customer,"REAL_MIXED_WITHDRAWAL_RESERVE",Map.of("gross",gross,"policyVersion",policyVersion),
            ()->asCustomer(customer,()->withdrawals.submit(customer,gross,TEST_NETWORK,TEST_ADDRESS,policyVersion,false,submitKey)));
        assertThat(submitted.getCode()).as(submitted.getMessage()).isZero();String no=String.valueOf(submitted.getData().get("withdrawalNo"));
        var order=physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer).get(0);
        assertThat(decimal(order,"d2_actual_fee")).isPositive();assertThat(decimal(order,"d2_net_receive")).isPositive();
        assertThat(decimal(order,"d2_net_receive").add(decimal(order,"d2_actual_fee"))).isEqualByComparingTo(gross);
        prepareOwnedDispatchMetadata(customer,no);LocalDateTime now=jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class);
        assertThat(command(customer,"REAL_MIXED_PAYOUT_CLAIM",Map.of("withdrawalNo",no),()->payouts.claim(no,now,now.plusMinutes(2)))).isEqualTo(1);
        long cid=UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE;assertThat(cid).isPositive();
        String event="TEST-EARNINGS-"+UUID.randomUUID(),txid="TEST-INPUT-NO-EXTERNAL-PAYOUT-"+UUID.randomUUID();
        var input=Map.of("testInput",true,"externalPaymentExecuted",false,"withdrawalNo",no,"providerCid",cid,"eventNo",event,"txid",txid);String hash=sha(json.writeValueAsBytes(input));
        assertThat(command(customer,"REAL_MIXED_SUBMITTED_TEST_INPUT",input,()->finalizer.submitted(payouts.payout(no),cid,"provider"))).isTrue();
        var sent=payouts.payout(no);assertThat(sent.status()).isEqualTo("SENT");
        assertThat(command(customer,"REAL_MIXED_TERMINAL_TEST_INPUT",input,()->finalizer.terminal(sent,cid,"provider",event,hash,"CONFIRMED",txid,null))).isTrue();
        var after=withdrawalWitness(customer,no);assertThat(decimal(walletRow(customer),"nex_available")).isEqualByComparingTo(decimal(before,"nex_available"));
        assertThat(command(customer,"REAL_MIXED_TERMINAL_REPLAY",input,()->finalizer.terminal(sent,cid,"provider",event,hash,"CONFIRMED",txid,null))).isTrue();
        assertThat(withdrawalWitness(customer,no)).isEqualTo(after);
        assertThat(physical("SELECT * FROM nx_audit_log WHERE action='D5_WITHDRAWAL_PAYOUT_CONFIRMED' AND resource_id=?",no)).hasSize(1);
        nativeObservations.put("mixedWithdrawal",Map.of("explicitTestProviderInput",input,"order",order,"beforeWallet",before,"after",after));saveIntent();return no;
    }

    void refundActualOrder(long customer,String order,BigDecimal price,BigDecimal earnings,BigDecimal expected) throws Exception {
        ok(request("GET","/api/admin/devices/orders/"+order,boss,null,null));
        var identity=ok(request("GET","/api/admin/auth/me",boss,null,null));var permissions=new ArrayList<String>();identity.path("authorities").forEach(v->permissions.add(v.asText()));
        assertThat(permissions).contains("device_e4_order_refund");String refundKey=key();
        var body=new DeviceOrderActionRequest("refunded","WALLET","Actual compute earnings funded consumption refund",run);
        var beforeWallet=walletRow(customer);
        var beforeChanges=fundsSnapshot(customer).changes();
        var data=ok(request("POST","/api/admin/devices/orders/"+order+"/refund",boss,body,refundKey));assertThat(data.path("state").asText()).isEqualTo("refunded");
        assertNet(customer,expected,"ORIGINAL_WALLET_REFUND_REVERSES_ONLY_EARNINGS_CONSUMPTION");
        var refundedWallet=walletRow(customer);assertThat(decimal(refundedWallet,"usdt_available")).isEqualByComparingTo(price);
        assertThat(decimal(refundedWallet,"nex_available")).isEqualByComparingTo(decimal(beforeWallet,"nex_available"));
        var negatives=fundsSnapshot(customer).changes().stream().filter(c->c.amount().signum()<0&&!beforeChanges.contains(c)).toList();
        assertThat(negatives.stream().map(c->c.amount().negate()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(earnings);
        assertThat(physical("SELECT * FROM nx_audit_log WHERE action='E4_ORDER_REFUNDED' AND resource_id=?",order)).hasSize(1);
        var witness=withdrawalWitness(customer,order);var orderState=orderWitness(customer,order);
        assertThat(ok(request("POST","/api/admin/devices/orders/"+order+"/refund",boss,body,refundKey))).isEqualTo(data);
        assertThat(withdrawalWitness(customer,order)).isEqualTo(witness);assertThat(orderWitness(customer,order)).isEqualTo(orderState);
        assertNet(customer,expected,"EARNINGS_REFUND_REPLAY_HAS_NO_SECOND_REVERSAL");
    }

    Map<String,Object> walletRow(long customer) throws Exception {var rows=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);assertThat(rows).hasSize(1);return rows.get(0);}
    static BigDecimal amount(Object value){assertThat(value).isNotNull();return new BigDecimal(value.toString());}
    static BigDecimal midpoint(TaskConfigRow t){BigDecimal low=t.minReward()==null?BigDecimal.ZERO:t.minReward(),high=t.maxReward()==null?low:t.maxReward();assertThat(low).isNotNegative();assertThat(high).isGreaterThanOrEqualTo(low);return low.add(high).divide(BigDecimal.valueOf(2),6,RoundingMode.HALF_UP);}
    static int seconds(String kind){return switch(kind==null?"":kind.toUpperCase(Locale.ROOT)){case "IG"->18;case "VG"->900;case "LL"->12;case "FT"->1800;case "EM"->5;case "SP"->30;default->60;};}
}
