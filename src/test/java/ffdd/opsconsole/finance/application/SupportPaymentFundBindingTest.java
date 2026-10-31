package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.BeforeSource;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.FreshLedgerReceipt;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.RecoveryEntry;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.Marker;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual source service + actual binder, with explicit storage doubles. MySQL remains unverified. */
class SupportPaymentFundBindingTest {
    private final DataSource dataSource=mock(DataSource.class);
    private final ObjectMapper json=new ObjectMapper();
    private final SupportPaymentSourceMapper mapper=mock(SupportPaymentSourceMapper.class);
    private final SupportPaymentFundFixture cash=new SupportPaymentFundFixture(dataSource,json);
    private final SupportPaymentSourceService service=new SupportPaymentSourceService(mapper,mock(SupportPaymentFactService.class),dataSource,json,
        mock(SupportPaymentCaptureHistoryFacade.class),cash.fund,cash.mapper,cash.earnings);
    private final LocalDateTime at=LocalDateTime.of(2026,10,8,12,0);
    private final Map<Long,Map<String,Object>> markers=new HashMap<>();
    private final Map<String,List<Map<String,Object>>> ledgers=new HashMap<>();
    private Source source;private String key,partition;private BigDecimal amount;
    private Map<String,Object> root,projection,order,original,trial;
    private BeforeSource before;private FreshLedgerReceipt receipt;
    private long freshLedgerId=101;

    @BeforeEach void transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(dataSource,cash.holder);
        when(mapper.ledgers(anyString())).thenAnswer(i->ledgers.getOrDefault(i.getArgument(0),List.of()));
        when(mapper.currentMarker(any(),anyLong())).thenAnswer(i->markers.get(i.getArgument(1)));
        when(mapper.before(any(),anyLong(),anyString())).thenAnswer(i->List.of(rootFor(i.getArgument(0))));
        when(mapper.currentBefore(any(),anyLong(),anyString(),anyLong())).thenAnswer(i->List.of(rootFor(i.getArgument(0))));
        when(mapper.settled(any(),anyList(),anyString())).thenAnswer(i->List.of(i.getArgument(0)==Source.WALLET_ORDER && source==Source.ORDER_REFUND?original:projection));
        when(mapper.currentSettled(any(),anyList(),anyString(),anyMap())).thenAnswer(i->List.of(i.<Map<String,Object>>getArgument(3)));
        when(mapper.refunds(anyList(),anyString())).thenAnswer(i->List.of(projection));
        when(mapper.settledVietqrCounterpart(anyString())).thenReturn(List.of());
        when(mapper.trialFundSource(anyLong(),anyString())).thenAnswer(i->List.of(trial));
        when(mapper.historyPaymentAudits(anyList())).thenAnswer(i->List.of(cash.authority(projection)));
        when(mapper.insertLedger(any(),anyMap())).thenAnswer(i->{
            Map<String,Object> write=i.getArgument(1);write.put("id",freshLedgerId);
            var ledger=ledger(freshLedgerId,write.get("key").toString(),SupportPaymentSourceSql.ledgerType(source),"USDT",
                SupportPaymentSourceSql.ledgerDirection(source),(BigDecimal)write.get("amount"),(BigDecimal)write.get("balanceAfter"));
            ledger.put("remark",write.get("remark"));markers.put(freshLedgerId,ledger);ledgers.put(key,List.of(ledger));return 1;
        });
    }
    @AfterEach void cleanup() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);TransactionSynchronizationManager.clear();
    }

    private Map<String,Object> rootFor(Source requested) {return requested==Source.WALLET_ORDER && (source==Source.TRIAL_CONVERT || source==Source.ORDER_REFUND)?order:root;}
    private void pending(Source requested,BigDecimal charge) {
        source=requested;amount=charge;key=switch(source) {case DEPOSIT_ORDER->"CR-23";case CARD_TOPUP->"card-1";case VIETQR->"D1-VIETQR-receipt-1";
            case HDPAY->"intent-1";case TRIAL_CONVERT->"claim-1:CHARGE";case ORDER_REFUND->"E4-REFUND-order-1";default->"order-1";};
        partition=source==Source.DEPOSIT_ORDER?"100":source==Source.VIETQR?"intent-1":null;
        root=new LinkedHashMap<>();root.put("id",source==Source.TRIAL_CONVERT?5L:1L);root.put("customerId",7L);
        root.put("businessId",source==Source.ORDER_REFUND?"order-1":key);root.put("status",source==Source.ORDER_REFUND?"PAID":source==Source.TRIAL_CONVERT?"ACTIVE":"OPEN");
        root.put("deviceId",0L);root.put("deleted",0);root.put("amount",BigDecimal.ZERO);root.put("sourceVersion","1");root.put("orderType",type(source));
        if(source==Source.VIETQR)root.put("intentNo","intent-1");
        projection=projection(source,key,freshLedgerId,charge);order=new LinkedHashMap<>(root);order.put("id",1L);order.put("businessId","order-1");
        order.put("orderNo","order-1");order.put("amount",source==Source.ORDER_REFUND?new BigDecimal("80"):charge);order.put("orderType",type(source));
        original=projection(Source.WALLET_ORDER,"order-1",201,new BigDecimal("80"));
        var intent=new LinkedHashMap<String,Object>();intent.put("id",3L);intent.put("customerId",7L);intent.put("businessId","intent-1");
        intent.put("status","OPEN");intent.put("amount",BigDecimal.ZERO);intent.put("rail",source==Source.HDPAY?"HDPAY":"MANUAL");
        intent.put("target","WALLET_TOPUP");intent.put("deleted",0);markers.put(3L,intent);when(mapper.intent(anyString())).thenReturn(List.of(intent));
        var payment=new LinkedHashMap<String,Object>();payment.put("id",2L);payment.put("customerId",7L);payment.put("businessId","payment-1");payment.put("orderNo","order-1");
        payment.put("provider","UVEL_WALLET");payment.put("currency","USDT");payment.put("amount",source==Source.ORDER_REFUND?new BigDecimal("80"):charge);
        payment.put("deleted",0);payment.put("status",source==Source.ORDER_REFUND?"PAID":"PENDING");
        if(source==Source.ORDER_REFUND) {payment.put("successAt",at);payment.put("ledgerId",201L);order.put("status","PAID");order.put("successAt",at);}
        markers.put(2L,payment);when(mapper.payments(anyString())).thenReturn(List.of(payment));
        var card=new LinkedHashMap<String,Object>();card.put("id",4L);card.put("customerId",7L);card.put("orderNo","card-order");card.put("provider","PSP");
        card.put("providerPaymentId","provider-1");card.put("amount",charge);card.put("status","PROCESSING");card.put("deleted",0);
        markers.put(4L,card);when(mapper.cardSettlements(anyString())).thenReturn(List.of(card));
        var cregis=new LinkedHashMap<String,Object>();cregis.put("id",6L);cregis.put("customerId",7L);cregis.put("projectId",100L);cregis.put("cid",23L);
        cregis.put("amount",charge);cregis.put("status","PROCESSING");markers.put(6L,cregis);when(mapper.cregisEvents(anyLong(),anyLong())).thenReturn(List.of(cregis));
        var device=new LinkedHashMap<String,Object>();device.put("id",8L);device.put("customerId",7L);device.put("orderNo","order-1");markers.put(8L,device);
        when(mapper.device(anyLong())).thenReturn(List.of(device));
        if(cash.wallet==null)cash.wallet(new BigDecimal("100"));before=service.beforeSource(7,source,key,partition);receipt=null;
    }
    private void cashAndLedger() {
        service.beforeCash(before);
        cash.advance(source==Source.WALLET_ORDER || source==Source.TRADE_IN || source==Source.CAPACITY_KEEP || source==Source.TRIAL_CONVERT?amount.negate():amount,BigDecimal.ZERO);
        if(amount.signum()>0)receipt=service.insertFreshPaymentLedger(before,amount,(BigDecimal)cash.wallet.get("usdtAvailable"),"actual payment");
        succeeded();
    }
    private void succeeded() {
        root.put("status",source==Source.ORDER_REFUND?"REFUNDED":source==Source.TRIAL_CONVERT?"REDEEMED":source==Source.DEPOSIT_ORDER || source==Source.VIETQR || source==Source.HDPAY?"CREDITED":"PAID");
        root.put("amount",amount);root.put("successAt",at);root.put("deviceId",source==Source.TRIAL_CONVERT?8L:0L);
        order.put("status","PAID");order.put("successAt",at);order.put("orderType",type(source));
        markers.get(3L).put("status","CREDITED");markers.get(3L).put("amount",amount);
        if(source!=Source.ORDER_REFUND) {markers.get(2L).put("status","PAID");markers.get(2L).put("successAt",at);markers.get(2L).put("ledgerId",101L);}
        markers.get(4L).put("status","SETTLED");markers.get(6L).put("status","CREDITED");markers.get(6L).put("ledgerId",101L);
        if(source==Source.TRIAL_CONVERT) {
            trial=new LinkedHashMap<>();trial.put("claimId",5L);trial.put("claimNo","claim-1");trial.put("customerId",7L);trial.put("claimStatus","REDEEMED");
            trial.put("claimDeleted",0);trial.put("chargeUsdt",amount);trial.put("remainderUsdt",BigDecimal.ZERO);trial.put("shadowNex",BigDecimal.ZERO);
            trial.put("settledAt",at);trial.put("claimVersion",2L);trial.put("deviceId",8L);trial.put("orderId",1L);trial.put("orderNo","order-1");
            trial.put("deviceEnvironment","PRODUCTION");trial.put("deviceStatus","ACTIVE");trial.put("ownershipStatus","OWNED");trial.put("sourceChannel","TRIAL");
            trial.put("deviceDeleted",0);trial.put("orderType","TRIAL_CONVERT");trial.put("paymentStatus","PAID");trial.put("amountUsdt",amount);trial.put("paidAt",at);
            trial.put("orderDeleted",0);trial.put("userSandbox",0);trial.put("userDeleted",0);trial.put("userStatus","ACTIVE");
        }
    }
    private Optional<Fact> record() {return receipt==null?service.readSettled(before):service.readSettled(before,receipt);}
    private void reward(String asset,String quantity,String bucket) {
        BigDecimal reward=new BigDecimal(quantity);String suffix=asset.equals("USDT")?":REMAINDER":":NEX";String entry="ER-"+asset;
        var actual=new RecoveryEntry(entry,7L,asset.equals("USDT")?"H2_TRIAL_REMAINDER":"H2_TRIAL_BONUS","claim-1"+suffix,asset,reward,bucket,
            "ACTIVE","PRODUCTION",0,BigDecimal.ZERO,BigDecimal.ZERO,70L);
        when(cash.earnings.lockRecoveryEntry(entry)).thenReturn(actual);
        cash.advance(asset.equals("USDT")?reward:BigDecimal.ZERO,asset.equals("UVT")?reward:BigDecimal.ZERO);
        service.trialReward(before,entry);
        trial.put(asset.equals("USDT")?"remainderUsdt":"shadowNex",reward);
        var ledger=ledger(asset.equals("USDT")?301:302,"claim-1"+suffix,"TRIAL_BONUS",asset,"IN",reward,
            (BigDecimal)cash.wallet.get(asset.equals("USDT")?"usdtAvailable":"nexAvailable"));
        markers.put(((Number)ledger.get("id")).longValue(),ledger);ledgers.put("claim-1"+suffix,List.of(ledger));
    }
    private Map<String,Object> projection(Source source,String key,long ledger,BigDecimal amount) {
        var row=new LinkedHashMap<String,Object>();Kind kind=source==Source.ORDER_REFUND?Kind.DEVICE_PURCHASE_REFUND:
            Set.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT).contains(source)?Kind.DEVICE_PURCHASE:Kind.DEPOSIT;
        row.put("sourceRootId",1L);row.put("deviceId",8L);row.put("claimId",5L);row.put("intentId",3L);row.put("source",source.name());row.put("kind",kind.name());
        row.put("sourceId","actual-source:1");row.put("customerId",7L);row.put("businessId",key);row.put("duplicateKey",source==Source.VIETQR?"intent-1":key);
        row.put("ledgerId",amount.signum()==0?null:ledger);row.put("ledgerCustomerId",7L);row.put("ledgerBusinessId",key);row.put("amount",amount);row.put("ledgerAmount",amount.signum()==0?null:amount);
        row.put("currency","USDT");row.put("ledgerCurrency","USDT");row.put("sourceLinked",1);row.put("ledgerDeleted",0);row.put("ledgerStatus",source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS");
        row.put("ledgerDirection",SupportPaymentSourceSql.ledgerDirection(source));row.put("ledgerType",SupportPaymentSourceSql.ledgerType(source));
        row.put("succeededAt",at);row.put("ledgerRecordedAt",amount.signum()==0?null:at);row.put("sourceConfirmationAt",at);row.put("sourceVersion","2");
        row.put("fractionalSecondDigits",kind==Kind.DEVICE_PURCHASE?6:0);row.put("successTimeField",source==Source.DEPOSIT_ORDER?"nx_deposit_order.credited_at":source==Source.HDPAY?"nx_hdpay_payin_order.settled_at":kind==Kind.DEVICE_PURCHASE?"nx_order.paid_at":"nx_wallet_ledger.created_at");
        row.put("orderNo","order-1");row.put("orderType",type(source));row.put("paymentNo","payment-1");row.put("sourceOrderNo","card-order");row.put("provider","PSP");row.put("providerPaymentId","provider-1");return row;
    }
    private Map<String,Object> ledger(long id,String key,String type,String asset,String direction,BigDecimal amount,BigDecimal after) {
        var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("customerId",7L);row.put("businessId",key);row.put("ledgerType",type);row.put("currency",asset);
        row.put("direction",direction);row.put("amount",amount);row.put("balanceAfter",after);row.put("status",source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS");
        row.put("deleted",0);row.put("successAt",at);row.put("updatedAt",at);return row;
    }
    private static String type(Source source) {return switch(source){case TRADE_IN->"TRADE_IN";case CAPACITY_KEEP->"CAPACITY_KEEP";case TRIAL_CONVERT->"TRIAL_CONVERT";default->"SINGLE";};}
    private com.fasterxml.jackson.databind.JsonNode operation(String field) throws Exception {return json.readTree(cash.operations.values().iterator().next().get(field).toString());}

    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void allNineSourceTypesBindFreshActualCashAndReplayTheSameTokenWithoutNewInterval(Source source) throws Exception {
        pending(source,BigDecimal.TEN);verifyNoInteractions(cash.mapper);cashAndLedger();assertThat(record()).isPresent();assertThat(record()).isPresent();
        assertThat(cash.operations).hasSize(1);assertThat(cash.legs.values()).singleElement().satisfies(rows->assertThat(rows).hasSize(1));
        assertThat(cash.parties.values()).singleElement().satisfies(rows->assertThat(rows).containsExactly(Map.of("operationId",101L,"customerId",7L)));
        var events=operation("orderedEventsJson");assertThat(events).hasSize(1);
        assertThat(events.get(0).path("action").asText()).isEqualTo(source==Source.ORDER_REFUND?"REFUND":Set.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT).contains(source)?"CONSUME":"DEPOSIT");
        verify(cash.mapper,times(1)).insertOperation(anyMap());verify(mapper,times(1)).insertLedger(eq(source),anyMap());
        assertThat(operation("sourceSnapshotJson").path("sourcePartition").isNull()?null:operation("sourceSnapshotJson").path("sourcePartition").asText()).isEqualTo(partition);
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void jdbcBooleanFlagsKeepProductionPaymentsAndTrialSourcesValid(Source source) {
        cash.wallet(new BigDecimal("100"));cash.wallet.put("sandbox",false);cash.wallet.put("isDeleted",false);
        pending(source,BigDecimal.TEN);cashAndLedger();
        if(trial!=null)for(String key:List.of("userSandbox","userDeleted","deviceDeleted","claimDeleted","orderDeleted"))trial.put(key,false);
        assertThat(record()).isPresent();assertThat(cash.operations).hasSize(1);
    }
    @Test void jdbcBooleanSandboxStillRejectsProductionPaymentBinding() {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);cash.wallet.put("sandbox",true);
        assertThatThrownBy(()->service.beforeCash(before)).hasMessage("PAYMENT_FUND_WALLET_IDENTITY_INVALID");
        assertThat(cash.operations).isEmpty();
    }
    @Test void binaryFlagParsingDoesNotCoerceIdsOrAcceptMissingAndNonBinaryFlags() {
        for(Object value:List.of(false,0,0L,new BigDecimal("0.000000")))assertThat(SupportPaymentFactService.flag(Map.of("flag",value),"flag")).isZero();
        for(Object value:List.of(true,1,1L,new BigDecimal("1.000000")))assertThat(SupportPaymentFactService.flag(Map.of("flag",value),"flag")).isEqualTo(1);
        for(Object value:List.of(2,-1,new BigDecimal("0.5"),"false","0"))assertThatThrownBy(()->SupportPaymentFactService.flag(Map.of("flag",value),"flag")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SupportPaymentFactService.flag(Map.of(),"flag")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SupportPaymentFactService.number(Map.of("id",false),"id")).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(()->SupportPaymentFactService.decimal(Map.of("amount",true),"amount")).isInstanceOf(NumberFormatException.class);
    }
    @ParameterizedTest @ValueSource(strings={"0","10"})
    void trialAlwaysOwnsChargeThenUsdtAndNexRewardsWithActualProtectedBucket(String charge) throws Exception {
        pending(Source.TRIAL_CONVERT,new BigDecimal(charge));cashAndLedger();reward("USDT","4","pending_review");reward("UVT","3","withdrawable");
        assertThat(record().isPresent()).isEqualTo(!charge.equals("0"));
        var events=operation("orderedEventsJson");assertThat(events).hasSize(charge.equals("0")?2:3);
        int offset=charge.equals("0")?0:1;
        assertThat(events.get(offset).path("action").asText()).isEqualTo("PROTECTED_EARNINGS");
        assertThat(events.get(offset+1).path("action").asText()).isEqualTo("EARNINGS");
        assertThat(operation("sourceProofJson").path("cashTrace")).hasSize(3);
        assertThat(cash.legs.values()).singleElement().satisfies(rows->assertThat(rows).hasSize(3));
        if(charge.equals("0"))verify(mapper,never()).insertLedger(any(),anyMap());
    }
    @ParameterizedTest @ValueSource(strings={"currency","amount","digest"})
    void oldTrialBonusReceiptCanReplayAndProveOriginalConsumeWithoutChangingSignedBytes(String alteration) throws Exception {
        pending(Source.TRIAL_CONVERT,BigDecimal.TEN);cashAndLedger();reward("UVT","3","withdrawable");
        var fact=record().orElseThrow();
        var stored=cash.operations.values().iterator().next();long operationId=((Number)stored.get("id")).longValue();
        LegacyUvtProofFixture.makeLegacy(stored,List.of(7L),cash.legs.get(operationId),(SupportFundJournalBindingService)cash.fund);
        var immutable=new LinkedHashMap<>(stored);
        assertThat(stored.get("orderedEventsJson").toString()).contains("\"NEX\"");
        var old=service.beforeSource(7,source,key,partition);
        assertThat(service.readSettled(old)).isPresent();
        Object evidence=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"originalConsumeEvidence",fact);
        assertThat(String.valueOf(evidence)).isEqualTo("VERIFIED");
        assertThat(stored).isEqualTo(immutable);
        verify(cash.mapper,times(1)).insertOperation(anyMap());
        switch(alteration) {
            case "currency" -> stored.put("orderedEventsJson",stored.get("orderedEventsJson").toString().replace("\"NEX\"","\"USDT\""));
            case "amount" -> stored.put("orderedEventsJson",stored.get("orderedEventsJson").toString().replace("\"amount\":3","\"amount\":4"));
            case "digest" -> stored.put("receiptSha256","0".repeat(64));
        }
        assertThatThrownBy(()->service.readSettled(old)).hasMessage("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
        verify(cash.mapper,times(1)).insertOperation(anyMap());
    }
    @Test void oldV1PurchaseWithoutFundConsumeCanRefundWhileFundHistoryRemainsUnknown() throws Exception {
        pending(Source.ORDER_REFUND,new BigDecimal("30"));cashAndLedger();assertThat(record()).isPresent();
        assertThat(operation("sourceProofJson").path("originalFundEvidence").asText()).isEqualTo("UNKNOWN");
        assertThat(operation("orderedEventsJson").get(0).path("reference").asText()).isEqualTo("CONSUME:PURCHASE:order-1");
        assertThat(cash.operations).hasSize(1);assertThat(cash.wallet.get("usdtAvailable")).isEqualTo(new BigDecimal("130"));
        verify(mapper).insertLedger(eq(Source.ORDER_REFUND),anyMap());
    }
    @Test void refundWithRealSourceAmountConflictStillFailsBeforeAnyFundReceipt() {
        pending(Source.ORDER_REFUND,new BigDecimal("30"));cashAndLedger();original.put("ledgerAmount",new BigDecimal("81"));
        assertThatThrownBy(this::record).hasMessage("SETTLEMENT_MISMATCH");assertThat(cash.operations).isEmpty();
    }
    @Test void unchangedNetDeltaCannotHideExtraOrOppositeCashRevisions() {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);cashAndLedger();cash.advance(new BigDecimal("7"),BigDecimal.ZERO);cash.advance(new BigDecimal("-7"),BigDecimal.ZERO);
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_CASH_TRACE_MISMATCH");assertThat(cash.operations).isEmpty();
    }
    @Test void sameCountAndTotalCannotHideWrongOrderedTrialRevisions() {
        pending(Source.TRIAL_CONVERT,BigDecimal.TEN);cashAndLedger();reward("USDT","4","withdrawable");reward("UVT","3","withdrawable");
        cash.journal.get(1).put("newUsdtAvailable",new BigDecimal("93"));
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_CASH_TRACE_MISMATCH");assertThat(cash.operations).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"SANDBOX","","TEST"})
    void trialRequiresActualPositiveProductionDeviceEnvironment(String environment) {
        pending(Source.TRIAL_CONVERT,BigDecimal.ZERO);cashAndLedger();trial.put("deviceEnvironment",environment);
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_TRIAL_SOURCE_MISMATCH");assertThat(cash.operations).isEmpty();
    }
    @Test void actualClaimRewardAmountAndFreshEntryMustAgree() {
        pending(Source.TRIAL_CONVERT,BigDecimal.ZERO);cashAndLedger();reward("UVT","3","bonus_locked");trial.put("shadowNex",new BigDecimal("4"));
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");assertThat(cash.operations).isEmpty();
    }
    @Test void missingOrExtraTrialRewardAndForeignEntryCannotBeReplacedByCallerAmounts() {
        pending(Source.TRIAL_CONVERT,BigDecimal.ZERO);cashAndLedger();trial.put("shadowNex",BigDecimal.ONE);
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");
        when(cash.earnings.lockRecoveryEntry("forged")).thenReturn(new RecoveryEntry("forged",8L,"H2_TRIAL_BONUS","claim-1:NEX","UVT",BigDecimal.ONE,"withdrawable","ACTIVE","PRODUCTION",0,BigDecimal.ZERO,BigDecimal.ZERO,70L));
        assertThatThrownBy(()->service.trialReward(before,"forged")).hasMessage("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");
    }
    @Test void prepareAndOldReplayNeverBeginOrBackfillAnEmptyCashInterval() {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);cashAndLedger();record();
        clearInvocations(cash.mapper);var old=service.beforeSource(7,source,key,partition);assertThat(old.oldSource()).isTrue();
        assertThat(service.readSettled(old)).isPresent();verify(cash.mapper,never()).lockWallet(anyLong());verify(cash.mapper,never()).insertOperation(anyMap());
        assertThatThrownBy(()->service.beforeCash(old)).hasMessage("PAYMENT_FUND_CASH_NOT_ALLOWED");
    }
    @Test void beforeCashIsMandatoryAndExpiredOrOtherPhysicalTokenCannotLockWallet() {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);
        assertThatThrownBy(()->service.insertFreshPaymentLedger(before,BigDecimal.TEN,BigDecimal.TEN,"missing cash guard")).hasMessage("PAYMENT_FUND_BEFORE_CASH_REQUIRED");
        TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.bindResource(dataSource,new Object());
        assertThatThrownBy(()->service.beforeCash(before)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
        TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.bindResource(dataSource,cash.holder);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertThatThrownBy(()->service.beforeCash(before)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");verifyNoInteractions(cash.mapper);
    }
    @Test void traceAndReceiptMustReadBackExactPartyAndLegIdentities() {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);cashAndLedger();record();
        cash.legs.get(101L).set(0,Map.of("operationId",101L,"incarnation","foreign","revision",1L));
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");
    }
    @ParameterizedTest @ValueSource(strings={"digest","raw","party"})
    void oldFormalReceiptMustRetainActualDigestRawRowsAndParty(String alteration) {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);cashAndLedger();record();
        if(alteration.equals("digest"))cash.operations.values().iterator().next().put("receiptSha256","0".repeat(64));
        else if(alteration.equals("raw"))cash.journal.get(0).put("newUsdtAvailable",new BigDecimal("111"));
        else cash.parties.get(101L).set(0,Map.of("operationId",101L,"customerId",8L));
        var old=service.beforeSource(7,source,key,partition);
        assertThatThrownBy(()->service.readSettled(old)).hasMessage("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
        verify(cash.mapper,times(1)).insertOperation(anyMap());
    }
    @Test void highPrecisionAmountSurvivesBinderCanonicalReadbackAndOneMicroDifferenceIsRejected() {
        pending(Source.CARD_TOPUP,new BigDecimal("999999999000.123456"));cashAndLedger();assertThat(record()).isPresent();
        var row=cash.operations.values().iterator().next();
        row.put("orderedEventsJson",row.get("orderedEventsJson").toString().replace("999999999000.123456","999999999000.123457"));
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");
    }
    @Test void duplicateReceiptJsonFieldsCannotHideBehindLastFieldWinsParsing() {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);cashAndLedger();record();
        var row=cash.operations.values().iterator().next();String snapshot=row.get("sourceSnapshotJson").toString();
        row.put("sourceSnapshotJson",snapshot.substring(0,snapshot.length()-1)+",\"customerId\":7}");
        assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_RECEIPT_JSON_INVALID");
    }
    @ParameterizedTest @ValueSource(strings={"VERIFIED","digest","originalLedger"})
    void refundChecksAnExistingFormalConsumeAgainstTheActualOriginalPurchase(String alteration) throws Exception {
        pending(Source.WALLET_ORDER,new BigDecimal("80"));cashAndLedger();record();
        Map<String,Object> savedOriginal=new LinkedHashMap<>(projection);
        freshLedgerId=102;pending(Source.ORDER_REFUND,new BigDecimal("30"));original=savedOriginal;
        cashAndLedger();markers.get(2L).put("ledgerId",101L);
        if(alteration.equals("digest"))cash.operations.values().iterator().next().put("receiptSha256","0".repeat(64));
        else if(alteration.equals("originalLedger")) {
            original.put("ledgerId",201L);markers.get(2L).put("ledgerId",201L);
        }
        if(!alteration.equals("VERIFIED")) {
            assertThatThrownBy(this::record).hasMessage("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");assertThat(cash.operations).hasSize(1);
        } else {
            assertThat(record()).isPresent();assertThat(cash.operations).hasSize(2);
            var refund=cash.operations.values().stream().filter(r->r.get("operationKey").toString().contains("ORDER_REFUND")).findFirst().orElseThrow();
            assertThat(json.readTree(refund.get("sourceProofJson").toString()).path("originalFundEvidence").asText()).isEqualTo("VERIFIED");
            assertThat(cash.wallet.get("usdtAvailable")).isEqualTo(new BigDecimal("50"));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void latePartyConflictEscapesOrCaughtOuterCommitStillRollsBackThroughRealSpring(boolean catchFailure) throws Exception {
        cleanup();when(dataSource.getConnection()).thenReturn(cash.connection);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var factory=new org.springframework.aop.framework.ProxyFactory(service);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxied=(SupportPaymentSourceService)factory.getProxy();
        cash.operations.put("old-blocker",Map.of("id",99L));
        when(cash.mapper.insertParty(anyLong(),anyLong())).thenThrow(new org.springframework.dao.DuplicateKeyException("late-party-conflict"));
        // The storage double models rollback for state assertions; Spring's actual physical rollback is verified separately.
        doAnswer(i->{cash.wallet(new BigDecimal("100"));cash.journal.clear();cash.operations.keySet().removeIf(k->!k.equals("old-blocker"));cash.parties.clear();cash.legs.clear();return null;})
            .when(cash.connection).rollback();
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        Throwable failure=catchThrowable(()->tx.executeWithoutResult(status->{
            pending(Source.CARD_TOPUP,BigDecimal.TEN);cashAndLedger();
            try {proxied.readSettled(before,receipt);}
            catch(org.springframework.dao.DuplicateKeyException ex) {if(!catchFailure)throw ex;}
        }));
        assertThat(failure).isInstanceOf(catchFailure?org.springframework.transaction.UnexpectedRollbackException.class:org.springframework.dao.DuplicateKeyException.class);
        verify(cash.connection).rollback();verify(cash.connection,never()).commit();
        assertThat(cash.operations).containsOnlyKeys("old-blocker");assertThat(cash.journal).isEmpty();assertThat(cash.parties).isEmpty();assertThat(cash.legs).isEmpty();
        assertThat(cash.wallet.get("usdtAvailable")).isEqualTo(new BigDecimal("100"));
        assertThatThrownBy(()->service.beforeCash(before)).hasMessage("WRITABLE_TRANSACTION_REQUIRED");
    }
    @Test void foreignThreadCannotUseALiveTokenEvenWithTheSameMockTransactionHolder() throws Exception {
        pending(Source.CARD_TOPUP,BigDecimal.TEN);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Throwable rejected=executor.submit(()->{
                // Pure double only: no connection is opened or shared with another actual database transaction.
                TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.bindResource(dataSource,cash.holder);
                try {return catchThrowable(()->service.beforeCash(before));}
                finally {TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.clear();}
            }).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(rejected).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");verifyNoInteractions(cash.mapper);
            service.beforeCash(before);verify(cash.mapper,atLeastOnce()).lockWallet(7L);
        } finally {executor.shutdownNow();}
    }
}
