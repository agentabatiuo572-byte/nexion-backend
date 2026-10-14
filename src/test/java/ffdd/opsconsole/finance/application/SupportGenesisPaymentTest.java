package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.application.SupportPaymentAttributionService;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql;
import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundReplay;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.market.application.SupportGenesisReadService;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import ffdd.opsconsole.market.mapper.SupportGenesisSourceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual market reader -> payment source -> actual journal binder. Storage is explicit, SQL is not exercised. */
class SupportGenesisPaymentTest {
    final DataSource ds=mock(DataSource.class);final ObjectMapper json=new ObjectMapper();
    final SupportPaymentSourceMapper mapper=mock(SupportPaymentSourceMapper.class);
    final SupportGenesisSourceMapper market=mock(SupportGenesisSourceMapper.class);
    final SupportGenesisReadService reader=new SupportGenesisReadService(market);
    final SupportPaymentFundFixture cash=new SupportPaymentFundFixture(ds,json);
    final SupportPaymentSourceService service=new SupportPaymentSourceService(mapper,mock(SupportPaymentFactService.class),ds,json,
        mock(SupportPaymentCaptureHistoryFacade.class),cash.fund,cash.mapper,cash.earnings,reader);
    final SupportPaymentAttributionMapper attribution=mock(SupportPaymentAttributionMapper.class);
    final SupportPaymentAttributionService capture=new SupportPaymentAttributionService(attribution,service,mock(AuditLogService.class),ds,json);
    final List<SupportPaymentAttributionMapper.StoredRow> captures=new ArrayList<>();
    final Map<Long,Map<String,Object>> wallets=new TreeMap<>();final Map<String,Map<String,Object>> orders=new LinkedHashMap<>();
    final Map<Long,Map<String,Object>> ledgers=new LinkedHashMap<>();
    final LocalDateTime at=LocalDateTime.of(2026,10,8,12,0);Map<String,Object> holding;long sequence=101;
    @BeforeEach void setup() {
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(ds,cash.holder);
        when(attribution.lockCustomer(7L)).thenReturn(new SupportPaymentAttributionMapper.Customer(7L,0,"ACTIVE",0));
        when(attribution.databaseUtc()).thenReturn(at.minusHours(8));
        when(attribution.insert(any())).thenAnswer(i->{captures.add(i.getArgument(0));return 1;});
        cash.wallet(new BigDecimal("100"));wallets.put(7L,cash.wallet);
        var seller=new LinkedHashMap<>(cash.wallet);seller.put("id",30L);seller.put("userId",3L);
        seller.put("incarnation","00000000-0000-4000-8000-000000000003");seller.put("usdtAvailable",BigDecimal.ZERO);wallets.put(3L,seller);
        when(cash.mapper.lockWallet(anyLong())).thenAnswer(i->List.of(new LinkedHashMap<>(wallets.get(i.getArgument(0)))));
        when(market.ordersByNos(anyList())).thenAnswer(i->i.<List<String>>getArgument(0).stream().filter(orders::containsKey).map(orders::get).toList());
        when(market.ledgers(anyList())).thenAnswer(i->ledgers.values().stream().filter(l->i.<List<String>>getArgument(0).contains(l.get("businessId"))).toList());
        when(market.holding(anyString())).thenAnswer(i->holding);
        when(mapper.ledgers(anyString())).thenAnswer(i->ledgers.values().stream().filter(l->Objects.equals(l.get("businessId"),i.getArgument(0))).toList());
        when(mapper.currentMarker(any(),anyLong())).thenAnswer(i->ledgers.get(i.getArgument(1)));
        when(mapper.insertLedger(any(),anyMap())).thenAnswer(i->{var w=i.<Map<String,Object>>getArgument(1);long id=++sequence;w.put("id",id);
            var l=ledger(id,7,w.get("key").toString(),SupportPaymentSourceSql.ledgerType(i.getArgument(0)),"OUT",(BigDecimal)w.get("amount"),(BigDecimal)w.get("balanceAfter"));
            l.put("remark",w.get("remark"));ledgers.put(id,l);return 1;});
    }
    @AfterEach void cleanup() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(ds);TransactionSynchronizationManager.clear();
    }
    Map<String,Object> order(long id,String no,long buyer,boolean secondary) {
        var r=new LinkedHashMap<String,Object>();r.put("id",id);r.put("orderNo",no);r.put("customerId",buyer);
        r.put("seriesCode","GEN");r.put("quantity",1);r.put("unitPrice",BigDecimal.TEN.setScale(6));r.put("amount",BigDecimal.TEN.setScale(6));
        r.put("currency","USDT");r.put("status","COMPLETED");r.put("orderType",secondary?"SECONDARY":"PRIMARY");
        r.put("sellerId",secondary?3L:null);r.put("holdingNo",secondary?"old-genesis-01":null);r.put("royalty",(secondary?BigDecimal.ONE:BigDecimal.ZERO).setScale(6));
        r.put("paidAt",at);r.put("completedAt",at);r.put("deleted",false);r.put("buyerSandbox",false);r.put("buyerDeleted",false);
        r.put("sellerSandbox",false);r.put("sellerDeleted",false);return r;
    }
    Map<String,Object> ledger(long id,long owner,String key,String type,String direction,BigDecimal amount,BigDecimal balance) {
        var r=new LinkedHashMap<String,Object>();r.put("id",id);r.put("customerId",owner);r.put("businessId",key);r.put("ledgerType",type);
        r.put("currency","USDT");r.put("direction",direction);r.put("amount",amount);r.put("balanceAfter",balance);r.put("status","SUCCESS");
        r.put("successAt",at.plusSeconds(3));r.put("updatedAt",at.plusSeconds(3));r.put("deleted",0);return r;
    }
    void acquisition() {
        orders.put("old-genesis",order(1,"old-genesis",3,false));ledgers.put(10L,ledger(10,3,"old-genesis","GENESIS_PURCHASE","OUT",BigDecimal.TEN,BigDecimal.ZERO));
        holding=new LinkedHashMap<>();holding.put("id",1L);holding.put("holdingNo","old-genesis-01");holding.put("customerId",3L);
        holding.put("orderNo","old-genesis");holding.put("seriesCode","GEN");holding.put("acquiredPrice",BigDecimal.TEN);
        holding.put("acquiredAt",at);holding.put("listingPrice",BigDecimal.TEN);holding.put("status","LISTED");holding.put("deleted",0);
    }
    void advance(long id,BigDecimal amount) {cash.wallet=wallets.get(id);cash.advance(amount,BigDecimal.ZERO);}
    void originalOwnership() {
        var binding=new SupportPaymentAttributionMapper.Binding(1L,7L,20L,"ACTIVE",at.minusDays(1),null,0,3L,"MANUAL","bind-1",7L,0,null,1L);
        var member=new SupportPaymentAttributionMapper.Member(2L,20L,100L,at.minusDays(1),null,4L,"member-1");
        when(attribution.assignments(7L)).thenReturn(List.of(binding));when(attribution.planMembers(20L)).thenReturn(List.of(member));
        var group=new SupportPaymentAttributionMapper.Group(100L,10L,"ENABLED",5L);when(attribution.planGroup(100L)).thenReturn(group);
        when(attribution.lockGroup(100L)).thenReturn(group);when(attribution.lockAdmin(20L)).thenReturn(new SupportPaymentAttributionMapper.Admin(20L,1,8L,0));
        when(attribution.lockAdmin(10L)).thenReturn(new SupportPaymentAttributionMapper.Admin(10L,1,9L,0));when(attribution.members(20L)).thenReturn(List.of(member));
        when(attribution.owners(100L)).thenReturn(List.of(new SupportPaymentAttributionMapper.Owner(3L,100L,10L,at.minusDays(1),null,6L,"owner-1")));
    }
    static java.util.stream.Stream<Arguments> fundingCases() {
        return java.util.stream.Stream.of(Source.GENESIS_PRIMARY,Source.GENESIS_SECONDARY).flatMap(source->
            java.util.stream.Stream.of(SupportFundAllocation.Action.EARNINGS,SupportFundAllocation.Action.DEPOSIT).map(action->Arguments.of(source,action)));
    }
    @ParameterizedTest @MethodSource("fundingCases")
    void actualSourcesProduceOneAtomicReceiptWithRealGrossNetFeeAndOrderTime(Source source,SupportFundAllocation.Action funding) throws Exception {
        boolean secondary=source==Source.GENESIS_SECONDARY;if(secondary)acquisition();
        wallets.get(7L).put("usdtAvailable",BigDecimal.ZERO);
        var seedBefore=cash.fund.begin(List.of(7L));advance(7,new BigDecimal("100"));
        var seedEvent=new SupportFundAllocation.Event("domain-seed",funding,null,
            new SupportFundAllocation.Account(7,"USDT","available"),null,new BigDecimal("100"),null);
        cash.fund.bind(seedBefore,new SupportFundJournalFacade.ReceiptInput("domain-seed","DOMAIN_FIXTURE","CREDIT","test_fixture","seed","PRODUCTION",
            json.writeValueAsString(List.of(seedEvent)),"{\"fixture\":\"domain allocation prefix\"}","{\"fixture\":true}"));
        originalOwnership();
        var prepared=capture.prepare(7,source,"new-genesis");
        when(attribution.assignments(7L)).thenReturn(List.of());when(attribution.members(20L)).thenReturn(List.of());
        when(attribution.lockAdmin(20L)).thenReturn(new SupportPaymentAttributionMapper.Admin(20L,0,9L,0));
        capture.beforeGenesisCash(prepared,secondary?List.of(7L,3L):List.of(7L),secondary?"old-genesis-01":null);
        advance(7,BigDecimal.TEN.negate());if(secondary)advance(3,new BigDecimal("9"));
        orders.put("new-genesis",order(2,"new-genesis",7,secondary));
        capture.insertLedger(prepared,BigDecimal.TEN,new BigDecimal("90"),"actual genesis payment");
        if(secondary)ledgers.put(103L,ledger(103,3,"new-genesis-SELL","GENESIS_SECONDARY_SELL","IN",new BigDecimal("9"),new BigDecimal("9")));
        capture.record(prepared);assertThat(captures).hasSize(1);
        var fact=SupportPaymentCapturedSourceProof.decodeSourceFact(captures.get(0).sourceFactJson(),json);
        assertThat(fact.kind()).isEqualTo(Kind.CONSUMPTION);assertThat(fact.amount()).isEqualByComparingTo("10");
        assertThat(fact.succeededAt()).isEqualTo(at);assertThat(fact.ledgerRecordedAt()).isEqualTo(at.plusSeconds(3));
        assertThat(fact.fractionalSecondDigits()).isZero();assertThat(SupportPaymentFacts.firstEligible(fact.kind())).isFalse();
        assertThat(SupportPaymentFacts.validateCanonical(fact,"Asia/Shanghai")).isNull();
        assertThat(captures.get(0).captureMode()).isEqualTo("NEW_SUCCESS");
        assertThat(captures.get(0).agentStatus()).isEqualTo("KNOWN");assertThat(captures.get(0).agentAdminId()).isEqualTo(20L);
        assertThat(captures.get(0).groupId()).isEqualTo(100L);assertThat(captures.get(0).ownerAdminId()).isEqualTo(10L);
        assertThat(cash.operations).hasSize(2);
        var operation=cash.operations.get("GENESIS:new-genesis");var events=json.readTree(operation.get("orderedEventsJson").toString());
        assertThat(events.size()).isEqualTo(1);
        assertThat(events.get(0).path("action").asText()).isEqualTo(secondary?"TRADE":"CONSUME");
        assertThat(events.get(0).path("amount").decimalValue()).isEqualByComparingTo("10");
        if(secondary)assertThat(events.get(0).path("targetAmount").decimalValue()).isEqualByComparingTo("9");
        var proof=json.readTree(operation.get("sourceProofJson").toString());assertThat(proof.path("cashTrace").size()).isEqualTo(secondary?2:1);
        var helper=new SupportFundGenesisEvidence(json,(SupportFundJournalBindingService)cash.fund);
        var snapshot=new Snapshot(List.of(fact),List.of(),List.of(),"Asia/Shanghai",java.time.Instant.now());
        var input=new SupportFundGenesisEvidence.Inputs(snapshot,reader.readByOrderNos(List.copyOf(orders.keySet())),cash.journal,List.copyOf(cash.operations.values()),
            cash.parties.values().stream().flatMap(List::stream).toList(),cash.legs.values().stream().flatMap(List::stream).toList());
        var decodedEvents=new ArrayList<SupportFundAllocation.Event>();for(var node:events)decodedEvents.add(json.treeToValue(node,SupportFundAllocation.Event.class));
        var verified=helper.verify(operation,json.readTree(operation.get("sourceSnapshotJson").toString()),proof,decodedEvents,input);
        assertThat(verified.factId()).isEqualTo(fact.factId());assertThat(verified.points()).hasSize(secondary?2:1);
        var represented=new HashSet<String>();for(var original:input.sources())represented.add(SupportPaymentFactService.fact(SupportPaymentSourceService.genesisRows(original).get(0),
            Source.valueOf(SupportPaymentSourceService.genesisRows(original).get(0).get("source").toString())).factId());
        helper.verifyCoverage(secondary?Set.of(3L,7L):Set.of(7L),input,represented);
        assertThatThrownBy(()->helper.verifyCoverage(secondary?Set.of(3L,7L):Set.of(7L),input,Set.of())).isInstanceOf(IllegalArgumentException.class);
        if(secondary)assertThatThrownBy(()->helper.verifyCoverage(Set.of(7L),input,represented)).isInstanceOf(IllegalArgumentException.class);
        var broken=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)broken.path("cashBefore")).remove("7");
        rejectSignedProof(helper,operation,broken,decodedEvents,input);
        var wrongCash=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongCash.path("cashAfter").path("7")).put("usdtAvailable",91);
        rejectSignedProof(helper,operation,wrongCash,decodedEvents,input);
        var fractionalRevision=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)fractionalRevision.path("cashBefore").path("7")).put("revision",new BigDecimal("1.5"));
        rejectSignedProof(helper,operation,fractionalRevision,decodedEvents,input);
        if(secondary) {
            var missingPartyInput=new SupportFundGenesisEvidence.Inputs(snapshot,input.sources(),input.raw(),input.operations(),
                input.parties().stream().filter(p->((Number)p.get("customerId")).longValue()!=3).toList(),input.legs());
            assertThatThrownBy(()->helper.verify(operation,json.readTree(operation.get("sourceSnapshotJson").toString()),proof,decodedEvents,missingPartyInput)).isInstanceOf(IllegalArgumentException.class);
            var wrongOwner=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongOwner.path("beforeHolding")).put("customerId",7);
            rejectSignedProof(helper,operation,wrongOwner,decodedEvents,input);
            var wrongAcquisition=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongAcquisition.path("acquisitionSource").path("order")).put("customerId",7);
            rejectSignedProof(helper,operation,wrongAcquisition,decodedEvents,input);
        }
        assertThat(cash.parties.get(operation.get("id"))).hasSize(secondary?2:1);assertThat(cash.legs.get(operation.get("id"))).hasSize(secondary?2:1);
        verify(cash.mapper,times(2)).insertOperation(anyMap());
        // Only the funding prefix is a named domain fixture; the Genesis event and cash points come from its actual persisted receipt.
        var seedRaw=cash.journal.get(0);var genesisRaw=cash.journal.stream().filter(r->((Number)r.get("revision")).longValue()>1 || ((Number)r.get("newUserId")).longValue()==3).toList();
        var replayEvents=new ArrayList<SupportFundAllocation.Event>();for(var node:events)replayEvents.add(json.treeToValue(node,SupportFundAllocation.Event.class));
        var seedOp=new SupportFundReplay.Operation(1,"seed",List.of(7L),List.of(seedEvent),List.of(point(seedRaw,List.of(seedEvent.id()))),
            new SupportFundReplay.Stamp("domain-prefix",at.minusDays(1),"Asia/Shanghai",0));
        var genOp=new SupportFundReplay.Operation(2,"GENESIS:new-genesis",secondary?List.of(3L,7L):List.of(7L),replayEvents,
            genesisRaw.stream().sorted(Comparator.comparingLong(r->((Number)r.get("newUserId")).longValue())).map(r->point(r,replayEvents.stream().map(SupportFundAllocation.Event::id).toList())).toList(),
            new SupportFundReplay.Stamp("nx_genesis_order:2",at,"Asia/Shanghai",0));
        var tails=new TreeMap<Long,SupportFundReplay.Cash>();tails.put(7L,cashValue(new BigDecimal("90")));if(secondary)tails.put(3L,cashValue(new BigDecimal("9")));
        var originFacts=new HashMap<String,String>();originFacts.put("CONSUME:CONSUMPTION:new-genesis",fact.factId());if(funding==SupportFundAllocation.Action.DEPOSIT)originFacts.put(seedEvent.id(),"DEPOSIT:seed");
        var replay=new SupportFundReplay().replay(tails.keySet(),List.of(seedOp,genOp),tails,originFacts,List.of());
        assertThat(replay.state()).withFailMessage(replay.toString()).isEqualTo(SupportFundReplay.State.READY);
        assertThat(replay.totals().get(7L).get("USDT")).isEqualByComparingTo(funding==SupportFundAllocation.Action.DEPOSIT?"100":"10");
        if(secondary)assertThat(replay.totals().get(3L).get("USDT")).isEqualByComparingTo("0");
        assertThat(replay.origins().values()).allSatisfy(origin->{assertThat(origin.customerId()).isEqualTo(7);assertThat(origin.paymentFactId()).isEqualTo(funding==SupportFundAllocation.Action.DEPOSIT?"DEPOSIT:seed":fact.factId());});
        if(secondary) {
            // A later successful seller withdrawal reverses transferred basis against its original buyer, leaving the consumed royalty.
            var sellerAccount=new SupportFundAllocation.Account(3,"USDT","available");
            var reserve=new SupportFundAllocation.Event("seller-reserve",SupportFundAllocation.Action.RESERVE,sellerAccount,null,null,new BigDecimal("9"),null);
            var confirm=new SupportFundAllocation.Event("seller-confirm",SupportFundAllocation.Action.CONFIRM,null,null,reserve.id(),null,null);
            var reserveOp=new SupportFundReplay.Operation(3,"seller-reserve",List.of(3L),List.of(reserve),
                List.of(new SupportFundReplay.Point(wallets.get(3L).get("incarnation").toString(),2,3,cashValue(new BigDecimal("9")),
                    new SupportFundReplay.Cash(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("9"),BigDecimal.ZERO),List.of(reserve.id()))),
                new SupportFundReplay.Stamp("withdrawal:1",at.plusMonths(1),"Asia/Shanghai",0));
            var confirmOp=new SupportFundReplay.Operation(4,"seller-confirm",List.of(3L),List.of(confirm),
                List.of(new SupportFundReplay.Point(wallets.get(3L).get("incarnation").toString(),3,3,
                    new SupportFundReplay.Cash(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("9"),BigDecimal.ZERO),cashValue(BigDecimal.ZERO),List.of(confirm.id()))),
                new SupportFundReplay.Stamp("withdrawal:1",at.plusMonths(1),"Asia/Shanghai",0));
            var withdrawnTails=new TreeMap<>(tails);withdrawnTails.put(3L,cashValue(BigDecimal.ZERO));
            var withdrawn=new SupportFundReplay().replay(tails.keySet(),List.of(seedOp,genOp,reserveOp,confirmOp),withdrawnTails,originFacts,List.of());
            assertThat(withdrawn.state()).withFailMessage(withdrawn.toString()).isEqualTo(SupportFundReplay.State.READY);
            assertThat(withdrawn.totals().get(7L).get("USDT")).isEqualByComparingTo(funding==SupportFundAllocation.Action.DEPOSIT?"91":"1");
            assertThat(withdrawn.changes().stream().filter(c->c.occurredAt().succeededAt().getMonthValue()==11).map(SupportFundReplay.ConfirmedChange::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("-9");
        }
        var missingParty=new SupportFundReplay.Operation(2,"GENESIS:new-genesis",List.of(7L),replayEvents,genOp.points(),genOp.stamp());
        if(secondary)assertThat(new SupportFundReplay().replay(tails.keySet(),List.of(seedOp,missingParty),tails,Map.of("CONSUME:CONSUMPTION:new-genesis",fact.factId()),List.of()).state()).isEqualTo(SupportFundReplay.State.UNKNOWN);
        assertActualHistory(fact);
    }
    private void assertActualHistory(Fact fact) throws Exception {
        var stored=captures.get(0);var captureReader=mock(SupportPaymentCaptureHistoryFacade.class);
        var identity=new SupportPaymentCaptureHistoryFacade.Identity(stored.factId(),stored.customerId(),stored.kind(),stored.source(),stored.ledgerId(),stored.sourceBusinessId(),
            stored.orderNo(),stored.orderType(),stored.originalFactId(),stored.currency(),stored.amount(),stored.succeededAt(),stored.sourceBusinessZone(),stored.successTimeField(),stored.fractionalSecondDigits());
        var evidence=json.readTree(stored.evidenceJson());
        var envelope=new SupportPaymentCaptureHistoryFacade.Envelope(identity,stored.sourcePartition(),stored.captureDbUtc(),stored.captureMode(),"support-payment-attribution-v1",
            stored.sourceFactJson(),evidence.path("beforeSource").toString(),"NEW_SUCCESS","support-payment-attribution-v1");
        when(captureReader.readNewFinancialProofs(List.of(7L))).thenReturn(List.of(envelope));
        when(captureReader.readBirths(List.of(7L))).thenReturn(List.of());
        when(mapper.historyNowUtc()).thenReturn(at.plusDays(1));
        when(mapper.historyLedgers(eq(List.of(7L)),anyList())).thenAnswer(i->ledgers.values().stream().filter(l->((Number)l.get("customerId")).longValue()==7).toList());
        when(market.ordersByCustomers(List.of(7L))).thenAnswer(i->orders.values().stream().filter(o->((Number)o.get("customerId")).longValue()==7).toList());
        when(cash.connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        var history=new SupportPaymentFactService(mock(ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper.class),reader);
        var sourceReader=new SupportPaymentSourceService(mapper,history,ds,json,captureReader,cash.fund,cash.mapper,cash.earnings,reader);
        var read=sourceReader.readHistory(List.of(7L));
        assertThat(read.facts()).withFailMessage(read.toString()).containsExactly(fact);
        assertThat(read.firstHistory()).allSatisfy(first->assertThat(first.status()).isEqualTo(Status.UNKNOWN));
        assertThat(read.facts()).noneMatch(f->SupportPaymentFacts.firstEligible(f.kind()));
    }
    private void rejectSignedProof(SupportFundGenesisEvidence helper,Map<String,Object> operation,com.fasterxml.jackson.databind.JsonNode proof,
                                  List<SupportFundAllocation.Event> events,SupportFundGenesisEvidence.Inputs input) throws Exception {
        var codec=(SupportFundJournalBindingService)cash.fund;var forged=new LinkedHashMap<>(operation);forged.put("sourceProofJson",codec.canonical(proof).toString());
        var digest=json.createObjectNode();for(String field:List.of("operationKey","businessType","phase","sourceTable","sourceKey","sourceEnvironment",
            "orderedEventsJson","sourceSnapshotJson","sourceProofJson","protocolVersion"))digest.set(field,json.valueToTree(forged.get(field)));
        long op=((Number)operation.get("id")).longValue();var partyIds=input.parties().stream().filter(p->((Number)p.get("operationId")).longValue()==op).map(p->((Number)p.get("customerId")).longValue()).toList();
        var legs=input.legs().stream().filter(l->((Number)l.get("operationId")).longValue()==op).toList();
        digest.set("customerIds",json.valueToTree(partyIds));digest.set("legs",json.valueToTree(legs.stream().map(l->Map.of("incarnation",l.get("incarnation"),"revision",l.get("revision"))).toList()));
        forged.put("receiptSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(codec.canonical(digest).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(codec.decodeReceipt(forged,partyIds,legs)).isEqualTo(events);
        var forgedInput=new SupportFundGenesisEvidence.Inputs(input.history(),input.sources(),input.raw(),input.operations().stream().map(o->((Number)o.get("id")).longValue()==op?forged:o).toList(),input.parties(),input.legs());
        assertThatThrownBy(()->helper.verify(forged,json.readTree(forged.get("sourceSnapshotJson").toString()),proof,events,forgedInput)).isInstanceOf(IllegalArgumentException.class);
    }
    private static SupportFundReplay.Cash cashValue(BigDecimal amount) {return new SupportFundReplay.Cash(amount,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);}
    private static SupportFundReplay.Point point(Map<String,Object> raw,List<String> eventIds) {
        return new SupportFundReplay.Point(raw.get("incarnation").toString(),((Number)raw.get("revision")).longValue(),((Number)raw.get("newUserId")).longValue(),
            cashValue((BigDecimal)raw.get("oldUsdtAvailable")),cashValue((BigDecimal)raw.get("newUsdtAvailable")),eventIds);
    }
    @Test void missingSellerPartyRejectsBeforeAnyWalletChange() {
        acquisition();var before=service.beforeSource(7,Source.GENESIS_SECONDARY,"new-genesis",null);
        assertThatThrownBy(()->service.beforeGenesisCash(before,List.of(7L),"old-genesis-01")).hasMessage("GENESIS_COMPLETE_PARTIES_REQUIRED");
        assertThat(cash.journal).isEmpty();assertThat(cash.operations).isEmpty();
    }
    @Test void originalAcquisitionMismatchRejectsBeforeCash() {
        acquisition();orders.get("old-genesis").put("customerId",42L);
        var before=service.beforeSource(7,Source.GENESIS_SECONDARY,"new-genesis",null);
        assertThatThrownBy(()->service.beforeGenesisCash(before,List.of(3L,7L),"old-genesis-01")).hasMessage("GENESIS_ORIGINAL_HOLDING_ORDER_MISMATCH");
        assertThat(cash.journal).isEmpty();
    }
    @Test void missingUnknownFlagsAndWrongSellerLedgerRemainInvalidRatherThanZeroOrNewPrincipal() {
        var original=order(2,"new-genesis",7,true);var buyer=ledger(102,7,"new-genesis-BUY","GENESIS_SECONDARY_BUY","OUT",BigDecimal.TEN,new BigDecimal("90"));
        var seller=ledger(103,3,"new-genesis-SELL","GENESIS_SECONDARY_SELL","IN",new BigDecimal("9"),new BigDecimal("9"));
        for(String field:List.of("buyerSandbox","buyerDeleted","sellerSandbox","sellerDeleted","deleted")) {
            var broken=new LinkedHashMap<>(original);broken.remove(field);var row=SupportPaymentSourceService.genesisRows(new SupportGenesisReadFacade.Source(broken,List.of(buyer,seller))).get(0);
            assertThat(row.get("excludedEnvironment")).isEqualTo(0);assertThat(SupportPaymentFactService.invalidNewSource(row,Source.GENESIS_SECONDARY)).isEqualTo("BROKEN_SOURCE_LINK");
        }
        seller.put("amount",BigDecimal.TEN);var row=SupportPaymentSourceService.genesisRows(new SupportGenesisReadFacade.Source(original,List.of(buyer,seller))).get(0);
        assertThat(SupportPaymentFactService.invalidNewSource(row,Source.GENESIS_SECONDARY)).isEqualTo("BROKEN_SOURCE_LINK");
    }
    @Test void missingGenesisReadStaysUnknownInActualFactsAndDoesNotInventAZero() {
        when(market.ordersByCustomers(List.of(7L))).thenReturn(null);
        var facts=new SupportPaymentFactService(mock(ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper.class),reader).read(List.of(7L));
        assertThat(facts.facts()).isEmpty();assertThat(facts.issues()).filteredOn(i->SupportPaymentFacts.genesis(i.source())).hasSize(2)
            .allSatisfy(i->assertThat(i.reason()).isEqualTo("SOURCE_READ_FAILED"));
        assertThat(facts.coverage()).filteredOn(c->SupportPaymentFacts.genesis(c.source())).allSatisfy(c->assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
    }
}
