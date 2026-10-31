package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.facade.SupportFundBirthReadFacade;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.device.application.SupportDeviceFundAdapter;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import ffdd.opsconsole.market.application.SupportExchangeFundAdapter;
import ffdd.opsconsole.market.application.SupportExchangeReadService;
import ffdd.opsconsole.market.application.SupportStakingFundAdapter;
import ffdd.opsconsole.market.application.SupportStakingReadService;
import ffdd.opsconsole.market.mapper.SupportExchangeSourceMapper;
import ffdd.opsconsole.market.mapper.SupportStakingSourceMapper;
import ffdd.opsconsole.treasury.application.SupportCommissionFundAdapter;
import ffdd.opsconsole.treasury.application.SupportCommissionReadService;
import ffdd.opsconsole.treasury.facade.SupportCommissionReadFacade;
import ffdd.opsconsole.treasury.mapper.SupportCommissionReadMapper;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real binder/codec, mocked source projections and JDBC; no MySQL claims. */
class SupportFundReadServiceTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,11,0,0);
    private static final String INC="12345678-1234-1234-1234-123456789abc",EVENT="12345678123412341234123456789abc";
    private final ObjectMapper json=new ObjectMapper();
    private final SupportFundReadMapper mapper=mock(SupportFundReadMapper.class);
    private final SupportFundJournalMapper writer=mock(SupportFundJournalMapper.class);
    private final SupportFundBirthReadFacade auth=mock(SupportFundBirthReadFacade.class);
    private final FinanceSupportPaymentFactsFacade payments=mock(FinanceSupportPaymentFactsFacade.class);
    private final DataSource ds=mock(DataSource.class);private final Connection connection=mock(Connection.class);
    private final SupportFundJournalBindingService binder=new SupportFundJournalBindingService(writer,ds,json);
    private final SupportGenesisReadFacade genesis=mock(SupportGenesisReadFacade.class);
    private final SupportExchangeSourceMapper exchangeMapper=mock(SupportExchangeSourceMapper.class);
    private final SupportExchangeReadFacade exchanges=new SupportExchangeReadService(exchangeMapper);
    private final SupportDeviceReadFacade devices=mock(SupportDeviceReadFacade.class);
    private final SupportDeviceReadMapper deviceMapper=mock(SupportDeviceReadMapper.class);
    private final SupportStakingSourceMapper stakingMapper=mock(SupportStakingSourceMapper.class);
    private final SupportStakingReadFacade staking=new SupportStakingReadService(stakingMapper);
    private final SupportFundEarningsEvidence earningsEvidence=new SupportFundEarningsEvidence(binder,json);
    private final SupportCommissionReadMapper commissionMapper=mock(SupportCommissionReadMapper.class);
    private final SupportCommissionReadFacade commissions=new SupportCommissionReadService(commissionMapper);
    private final SupportDirectReferralReadFacade direct=mock(SupportDirectReferralReadFacade.class);
    private final SupportFundReadService reader=new SupportFundReadService(mapper,auth,payments,binder,ds,
        earningsEvidence,genesis,exchanges,new SupportFundGenesisEvidence(json,binder),new SupportFundExchangeEvidence(binder,json,exchanges),
        devices,staking,new SupportFundDeviceEvidence(binder,json),new SupportFundStakingEvidence(binder,json,earningsEvidence),
        commissions,direct,new SupportFundCommissionEvidence(binder,json),new SupportFundDirectReferralEvidence(binder,json));
    private final List<Map<String,Object>> raw=new ArrayList<>(),operations=new ArrayList<>(),parties=new ArrayList<>(),legs=new ArrayList<>();
    private final List<Map<String,Object>> walletRows=new ArrayList<>(),paymentLedgers=new ArrayList<>();
    private final List<SupportPaymentFacts.Fact> facts=new ArrayList<>();
    private final List<Map<String,Object>> settledSources=new ArrayList<>();
    private final List<Map<String,Object>> earningSources=new ArrayList<>(),earningAudits=new ArrayList<>();
    private final List<Map<String,Object>> commissionEvents=new ArrayList<>(),commissionLedgers=new ArrayList<>();
    private final List<SupportGenesisReadFacade.Source> genesisSources=new ArrayList<>();
    private final List<Map<String,Object>> exchangeOrders=new ArrayList<>(),exchangeLedgers=new ArrayList<>();
    private final List<Map<String,Object>> deviceReceipts=new ArrayList<>(),deviceTasks=new ArrayList<>(),deviceLedgers=new ArrayList<>(),deviceEvents=new ArrayList<>();
    private final Map<String,Object> stakingPosition=new LinkedHashMap<>();
    private final List<Map<String,Object>> stakingLedgers=new ArrayList<>();
    private final Map<String,Object> cashWalletOverrides=new HashMap<>();
    private boolean omitCashFlag;
    private Map<String,Object> wallet;
    @BeforeEach void setup() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(ds,holder);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        wallet=wallet();walletRows.add(wallet);raw.add(birth(wallet));
        when(writer.lockWallet(1)).thenAnswer(inv->List.of(wallet));
        when(writer.birthCandidates(11,1)).thenAnswer(inv->List.of(Map.of("incarnation",INC,"revision",0L)));
        when(writer.journal(anyString(),anyLong(),anyLong())).thenAnswer(inv->raw.stream().filter(row->num(row,"revision")>inv.<Long>getArgument(1) &&num(row,"revision")<=inv.<Long>getArgument(2)).toList());
        when(writer.insertOperation(anyMap())).thenAnswer(inv->{var row=new LinkedHashMap<String,Object>(inv.getArgument(0));row.put("id",(long)operations.size()+1);row.put("recordedDbUtc",AT);operations.add(row);return 1;});
        when(writer.operation(anyString())).thenAnswer(inv->operations.stream().filter(row->row.get("operationKey").equals(inv.getArgument(0))).findFirst().orElse(null));
        when(writer.insertParty(anyLong(),anyLong())).thenAnswer(inv->{parties.add(Map.of("operationId",inv.getArgument(0),"customerId",inv.getArgument(1)));return 1;});
        when(writer.insertLeg(anyLong(),anyString(),anyLong())).thenAnswer(inv->{legs.add(Map.of("operationId",inv.getArgument(0),"incarnation",inv.getArgument(1),"revision",inv.getArgument(2)));return 1;});
        when(writer.parties(anyLong())).thenAnswer(inv->parties.stream().filter(row->num(row,"operationId")==inv.<Long>getArgument(0)).toList());
        when(writer.legs(anyLong())).thenAnswer(inv->legs.stream().filter(row->num(row,"operationId")==inv.<Long>getArgument(0)).toList());
        new SupportFundAuthBirthAdapter(writer,binder,json).record(new SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts(1,AT,INC,EVENT,33,AT,1));
        when(mapper.schemaReady()).thenReturn(true);when(mapper.operationIds(anyList())).thenAnswer(inv->operations.stream().map(row->num(row,"id")).toList());
        when(mapper.operations(anyList())).thenAnswer(inv->operations);when(mapper.parties(anyList())).thenAnswer(inv->parties.stream().filter(row->inv.<List<Long>>getArgument(0).contains(num(row,"operationId"))).toList());
        when(mapper.legs(anyList())).thenAnswer(inv->legs.stream().filter(row->inv.<List<Long>>getArgument(0).contains(num(row,"operationId"))).toList());when(mapper.wallets(anyList())).thenAnswer(inv->walletRows);
        when(mapper.raw(anyList(),anyList())).thenAnswer(inv->raw);when(mapper.paymentLedgers(anyList())).thenAnswer(inv->paymentLedgers);
        when(mapper.withdrawals(anyList())).thenReturn(List.of());when(mapper.ledgers(anyList())).thenReturn(List.of());
        when(mapper.withdrawalAudits(anyList())).thenReturn(List.of());when(mapper.bankReservations(anyList())).thenReturn(List.of());
        when(mapper.withdrawalAttempts(anyList())).thenReturn(List.of());when(mapper.earnings(anyList())).thenReturn(List.of());
        when(mapper.earningsSources(anyList())).thenAnswer(inv->earningSources.isEmpty()?mapper.earnings(inv.getArgument(0)):earningSources);
        when(mapper.earningsRecoveries(anyList())).thenReturn(List.of());when(mapper.earningsAppliedProofs(anyList())).thenReturn(List.of());
        when(mapper.earningsAudits(anyList())).thenAnswer(inv->earningAudits);
        when(mapper.earningsTimePrecisions()).thenReturn(List.of(Map.of("tableName","nx_earnings_release_entry","columnName","created_at","precision",0),Map.of("tableName","nx_earnings_release_entry","columnName","released_at","precision",0),Map.of("tableName","nx_earnings_source_recovery","columnName","created_at","precision",0)));
        when(mapper.paymentSources(any(),anyList())).thenAnswer(inv->inv.getArgument(0)==SupportPaymentFacts.Source.DEPOSIT_ORDER?settledSources:List.of());
        when(mapper.paymentAudits(anyList())).thenReturn(List.of());
        when(mapper.trialSources(anyList())).thenReturn(List.of());
        when(genesis.readByCustomers(anyList())).thenAnswer(inv->genesisSources);
        when(genesis.readByOrderNos(anyList())).thenAnswer(inv->genesisSources.stream().filter(s->inv.<List<String>>getArgument(0).contains(s.order().get("orderNo"))).toList());
        when(exchangeMapper.byCustomers(anyCollection())).thenAnswer(inv->exchangeOrders);
        when(exchangeMapper.byOrderNos(anyCollection())).thenAnswer(inv->exchangeOrders.stream().filter(o->inv.<Collection<String>>getArgument(0).contains(o.get("exchangeNo"))).toList());
        when(exchangeMapper.ledgers(anyList())).thenAnswer(inv->exchangeLedgers);
        when(exchangeMapper.fees(anyCollection())).thenReturn(List.of());
        when(exchangeMapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_exchange_order","columnName","updated_at","precision",0)));
        when(deviceMapper.fundWallets(1)).thenAnswer(inv->List.of(deviceWallet()));
        when(devices.readFundEarnings(anyList())).thenAnswer(inv->new SupportDeviceReadFacade.FundEarnings(deviceReceipts,deviceTasks,deviceLedgers,deviceEvents));
        when(devices.readFundEarning(anyLong(),anyString())).thenAnswer(inv->new SupportDeviceReadFacade.FundEarnings(deviceReceipts,deviceTasks,deviceLedgers,deviceEvents));
        when(devices.timePrecisions()).thenReturn(Map.of("nx_compute_receipt.completed_at",6,"nx_compute_task.completed_at",6,"nx_compute_task.proof_consumed_at",0,
            "nx_wallet_ledger.created_at",0,"nx_earning_event.wallet_posted_at",0,"nx_earning_event.created_at",0));
        when(stakingMapper.wallets(1)).thenAnswer(inv->List.of(stakingWallet()));
        when(stakingMapper.byCustomers(anyCollection())).thenAnswer(inv->stakingPosition.isEmpty()?List.of():List.of(new LinkedHashMap<>(stakingPosition)));
        when(stakingMapper.byPositionNos(anyCollection())).thenAnswer(inv->stakingPosition.isEmpty()?List.of():List.of(new LinkedHashMap<>(stakingPosition)));
        when(stakingMapper.ledgers(anyCollection())).thenAnswer(inv->stakingLedgers.stream().map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());
        when(stakingMapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_staking_position","columnName","created_at","precision",0),
            Map.of("tableName","nx_staking_position","columnName","claimed_at","precision",0),Map.of("tableName","nx_staking_position","columnName","early_withdrawn_at","precision",0)));
        when(commissionMapper.fundEvents(anyList())).thenAnswer(inv->commissionEvents);
        when(commissionMapper.fundLedgers(anyList())).thenAnswer(inv->commissionLedgers);
        when(commissionMapper.fundWallets(1L)).thenAnswer(inv->{var row=deviceWallet();row.put("userSandbox",false);row.put("userDeleted",false);return List.of(row);});
        when(commissionMapper.fundTimePrecisions()).thenReturn(List.of(Map.of("tableName","nx_wallet_ledger","columnName","created_at","precision",0),
            Map.of("tableName","nx_commission_event","columnName","created_at","precision",0),Map.of("tableName","nx_commission_event","columnName","updated_at","precision",0)));
        when(direct.readByCustomers(anyList())).thenReturn(List.of());
        var identity=Map.<String,Object>of("id",1L,"createdAt",AT,"sandbox",false,"isDeleted",false,"status","ACTIVE");
        var event=new HashMap<String,Object>();event.put("id",33L);event.put("eventId",EVENT);event.put("aggregateType","USER_REGISTRATION");event.put("aggregateId","1");
        event.put("eventType","auth.register_completed");event.put("eventName","auth.register_completed");event.put("eventTs",AT);
        event.put("serverAuthoritative",true);event.put("schemaRegistered",true);event.put("analyticsEvent",true);event.put("schemaRevision",1);event.put("isDeleted",false);
        event.put("payload",json.writeValueAsString(Map.of("userId",1,"user_id",1,"event_id",EVENT,"event_name","auth.register_completed","is_server_authoritative",true,"schema_revision",1)));
        when(auth.read(anyList())).thenReturn(new SupportFundBirthReadFacade.Evidence(List.of(identity),List.of(event)));
        when(payments.readHistory(anyList())).thenAnswer(inv->new SupportPaymentFacts.Snapshot(facts,List.of(),List.of(),"Asia/Taipei",AT.toInstant(java.time.ZoneOffset.UTC)));
        readOnly();
    }
    @AfterEach void clear(){if(TransactionSynchronizationManager.hasResource(ds))TransactionSynchronizationManager.unbindResource(ds);TransactionSynchronizationManager.clear();}
    private void readOnly() throws Exception {TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);when(connection.isReadOnly()).thenReturn(true);}
    private void writable() throws Exception {TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);when(connection.isReadOnly()).thenReturn(false);}
    private SupportFundsReadFacade.NetSnapshot read(){return reader.read(List.of(1L));}
    @Test void oneReadReusesItsValidatedReceiptButNextReadValidatesAgain() throws Exception {
        ready();var expected=read();var observed=spy(binder);
        var instrumented=new SupportFundReadService(mapper,auth,payments,observed,ds,
            earningsEvidence,genesis,exchanges,new SupportFundGenesisEvidence(json,binder),new SupportFundExchangeEvidence(binder,json,exchanges),
            devices,staking,new SupportFundDeviceEvidence(binder,json),new SupportFundStakingEvidence(binder,json,earningsEvidence),
            commissions,direct,new SupportFundCommissionEvidence(binder,json),new SupportFundDirectReferralEvidence(binder,json));
        assertEquals(expected,instrumented.read(List.of(1L)));
        verify(observed,times(1)).decodeReceipt(anyMap(),anyList(),anyList());
        assertEquals(expected,instrumented.read(List.of(1L)));
        verify(observed,times(2)).decodeReceipt(anyMap(),anyList(),anyList());
        operations.get(0).put("receiptSha256","invalid");
        var changed=instrumented.read(List.of(1L)).customers().get(0);
        assertEquals(SupportFundsReadFacade.EvidenceState.UNKNOWN,changed.state());
        assertNull(changed.usdt());assertNull(changed.nex());
        verify(observed,times(3)).decodeReceipt(anyMap(),anyList(),anyList());
    }
    @Test void completeLegHistoryIsNotRescannedForEveryReceiptAndUnknownStaysUnknown() {
        for(long id=2;id<=2000;id++){
            var op=new LinkedHashMap<>(operations.get(0));
            op.put("id",id);op.put("operationKey","UNREPRESENTED:"+id);op.put("businessType","UNREPRESENTED_SOURCE");
            operations.add(op);parties.add(Map.of("operationId",id,"customerId",1L));
            legs.add(Map.of("operationId",id,"incarnation",INC,"revision",id));
        }
        operations.forEach(this::resign);
        int[] reads={0};
        var observedLegs=new AbstractList<Map<String,Object>>(){
            @Override public Map<String,Object> get(int index){
                if(++reads[0]>legs.size()*8)throw new AssertionError("Reader repeatedly scanned the complete operation-leg history");
                return legs.get(index);
            }
            @Override public int size(){return legs.size();}
        };
        when(mapper.legs(anyList())).thenReturn(observedLegs);
        var first=read();var second=read();
        assertEquals(first,second);
        assertEquals(SupportFundsReadFacade.EvidenceState.UNKNOWN,first.customers().get(0).state());
        assertNull(first.customers().get(0).usdt());assertNull(first.customers().get(0).nex());
        assertTrue(first.customers().get(0).reasons().contains("RAW_BINDING"));
        assertTrue(reads[0]<=legs.size()*8);
    }
    @ParameterizedTest @ValueSource(strings={"missing","duplicate","reversed","foreign","fractional"})
    void indexedLegLookupKeepsMissingDuplicateOrderAndOwnershipFailures(String corruption) throws Exception {
        deviceEarning(true,false);ready();
        switch(corruption){
            case "missing" -> legs.remove(legs.size()-1);
            case "duplicate" -> legs.add(new HashMap<>(legs.get(legs.size()-1)));
            case "reversed" -> Collections.reverse(legs);
            case "foreign" -> {var leg=new HashMap<>(legs.get(legs.size()-1));leg.put("operationId",999999L);legs.set(legs.size()-1,leg);}
            case "fractional" -> {var leg=new HashMap<>(legs.get(legs.size()-1));leg.put("operationId",new BigDecimal("1.5"));legs.set(legs.size()-1,leg);}
            default -> throw new AssertionError(corruption);
        }
        when(mapper.legs(anyList())).thenAnswer(inv->legs);
        var result=read().customers().get(0);
        assertEquals(SupportFundsReadFacade.EvidenceState.UNKNOWN,result.state());
        assertNull(result.usdt());assertNull(result.nex());assertFalse(result.reasons().isEmpty());
    }
    @Test void readSnapshotCannotUseAnotherDataSourceOrMissingSynchronization() {
        assertThrows(IllegalStateException.class,()->binder.readSnapshot(mock(DataSource.class)));
        TransactionSynchronizationManager.clearSynchronization();
        assertThrows(IllegalStateException.class,()->read());
        TransactionSynchronizationManager.initSynchronization();
    }
    private void unknown(String reason){var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.UNKNOWN,result.customers().get(0).state());assertNull(result.customers().get(0).usdt());assertNull(result.customers().get(0).nex());assertTrue(result.customers().get(0).reasons().contains(reason),result.toString());assertTrue(result.changes().isEmpty());}
    @ParameterizedTest @ValueSource(strings={"customerId","actorId"})
    void nativeUnionAuditIdentityStringsRetainVerifiedContribution(String field) throws Exception {
        zeroTrial();trialAuditIdentity(field,"1");ready();
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidAuditIdentityStrings() {
        return java.util.stream.Stream.of("customerId","actorId").flatMap(field->
            java.util.stream.Stream.of("1.0","1e0","+1","01"," 1","1tail","0","9223372036854775808")
                .map(value->org.junit.jupiter.params.provider.Arguments.of(field,value)));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("invalidAuditIdentityStrings")
    void noncanonicalAuditIdentityStringsNeverProduceAnAmount(String field,String value) throws Exception {
        zeroTrial();trialAuditIdentity(field,value);unknown("RECEIPT");
    }
    private void trialAuditIdentity(String field,String value) throws Exception {
        var audit=new LinkedHashMap<>(mapper.paymentAudits(List.of(1L)).get(0));audit.put(field,value);
        when(mapper.paymentAudits(anyList())).thenReturn(List.of(audit));
        var receipt=operations.stream().filter(row->"SUPPORT_PAYMENT".equals(row.get("businessType"))).findFirst().orElseThrow();
        var proof=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(receipt.get("sourceProofJson").toString());
        proof.set("sourceAuthority",json.valueToTree(audit));receipt.put("sourceProofJson",binder.canonical(proof).toString());resign(receipt);
    }
    @Test void nativeAuthWalletBooleanAndJournalNumericRemainReady() throws Exception {
        assertTrue(wallet.get("sandbox") instanceof Boolean);assertTrue(raw.get(0).get("newSandbox") instanceof Integer);
        var recorded=json.readTree(operations.get(0).get("sourceSnapshotJson").toString());
        assertTrue(recorded.path("wallet").path("sandbox").isBoolean());assertTrue(recorded.path("birthJournal").path("newSandbox").isIntegralNumber());ready();
    }
    @ParameterizedTest @ValueSource(longs={10,70,100,1000})
    void canonicalExponentCustomerIdentifiersStillReadTheirRealBirth(long customer) throws Exception {
        var evidence=auth.read(List.of(1L));writable();
        operations.clear();parties.clear();legs.clear();raw.clear();wallet.put("userId",customer);raw.add(birth(wallet));
        when(writer.lockWallet(customer)).thenAnswer(inv->List.of(wallet));
        when(writer.birthCandidates(11,customer)).thenReturn(List.of(Map.of("incarnation",INC,"revision",0L)));
        new SupportFundAuthBirthAdapter(writer,binder,json).record(new SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts(customer,AT,INC,EVENT,33,AT,1));
        var identity=new HashMap<>(evidence.customers().get(0));identity.put("id",customer);
        var event=new HashMap<>(evidence.registrationEvents().get(0));event.put("aggregateId",Long.toString(customer));
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(event.get("payload").toString());
        payload.put("userId",customer);payload.put("user_id",customer);event.put("payload",json.writeValueAsString(payload));
        when(auth.read(anyList())).thenReturn(new SupportFundBirthReadFacade.Evidence(List.of(identity),List.of(event)));readOnly();
        var result=reader.read(List.of(customer));assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());
        assertEquals(0,result.customers().get(0).usdt().signum());assertTrue(result.origins().isEmpty());assertTrue(result.changes().isEmpty());
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidRegistrationPayloadIntegers() {
        return java.util.stream.Stream.of("userId","user_id","schema_revision").flatMap(field->
            java.util.stream.Stream.of("1.5","true","\"1\"","18446744073709551617").map(value->org.junit.jupiter.params.provider.Arguments.of(field,value)));
    }
    @ParameterizedTest @MethodSource("invalidRegistrationPayloadIntegers")
    void registrationPayloadIntegersCannotTruncateOrWrap(String field,String literal) throws Exception {
        var evidence=auth.read(List.of(1L));var event=new HashMap<>(evidence.registrationEvents().get(0));
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(event.get("payload").toString());
        payload.set(field,json.readTree(literal));event.put("payload",json.writeValueAsString(payload));
        when(auth.read(anyList())).thenReturn(new SupportFundBirthReadFacade.Evidence(evidence.customers(),List.of(event)));unknown("AUTH_BIRTH");
    }
    @Test void nativePaymentBeforeAfterBooleanAgainstJournalNumericRemainReady() throws Exception {deposit();nativeCashProof(operations.get(1));ready();}
    @Test void actualPaymentSourceBinderReaderWithNativeCashFlagTypesIsReady() throws Exception {
        actualPayment();nativeCashProof(operations.get(1));ready();assertEquals(new BigDecimal("10.000000"),read().customers().get(0).usdt());
    }
    @Test void nativeWithdrawalReserveConfirmBeforeAfterRemainReady() throws Exception {
        withdrawal(true);nativeCashProof(operations.get(2));nativeCashProof(operations.get(3));ready();
    }
    @Test void nativeWithdrawalReserveReturnBeforeAfterRemainReady() throws Exception {
        withdrawal(false);nativeCashProof(operations.get(2));nativeCashProof(operations.get(3));ready();
    }
    @Test void nativeTrialRewardBooleanSourceMatchesActualTypedIntegerSnapshot() throws Exception {
        zeroTrial();var reward=json.readTree(operations.get(1).get("sourceSnapshotJson").toString()).path("rewards").get(0);
        assertTrue(reward.path("isDeleted").isIntegralNumber());nativeCashProof(operations.get(1));ready();
    }
    @Test void birthJournalSameSourceTypeTamperStillUnknown() {raw.get(0).put("newSandbox",false);unknown("AUTH_BIRTH");}
    @Test void booleanWalletIdIsNeverAFlag() {wallet.put("id",true);unknown("RECEIPT");}
    @Test void booleanRawCustomerIdIsNeverAFlag() {raw.get(0).put("newUserId",true);unknown("RECEIPT");}
    @Test void booleanMoneyIsNeverAFlag() {wallet.put("usdtAvailable",false);unknown("RECEIPT");}
    @Test void flippedWalletFlagRemainsUnknown() {wallet.put("sandbox",true);unknown("WALLET");}
    @Test void nonbinaryWalletFlagRemainsUnknown() {wallet.put("sandbox",2);unknown("RECEIPT");}
    @Test void fractionalWalletFlagRemainsUnknown() {wallet.put("isDeleted",new BigDecimal("0.5"));unknown("RECEIPT");}
    @ParameterizedTest @MethodSource("invalidFlags") void validReceiptWithInvalidCashFlagIsUnknown(Object invalid) throws Exception {
        cashWalletOverrides.put("sandbox",invalid);deposit();unknown("SOURCE");
    }
    private static List<Object> invalidFlags(){return Arrays.asList(2,new BigDecimal("0.5"),null,"0","false");}
    @Test void validReceiptWithMissingCashFlagIsUnknown() throws Exception {omitCashFlag=true;deposit();unknown("SOURCE");}
    @Test void validReceiptWithFlippedCashFlagIsUnknown() throws Exception {cashWalletOverrides.put("isDeleted",true);deposit();unknown("CASH_TRACE");}
    @Test void validReceiptWithBooleanCashIdIsUnknown() throws Exception {cashWalletOverrides.put("id",true);deposit();unknown("CASH_TRACE");}
    @Test void validReceiptWithBooleanCashMoneyIsUnknown() throws Exception {cashWalletOverrides.put("nexAvailable",false);deposit();unknown("CASH_TRACE");}
    @Test void validReceiptWithWrongFlagNameIsUnknown() throws Exception {cashWalletOverrides.put("Sandbox",false);deposit();unknown("SOURCE");}
    @Test void missingWalletFlagRemainsUnknown() {wallet.remove("sandbox");unknown("RECEIPT");}
    @Test void sameSourcePaymentRawFlagTypeTamperIsStillUnknown() throws Exception {deposit();raw.get(1).put("newSandbox",false);unknown("CASH_TRACE");}
    @Test void sameSourceLedgerFlagTypeTamperIsStillUnknown() throws Exception {deposit();paymentLedgers.get(0).put("deleted",false);unknown("SOURCE");}
    @ParameterizedTest @MethodSource("invalidFlags") void invalidNativeRewardFlagRemainsUnknown(Object invalid) throws Exception {
        zeroTrial();mapper.earnings(List.of(1L)).get(0).put("isDeleted",invalid);unknown("SOURCE");
    }
    @Test void flippedNativeRewardFlagRemainsUnknown() throws Exception {zeroTrial();mapper.earnings(List.of(1L)).get(0).put("isDeleted",true);unknown("SOURCE");}
    @Test void booleanRewardSourceWalletIdIsNeverAFlag() throws Exception {zeroTrial();mapper.earnings(List.of(1L)).get(0).put("sourceWalletId",true);unknown("SOURCE");}
    @Test void booleanRewardMoneyIsNeverAFlag() throws Exception {zeroTrial();mapper.earnings(List.of(1L)).get(0).put("amount",false);unknown("SOURCE");}
    private void ready(){var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());}
    private void nativeCashProof(Map<String,Object> operation) throws Exception {
        var proof=json.readTree(operation.get("sourceProofJson").toString());
        for(String side:List.of("cashBefore","cashAfter"))for(String field:List.of("sandbox","isDeleted"))assertTrue(proof.path(side).path(field).isBoolean());
        for(var item:proof.path("cashTrace"))for(String side:List.of("old","new"))for(String field:List.of("Sandbox","IsDeleted"))assertTrue(item.path("journal").path(side+field).isIntegralNumber());
    }
    @Test void operationPartyIndexPreservesIndependentComponentsDuplicateOrderAndMissingOperation() throws Exception {
        var build=SupportFundReadService.class.getDeclaredMethod("indexParties",List.class);build.setAccessible(true);
        var rows=List.<Map<String,Object>>of(Map.of("operationId",1L,"customerId",4L),Map.of("operationId",2L,"customerId",5L),Map.of("operationId",1L,"customerId",3L),Map.of("operationId",1L,"customerId",4L));
        var indexed=build.invoke(null,rows);assertEquals(Map.of(1L,List.of(4L,3L,4L),2L,List.of(5L)),indexed);
        var lookup=SupportFundReadService.class.getDeclaredMethod("parties",long.class,Map.class);lookup.setAccessible(true);
        assertEquals(List.of(4L,3L,4L),lookup.invoke(null,1L,indexed));assertEquals(List.of(5L),lookup.invoke(null,2L,indexed));assertEquals(List.of(),lookup.invoke(null,99L,indexed));
    }
    @Test void disconnectedComponentsDoNotRescanAllPartyRowsForEachCustomer() throws Exception {
        var visits=new java.util.concurrent.atomic.AtomicInteger();
        var rows=new ArrayList<Map<String,Object>>();var ids=new TreeSet<Long>();
        for(long id=1;id<=400;id++) {
            var row=new HashMap<String,Object>() {
                @Override public Object get(Object key) {visits.incrementAndGet();return super.get(key);}
            };
            row.put("operationId",id);row.put("customerId",id);rows.add(row);ids.add(id);
        }
        var method=SupportFundReadService.class.getDeclaredMethod("components",Set.class,List.class);method.setAccessible(true);
        var expected=ids.stream().map(Set::of).toList();
        assertEquals(expected,method.invoke(null,ids,rows));
        assertTrue(visits.get()<=rows.size()*3,"Disconnected accounts must not cause quadratic scans: "+visits.get());
    }
    @Test void indexedComponentsKeepTransitiveCyclesDuplicatesAndIsolatedCustomers() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();
        for(long[] pair:new long[][]{{20,3},{10,1},{20,2},{10,2},{30,3},{30,1},{40,6},{40,5},{40,5}})
            rows.add(Map.of("operationId",pair[0],"customerId",pair[1]));
        var method=SupportFundReadService.class.getDeclaredMethod("components",Set.class,List.class);method.setAccessible(true);
        assertEquals(List.of(Set.of(1L,2L,3L),Set.of(4L),Set.of(5L,6L),Set.of(7L)),
            method.invoke(null,Set.of(7L,6L,5L,4L,3L,2L,1L),rows));
        assertEquals(List.of(),method.invoke(null,Set.of(),List.of()));
    }
    @Test void duplicatePartyStillFailsOriginalReceiptValidation(){parties.add(new LinkedHashMap<>(parties.get(0)));unknown("RECEIPT");}
    @Test void foreignOperationPartyStillFailsOriginalGlobalScopeValidation(){var row=new LinkedHashMap<>(parties.get(0));row.put("operationId",999L);parties.set(0,row);when(mapper.parties(anyList())).thenAnswer(inv->parties);unknown("RECEIPT");}
    @Test void nullPartyCustomerStillFailsOriginalGlobalScopeValidation(){var row=new LinkedHashMap<>(parties.get(0));row.put("customerId",null);parties.set(0,row);unknown("RECEIPT");}
    @Test void genuineAuthBirthAndZeroLifetimeAreReadyWithBooleanFlags(){var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state());assertEquals(0,result.customers().get(0).usdt().signum());}
    @Test void genuineBoundDepositHasConfirmedAmountAndOriginalFact() throws Exception {deposit();var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());assertEquals(new BigDecimal("10.000000"),result.customers().get(0).usdt());assertEquals("DEPOSIT:20",result.origins().get(0).paymentFactId());assertEquals(1,result.changes().size());}
    @Test void tailMismatchIsUnknown() throws Exception {deposit();wallet.put("usdtAvailable",new BigDecimal("11.000000"));unknown("RAW_HISTORY");}
    @Test void rawRevisionGapIsUnknown() throws Exception {deposit();raw.remove(1);unknown("RAW_HISTORY");}
    @Test void extraRawFormerIncarnationIsUnknown(){var former=new HashMap<>(raw.get(0));former.put("id",2L);former.put("incarnation","22345678-1234-1234-1234-123456789abc");raw.add(former);unknown("RAW_HISTORY");}
    @Test void persistedDigestTamperIsUnknown(){operations.get(0).put("receiptSha256","0".repeat(64));unknown("RECEIPT");}
    @Test void missingPartyDoesNotInventSingleCustomerScope(){parties.clear();unknown("RAW_BINDING");}
    @Test void duplicateRawOwnershipIsUnknown(){legs.add(new HashMap<>(legs.get(0)));unknown("RECEIPT");}
    @Test void missingAuthOriginalOutboxIsUnknown(){when(auth.read(anyList())).thenReturn(new SupportFundBirthReadFacade.Evidence(List.of(),List.of()));unknown("AUTH_BIRTH");}
    @Test void walletIncarnationNonceMismatchIsUnknown(){wallet.put("incarnation","22345678-1234-1234-1234-123456789abc");unknown("RAW_HISTORY");}
    @Test void originalSourceMissingIsUnknown() throws Exception {deposit();facts.clear();unknown("SOURCE");}
    @Test void actualSuccessWithoutFormalReceiptIsUnknown() throws Exception {deposit();operations.remove(1);legs.remove(1);parties.remove(1);unknown("RAW_BINDING");}
    @Test void extraSuccessfulSourceWithoutCashReceiptIsUnknown() throws Exception {deposit();facts.add(new SupportPaymentFacts.Fact("DEPOSIT:21",SupportPaymentFacts.Kind.DEPOSIT,SupportPaymentFacts.Source.DEPOSIT_ORDER,List.of("nx_deposit_order:2"),1,21,"topup2",null,null,null,"USDT",BigDecimal.ONE,AT,"nx_deposit_order.credited_at",0,null,AT,AT,"1",SupportPaymentFacts.Status.UNKNOWN));unknown("SOURCE_COVERAGE");}
    @Test void missingZeroCashReleaseIsDiscoveredBySourceEnumeration(){when(mapper.earnings(anyList())).thenReturn(List.of(Map.of("userId",1L,"entryNo","earned","releasedAt",AT)));unknown("SOURCE_COVERAGE");}
    @Test void actualCentralEarningsCreditHasNoContributionUntilConsumed() throws Exception {
        earning("withdrawable",false,false);ready();assertEquals(0,read().customers().get(0).usdt().signum());assertTrue(read().origins().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"}) void actualTreasuryCommissionIsUncreditedIncome(String asset) throws Exception {
        commission(asset,false);ready();assertEquals(0,read().customers().get(0).usdt().signum());assertEquals(0,read().customers().get(0).nex().signum());assertTrue(read().origins().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"}) void actualTreasuryClawbackPreservesZeroContribution(String asset) throws Exception {
        commission(asset,false);commission(asset,true);ready();assertTrue(read().changes().isEmpty());assertTrue(read().origins().isEmpty());
    }
    @Test void commissionClawbackAfterConsumptionAndRechargeDoesNotReverseTheirContribution() throws Exception {
        commission("USDT",false);genesisPurchase();actualPayment();commission("USDT",true);ready();
        assertEquals(new BigDecimal("12.000000"),read().customers().get(0).usdt());assertEquals(2,read().origins().size());assertEquals(2,read().changes().size());assertEquals(n("8"),wallet.get("usdtAvailable"));
    }
    @Test void missingTreasuryEventDoesNotInventAnIncomeSource() throws Exception {commission("USDT",false);commissionEvents.clear();unknown("SOURCE");}
    @Test void missingTreasuryLedgerDoesNotInventAnIncomeSource() throws Exception {commission("USDT",false);commissionLedgers.clear();unknown("SOURCE");}
    @Test void unreadableTreasurySourceDoesNotBecomeZero() throws Exception {commission("USDT",false);when(commissionMapper.fundEvents(anyList())).thenThrow(new IllegalStateException("unreadable"));unknown("READ_FAILED");}
    @Test void missingTreasuryTimePrecisionDoesNotGuessSuccessfulDate() throws Exception {commission("USDT",false);when(commissionMapper.fundTimePrecisions()).thenReturn(List.of());unknown("SOURCE");}
    @Test void extraTreasuryLedgerWithoutReceiptIsDetectedByIndependentCoverage() throws Exception {
        commission("USDT",false);var extra=new LinkedHashMap<>(commissionLedgers.get(0));extra.put("id",401L);extra.put("bizNo","F5-COMMISSION-56-RELEASE");commissionLedgers.add(extra);unknown("SOURCE_COVERAGE");
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void deviceEarningsKeepEachCurrencyUncredited(boolean dual) throws Exception {
        deviceEarning(dual,false);ready();assertEquals(0,read().customers().get(0).usdt().signum());assertEquals(0,read().customers().get(0).nex().signum());assertTrue(read().origins().isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void zeroDeviceUsdtStillRequiresItsActualVersionOnlyCashLeg(boolean dual) throws Exception {
        deviceEarning(dual,true);ready();assertTrue(read().changes().isEmpty());
    }
    @Test void deviceEarningConsumptionCountsOnce() throws Exception {
        deviceEarning(false,false);genesisPurchase();ready();assertEquals(new BigDecimal("2.000000"),read().customers().get(0).usdt());
    }
    @Test void deviceEarningWithdrawalUsesEarningBeforeRechargePrincipal() throws Exception {
        deposit();deviceEarning(false,false);withdrawal(true);ready();assertEquals(new BigDecimal("8.000000"),read().customers().get(0).usdt());
    }
    @ParameterizedTest @ValueSource(strings={"receipt","task","ledger","event","precision"}) void missingDeviceOriginalsCannotProduceACompleteContribution(String source) throws Exception {
        deviceEarning(true,false);switch(source){case "receipt"->deviceReceipts.clear();case "task"->deviceTasks.clear();case "ledger"->deviceLedgers.clear();case "event"->deviceEvents.clear();default->when(devices.timePrecisions()).thenReturn(Map.of());}unknown("SOURCE");
    }
    @ParameterizedTest @ValueSource(strings={"OPEN","CLAIM","EARLY","ZERO_RETURN"}) void stakingPreservesRechargeBasisAcrossEachRealCashPhase(String phase) throws Exception {
        deposit();staking(phase,true);ready();assertEquals(new BigDecimal("10.000000"),read().customers().get(0).usdt());
    }
    @Test void stakingInterestWithdrawalDoesNotDeductTheInterestFromContribution() throws Exception {
        deposit();staking("CLAIM",true);withdrawal(true);ready();assertEquals(new BigDecimal("7.000000"),read().customers().get(0).usdt());
    }
    @Test void g1PrincipalClaimRequiresItsSeparateActualCentralInterestCredit() throws Exception {
        deposit();staking("CLAIM",false);ready();assertEquals(new BigDecimal("10.000000"),read().customers().get(0).usdt());
        operations.removeIf(row->"SUPPORT_EARNINGS".equals(row.get("businessType")));unknown("RAW_BINDING");
    }
    @Test void stakingOriginalWithoutOpenReceiptIsNeverTreatedAsNewPrincipal() throws Exception {
        deposit();staking("OPEN",true);operations.removeIf(row->"SUPPORT_STAKING".equals(row.get("businessType")));unknown("RAW_BINDING");
    }
    @Test void deviceReadFailureIsUnknownAndCannotEraseExistingContribution() throws Exception {
        deposit();when(devices.readFundEarnings(anyList())).thenThrow(new IllegalStateException("database unavailable"));unknown("READ_FAILED");
    }
    @Test void releasedEarningsAreSelectedBeforeCreditedWithdrawalPrincipal() throws Exception {
        deposit();earning("bonus_locked",true,false);withdrawal(true);ready();assertEquals(new BigDecimal("8.000000"),read().customers().get(0).usdt());
    }
    @Test void unreleasedProtectedEarningsDoNotReducePrincipalWithdrawalContribution() throws Exception {
        deposit();earning("bonus_locked",false,false);withdrawal(true);ready();assertEquals(new BigDecimal("5.000000"),read().customers().get(0).usdt());
    }
    @Test void zeroReleaseNeedsOriginalWalletCausalPredecessor() throws Exception {deposit();earning("bonus_locked",true,true);unknown("CAUSAL_ORDER");}
    @Test void zeroReleaseNeedsPersistedAuthorityAudit() throws Exception {earning("bonus_locked",true,false);earningAudits.clear();unknown("SOURCE");}
    @Test void actualEarningsSourceWithoutReceiptIsUnknown() {earningSources.add(Map.of("entryNo","omitted","userId",1L));unknown("SOURCE_COVERAGE");}
    @Test void earningTimePrecisionCannotBeInvented() throws Exception {earning("withdrawable",false,false);when(mapper.earningsTimePrecisions()).thenReturn(List.of());unknown("SOURCE");}
    @Test void protectedEarningBucketCannotChangeWithoutReleaseAuthority() throws Exception {
        earning("bonus_locked",false,false);earningSources.get(0).put("bucket","withdrawable");unknown("SOURCE");
    }
    @Test void releaseSourceWithoutActualReleaseCannotProveReady() throws Exception {
        earning("bonus_locked",false,false);earningSources.get(0).put("releaseSource","manual");unknown("SOURCE");
    }
    @Test void releasedTrialRewardKeepsItsOriginalCompoundCredit() throws Exception {
        zeroTrial("bonus_locked");writable();var entry=mapper.earnings(List.of(1L)).get(0);var original=new LinkedHashMap<String,Object>(entry);
        var sourceMapper=mock(EarningsReleaseMapper.class);when(sourceMapper.fundEntry("trial-USDT")).thenAnswer(inv->entry);
        when(sourceMapper.previousFundOperation(1)).thenReturn((long)operations.size());var source=new SupportFundEarningsAdapter(binder,writer,sourceMapper,json);
        var before=source.begin(1);entry.put("bucket","withdrawable");entry.put("releasedAt",AT);entry.put("releaseSource","manual");
        var audit=new LinkedHashMap<String,Object>();audit.put("id",89L);audit.put("resourceId","trial-USDT");audit.put("resourceType","EARNINGS_RELEASE_ENTRY");
        audit.put("action","K1_EARNINGS_MANUALLY_RELEASED");audit.put("result","SUCCESS");audit.put("isDeleted",false);
        audit.put("detailJson",json.writeValueAsString(Map.of("userId",1L,"before","bonus_locked","after","withdrawable","reason","test release authority","coverageAtSubmit",BigDecimal.ONE,"coverageRedline",BigDecimal.ONE)));
        earningAudits.add(audit);when(sourceMapper.fundReleaseAudit("trial-USDT","K1_EARNINGS_MANUALLY_RELEASED")).thenReturn(audit);
        source.release(before,original);readOnly();ready();assertEquals(0,read().customers().get(0).usdt().signum());assertTrue(read().changes().isEmpty());
    }
    @Test void actualGenesisConsumptionDeduplicatesRechargeInCompleteReader() throws Exception {
        deposit();genesisPurchase();var result=read();ready();
        assertEquals(0,new BigDecimal("10").compareTo(result.customers().get(0).usdt()));
        assertEquals(1,result.origins().size());assertEquals("DEPOSIT:20",result.origins().get(0).paymentFactId());
    }
    @Test void actualGenesisConsumptionOfEarningsFormsItsOwnContribution() throws Exception {
        earning("withdrawable",false,false);genesisPurchase();var result=read();ready();
        assertEquals(0,new BigDecimal("2").compareTo(result.customers().get(0).usdt()));
        assertEquals("CONSUMPTION:GEN-reader",result.origins().get(0).paymentFactId());
    }
    @Test void genesisOriginalCannotDisappearWhileItsReceiptSurvives() throws Exception {
        deposit();genesisPurchase();genesisSources.clear();unknown("SOURCE");
    }
    @Test void actualMarketExchangeThroughRealBinderKeepsOriginalContributionBasis() throws Exception {
        deposit();exchange("IMMEDIATE");var result=read();ready();
        assertEquals(0,new BigDecimal("10").compareTo(result.customers().get(0).usdt()));
        assertEquals(0,result.customers().get(0).nex().signum());assertEquals(1,result.origins().size());
    }
    @Test void actualQueuedExchangeHasVerifiedSourceHoldingInsteadOfMissingCash() throws Exception {
        deposit();exchange("HOLD");ready();
        assertEquals(0,new BigDecimal("10").compareTo(read().customers().get(0).usdt()));
    }
    @Test void queuedExchangeCompletionKeepsItsOriginalHoldAndContributionBasis() throws Exception {
        deposit();exchange("HOLD");settleExchange(false);ready();
        assertEquals(0,new BigDecimal("10").compareTo(read().customers().get(0).usdt()));
        assertEquals(new BigDecimal("2.000000"),wallet.get("nexAvailable"));
    }
    @Test void queuedExchangeCancellationReturnsItsOriginalSource() throws Exception {
        deposit();exchange("HOLD");settleExchange(true);ready();
        assertEquals(0,new BigDecimal("10").compareTo(read().customers().get(0).usdt()));
        assertEquals(new BigDecimal("10.000000"),wallet.get("usdtAvailable"));
    }
    @Test void withdrawalAfterExchangeStillReversesOriginalRechargeIncludingWithdrawalFee() throws Exception {
        deposit();exchange("IMMEDIATE");withdrawal(true);var result=read();ready();
        assertEquals(0,new BigDecimal("5").compareTo(result.customers().get(0).usdt()));
        assertEquals(0,result.customers().get(0).nex().signum());
        assertEquals("DEPOSIT:20",result.origins().get(0).paymentFactId());
    }
    @Test void exchangeOriginalCannotDisappearWhileItsReceiptSurvives() throws Exception {
        deposit();exchange("IMMEDIATE");exchangeOrders.clear();unknown("SOURCE");
    }
    @Test void malformedSignedExchangeTimestampYieldsUnknownRatherThanThrowing() throws Exception {
        deposit();exchange("IMMEDIATE");var operation=operations.get(2);
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(operation.get("sourceSnapshotJson").toString());
        source.put("phaseAt","invalid-date");operation.put("sourceSnapshotJson",binder.canonical(source).toString());resign(operation);
        unknown("SOURCE");
    }
    @Test void invalidOwningTimestampMetadataYieldsUnknownRatherThanThrowing() throws Exception {
        deposit();exchange("IMMEDIATE");
        when(exchangeMapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_exchange_order","columnName","updated_at","precision",new BigDecimal("0.5"))));
        unknown("READ_FAILED");
    }
    @Test void malformedOwningSourceReadYieldsUnknownRatherThanThrowing() throws Exception {
        when(genesis.readByCustomers(anyList())).thenThrow(new IllegalArgumentException("Invalid source projection"));unknown("READ_FAILED");
    }
    @Test void missingCurrentSchemaGuardIsUnknown(){when(mapper.schemaReady()).thenReturn(false);unknown("RAW_HISTORY");verify(mapper,never()).wallets(anyList());}
    @Test void physicalTransactionIsRequired() throws Exception {when(connection.getAutoCommit()).thenReturn(true);assertThrows(IllegalStateException.class,this::read);}
    @Test void emptyScopeCannotExpandToPlatform(){assertTrue(reader.read(List.of()).customers().isEmpty());verify(mapper,never()).wallets(anyList());}
    @Test void facadeFirstConsumerDelegatesOnlyExplicitScope(){var service=new SupportFundsReadService(mock(SupportFundsReadMapper.class),reader);assertEquals(read(),service.readNetContributions(List.of(1L)));}
    @Test void zeroChargeTrialUsesActualRawRevisionAndBothActualRewardsWithoutInventingContribution() throws Exception {
        zeroTrial();var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());
        assertEquals(0,result.customers().get(0).usdt().signum());assertEquals(0,result.customers().get(0).nex().signum());assertTrue(result.changes().isEmpty());
        assertEquals(4,raw.size());
    }
    @Test void missingActualTrialRewardSourceCannotBeReplacedByJson() throws Exception {zeroTrial();when(mapper.earnings(anyList())).thenReturn(List.of());unknown("SOURCE");}
    @Test void changedOriginalPaymentSourceTupleIsUnknown() throws Exception {deposit();settledSources.set(0,Map.of("customerId",1L,"ledgerId",20L,"actual","changed"));unknown("SOURCE");}
    @Test void realPositiveSourceWithoutHistoryCaptureOrCashCannotDisappear() {
        settledSources.add(Map.of("customerId",1L,"ledgerId",20L,"source","DEPOSIT_ORDER","kind","DEPOSIT","amount",BigDecimal.ONE));unknown("SOURCE_COVERAGE");
    }
    @Test void genuineReservedThenConfirmedWithdrawalDeductsGrossIncludingFee() throws Exception {
        withdrawal(true);var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());
        assertEquals(new BigDecimal("5.000000"),result.customers().get(0).usdt());assertEquals(2,result.changes().size());
        assertEquals(new BigDecimal("-5.000000"),result.changes().get(1).amount());
        assertEquals(result.changes().get(0).contributionId(),result.changes().get(1).contributionId());
    }
    @Test void failedWithdrawalReturnsOriginalSourceAndProducesNoNegativeContribution() throws Exception {
        withdrawal(false);var result=read();assertEquals(SupportFundsReadFacade.EvidenceState.READY,result.customers().get(0).state(),result.toString());
        assertEquals(new BigDecimal("10.000000"),result.customers().get(0).usdt());assertEquals(1,result.changes().size());
    }
    @Test void withdrawalWithoutOriginalReservationIsUnknown() throws Exception {
        withdrawal(true);operations.remove(2);parties.remove(2);legs.remove(2);unknown("RAW_BINDING");
    }
    @Test void unknownWithdrawalTimePrecisionCannotInventSuccessfulMonth() throws Exception {
        withdrawal(true);when(mapper.withdrawalSuccessPrecision()).thenReturn(null);unknown("SOURCE");
    }
    @Test void transitivePartyClosureReadsBAndCButNeverPublishesPeers() {
        operations.add(Map.of("id",2L));operations.add(Map.of("id",3L));
        parties.add(Map.of("operationId",2L,"customerId",1L));parties.add(Map.of("operationId",2L,"customerId",2L));
        parties.add(Map.of("operationId",3L,"customerId",2L));parties.add(Map.of("operationId",3L,"customerId",3L));
        when(mapper.operationIds(anyList())).thenAnswer(inv->{List<Long> scope=inv.getArgument(0);return parties.stream().filter(row->scope.contains(num(row,"customerId"))).map(row->num(row,"operationId")).distinct().sorted().toList();});
        when(mapper.parties(anyList())).thenAnswer(inv->{List<Long> scope=inv.getArgument(0);return parties.stream().filter(row->scope.contains(num(row,"operationId"))).toList();});
        when(mapper.operations(anyList())).thenAnswer(inv->{List<Long> scope=inv.getArgument(0);return operations.stream().filter(row->scope.contains(num(row,"id"))).toList();});
        var result=read();verify(mapper).wallets(List.of(1L,2L,3L));assertEquals(1,result.customers().size());assertEquals(1,result.customers().get(0).customerId());
        assertEquals(SupportFundsReadFacade.EvidenceState.UNKNOWN,result.customers().get(0).state());assertTrue(result.origins().isEmpty());assertTrue(result.changes().isEmpty());
    }
    private void withdrawal(boolean confirmed) throws Exception {
        if(facts.isEmpty())deposit();writable();String no="withdraw";
        var sourceMapper=mock(WithdrawalPayoutMapper.class);
        var order=new LinkedHashMap<String,Object>();order.put("id",60L);order.put("userId",1L);order.put("withdrawalNo",no);order.put("asset","USDT");order.put("chain","TRC20");
        order.put("amount",new BigDecimal("5.000000"));order.put("netReceive",new BigDecimal("4.000000"));order.put("actualFee",new BigDecimal("1.000000"));order.put("nexBurned",new BigDecimal("0.000000"));order.put("idempotencyKey","withdraw-key");
        order.put("status","SUBMITTED");order.put("createdAt",AT);order.put("completedAt",null);order.put("userSandbox",false);order.put("userDeleted",false);order.put("isDeleted",false);
        order.put("attemptStatus","COMMITTED");order.put("attemptWithdrawalNo",no);order.put("attemptUserId",1L);order.put("attemptKey","withdraw-key");
        var reservationLedgers=new ArrayList<Map<String,Object>>();
        for(String part:List.of("PRINCIPAL","NETWORK_FEE")) {
            var ledger=new LinkedHashMap<String,Object>();ledger.put("id",part.equals("PRINCIPAL")?61L:62L);ledger.put("userId",1L);ledger.put("bizNo",no+":USDT:"+part);
            ledger.put("bizType",part.equals("PRINCIPAL")?"WITHDRAW_NET_PRINCIPAL":"WITHDRAW_NETWORK_FEE");ledger.put("asset","USDT");ledger.put("direction","OUT");
            ledger.put("amount",new BigDecimal(part.equals("PRINCIPAL")?"4.000000":"1.000000"));ledger.put("balanceAfter",new BigDecimal("5.000000"));ledger.put("status","POSTED");ledger.put("createdAt",AT);ledger.put("isDeleted",false);reservationLedgers.add(ledger);
        }
        when(sourceMapper.fundOrder(no)).thenAnswer(inv->order);when(sourceMapper.fundReservationLedgers(no)).thenReturn(reservationLedgers);
        when(sourceMapper.fundSubmissionAudits(no)).thenReturn(List.of());
        var source=new SupportWithdrawalFundSourceService(binder,writer,sourceMapper,json);
        var reserveBefore=source.begin(1);advanceCash(new BigDecimal("-5"),BigDecimal.ZERO);wallet.put("pendingWithdraw",new BigDecimal("5.000000"));raw.get(raw.size()-1).put("newPendingWithdraw",wallet.get("pendingWithdraw"));source.reserved(reserveBefore,no);
        var terminalBefore=source.begin(1);
        advanceCash(confirmed?BigDecimal.ZERO:new BigDecimal("5"),BigDecimal.ZERO);wallet.put("pendingWithdraw",new BigDecimal("0.000000"));raw.get(raw.size()-1).put("newPendingWithdraw",wallet.get("pendingWithdraw"));
        order.put("status",confirmed?"CONFIRMED":"FAILED");order.put("completedAt",confirmed?AT:null);
        if(confirmed)source.confirmed(terminalBefore,no,AT);else source.returned(terminalBefore,no);
        when(mapper.withdrawals(anyList())).thenAnswer(inv->List.of(order));when(mapper.ledgers(anyList())).thenReturn(reservationLedgers);
        when(mapper.withdrawalAttempts(anyList())).thenReturn(List.of(Map.of("userId",1L,"withdrawalNo",no,"idempotencyKey","withdraw-key","status","COMMITTED")));
        when(mapper.withdrawalSuccessPrecision()).thenReturn(0);readOnly();
    }
    @ParameterizedTest @ValueSource(strings={"withdrawable","pending_review","bonus_locked"})
    void actualCreditCapturesNullableColumnsOmittedByMyBatis(String bucket) throws Exception {
        earning(bucket,false,false,true);ready();
        assertEquals(0,read().customers().get(0).usdt().signum());
        for(String field:List.of("clusterId","releasedAt","releaseSource"))assertFalse(earningSources.get(0).containsKey(field));
    }
    @ParameterizedTest @ValueSource(strings={"pending_review","bonus_locked"})
    void actualReleasePreservesAnOriginalCreditWithOmittedNullColumns(String bucket) throws Exception {
        earning(bucket,true,false,true);ready();
        assertEquals(0,read().customers().get(0).usdt().signum());assertTrue(read().changes().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"id","amount","sourceWalletId"})
    void missingRequiredEarningColumnsAreStillUnknown(String field) throws Exception {
        earning("withdrawable",false,false);earningSources.get(0).remove(field);unknown("SOURCE");
    }
    private void earning(String bucket,boolean release,boolean wrongPredecessor) throws Exception {
        earning(bucket,release,wrongPredecessor,false);
    }
    private void earning(String bucket,boolean release,boolean wrongPredecessor,boolean omitNulls) throws Exception {
        writable();String no="central-earned";var sourceMapper=mock(EarningsReleaseMapper.class);
        var entry=new LinkedHashMap<String,Object>();entry.put("id",88L);entry.put("entryNo",no);entry.put("userId",1L);entry.put("clusterId",null);
        entry.put("sourceType","ACCOUNT_REWARD");entry.put("sourceRef","reward-source");entry.put("asset","USDT");entry.put("amount",new BigDecimal("3.000000"));
        entry.put("bucket",bucket);entry.put("status","ACTIVE");entry.put("idempotencyKey","reward-key");entry.put("sourceEnvironment","PRODUCTION");
        entry.put("isDeleted",false);entry.put("debitBaseline",BigDecimal.ZERO);entry.put("sourceWalletId",11L);entry.put("recoveredAmount",BigDecimal.ZERO);
        entry.put("createdAt",AT);entry.put("releasedAt",null);entry.put("releaseSource",null);
        if(omitNulls)for(String field:List.of("clusterId","releasedAt","releaseSource"))entry.remove(field);
        earningSources.add(entry);
        when(sourceMapper.fundEntry(no)).thenAnswer(inv->entry);
        when(sourceMapper.previousFundOperation(1)).thenAnswer(inv->wrongPredecessor?1L:(long)operations.size());
        var source=new SupportFundEarningsAdapter(binder,writer,sourceMapper,json);
        var before=source.begin(1);advanceCash(new BigDecimal("3.000000"),BigDecimal.ZERO);source.credit(before,no);
        if(release) {
            before=source.begin(1);var original=source.entry(no);entry.put("bucket","withdrawable");entry.put("releasedAt",AT);entry.put("releaseSource","manual");
            var audit=new LinkedHashMap<String,Object>();audit.put("id",89L);audit.put("resourceId",no);audit.put("resourceType","EARNINGS_RELEASE_ENTRY");
            audit.put("action","K1_EARNINGS_MANUALLY_RELEASED");audit.put("result","SUCCESS");audit.put("isDeleted",false);
            audit.put("detailJson",json.writeValueAsString(Map.of("userId",1L,"before",bucket,"after","withdrawable","reason","test release authority","coverageAtSubmit",BigDecimal.ONE,"coverageRedline",BigDecimal.ONE)));
            earningAudits.add(audit);when(sourceMapper.fundReleaseAudit(no,"K1_EARNINGS_MANUALLY_RELEASED")).thenReturn(audit);
            source.release(before,original);
        }
        readOnly();
    }
    private void zeroTrial() throws Exception {zeroTrial("withdrawable");}
    private void zeroTrial(String bucket) throws Exception {
        writable();var before=binder.begin(List.of(1L));var old=new HashMap<>(wallet);
        String claim="trial",order="trial-order",factId="PURCHASE:"+order,key="PAYMENT:TRIAL_CONVERT:"+sha("TRIAL_CONVERT||"+factId);
        var trace=new ArrayList<Map<String,Object>>();
        advanceCash(BigDecimal.ZERO,BigDecimal.ZERO);trace.add(Map.of("sourceId","nx_trial_claim:44","journal",strings(raw.get(raw.size()-1))));
        var trial=new HashMap<String,Object>();trial.put("customerId",1L);trial.put("claimNo",claim);trial.put("claimId",44L);trial.put("orderId",45L);trial.put("deviceId",46L);trial.put("orderNo",order);
        trial.put("claimStatus","REDEEMED");trial.put("orderType","TRIAL_CONVERT");trial.put("paymentStatus","PAID");trial.put("deviceEnvironment","PRODUCTION");
        trial.put("sourceChannel","TRIAL");trial.put("deviceStatus","ACTIVE");trial.put("ownershipStatus","OWNED");trial.put("userStatus","ACTIVE");
        trial.put("chargeUsdt",BigDecimal.ZERO);trial.put("amountUsdt",BigDecimal.ZERO);trial.put("remainderUsdt",new BigDecimal("3"));trial.put("shadowNex",new BigDecimal("2"));trial.put("paidAt",AT);trial.put("settledAt",AT);
        for(String field:List.of("userSandbox","userDeleted","claimDeleted","deviceDeleted","orderDeleted"))trial.put(field,false);
        var entries=new ArrayList<Map<String,Object>>();var rewardJson=new ArrayList<Map<String,Object>>();var rewardLedgerJson=new ArrayList<Map<String,Object>>();var events=new ArrayList<Event>();
        for(String asset:List.of("USDT","UVT")) {
            String no="trial-"+asset,ref=claim+(asset.equals("USDT")?":REMAINDER":":NEX");BigDecimal amount=new BigDecimal(asset.equals("USDT")?"3":"2");
            var entry=new LinkedHashMap<String,Object>();entry.put("entryNo",no);entry.put("userId",1L);entry.put("sourceType",asset.equals("USDT")?"H2_TRIAL_REMAINDER":"H2_TRIAL_BONUS");entry.put("sourceRef",ref);
            entry.put("asset",asset);entry.put("amount",amount);entry.put("bucket",bucket);entry.put("status","ACTIVE");entry.put("sourceEnvironment","PRODUCTION");entry.put("isDeleted",false);
            entry.put("id",47L+entries.size());entry.put("clusterId",null);entry.put("idempotencyKey",no);entry.put("createdAt",AT);entry.put("releaseSource",null);
            entry.put("recoveredAmount",BigDecimal.ZERO);entry.put("sourceWalletId",11L);entry.put("debitBaseline",BigDecimal.ZERO);entry.put("releasedAt",null);entries.add(entry);
            var typed=new EarningsReleaseMapper.RecoveryEntry(no,1L,entry.get("sourceType").toString(),ref,asset,amount,bucket,"ACTIVE","PRODUCTION",0,BigDecimal.ZERO,BigDecimal.ZERO,11L);
            var method=SupportPaymentSourceService.class.getDeclaredMethod("rewardSnapshot",EarningsReleaseMapper.RecoveryEntry.class);method.setAccessible(true);
            @SuppressWarnings("unchecked") var snapshot=(Map<String,Object>)method.invoke(null,typed);rewardJson.add(snapshot);
            advanceCash(asset.equals("USDT")?amount:BigDecimal.ZERO,asset.equals("UVT")?amount:BigDecimal.ZERO);
            var ledger=new LinkedHashMap<String,Object>();ledger.put("id",100L+entries.size());ledger.put("sourceId","nx_wallet_ledger:"+(100+entries.size()));ledger.put("customerId",1L);ledger.put("businessId",ref);ledger.put("ledgerType","TRIAL_BONUS");
            ledger.put("status","POSTED");ledger.put("successAt",AT);ledger.put("deleted",false);ledger.put("currency",asset);ledger.put("direction","IN");ledger.put("amount",amount);
            ledger.put("balanceAfter",wallet.get(asset.equals("USDT")?"usdtAvailable":"nexAvailable"));ledger.put("remark","actual reward");ledger.put("updatedAt",AT);paymentLedgers.add(ledger);rewardLedgerJson.add(strings(ledger));
            events.add(new Event("EARNINGS:"+no,"withdrawable".equals(bucket)?Action.EARNINGS:Action.PROTECTED_EARNINGS,null,new Account(1,asset,"available"),null,amount,null));
            trace.add(Map.of("sourceId","nx_earnings_release_entry:"+no,"eventId","EARNINGS:"+no,"journal",strings(raw.get(raw.size()-1))));
        }
        // The zero primary cash observation remains real, with an explicit null event ID.
        var primary=new HashMap<>(trace.get(0));primary.put("eventId",null);trace.set(0,primary);
        var projection=Map.<String,Object>of("customerId",1L,"orderNo",order,"amount",BigDecimal.ZERO,"source","TRIAL_CONVERT","kind","DEVICE_PURCHASE");
        var audit=Map.<String,Object>of("customerId",1L,"orderNo",order,"validDigest",1,"actorId",1L,"actorType","USER","result","SUCCESS");
        var source=new LinkedHashMap<String,Object>();source.put("schemaVersion","SUPPORT_PAYMENT_FUND_V1");source.put("customerId",1L);source.put("factId",factId);source.put("source","TRIAL_CONVERT");
        source.put("stableBusinessKey",claim+":CHARGE");source.put("sourcePartition",null);source.put("orderNo",order);source.put("amount",BigDecimal.ZERO);source.put("currency","USDT");source.put("succeededAt",AT.toString());
        source.put("settledSource",projection);source.put("freshLedger",null);source.put("rewards",rewardJson);source.put("rewardLedgers",rewardLedgerJson);source.put("trialSource",strings(trial));
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_PAYMENT_FUND_V1");proof.put("sourceAuthority",audit);proof.put("cashBefore",cashWallet(old));proof.put("cashAfter",cashWallet(wallet));
        proof.put("cashTrace",trace);proof.put("originalConsume",null);proof.put("originalFundEvidence",null);
        binder.bind(before,new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_PAYMENT","SETTLED","nx_trial_claim","TRIAL_CONVERT:"+sha(claim+":CHARGE|"),"PRODUCTION",
            json.writeValueAsString(events),json.writeValueAsString(source),json.writeValueAsString(proof)));
        when(mapper.paymentSources(eq(SupportPaymentFacts.Source.TRIAL_CONVERT),anyList())).thenReturn(List.of(projection));
        when(mapper.paymentAudits(anyList())).thenReturn(List.of(audit));when(mapper.trialSources(anyList())).thenReturn(List.of(trial));when(mapper.earnings(anyList())).thenReturn(entries);
        readOnly();
    }
    private void genesisPurchase() throws Exception {
        writable();String no="GEN-reader";var family=SupportPaymentFacts.Source.GENESIS_PRIMARY;
        var sourceMapper=mock(SupportPaymentSourceMapper.class);
        var ledgerRows=new ArrayList<Map<String,Object>>();
        when(sourceMapper.ledgers(no)).thenAnswer(inv->ledgerRows);
        when(sourceMapper.currentMarker(eq(SupportPaymentSourceMapper.Marker.LEDGER),anyLong())).thenAnswer(inv->ledgerRows.get(0));
        when(sourceMapper.insertLedger(eq(family),anyMap())).thenAnswer(inv->{
            Map<String,Object> write=inv.getArgument(1);write.put("id",200L);
            var ledger=new LinkedHashMap<String,Object>();ledger.put("id",200L);ledger.put("customerId",1L);ledger.put("businessId",no);
            ledger.put("ledgerType","GENESIS_PURCHASE");ledger.put("currency","USDT");ledger.put("direction","OUT");ledger.put("amount",write.get("amount"));
            ledger.put("balanceAfter",write.get("balanceAfter"));ledger.put("status","SUCCESS");ledger.put("deleted",0);ledger.put("successAt",AT);ledger.put("updatedAt",AT);
            ledger.put("remark",write.get("remark"));ledgerRows.add(ledger);return 1;
        });
        var sourceService=new SupportPaymentSourceService(sourceMapper,mock(SupportPaymentFactService.class),ds,json,
            mock(ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.class),binder,writer,mock(EarningsReleaseMapper.class),genesis);
        var before=sourceService.beforeSource(1,family,no,null);sourceService.beforeGenesisCash(before,List.of(1L),null);
        advanceCash(new BigDecimal("-2"),BigDecimal.ZERO);
        var ledger=sourceService.insertFreshPaymentLedger(before,new BigDecimal("2"),(BigDecimal)wallet.get("usdtAvailable"),"Genesis purchase");
        var order=new LinkedHashMap<String,Object>();order.put("id",100L);order.put("orderNo",no);order.put("customerId",1L);order.put("seriesCode","GEN");
        order.put("quantity",1);order.put("unitPrice",new BigDecimal("2.000000"));order.put("amount",new BigDecimal("2.000000"));order.put("currency","USDT");
        order.put("status","COMPLETED");order.put("orderType","PRIMARY");order.put("sellerId",null);order.put("holdingNo",null);order.put("royalty",BigDecimal.ZERO);
        order.put("paidAt",AT);order.put("completedAt",AT);order.put("deleted",false);order.put("buyerSandbox",false);order.put("buyerDeleted",false);
        order.put("sellerSandbox",false);order.put("sellerDeleted",false);
        genesisSources.add(new SupportGenesisReadFacade.Source(order,ledgerRows));
        facts.add(sourceService.readSettled(before,ledger).orElseThrow());readOnly();
    }
    private void exchange(String phase) throws Exception {
        writable();String no="EX-reader";
        when(exchangeMapper.wallets(1)).thenAnswer(inv->{
            var result=new LinkedHashMap<String,Object>();
            for(String field:List.of("id","incarnation","revision","version","usdtAvailable","nexAvailable","sandbox"))result.put(field,wallet.get(field));
            result.put("customerId",1L);result.put("deleted",wallet.get("isDeleted"));result.put("riskHeld",wallet.get("cregisRiskHeld"));result.put("pendingWithdrawal",wallet.get("pendingWithdraw"));
            return List.of(result);
        });
        var adapter=new SupportExchangeFundAdapter(binder,exchangeMapper,exchanges,json);var before=adapter.begin(1);
        advanceCash(new BigDecimal("-4"),phase.equals("HOLD")?BigDecimal.ZERO:new BigDecimal("2"));
        var order=new LinkedHashMap<String,Object>();order.put("id",101L);order.put("customerId",1L);order.put("exchangeNo",no);order.put("fromAsset","USDT");order.put("toAsset","UVT");
        order.put("fromAmount",new BigDecimal("4.000000"));order.put("toAmount",new BigDecimal("2.000000"));order.put("rate",new BigDecimal("2.00000000"));
        order.put("status",phase.equals("HOLD")?"QUEUED":"COMPLETED");order.put("createdAt",AT);order.put("updatedAt",AT);
        order.put("deleted",false);order.put("userSandbox",false);order.put("userDeleted",false);exchangeOrders.add(order);
        for(String suffix:phase.equals("HOLD")?List.of("HOLD"):List.of("OUT","IN")) {
            boolean incoming=suffix.equals("IN");var ledger=new LinkedHashMap<String,Object>();ledger.put("id",incoming?203L:202L);
            ledger.put("customerId",1L);ledger.put("exchangeNo",no);ledger.put("bizNo",no+"-"+suffix);ledger.put("bizType","EXCHANGE_SWAP");ledger.put("asset",incoming?"UVT":"USDT");
            ledger.put("direction",incoming?"IN":"OUT");ledger.put("amount",new BigDecimal(incoming?"2.000000":"4.000000"));ledger.put("balanceAfter",wallet.get(incoming?"nexAvailable":"usdtAvailable"));
            ledger.put("status","SUCCESS");ledger.put("createdAt",AT);ledger.put("updatedAt",AT);ledger.put("deleted",0);exchangeLedgers.add(ledger);
        }
        adapter.recorded(before,no,phase,BigDecimal.ZERO);readOnly();
    }
    private void settleExchange(boolean cancel) throws Exception {
        writable();String no="EX-reader";var at=AT.plusSeconds(2);
        var adapter=new SupportExchangeFundAdapter(binder,exchangeMapper,exchanges,json);var before=adapter.begin(1,no);
        advanceCash(cancel?new BigDecimal("4"):BigDecimal.ZERO,cancel?BigDecimal.ZERO:new BigDecimal("2"));
        var order=exchangeOrders.get(0);order.put("status",cancel?"CANCELLED":"COMPLETED");order.put("updatedAt",at);
        var ledger=new LinkedHashMap<String,Object>();ledger.put("id",204L);ledger.put("customerId",1L);ledger.put("exchangeNo",no);
        ledger.put("bizNo",no+(cancel?"-REFUND":"-IN"));ledger.put("bizType","EXCHANGE_SWAP");ledger.put("asset",cancel?"USDT":"UVT");ledger.put("direction","IN");
        ledger.put("amount",new BigDecimal(cancel?"4.000000":"2.000000"));ledger.put("balanceAfter",wallet.get(cancel?"usdtAvailable":"nexAvailable"));
        ledger.put("status","SUCCESS");ledger.put("createdAt",at);ledger.put("updatedAt",at);ledger.put("deleted",0);exchangeLedgers.add(ledger);
        adapter.recorded(before,no,cancel?"CANCEL":"COMPLETE",BigDecimal.ZERO);readOnly();
    }
    private void advanceCash(BigDecimal usdt,BigDecimal nex) {
        var old=new HashMap<>(wallet);wallet=new HashMap<>(wallet);walletRows.set(0,wallet);wallet.put("version",num(old,"version")+1);wallet.put("revision",num(old,"revision")+1);
        wallet.put("usdtAvailable",((BigDecimal)old.get("usdtAvailable")).add(usdt));wallet.put("nexAvailable",((BigDecimal)old.get("nexAvailable")).add(nex));
        var row=change(old,wallet);row.put("id",(long)raw.size()+1);row.put("revision",num(wallet,"revision"));raw.add(row);
    }
    private Map<String,Object> deviceWallet(){var result=new LinkedHashMap<>(wallet);result.put("customerId",result.remove("userId"));result.put("deleted",result.remove("isDeleted"));return result;}
    private Map<String,Object> stakingWallet(){var result=deviceWallet();result.remove("createdAt");result.put("riskHeld",result.remove("cregisRiskHeld"));result.put("pendingWithdrawal",result.remove("pendingWithdraw"));return result;}
    private static Map<String,Object> row(Object... fields){var result=new LinkedHashMap<String,Object>();for(int i=0;i<fields.length;i+=2)result.put((String)fields[i],fields[i+1]);return result;}
    private static BigDecimal n(String value){return new BigDecimal(value).setScale(6);}
    private void commission(String asset,boolean reverse) throws Exception {
        writable();String phase=reverse?"REVERSE":"RELEASE";var amount=n("3");
        if(!reverse)commissionEvents.add(row("id",55L,"customerId",1L,"commissionType","network","sourceUserId",2L,"sourceUserName","Source","layerNo",1,
            "orderNo","ORDER-reader","orderAmountUsd",n("100"),"amountUsdt",asset.equals("USDT")?amount:n("0"),"amountNex",asset.equals("UVT")?amount:n("0"),
            "asset",asset,"status","UNLOCKED","version",1L,"unlockAt",AT,"frozenFromStatus",null,"remark","Commission","createdAt",AT,"updatedAt",AT,
            "deleted",false,"userSandbox",false,"userDeleted",false));
        var adapter=new SupportCommissionFundAdapter(binder,commissionMapper,commissions,json);var before=adapter.begin(1,55,phase);
        var delta=reverse?amount.negate():amount;advanceCash(asset.equals("USDT")?delta:BigDecimal.ZERO,asset.equals("UVT")?delta:BigDecimal.ZERO);
        if(reverse){commissionEvents.get(0).put("status","REVERSED");commissionEvents.get(0).put("version",2L);}
        commissionLedgers.add(row("id",400L+commissionLedgers.size(),"customerId",1L,"bizNo",SupportCommissionFundAdapter.ledgerNo(55,phase),"bizType","TEAM_COMMISSION",
            "asset",asset,"direction",reverse?"OUT":"IN","amount",amount,"balanceAfter",wallet.get(asset.equals("USDT")?"usdtAvailable":"nexAvailable"),
            "status","SUCCESS","remark","Commission wallet "+(reverse?"reversal":"release")+" | eventId=55","createdAt",AT,"updatedAt",AT,"deleted",false));
        adapter.recorded(before);readOnly();
    }
    private void deviceEarning(boolean dual,boolean zero) throws Exception {
        writable();String no="CTR-reader",taskNo="CTA-reader";var usd=n(zero?"0":"3");var nex=n(dual?"4.25":"0");
        var adapter=new SupportDeviceFundAdapter(binder,deviceMapper,devices,json);var before=adapter.begin(1);advanceCash(usd,BigDecimal.ZERO);adapter.afterUsdt(before);
        var usdAfter=wallet.get("usdtAvailable");if(dual)advanceCash(BigDecimal.ZERO,nex);
        deviceReceipts.add(row("id",88L,"customerId",1L,"deviceId",11L,"taskNo",taskNo,"receiptNo",no,"taskType","IG","clientName","Production compute",
            "rewardUsdt",usd,"rewardNex",nex,"earningStatus","CREDITED","sourceEnvironment","PRODUCTION","proofHash","a".repeat(64),"completedAt",AT,"createdAt",AT,"updatedAt",AT,"deleted",0,"userSandbox",0,"userDeleted",0));
        deviceTasks.add(row("id",99L,"customerId",1L,"deviceId",11L,"taskNo",taskNo,"taskId","TASK-IG","taskType","IG","rewardUsdt",usd,"status","COMPLETED","sourceEnvironment","PRODUCTION",
            "clientName","Production compute","startedAt",AT.minusMinutes(1),"completedAt",AT,"proofConsumedAt",AT,"deleted",0));
        for(String asset:dual?List.of("USDT","UVT"):List.of("USDT")){
            boolean dollar=asset.equals("USDT");long id=dollar?1L:2L;var amount=dollar?usd:nex;
            deviceLedgers.add(row("id",id,"customerId",1L,"bizNo",dollar?taskNo:"CLOUD_SHARE_DAILY:11:2026-10-10","bizType","COMPUTE_TASK_REWARD","asset",asset,"direction","IN","amount",amount,
                "balanceAfter",dollar?usdAfter:wallet.get("nexAvailable"),"status","SUCCESS","createdAt",AT,"updatedAt",AT,"deleted",0));
            deviceEvents.add(row("id",id,"eventNo",dollar?"EARN-"+taskNo:"EARN-NEX-"+no,"customerId",1L,"deviceId",11L,"receiptNo",no,"asset",asset,"amount",amount,
                "status","POSTED","walletPostedAt",AT,"createdAt",AT,"updatedAt",AT,"deleted",0));
        }
        adapter.recorded(before,no);readOnly();
    }
    private void staking(String phase,boolean g7) throws Exception {
        writable();String no="STK-reader";var adapter=new SupportStakingFundAdapter(binder,stakingMapper,staking,json);var before=adapter.begin(1,null);advanceCash(n("-6"),BigDecimal.ZERO);
        stakingPosition.putAll(row("id",40L,"customerId",1L,"positionNo",no,"productId",2L,"productCode",g7?"REPURCHASE_90D":"USDT_30D","productName","Staking",
            "amountUsdt",n("6"),"apyBps",1200,"earlyPenaltyBps",phase.equals("ZERO_RETURN")?10000:500,"termDays",30,"lockedAt",AT,"unlockAt",AT.plusDays(30),
            "estimatedInterestUsdt",n("2"),"status","ACTIVE","claimedAt",null,"earlyWithdrawnAt",null,"createdAt",AT,"updatedAt",AT,"deleted",false,"userSandbox",false,"userDeleted",false));
        stakingLedger(no,"OPEN",g7,n("6"),wallet.get("usdtAvailable"),AT);adapter.recorded(before,no,"OPEN");
        if(!phase.equals("OPEN")){
            before=adapter.begin(1,no);boolean claim=phase.equals("CLAIM");String actual=claim?"CLAIM":"EARLY";var at=claim?AT.plusDays(30):AT.plusDays(1);
            stakingPosition.put("status",claim?"CLAIMED":"EARLY_WITHDRAWN");stakingPosition.put(claim?"claimedAt":"earlyWithdrawnAt",at);stakingPosition.put("updatedAt",at);
            var cash=claim?n(g7?"8":"6"):n("6").subtract(SupportStakingReadFacade.penalty(stakingPosition));advanceCash(cash,BigDecimal.ZERO);
            stakingLedger(no,actual,g7,claim?n("8"):cash,claim&&!g7?((BigDecimal)wallet.get("usdtAvailable")).add(n("2")):wallet.get("usdtAvailable"),at);
            adapter.recorded(before,no,actual);if(claim&&!g7)stakingInterest(no,at);
        }
        readOnly();
    }
    private void stakingLedger(String no,String phase,boolean g7,BigDecimal amount,Object balance,LocalDateTime at){
        String type=switch(phase){case "OPEN"->g7?"WALLET_REINVEST":"STAKING_OPEN";case "CLAIM"->g7?"REPURCHASE_CLAIM":"STAKING_CLAIM";default->g7?"REPURCHASE_EARLY_WITHDRAW":"STAKING_EARLY_WITHDRAW";};
        stakingLedgers.add(row("id",(long)stakingLedgers.size()+1,"customerId",1L,"positionNo",no,"bizNo",no+"-"+(phase.equals("EARLY")?"EARLY":phase),"bizType",type,"asset","USDT","direction",phase.equals("OPEN")?"OUT":"IN",
            "amount",amount,"balanceAfter",balance,"status","SUCCESS","createdAt",at,"updatedAt",at,"deleted",false));
    }
    private void stakingInterest(String no,LocalDateTime at){
        var sourceMapper=mock(EarningsReleaseMapper.class);String entryNo="staking-earned";
        var entry=row("id",89L,"entryNo",entryNo,"userId",1L,"clusterId",null,"sourceType","staking_interest","sourceRef",no,"asset","USDT","amount",n("2"),
            "bucket","withdrawable","status","ACTIVE","idempotencyKey","G1-STAKING-INTEREST-40","sourceEnvironment","PRODUCTION","isDeleted",false,"debitBaseline",BigDecimal.ZERO,
            "sourceWalletId",11L,"recoveredAmount",BigDecimal.ZERO,"createdAt",at,"releasedAt",null,"releaseSource",null);earningSources.add(entry);
        when(sourceMapper.fundEntry(entryNo)).thenAnswer(inv->entry);when(sourceMapper.previousFundOperation(1)).thenAnswer(inv->(long)operations.size());
        var adapter=new SupportFundEarningsAdapter(binder,writer,sourceMapper,json);var before=adapter.begin(1);advanceCash(n("2"),BigDecimal.ZERO);adapter.credit(before,entryNo);
    }
    private void deposit() throws Exception {
        writable();var before=binder.begin(List.of(1L));var old=new HashMap<>(wallet);
        wallet=new HashMap<>(wallet);walletRows.set(0,wallet);wallet.put("revision",1L);wallet.put("version",1L);wallet.put("usdtAvailable",new BigDecimal("10.000000"));
        var row=change(old,wallet);raw.add(row);
        var ledger=new LinkedHashMap<String,Object>();ledger.put("id",20L);ledger.put("sourceId","nx_wallet_ledger:20");ledger.put("customerId",1L);ledger.put("businessId","topup");
        ledger.put("ledgerType","DEPOSIT");ledger.put("status","SUCCESS");ledger.put("successAt",AT);ledger.put("deleted",0);ledger.put("currency","USDT");ledger.put("direction","IN");
        ledger.put("amount",new BigDecimal("10.000000"));ledger.put("balanceAfter",new BigDecimal("10.000000"));ledger.put("remark","actual");ledger.put("updatedAt",AT);paymentLedgers.add(ledger);
        String key="PAYMENT:DEPOSIT_ORDER:"+sha("DEPOSIT_ORDER||DEPOSIT:20");
        var source=new LinkedHashMap<String,Object>();source.put("schemaVersion","SUPPORT_PAYMENT_FUND_V1");source.put("customerId",1);source.put("factId","DEPOSIT:20");
        source.put("source","DEPOSIT_ORDER");source.put("stableBusinessKey","topup");source.put("sourcePartition",null);source.put("orderNo",null);source.put("amount",new BigDecimal("10.000000"));
        settledSources.add(Map.of("customerId",1L,"ledgerId",20L,"actual","source"));
        source.put("currency","USDT");source.put("succeededAt",AT.toString());source.put("settledSource",settledSources.get(0));source.put("freshLedger",strings(ledger));source.put("rewards",List.of());source.put("rewardLedgers",List.of());source.put("trialSource",null);
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_PAYMENT_FUND_V1");proof.put("sourceAuthority",settledSources.get(0));proof.put("cashBefore",cashWallet(old));proof.put("cashAfter",cashWallet(wallet));
        proof.put("cashTrace",List.of(Map.of("sourceId","nx_wallet_ledger:20","eventId","DEPOSIT:"+key,"journal",strings(row))));proof.put("originalConsume",null);proof.put("originalFundEvidence",null);
        binder.bind(before,new SupportFundJournalFacade.ReceiptInput(key,"SUPPORT_PAYMENT","SETTLED","nx_deposit_order","DEPOSIT_ORDER:"+sha("topup|"),"PRODUCTION",
            json.writeValueAsString(List.of(new Event("DEPOSIT:"+key,Action.DEPOSIT,null,new Account(1,"USDT","available"),null,new BigDecimal("10"),null))),json.writeValueAsString(source),json.writeValueAsString(proof)));
        facts.add(new SupportPaymentFacts.Fact("DEPOSIT:20",SupportPaymentFacts.Kind.DEPOSIT,SupportPaymentFacts.Source.DEPOSIT_ORDER,List.of("nx_deposit_order:1"),1,20,"topup",null,null,null,"USDT",new BigDecimal("10.000000"),AT,"nx_deposit_order.credited_at",0,null,AT,AT,"1",SupportPaymentFacts.Status.UNKNOWN));
        readOnly();
    }
    private void actualPayment() throws Exception {
        writable();String key="CR-23";var source=SupportPaymentFacts.Source.DEPOSIT_ORDER;
        var sm=mock(SupportPaymentSourceMapper.class);var root=new LinkedHashMap<String,Object>();
        when(sm.before(source,1,key)).thenAnswer(inv->root.isEmpty()?List.of():List.of(root));
        when(sm.currentBefore(eq(source),eq(1L),eq(key),anyLong())).thenAnswer(inv->List.of(root));
        var cregis=new LinkedHashMap<String,Object>();when(sm.cregisEvents(100,23)).thenAnswer(inv->cregis.isEmpty()?List.of():List.of(cregis));
        when(sm.currentMarker(eq(SupportPaymentSourceMapper.Marker.CREGIS_EVENT),anyLong())).thenAnswer(inv->cregis);
        when(sm.ledgers(key)).thenAnswer(inv->paymentLedgers);
        when(sm.currentMarker(eq(SupportPaymentSourceMapper.Marker.LEDGER),anyLong())).thenAnswer(inv->paymentLedgers.get(0));
        when(sm.insertLedger(eq(source),anyMap())).thenAnswer(inv->{
            Map<String,Object> write=inv.getArgument(1);write.put("id",20L);advanceCash(new BigDecimal("10.000000"),BigDecimal.ZERO);
            var ledger=new LinkedHashMap<String,Object>();ledger.put("id",20L);ledger.put("sourceId","nx_wallet_ledger:20");ledger.put("customerId",1L);
            ledger.put("businessId",key);ledger.put("ledgerType","CHAIN_TOPUP");ledger.put("status","SUCCESS");ledger.put("deleted",0);ledger.put("currency","USDT");ledger.put("direction","IN");
            ledger.put("amount",write.get("amount"));ledger.put("balanceAfter",write.get("balanceAfter"));ledger.put("remark",write.get("remark"));ledger.put("successAt",AT);ledger.put("updatedAt",AT);paymentLedgers.add(ledger);return 1;
        });
        var service=new SupportPaymentSourceService(sm,mock(SupportPaymentFactService.class),ds,json,
            mock(ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.class),binder,writer,mock(EarningsReleaseMapper.class));
        var before=service.beforeSource(1,source,key,"100");assertFalse(before.oldSource());service.beforeCash(before);
        var fresh=service.insertFreshPaymentLedger(before,new BigDecimal("10.000000"),((BigDecimal)wallet.get("usdtAvailable")).add(new BigDecimal("10.000000")),"actual");
        root.put("id",1L);root.put("customerId",1L);root.put("businessId",key);root.put("status","CREDITED");root.put("deleted",0);
        cregis.put("id",30L);cregis.put("customerId",1L);cregis.put("projectId",100L);cregis.put("cid",23L);cregis.put("ledgerId",20L);cregis.put("amount",new BigDecimal("10.000000"));cregis.put("status","CREDITED");
        var projection=new LinkedHashMap<String,Object>();projection.put("sourceRootId",1L);projection.put("source",source.name());projection.put("kind","DEPOSIT");projection.put("sourceId","nx_deposit_order:1");
        projection.put("customerId",1L);projection.put("businessId",key);projection.put("ledgerCustomerId",1L);projection.put("ledgerId",20L);projection.put("ledgerBusinessId",key);
        projection.put("amount",new BigDecimal("10.000000"));projection.put("ledgerAmount",new BigDecimal("10.000000"));projection.put("currency","USDT");projection.put("ledgerCurrency","USDT");
        projection.put("sourceLinked",1);projection.put("ledgerDeleted",0);projection.put("ledgerStatus","SUCCESS");projection.put("ledgerDirection","IN");projection.put("ledgerType","CHAIN_TOPUP");
        projection.put("succeededAt",AT);projection.put("ledgerRecordedAt",AT);projection.put("fractionalSecondDigits",0);projection.put("successTimeField","nx_deposit_order.credited_at");
        when(sm.settled(source,List.of(1L),key)).thenReturn(List.of(projection));when(sm.currentSettled(eq(source),anyList(),eq(key),anyMap())).thenReturn(List.of(projection));
        facts.add(service.readSettled(before,fresh).orElseThrow());settledSources.add(projection);readOnly();
    }
    private static Map<String,Object> wallet(){var row=new LinkedHashMap<String,Object>();row.put("id",11L);row.put("userId",1L);row.put("incarnation",INC);row.put("revision",0L);row.put("version",0L);
        for(String field:List.of("usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld"))row.put(field,new BigDecimal("0.000000"));row.put("sandbox",false);row.put("isDeleted",false);row.put("createdAt",AT);return row;}
    private static Map<String,Object> birth(Map<String,Object> wallet){var row=new LinkedHashMap<String,Object>();row.put("id",1L);row.put("incarnation",INC);row.put("revision",0L);row.put("observation","NEW_ZERO");
        row.put("recordedDbUtc",AT);row.put("protocolSha256",sha("SUPPORT_WALLET_JOURNAL_V1"));for(var e:wallet.entrySet())if(!Set.of("incarnation","revision").contains(e.getKey())){String field=Character.toUpperCase(e.getKey().charAt(0))+e.getKey().substring(1);row.put("old"+field,null);row.put("new"+field,Set.of("sandbox","isDeleted").contains(e.getKey())?((Boolean)e.getValue()?1:0):e.getValue());}return row;}
    private static Map<String,Object> change(Map<String,Object> before,Map<String,Object> after){var row=birth(after);row.put("id",2L);row.put("revision",1L);row.put("observation","CHANGE");
        for(var e:before.entrySet())if(!Set.of("incarnation","revision").contains(e.getKey()))row.put("old"+Character.toUpperCase(e.getKey().charAt(0))+e.getKey().substring(1),Set.of("sandbox","isDeleted").contains(e.getKey())?((Boolean)e.getValue()?1:0):e.getValue());return row;}
    private static Map<String,Object> strings(Map<String,Object> input){var output=new HashMap<String,Object>();input.forEach((k,v)->output.put(k,v instanceof LocalDateTime at?at.toString():v));return output;}
    private Map<String,Object> cashWallet(Map<String,Object> input){var output=strings(input);output.putAll(cashWalletOverrides);if(omitCashFlag)output.remove("sandbox");return output;}
    private void resign(Map<String,Object> operation) {
        var digest=json.createObjectNode();for(String field:List.of("operationKey","businessType","phase","sourceTable","sourceKey","sourceEnvironment",
            "orderedEventsJson","sourceSnapshotJson","sourceProofJson","protocolVersion"))digest.set(field,json.valueToTree(operation.get(field)));
        long id=num(operation,"id");digest.set("customerIds",json.valueToTree(parties.stream().filter(p->num(p,"operationId")==id).map(p->num(p,"customerId")).toList()));
        digest.set("legs",json.valueToTree(legs.stream().filter(l->num(l,"operationId")==id).map(l->Map.of("incarnation",l.get("incarnation"),"revision",l.get("revision"))).toList()));
        operation.put("receiptSha256",sha(binder.canonical(digest).toString()));
    }
    private static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String sha(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
