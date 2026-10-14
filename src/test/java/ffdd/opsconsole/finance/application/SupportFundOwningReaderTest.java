package ffdd.opsconsole.finance.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.facade.SupportFundBirthReadFacade;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.finance.mapper.SupportFundReadMapper;
import ffdd.opsconsole.treasury.facade.SupportCommissionReadFacade;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reuses actual Direct service/Binder storage doubles; this is not a SQL or HTTP test. */
class SupportFundOwningReaderTest {
    private static final String EVENT="00000000000040008000000000000007";
    private final SupportFundDirectReferralEvidenceTest direct=new SupportFundDirectReferralEvidenceTest();
    private final SupportFundReadMapper read=mock(SupportFundReadMapper.class);
    private final SupportFundBirthReadFacade auth=mock(SupportFundBirthReadFacade.class);
    private final FinanceSupportPaymentFactsFacade payments=mock(FinanceSupportPaymentFactsFacade.class);
    private final SupportCommissionReadFacade commissions=mock(SupportCommissionReadFacade.class);
    private SupportFundReadService reader;

    @BeforeEach void setup() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);when(direct.cash.connection.isReadOnly()).thenReturn(false);
        direct.setup();var cash=direct.cash;cash.operations.clear();cash.parties.clear();cash.legs.clear();cash.journal.clear();direct.entries.clear();cash.wallet(BigDecimal.ZERO);
        var at=(LocalDateTime)cash.wallet.get("createdAt");String incarnation=(String)cash.wallet.get("incarnation");
        var birth=new LinkedHashMap<String,Object>();birth.put("id",999L);birth.put("incarnation",incarnation);birth.put("revision",0L);birth.put("observation","NEW_ZERO");birth.put("recordedDbUtc",at);
        birth.put("protocolSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("SUPPORT_WALLET_JOURNAL_V1".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        for(String field:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","sandbox","isDeleted","createdAt")){String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);birth.put("old"+cap,null);birth.put("new"+cap,cash.wallet.get(field));}
        cash.journal.add(birth);when(cash.mapper.birthCandidates(70,7)).thenReturn(List.of(Map.of("incarnation",incarnation,"revision",0L)));
        new SupportFundAuthBirthAdapter(cash.mapper,cash.fund,direct.json).record(new SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts(7,at,incarnation,EVENT,33,at,1));
        direct.credit("USDT");direct.credit("NEX");
        var identity=Map.<String,Object>of("id",7L,"createdAt",at,"sandbox",false,"isDeleted",false,"status","ACTIVE");
        var event=new LinkedHashMap<String,Object>();event.put("id",33L);event.put("eventId",EVENT);event.put("aggregateType","USER_REGISTRATION");event.put("aggregateId","7");event.put("eventType","auth.register_completed");event.put("eventName","auth.register_completed");event.put("eventTs",at);event.put("serverAuthoritative",true);event.put("schemaRegistered",true);event.put("analyticsEvent",true);event.put("schemaRevision",1);event.put("isDeleted",false);
        event.put("payload",direct.json.writeValueAsString(Map.of("userId",7,"user_id",7,"event_id",EVENT,"event_name","auth.register_completed","is_server_authoritative",true,"schema_revision",1)));
        when(auth.read(anyList())).thenReturn(new SupportFundBirthReadFacade.Evidence(List.of(identity),List.of(event)));
        when(read.schemaReady()).thenReturn(true);
        when(read.operationIds(anyList())).thenAnswer(i->operations().stream().map(o->((Number)o.get("id")).longValue()).toList());
        when(read.operations(anyList())).thenAnswer(i->operations());
        when(read.parties(anyList())).thenAnswer(i->cash.parties.values().stream().flatMap(Collection::stream).filter(o->i.<List<Long>>getArgument(0).contains(((Number)o.get("operationId")).longValue())).sorted(Comparator.comparingLong(o->((Number)o.get("operationId")).longValue())).toList());
        when(read.legs(anyList())).thenAnswer(i->cash.legs.values().stream().flatMap(Collection::stream).filter(o->i.<List<Long>>getArgument(0).contains(((Number)o.get("operationId")).longValue())).toList());
        when(read.wallets(anyList())).thenAnswer(i->List.of(cash.wallet));when(read.raw(anyList(),anyList())).thenAnswer(i->cash.journal);
        when(read.earningsSources(anyList())).thenAnswer(i->direct.entries);
        when(read.earningsTimePrecisions()).thenReturn(List.of(Map.of("tableName","nx_earnings_release_entry","columnName","created_at","precision",0),Map.of("tableName","nx_earnings_release_entry","columnName","released_at","precision",0),Map.of("tableName","nx_earnings_source_recovery","columnName","created_at","precision",0)));
        when(direct.read.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_direct_referral_settlement","columnName","updated_at","precision",3),Map.of("tableName","nx_unilevel_event_recovery","columnName","updated_at","precision",3),Map.of("tableName","nx_earnings_release_entry","columnName","created_at","precision",0)));
        when(payments.readHistory(anyList())).thenReturn(new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Taipei",at.toInstant(ZoneOffset.UTC)));
        when(commissions.readFundCommissions(anyList())).thenReturn(new SupportCommissionReadFacade.Sources(List.of(),List.of()));
        var codec=direct.codec;var earnings=new SupportFundEarningsEvidence(codec,direct.json);
        reader=new SupportFundReadService(read,auth,payments,codec,direct.dataSource,earnings,null,null,new SupportFundGenesisEvidence(direct.json,codec),new SupportFundExchangeEvidence(codec,direct.json,null),null,null,new SupportFundDeviceEvidence(codec,direct.json),new SupportFundStakingEvidence(codec,direct.json,earnings),commissions,direct.sources,new SupportFundCommissionEvidence(codec,direct.json),direct.evidence);
    }
    @AfterEach void cleanup(){direct.cleanup();TransactionSynchronizationManager.clear();}
    private List<Map<String,Object>> operations(){return direct.cash.operations.values().stream().sorted(Comparator.comparingLong(o->((Number)o.get("id")).longValue())).toList();}
    private SupportFundsReadFacade.NetSnapshot snapshot() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        when(direct.cash.connection.isReadOnly()).thenReturn(true);when(direct.cash.connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);return reader.read(List.of(7L));
    }
    private void ready() throws Exception {direct.evidence.validate(Set.of(7L),direct.input());var result=snapshot();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());assertEquals(0,result.customers().get(0).usdt().signum());assertEquals(0,result.customers().get(0).nex().signum());assertTrue(result.origins().isEmpty());assertTrue(result.changes().isEmpty());}
    private void unknown(String reason) throws Exception {var result=snapshot();assertEquals(SupportFundsReadFacade.EvidenceState.UNKNOWN,result.customers().get(0).state(),result.toString());assertTrue(result.customers().get(0).reasons().contains(reason),result.toString());assertNull(result.customers().get(0).usdt());assertTrue(result.changes().isEmpty());}
    @Test void actualDualCurrencyDirectRecoveryAuthorizesHistoricalCreditsOnlyAfterCompleteValidation() throws Exception {direct.service.reverse("DR-test",BigDecimal.ONE);assertEquals(1,direct.debitCalls);ready();}
    @Test void realFailureCheckpointDoesNotInventCashOrInvalidateActiveCredits() throws Exception {direct.service.markRecoveryFailure("DR-test");assertEquals(0,direct.debitCalls);ready();}
    @Test void missingZeroCashFailureReceiptIsNotHiddenByUnchangedWallet() throws Exception {direct.service.markRecoveryFailure("DR-test");direct.cash.operations.remove(direct.last().get("operationKey"));unknown("SOURCE");}
    @Test void missingActualDirectSourceDoesNotAuthorizeReversedCredit() throws Exception {direct.service.reverse("DR-test",BigDecimal.ONE);when(direct.read.settlementsByCustomers(any())).thenReturn(List.of());unknown("SOURCE");}
    @Test void alteredCurrentCentralAmountCannotReuseAValidatedRecoveryCapability() throws Exception {direct.service.reverse("DR-test",BigDecimal.ONE);direct.entries.get(0).put("amount",new BigDecimal("11"));unknown("SOURCE");}
    @Test void nativeBooleanWalletFlagsAndIntegerRawFlagsRemainTheSamePhysicalFlags() throws Exception {
        when(direct.read.wallets(7L)).thenAnswer(i->{var row=new LinkedHashMap<>(direct.cash.wallet);row.put("sandbox",false);row.put("isDeleted",false);return List.of(row);});
        direct.service.reverse("DR-test",BigDecimal.ONE);assertEquals(1,direct.debitCalls);ready();
    }
    @Test void nativeBooleanZeroCashCheckpointPreservesIntegerRawAnchorFlags() throws Exception {
        when(direct.read.wallets(7L)).thenAnswer(i->{var row=new LinkedHashMap<>(direct.cash.wallet);row.put("sandbox",false);row.put("isDeleted",false);return List.of(row);});
        direct.service.markRecoveryFailure("DR-test");assertEquals(0,direct.debitCalls);ready();
    }
}
