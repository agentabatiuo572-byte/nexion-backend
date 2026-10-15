package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.AppWithdrawalService;
import ffdd.opsconsole.finance.application.WithdrawalPayoutFinalizer;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Production cash/NET transaction integration; address, risk, approval/dispatch and provider inputs are test prerequisites. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CS_NET_CONTRIBUTION_COMMITTED_ENABLED",matches="true")
class SupportNetContributionSettlementRuntimeTest extends SupportNetContributionCommittedRuntimeTest {
    static final BigDecimal RECHARGE=new BigDecimal("1000"), REQUESTED_GROSS=new BigDecimal("100");
    static final String TEST_NETWORK="USDT-BEP20";
    static final String TEST_ADDRESS="0x000000000000000000000000000000000000dEaD";
    static final long RETAINED_PRINCIPAL_CUSTOMER=6280;
    static final String RETAINED_PRINCIPAL_SCENE="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/refund-readback-and-principal/SupportNetContributionSettlementRuntimeTest-principalWithdrawalReserveSubmittedTerminalRollbackAndReplayDeductActualGrossOnce.json";
    static final String RETAINED_PRINCIPAL_SHA="00adec3b80c0ccc1f6aa629722e57bb26afde435602ead783dd859e684e805c2";
    static final String RETAINED_PRINCIPAL_NICKNAME="an12_0ac1b175e0";
    static final String RETAINED_PRINCIPAL_INCARNATION="197e63d8-8361-48e3-823f-c3b809669979";
    static final String RETAINED_SENT_SCENE="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/earnings-principal-cloud-20261013/SupportNetContributionEarningsRuntimeTest-retainedPrincipalContinuesAfterPolicyFailureWithoutAnotherAuthOrRecharge.json";
    static final String RETAINED_SENT_SHA="4be16323e8bdee8d86cc95a1e9d5fda9b4417eb458ab885675af839e2d584ba3";
    JsonNode retainedPrincipalScene;
    Map<String,Object> retainedPrincipalIdentity;
    @Autowired AppWithdrawalService withdrawals;
    @Autowired WithdrawalPayoutMapper payouts;
    @Autowired WithdrawalPayoutFinalizer finalizer;

    @Test void principalWithdrawalReserveSubmittedTerminalRollbackAndReplayDeductActualGrossOnce() throws Exception {
        long customer=bornCustomer();bind(customer,agent);assertNet(customer,BigDecimal.ZERO,"AUTH_NEW_ZERO_BEFORE_WITHDRAWAL");
        deposit(customer,RECHARGE,"principal-withdrawal-source");assertNet(customer,RECHARGE,"REAL_RECHARGE_BEFORE_WITHDRAWAL");
        var originalAttribution=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer);
        assertThat(originalAttribution).singleElement().satisfies(r->{
            assertThat(number(r,"agent_admin_id")).isEqualTo(agent);assertThat(number(r,"group_id")).isEqualTo(group);
            assertThat(r.get("agent_status")).isEqualTo("KNOWN");assertThat(r.get("capture_mode")).isEqualTo("NEW_SUCCESS");
        });
        prepareOwnedNonFinancialMetadata(customer);
        finishPrincipalWithdrawal(customer,originalAttribution);
    }

    @Test void retainedPrincipalContinuesAfterPolicyFailureWithoutAnotherAuthOrRecharge() throws Exception {
        requireNetClone();retainedPrincipalScene=readRetainedPrincipalScene();
        var sent=reference(json.createObjectNode().put("path",RETAINED_SENT_SCENE).put("sha256",RETAINED_SENT_SHA),RETAINED_SENT_SCENE);
        assertThat(sent.path("status").asText()).isEqualTo("not-completed");assertThat(sent.path("suite").asText()).isEqualTo(getClass().getSimpleName());
        assertThat(sent.path("testcase").asText()).isEqualTo(testcase);
        var events=new ArrayList<String>();sent.path("committedCommands").forEach(c->events.add(c.path("event").asText()));
        assertThat(events).containsExactly("VERIFIED_RETAINED_PRINCIPAL_BEFORE_POLICY","COMMITTED_PRODUCTION_WITHDRAWAL_SUBMIT","REAL_RESERVATION_DOES_NOT_FINALIZE_NET","COMMITTED_OWNED_TEST_APPROVAL_DISPATCH_METADATA","COMMITTED_PRODUCTION_PAYOUT_CLAIM","COMMITTED_PRODUCTION_PAYOUT_SUBMITTED","REAL_SENT_STILL_NOT_FINAL_NET","PREPARED_EXPECTED_OUTER_TERMINAL_ROLLBACK");
        long customer=RETAINED_PRINCIPAL_CUSTOMER;
        var identity=physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer);assertThat(identity).hasSize(1);
        assertThat(principalTree(identity.get(0))).isEqualTo(principalTree(sent.path("nativeObservations").path("retainedPrincipal").path("actualIdentity")));
        assertThat(identity.get(0).get("nickname")).isEqualTo(RETAINED_PRINCIPAL_NICKNAME);
        assertThat(identity.get(0).get("referral_code")).isEqualTo(retainedPrincipalScene.path("committedCommands").get(0).path("referralCode").asText());
        retainedPrincipalIdentity=Collections.unmodifiableMap(new LinkedHashMap<>(identity.get(0)));customerIdentities.put(customer,retainedPrincipalIdentity);
        var checkpoint=sent.path("committedCommands").get(6);
        var wallets=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);assertThat(wallets).hasSize(1);
        assertThat(principalTree(wallets)).isEqualTo(principalTree(checkpoint.path("wallet")));
        var reserved=wallets.get(0);assertThat(decimal(reserved,"usdt_available")).isEqualByComparingTo("900");assertThat(decimal(reserved,"pending_withdraw")).isEqualByComparingTo(REQUESTED_GROSS);
        assertThat(reserved.get("fund_incarnation")).isEqualTo(RETAINED_PRINCIPAL_INCARNATION);
        assertThat(number(reserved,"version")).isEqualTo(2);assertThat(number(reserved,"fund_revision")).isEqualTo(2);
        var originalDeposit=retainedPrincipalScene.path("nativeObservations").path("deposit-principal-withdrawal-source");
        assertThat(principalTree(physical("SELECT * FROM nx_payment_record WHERE user_id=? ORDER BY id",customer))).isEqualTo(principalTree(originalDeposit.path("payment")));
        assertThat(principalTree(physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_type='CARD_TOPUP' ORDER BY id",customer))).isEqualTo(principalTree(originalDeposit.path("ledger")));
        var providerInput=principalJson().convertValue(sent.path("nativeObservations").path("explicitTestProviderInput"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        String no=String.valueOf(providerInput.get("withdrawalNo"));assertThat(no).isEqualTo("WD-9F6F757FC792408AA72C012EDB954F07");
        assertThat(providerInput.get("testInput")).isEqualTo(true);assertThat(providerInput.get("externalPaymentExecuted")).isEqualTo(false);
        var order=physical("SELECT * FROM nx_withdrawal_order WHERE user_id=?",customer);assertThat(order).hasSize(1);
        assertThat(order.get(0).get("withdrawal_no")).isEqualTo(no);assertThat(order.get(0).get("status")).isEqualTo("SENT");
        assertThat(number(order.get(0),"d5_provider_cid")).isEqualTo(number(providerInput,"providerCid"));assertThat(order.get(0).get("completed_at")).isNull();
        assertThat(decimal(order.get(0),"amount")).isEqualByComparingTo(REQUESTED_GROSS);assertThat(decimal(order.get(0),"d2_actual_fee")).isEqualByComparingTo("1");
        var ops=physical("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",customer);assertThat(ops).hasSize(3);
        assertThat(principalTree(ops.subList(0,2))).isEqualTo(principalTree(retainedPrincipalScene.path("committedCommands").get(5).path("operations")));
        assertThat(ops.get(2).get("source_key")).isEqualTo(no);assertThat(ops.get(2).get("phase")).isEqualTo("RESERVE");
        assertThat(physical("SELECT * FROM nx_withdrawal_payout_ledger WHERE withdrawal_no=?",no)).hasSize(1);
        assertThat(principalTree(fundsSnapshot(customer))).isEqualTo(principalTree(checkpoint.path("actualReader-0")));
        var attribution=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer);
        assertThat(principalTree(attribution)).isEqualTo(principalTree(sent.path("nativeObservations").path("retainedPrincipal").path("currentAttributionBaseline")));
        nativeObservations.put("retainedSentPrincipal",Map.of("scenePath",RETAINED_SENT_SCENE,"sceneSha256",RETAINED_SENT_SHA,"resubmitted",false,"current",withdrawalWitness(customer,no)));saveIntent();
        principalNet(customer,RECHARGE,"RETAINED_SENT_NET_BEFORE_TERMINAL");
        var before=principalJson().convertValue(retainedPrincipalScene.path("committedCommands").get(5).path("wallet").get(0),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        finishPrincipalTerminal(customer,attribution,before,reserved,providerInput);
    }

    void finishPrincipalWithdrawal(long customer,List<Map<String,Object>> originalAttribution) throws Exception {
        var policy=withdrawals.policy(customer);assertThat(policy.getCode()).as(policy.getMessage()).isZero();
        assertThat(policy.getData().get("withdrawalEnabled")).isEqualTo(true);
        assertThat(((Collection<?>)policy.getData().get("enabledNetworks")).contains(TEST_NETWORK)).isTrue();
        String policyVersion=String.valueOf(policy.getData().get("policyVersion"));String submitKey=key();
        var submitRequest=Map.of("customerId",customer,"amount",REQUESTED_GROSS,"chain",TEST_NETWORK,"address",TEST_ADDRESS,
            "policyVersion",policyVersion,"useNexFeeOffset",false,"idempotencyKey",submitKey);
        var before=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer).get(0);
        var submitted=command(customer,"PRODUCTION_WITHDRAWAL_SUBMIT",submitRequest,
            ()->asCustomer(customer,()->withdrawals.submit(customer,REQUESTED_GROSS,TEST_NETWORK,TEST_ADDRESS,policyVersion,false,submitKey)));
        assertThat(submitted.getCode()).as(submitted.getMessage()).isZero();String no=String.valueOf(submitted.getData().get("withdrawalNo"));
        assertThat(no).startsWith("WD-");var reserved=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer).get(0);
        var order=physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer).get(0);
        BigDecimal gross=decimal(order,"amount"),fee=decimal(order,"d2_actual_fee"),net=decimal(order,"d2_net_receive");
        assertThat(gross).isEqualByComparingTo(REQUESTED_GROSS);assertThat(fee).isPositive();assertThat(net.add(fee)).isEqualByComparingTo(gross);
        assertThat(decimal(reserved,"usdt_available")).isEqualByComparingTo(decimal(before,"usdt_available").subtract(gross));
        assertThat(decimal(reserved,"pending_withdraw")).isEqualByComparingTo(decimal(before,"pending_withdraw").add(gross));
        assertThat(decimal(reserved,"nex_available")).isEqualByComparingTo(decimal(before,"nex_available"));
        principalNet(customer,RECHARGE,"REAL_RESERVATION_DOES_NOT_FINALIZE_NET");
        assertThat(physical("SELECT * FROM nx_support_fund_operation WHERE source_key=? AND business_type='SUPPORT_WITHDRAWAL'",no))
            .singleElement().satisfies(r->assertThat(r.get("phase")).isEqualTo("RESERVE"));

        // Deliberately isolated test prerequisite, not a claim of real K3/K4/H1/B1 approval or elapsed cooldown.
        prepareOwnedDispatchMetadata(customer,no);
        LocalDateTime now=jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class);
        int claimed=command(customer,"PRODUCTION_PAYOUT_CLAIM",Map.of("withdrawalNo",no,"testDispatchClock",now),
            ()->payouts.claim(no,now,now.plusMinutes(2)));assertThat(claimed).isEqualTo(1);
        var claimedRow=payouts.payout(no);assertThat(claimedRow.userId()).isEqualTo(customer);assertThat(claimedRow.status()).isEqualTo("PROCESSING");
        long testProviderCid=(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);require(testProviderCid>0,"Positive test provider identity required");
        String testEvent="TEST-NET-TERMINAL-"+UUID.randomUUID();String testTxid="TEST-INPUT-NO-CHAIN-PAYOUT-"+UUID.randomUUID();
        Map<String,Object> providerInput=Map.of("testInput",true,"externalPaymentExecuted",false,"withdrawalNo",no,"providerCid",testProviderCid,
            "source","provider","eventNo",testEvent,"status","CONFIRMED","txid",testTxid);
        String payloadHash=sha(json.writeValueAsBytes(providerInput));nativeObservations.put("explicitTestProviderInput",providerInput);saveIntent();
        boolean sent=command(customer,"PRODUCTION_PAYOUT_SUBMITTED",providerInput,()->finalizer.submitted(claimedRow,testProviderCid,"provider"));
        assertThat(sent).isTrue();var sentRow=payouts.payout(no);assertThat(sentRow.status()).isEqualTo("SENT");assertThat(sentRow.providerCid()).isEqualTo(testProviderCid);
        principalNet(customer,RECHARGE,"REAL_SENT_STILL_NOT_FINAL_NET");
        finishPrincipalTerminal(customer,originalAttribution,before,reserved,providerInput);
    }

    void finishPrincipalTerminal(long customer,List<Map<String,Object>> originalAttribution,Map<String,Object> before,Map<String,Object> reserved,Map<String,Object> providerInput) throws Exception {
        String no=String.valueOf(providerInput.get("withdrawalNo")),testEvent=String.valueOf(providerInput.get("eventNo")),testTxid=String.valueOf(providerInput.get("txid"));
        long testProviderCid=number(providerInput,"providerCid");String payloadHash=sha(json.writeValueAsBytes(providerInput));
        var sentRow=payouts.payout(no);assertThat(sentRow.userId()).isEqualTo(customer);assertThat(sentRow.status()).isEqualTo("SENT");assertThat(sentRow.providerCid()).isEqualTo(testProviderCid);
        var order=physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer).get(0);
        BigDecimal gross=decimal(order,"amount"),fee=decimal(order,"d2_actual_fee"),net=decimal(order,"d2_net_receive");assertThat(net.add(fee)).isEqualByComparingTo(gross);
        var beforeTerminal=withdrawalWitness(customer,no);
        verifyTerminalRollback(customer,no,sentRow,testProviderCid,testEvent,payloadHash,testTxid,beforeTerminal);
        principalNet(customer,RECHARGE,"ROLLED_BACK_TERMINAL_LEAVES_NET_UNCHANGED");
        boolean confirmed=command(customer,"PRODUCTION_PAYOUT_TERMINAL_TEST_INPUT",providerInput,
            ()->finalizer.terminal(sentRow,testProviderCid,"provider",testEvent,payloadHash,"CONFIRMED",testTxid,null));
        assertThat(confirmed).isTrue();var after=withdrawalWitness(customer,no);
        var paidWallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer).get(0);
        assertThat(decimal(paidWallet,"pending_withdraw")).isEqualByComparingTo(decimal(before,"pending_withdraw"));
        assertThat(decimal(paidWallet,"usdt_available")).isEqualByComparingTo(decimal(reserved,"usdt_available"));
        assertThat(number(paidWallet,"version")).isEqualTo(number(reserved,"version")+1);
        assertThat(number(paidWallet,"fund_revision")).isEqualTo(number(reserved,"fund_revision")+1);
        principalNet(customer,RECHARGE.subtract(gross),"CONFIRMED_NET_DEDUCTS_ACTUAL_WALLET_GROSS_INCLUDING_FEE_ONCE");
        assertThat(physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer)).singleElement()
            .satisfies(r->{assertThat(r.get("status")).isEqualTo("CONFIRMED");assertThat(r.get("chain_tx_hash")).isEqualTo(testTxid);assertThat(r.get("completed_at")).isNotNull();});
        assertThat(physical("SELECT * FROM nx_withdrawal_payout_ledger WHERE withdrawal_no=? ORDER BY id",no)).hasSize(2);
        assertThat(physical("SELECT * FROM nx_audit_log WHERE resource_id=? AND action='D5_WITHDRAWAL_PAYOUT_CONFIRMED'",no))
            .singleElement().satisfies(r->assertThat(r.get("result")).isEqualTo("SUCCESS"));
        var finalOps=physical("SELECT * FROM nx_support_fund_operation WHERE source_key=? AND business_type='SUPPORT_WITHDRAWAL' ORDER BY id",no);
        assertThat(finalOps).hasSize(2);assertThat(finalOps.stream().map(r->r.get("phase"))).containsExactly("RESERVE","CONFIRM");
        var confirmation=json.readTree(finalOps.get(1).get("source_proof").toString());
        assertThat(confirmation.path("originalReserve").asText()).isEqualTo(finalOps.get(0).get("op_key"));
        assertThat(confirmation.path("originalFundEvidence").asText()).isEqualTo("PRESENT");
        var change=fundsSnapshot(customer);assertThat(change.origins()).isNotEmpty();
        assertThat(change.origins()).singleElement().satisfies(origin->{
            assertThat(origin.customerId()).isEqualTo(customer);assertThat(origin.paymentFactId()).isEqualTo(originalAttribution.get(0).get("fact_id"));
        });
        assertThat(change.changes().stream().filter(c->c.amount().signum()<0).toList()).singleElement().satisfies(c->{
            assertThat(c.originCustomerId()).isEqualTo(customer);assertThat(c.currency()).isEqualTo("USDT");
            assertThat(c.amount()).isEqualByComparingTo(gross.negate());assertThat(c.sourceReference()).isEqualTo(no);
        });
        assertThat(physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer)).isEqualTo(originalAttribution);
        boolean replay=command(customer,"PRODUCTION_TERMINAL_EVENT_REPLAY",providerInput,
            ()->finalizer.terminal(sentRow,testProviderCid,"provider",testEvent,payloadHash,"CONFIRMED",testTxid,null));
        assertThat(replay).isTrue();assertThat(withdrawalWitness(customer,no)).isEqualTo(after);
        principalNet(customer,RECHARGE.subtract(gross),"TERMINAL_REPLAY_DOES_NOT_DEDUCT_NET_AGAIN");
        nativeObservations.put("principalSettlement",Map.of("withdrawalNo",no,"actualGross",gross,"actualFee",fee,"actualNetReceive",net,
            "approvalAndDispatchWereTestPrerequisites",true,"externalPaymentExecuted",false,"beforeTerminal",beforeTerminal,"afterTerminal",after));saveIntent();
        checked((customer==RETAINED_PRINCIPAL_CUSTOMER?"Fixed failed-run AUTH/card sources and existing nonfinancial metadata strictly verified and reused; this run did not recreate them. ":"")+"Production AUTH_NEW_ZERO/card recharge/submit/reserve/claim/submitted/terminal and independent Reader/HTTP NET; actual gross including fee deducted once after CONFIRMED only; rollback and event replay preserve all cash/receipts. Address, risk, approval/dispatch and provider are explicit test inputs; no real approval, elapsed cooldown, OTP delivery or chain payout claimed. Pure earnings withdrawal remains NOT_RUN.");
    }

    List<Map<String,Object>> verifyRetainedPrincipal() throws Exception {
        requireNetClone();retainedPrincipalScene=readRetainedPrincipalScene();
        assertThat(retainedPrincipalScene.path("suite").asText()).isEqualTo("SupportNetContributionSettlementRuntimeTest");
        assertThat(retainedPrincipalScene.path("testcase").asText()).isEqualTo("principalWithdrawalReserveSubmittedTerminalRollbackAndReplayDeductActualGrossOnce");
        assertThat(retainedPrincipalScene.path("status").asText()).isEqualTo("not-completed");
        assertThat(retainedPrincipalScene.path("assertions").isNull()).isTrue();
        assertThat(retainedPrincipalScene.path("financialRecordsRetained").asBoolean()).isTrue();
        assertThat(required("WORKFLOW_RUN_ID")).isNotEqualTo(retainedPrincipalScene.path("workflowRunId").asText());
        assertThat(required("WORKFLOW_SNAPSHOT_HASH")).isNotEqualTo(retainedPrincipalScene.path("snapshotHash").asText());
        assertThat(run).isNotEqualTo(RETAINED_PRINCIPAL_NICKNAME);
        var commands=retainedPrincipalScene.path("committedCommands");assertThat(commands.isArray()).isTrue();assertThat(commands.size()).isEqualTo(7);
        var events=new ArrayList<String>();commands.forEach(c->events.add(c.path("event").asText()));
        assertThat(events).containsExactly("AUTH_COMMITTED_NEW_ZERO_READBACK","AUTH_NEW_ZERO_BEFORE_WITHDRAWAL","COMMITTED_CARD_ADMIT","COMMITTED_CARD_SETTLE","COMMITTED_CARD_SETTLEMENT_REPLAY","REAL_RECHARGE_BEFORE_WITHDRAWAL","COMMITTED_OWNED_TEST_ADDRESS_AND_RISK_METADATA");
        for(var request:retainedPrincipalScene.path("requests"))assertThat(request.path("path").asText()).doesNotContain("/withdrawals");
        var checkpoint=commands.get(5);assertThat(checkpoint.path("customerId").asLong()).isEqualTo(RETAINED_PRINCIPAL_CUSTOMER);
        var savedWallet=checkpoint.path("wallet");assertThat(savedWallet.size()).isEqualTo(1);
        var wallets=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",RETAINED_PRINCIPAL_CUSTOMER);assertThat(wallets).hasSize(1);
        assertThat(principalTree(wallets)).isEqualTo(principalTree(savedWallet));var wallet=wallets.get(0);
        assertThat(number(wallet,"id")).isEqualTo(2210);assertThat(number(wallet,"version")).isEqualTo(1);assertThat(number(wallet,"fund_revision")).isEqualTo(1);
        assertThat(wallet.get("fund_incarnation")).isEqualTo(RETAINED_PRINCIPAL_INCARNATION);
        assertThat(decimal(wallet,"usdt_available")).isEqualByComparingTo(RECHARGE);
        for(String field:List.of("nex_available","pending_withdraw","cregis_risk_held","lifetime_earned","earnings_usdt_debited","earnings_nex_debited"))assertThat(decimal(wallet,field)).isZero();
        var operations=physical("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",RETAINED_PRINCIPAL_CUSTOMER);
        assertThat(principalTree(operations)).isEqualTo(principalTree(checkpoint.path("operations")));assertThat(operations).hasSize(2);
        var birth=principalJson().readTree(operations.get(0).get("source_snapshot").toString());
        assertThat(operations.get(0).get("business_type")).isEqualTo("AUTH_FUND_BIRTH");assertThat(operations.get(1).get("business_type")).isEqualTo("SUPPORT_PAYMENT");
        assertThat(birth.path("customerId").asLong()).isEqualTo(RETAINED_PRINCIPAL_CUSTOMER);
        assertThat(birth.path("creator").asText()).isEqualTo("AUTH_NEW_ACCOUNT_REGISTRATION");
        assertThat(birth.path("actualUserInsert").asInt()).isEqualTo(1);assertThat(birth.path("expectedWalletIncarnation").asText()).isEqualTo(RETAINED_PRINCIPAL_INCARNATION);
        var identity=physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",RETAINED_PRINCIPAL_CUSTOMER);assertThat(identity).hasSize(1);
        var tuple=identity.get(0);assertThat(number(tuple,"id")).isEqualTo(RETAINED_PRINCIPAL_CUSTOMER);
        assertThat(tuple.get("referral_code")).isEqualTo(commands.get(0).path("referralCode").asText());
        assertThat(tuple.get("nickname")).isEqualTo(RETAINED_PRINCIPAL_NICKNAME);
        assertThat(tuple.get("created_at").toString().replace(' ','T')).isEqualTo(birth.path("customerCreatedAt").asText().replace(' ','T'));
        assertThat(principalTree(physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=? ORDER BY revision",RETAINED_PRINCIPAL_INCARNATION))).isEqualTo(principalTree(checkpoint.path("journal")));
        assertThat(principalTree(physical("SELECT p.* FROM nx_support_fund_operation_party p WHERE p.operation_id IN (SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=?) ORDER BY operation_id,customer_id",RETAINED_PRINCIPAL_CUSTOMER))).isEqualTo(principalTree(checkpoint.path("parties")));
        assertThat(principalTree(physical("SELECT * FROM nx_support_fund_operation_leg WHERE wallet_incarnation=? ORDER BY revision",RETAINED_PRINCIPAL_INCARNATION))).isEqualTo(principalTree(checkpoint.path("legs")));
        var deposit=retainedPrincipalScene.path("nativeObservations").path("deposit-principal-withdrawal-source");
        var payments=physical("SELECT * FROM nx_payment_record WHERE user_id=? ORDER BY id",RETAINED_PRINCIPAL_CUSTOMER);assertThat(payments).hasSize(1);
        assertThat(number(payments.get(0),"id")).isEqualTo(1889);assertThat(number(payments.get(0),"wallet_ledger_id")).isEqualTo(4999);
        assertThat(principalTree(payments)).isEqualTo(principalTree(deposit.path("payment")));
        var ledgers=physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",RETAINED_PRINCIPAL_CUSTOMER);assertThat(ledgers).hasSize(1);
        assertThat(number(ledgers.get(0),"id")).isEqualTo(4999);assertThat(principalTree(ledgers)).isEqualTo(principalTree(deposit.path("ledger")));
        for(String sql:List.of("SELECT * FROM nx_withdrawal_order WHERE user_id=?","SELECT * FROM nx_withdrawal_attempt_control WHERE user_id=?","SELECT * FROM nx_order WHERE user_id=?","SELECT * FROM nx_compute_receipt WHERE user_id=?"))assertThat(physical(sql,RETAINED_PRINCIPAL_CUSTOMER)).isEmpty();
        assertThat(principalTree(fundsSnapshot(RETAINED_PRINCIPAL_CUSTOMER))).isEqualTo(principalTree(checkpoint.path("actualReader-0")));
        assertThat(checkpoint.path("actualReader-1")).isEqualTo(checkpoint.path("actualReader-0"));
        var metadata=retainedPrincipalScene.path("nativeObservations").path("ownedNonFinancialMetadata");
        assertThat(principalTree(physical("SELECT * FROM nx_user_payout_address WHERE user_id=?",RETAINED_PRINCIPAL_CUSTOMER))).isEqualTo(principalTree(metadata.path("address")));
        var riskProofPath="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/retained-risk-refresh-verified.json";
        var riskRef=json.createObjectNode().put("path",riskProofPath).put("sha256","e8972ee471c82f24a915bdf348472f832f07d688ee527a81380376b2a2423308");
        var refreshed=reference(riskRef,riskProofPath);
        assertThat(refreshed.path("original").path("sha256").asText()).isEqualTo(RETAINED_PRINCIPAL_SHA);
        assertThat(refreshed.path("rowsWritten").asInt(-1)).isZero();
        var currentRisk=physical("SELECT * FROM nx_admin_risk_score_user WHERE user_no=?","U00006280");assertThat(currentRisk).hasSize(1);
        var expectedRisk=((com.fasterxml.jackson.databind.node.ObjectNode)refreshed.path("risk").get(0)).deepCopy();
        // Startup synchronization touches updated_at without changing the risk result or its version.
        currentRisk.get(0).remove("updated_at");expectedRisk.remove("updated_at");
        assertThat(principalTree(currentRisk.get(0))).isEqualTo(principalTree(expectedRisk));
        var attribution=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",RETAINED_PRINCIPAL_CUSTOMER);
        var savedOwner=checkpoint.path("httpAnalytics").path("records").get(0).path("owner");
        assertThat(savedOwner.path("agentId").asLong()).isEqualTo(5442);assertThat(savedOwner.path("groupId").asLong()).isEqualTo(701);
        var paymentSource=principalJson().readTree(operations.get(1).get("source_snapshot").toString());
        assertThat(paymentSource.path("factId").asText()).isEqualTo("DEPOSIT:4999");
        assertThat(attribution).singleElement().satisfies(row->{assertThat(row.get("fact_id")).isEqualTo(paymentSource.path("factId").asText());assertThat(number(row,"agent_admin_id")).isEqualTo(savedOwner.path("agentId").asLong());assertThat(number(row,"group_id")).isEqualTo(savedOwner.path("groupId").asLong());assertThat(row.get("agent_status")).isEqualTo("KNOWN");assertThat(row.get("capture_mode")).isEqualTo("NEW_SUCCESS");});
        // Evidence permits continued mutation of exactly this object; it is not a new creator/cleanup object.
        retainedPrincipalIdentity=Collections.unmodifiableMap(new LinkedHashMap<>(tuple));customerIdentities.put(RETAINED_PRINCIPAL_CUSTOMER,retainedPrincipalIdentity);
        assertThat(customers).doesNotContain(RETAINED_PRINCIPAL_CUSTOMER);
        var page=retainedPrincipalPage();assertThat(page.path("records").get(0).path("owner")).isEqualTo(savedOwner);
        assertThat(page.path("records").get(0).path("first")).isEqualTo(checkpoint.path("httpAnalytics").path("records").get(0).path("first"));
        nativeObservations.put("retainedPrincipal",Map.of("scenePath",RETAINED_PRINCIPAL_SCENE,"sceneSha256",RETAINED_PRINCIPAL_SHA,"actualIdentity",retainedPrincipalIdentity,"newContextRunId",required("WORKFLOW_RUN_ID"),"currentAttributionBaseline",attribution,"rawAttributionWasNotSavedInPriorScene",true));saveIntent();return attribution;
    }

    @Override void requireCustomer(long customer) {
        if(customer!=RETAINED_PRINCIPAL_CUSTOMER){super.requireCustomer(customer);return;}
        require(retainedPrincipalIdentity!=null && retainedPrincipalScene!=null,"Retained principal must pass fixed evidence verification first");
        try {assertThat(readRetainedPrincipalScene()).isEqualTo(retainedPrincipalScene);
            var rows=physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer);assertThat(rows).hasSize(1);
            assertThat(principalTree(rows.get(0))).isEqualTo(principalTree(retainedPrincipalIdentity));
        }catch(Exception failure){throw new IllegalStateException("Retained principal evidence recheck failed",failure);}
    }
    ObjectMapper principalJson(){return json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);}
    JsonNode principalTree(Object value) throws Exception {var mapper=principalJson();return exactEvidenceNumbers(mapper.readTree(mapper.writeValueAsBytes(value)));}
    // JDBC decimals and saved integral JSON numbers must compare by exact value, never double precision.
    static JsonNode exactEvidenceNumbers(JsonNode node) {
        if(node.isNumber())return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(node.decimalValue());
        if(node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for(int i=0;i<array.size();i++)array.set(i,exactEvidenceNumbers(array.get(i)));
        } else if(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var fields=object.fields();while(fields.hasNext()){var field=fields.next();field.setValue(exactEvidenceNumbers(field.getValue()));}
        }
        return node;
    }
    JsonNode readRetainedPrincipalScene() throws Exception {Path path=Path.of(RETAINED_PRINCIPAL_SCENE).toAbsolutePath().normalize();require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().equals(path),"Plain fixed principal evidence required");require(Files.size(path)>0&&Files.size(path)<=64L*1024*1024,"Bounded principal evidence required");byte[] bytes=Files.readAllBytes(path);require(RETAINED_PRINCIPAL_SHA.equals(sha(bytes)),"Principal evidence bytes changed");return principalJson().readTree(bytes);}
    JsonNode retainedPrincipalPage() throws Exception {
        requireCustomer(RETAINED_PRINCIPAL_CUSTOMER);assertThat(actors.ownedIds()).contains(boss);
        var page=ok(request("GET","/api/admin/content/support-workbench/analytics?view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&agentId=5442&groupId=701&keyword="+RETAINED_PRINCIPAL_NICKNAME,boss,null,null));
        assertThat(page.path("records").size()).isEqualTo(1);assertThat(page.path("records").get(0).path("customerId").asText()).isEqualTo(Long.toString(RETAINED_PRINCIPAL_CUSTOMER));return page;
    }
    void principalNet(long customer,BigDecimal expected,String checkpoint) throws Exception {
        if(customer!=RETAINED_PRINCIPAL_CUSTOMER){super.assertNet(customer,expected,checkpoint);return;}
        requireCustomer(customer);var observed=new LinkedHashMap<String,Object>();observed.put("event",checkpoint);observed.put("customerId",customer);observed.put("expectedNetUSDT",expected);committedCommands.add(observed);saveIntent();
        var wallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);assertThat(wallet).hasSize(1);observed.put("wallet",wallet);
        ffdd.opsconsole.finance.facade.SupportFundsReadFacade.NetSnapshot previous=null;
        for(int i=0;i<2;i++){var snapshot=fundsSnapshot(customer);observed.put("actualReader-"+i,snapshot);saveIntent();assertThat(snapshot.customers()).singleElement().satisfies(row->{assertThat(row.customerId()).isEqualTo(customer);assertThat(row.state()).isEqualTo(ffdd.opsconsole.finance.facade.SupportFundsReadFacade.EvidenceState.READY);assertThat(row.reasons()).isEmpty();assertThat(row.usdt()).isEqualByComparingTo(expected);assertThat(row.nex()).isZero();});if(previous!=null)assertThat(snapshot).isEqualTo(previous);previous=snapshot;}
        var page=retainedPrincipalPage();observed.put("httpAnalytics",page);saveIntent();var currencies=new ArrayList<JsonNode>();page.path("records").get(0).path("lifetime").forEach(c->{if("USDT".equals(c.path("currency").asText()))currencies.add(c);});
        assertThat(currencies).singleElement().satisfies(c->{var net=c.path("net");assertThat(net.path("status").asText()).isEqualTo("AVAILABLE");for(String field:List.of("observed","confirmed")){assertThat(net.path(field).isTextual()).isTrue();assertThat(new BigDecimal(net.path(field).textValue())).isEqualByComparingTo(expected);}});
        assertThat(page.path("records").get(0).path("owner")).isEqualTo(retainedPrincipalScene.path("committedCommands").get(5).path("httpAnalytics").path("records").get(0).path("owner"));
        assertThat(page.path("records").get(0).path("first")).isEqualTo(retainedPrincipalScene.path("committedCommands").get(5).path("httpAnalytics").path("records").get(0).path("first"));
    }

    void prepareOwnedNonFinancialMetadata(long customer) throws Exception {
        requireNetClone();requireCustomer(customer);
        var model=physical("SELECT model_version FROM nx_admin_risk_score_model WHERE state='active' AND is_deleted=0");assertThat(model).hasSize(1);
        String modelVersion="k4-v"+number(model.get(0),"model_version");String userNo="U"+String.format(Locale.ROOT,"%08d",customer);
        assertThat(physical("SELECT * FROM nx_user_payout_address WHERE user_id=?",customer)).isEmpty();
        var pendingRisk=physical("SELECT * FROM nx_admin_risk_score_user WHERE user_no=?",userNo);
        if(!pendingRisk.isEmpty()) {
            assertThat(customer).isEqualTo(6281);
            assertThat(pendingRisk).singleElement().satisfies(row->{assertThat(row.get("model_version")).isEqualTo("pending");assertThat(number(row,"model_score")).isZero();assertThat(number(row,"row_version")).isZero();assertThat(number(row,"is_deleted")).isZero();});
            for(String table:List.of("nx_admin_risk_score_history","nx_admin_risk_score_contribution","nx_admin_risk_score_override"))assertThat(physical("SELECT * FROM "+table+" WHERE user_no=?",userNo)).isEmpty();
        }
        var input=Map.of("testMetadataOnly",true,"customerCreator",customerIdentities.get(customer),"network",TEST_NETWORK,"address",TEST_ADDRESS,
            "riskScore",0,"riskModel",modelVersion,"otpAndAddressDelayNotUnderTest",true,"riskAlgorithmNotUnderTest",true);
        command(customer,"OWNED_TEST_ADDRESS_AND_RISK_METADATA",input,()->{
            assertThat(jdbc.update("INSERT INTO nx_user_payout_address(user_id,network,address,status,effective_at,next_change_allowed_at,version,created_at,updated_at,is_deleted) VALUES(?,?,?,'ACTIVE',NOW(),DATE_ADD(NOW(),INTERVAL 7 DAY),0,NOW(),NOW(),0)",customer,TEST_NETWORK,TEST_ADDRESS)).isEqualTo(1);
            if(pendingRisk.isEmpty())assertThat(jdbc.update("INSERT INTO nx_admin_risk_score_user(user_no,model_score,model_version,row_version,as_of,updated_text,is_deleted) VALUES(?,0,?,1,NOW(),'TEST NET settlement prerequisite',0)",userNo,modelVersion)).isEqualTo(1);
            else assertThat(jdbc.update("UPDATE nx_admin_risk_score_user SET model_version=?,row_version=1,as_of=NOW(),updated_text='TEST NET settlement prerequisite' WHERE id=? AND user_no=? AND model_version='pending' AND model_score=0 AND row_version=0 AND is_deleted=0",modelVersion,number(pendingRisk.get(0),"id"),userNo)).isEqualTo(1);
            return true;
        });
        nativeObservations.put("ownedNonFinancialMetadata",Map.of("customerId",customer,"address",physical("SELECT * FROM nx_user_payout_address WHERE user_id=?",customer),
            "risk",physical("SELECT * FROM nx_admin_risk_score_user WHERE user_no=?",userNo),"realOtpRiskAndCooldownAcceptance",false));saveIntent();
    }
    void prepareOwnedDispatchMetadata(long customer,String no) throws Exception {
        requireNetClone();requireCustomer(customer);
        var owned=physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer);assertThat(owned).hasSize(1);
        String old=String.valueOf(owned.get(0).get("status"));assertThat(old).isIn("EXTENDED_HOLD","REVIEW_PENDING","REVIEW_PASSED");
        var payload=Map.of("testMetadataOnly",true,"withdrawalNo",no,"customerId",customer,"fromState",old,"toState","REVIEW_PASSED",
            "realRiskTreasuryCooldownApprovalNotUnderTest",true,"financialReservationAlreadyProduced",true,"beforeOrder",owned.get(0));
        command(customer,"OWNED_TEST_APPROVAL_DISPATCH_METADATA",payload,()->{
            assertThat(jdbc.update("UPDATE nx_withdrawal_order SET status='REVIEW_PASSED',d2_hold_until=NOW(),d5_payout_due_at=NOW(),next_broadcast_at=NULL,updated_at=NOW() WHERE withdrawal_no=? AND user_id=? AND status=? AND is_deleted=0",no,customer,old)).isEqualTo(1);return true;
        });
        nativeObservations.put("ownedTestDispatchMetadata",Map.of("before",owned,"after",physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer),"realApprovalExecuted",false));saveIntent();
    }
    void verifyTerminalRollback(long customer,String no,WithdrawalPayoutMapper.PayoutRow row,long cid,String event,String hash,String txid,Map<String,Object> before) throws Exception {
        requireNetClone();requireCustomer(customer);var intent=new LinkedHashMap<String,Object>();intent.put("event","PREPARED_EXPECTED_OUTER_TERMINAL_ROLLBACK");
        intent.put("customerId",customer);intent.put("withdrawalNo",no);intent.put("providerInputIsTest",true);intent.put("before",before);committedCommands.add(intent);saveIntent();
        var transaction=new TransactionTemplate(transactions);
        assertThatThrownBy(()->transaction.execute(status->{
            assertThat(finalizer.terminal(row,cid,"provider",event,hash,"CONFIRMED",txid,null)).isTrue();throw new ExpectedTestRollback();
        })).isInstanceOf(ExpectedTestRollback.class);
        var after=withdrawalWitness(customer,no);intent.put("event","EXPECTED_TERMINAL_ROLLBACK_INDEPENDENT_READBACK");intent.put("before",before);intent.put("after",after);saveIntent();
        assertThat(after).isEqualTo(before);
    }
    Map<String,Object> withdrawalWitness(long customer,String no) throws Exception {
        var r=new LinkedHashMap<String,Object>();var wallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);assertThat(wallet).hasSize(1);r.put("wallet",wallet);
        String incarnation=String.valueOf(wallet.get(0).get("fund_incarnation"));
        r.put("order",physical("SELECT * FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",no,customer));
        r.put("attempt",physical("SELECT * FROM nx_withdrawal_attempt_control WHERE user_id=? ORDER BY idempotency_key",customer));
        r.put("ledger",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",customer));r.put("bill",physical("SELECT * FROM nx_wallet_bill WHERE user_id=? ORDER BY id",customer));
        r.put("payout",physical("SELECT * FROM nx_withdrawal_payout_ledger WHERE withdrawal_no=? ORDER BY id",no));
        r.put("audit",physical("SELECT * FROM nx_audit_log WHERE resource_id=? ORDER BY id",no));r.put("outbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='WITHDRAWAL' AND aggregate_id=? ORDER BY id",no));
        r.put("journal",physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=? ORDER BY revision",incarnation));
        r.put("operation",physical("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",customer));
        r.put("party",physical("SELECT * FROM nx_support_fund_operation_party WHERE operation_id IN (SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=?) ORDER BY operation_id,customer_id",customer));
        r.put("leg",physical("SELECT * FROM nx_support_fund_operation_leg WHERE wallet_incarnation=? ORDER BY revision",incarnation));return r;
    }
    ffdd.opsconsole.finance.facade.SupportFundsReadFacade.NetSnapshot fundsSnapshot(long customer){
        var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(tx.execute(status->funds.readNetContributions(List.of(customer))));
    }
    <T>T asCustomer(long customer,Supplier<T> call){
        var prior=SecurityContextHolder.getContext().getAuthentication();var auth=new UsernamePasswordAuthenticationToken(String.valueOf(customer),null,List.of());
        auth.setDetails(Map.of("subjectType","USER","username","user:"+customer));SecurityContextHolder.getContext().setAuthentication(auth);
        try{return call.get();}finally{SecurityContextHolder.getContext().setAuthentication(prior);}
    }
    static final class ExpectedTestRollback extends RuntimeException { }
}
