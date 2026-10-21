package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.facade.SupportFundBirthReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.mapper.SupportFundReadMapper;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import ffdd.opsconsole.treasury.facade.SupportCommissionReadFacade;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Complete component readback, inside the caller's physical read-only RR snapshot. */
@ApplicationService
public class SupportFundReadService {
    private static final int MAX_COMPONENT_CUSTOMERS=4096;
    private static final String RAW_SHA=sha("SUPPORT_WALLET_JOURNAL_V1");
    private final SupportFundReadMapper mapper;
    private final SupportFundBirthReadFacade auth;
    private final FinanceSupportPaymentFactsFacade payments;
    private final SupportFundJournalBindingService codec;
    private final DataSource dataSource;
    private final SupportFundEarningsEvidence earningsEvidence;
    private final SupportGenesisReadFacade genesis;
    private final SupportExchangeReadFacade exchanges;
    private final SupportFundGenesisEvidence genesisEvidence;
    private final SupportFundExchangeEvidence exchangeEvidence;
    private final SupportDeviceReadFacade devices;
    private final SupportStakingReadFacade staking;
    private final SupportFundDeviceEvidence deviceEvidence;
    private final SupportFundStakingEvidence stakingEvidence;
    private final SupportCommissionReadFacade commissions;
    private final SupportDirectReferralReadFacade direct;
    private final SupportFundCommissionEvidence commissionEvidence;
    private final SupportFundDirectReferralEvidence directEvidence;
    public SupportFundReadService(SupportFundReadMapper mapper,SupportFundBirthReadFacade auth,
            FinanceSupportPaymentFactsFacade payments,SupportFundJournalBindingService codec,DataSource dataSource) {
        this(mapper,auth,payments,codec,dataSource,new SupportFundEarningsEvidence(codec,new ObjectMapper()),null,null,
            new SupportFundGenesisEvidence(new ObjectMapper(),codec),new SupportFundExchangeEvidence(codec,new ObjectMapper(),null));
    }
    public SupportFundReadService(SupportFundReadMapper mapper,SupportFundBirthReadFacade auth,
            FinanceSupportPaymentFactsFacade payments,SupportFundJournalBindingService codec,DataSource dataSource,
            SupportFundEarningsEvidence earningsEvidence,SupportGenesisReadFacade genesis,SupportExchangeReadFacade exchanges,
            SupportFundGenesisEvidence genesisEvidence,SupportFundExchangeEvidence exchangeEvidence) {
        this(mapper,auth,payments,codec,dataSource,earningsEvidence,genesis,exchanges,genesisEvidence,exchangeEvidence,
            null,null,new SupportFundDeviceEvidence(codec,new ObjectMapper()),new SupportFundStakingEvidence(codec,new ObjectMapper(),earningsEvidence));
    }
    public SupportFundReadService(SupportFundReadMapper mapper,SupportFundBirthReadFacade auth,
            FinanceSupportPaymentFactsFacade payments,SupportFundJournalBindingService codec,DataSource dataSource,
            SupportFundEarningsEvidence earningsEvidence,SupportGenesisReadFacade genesis,SupportExchangeReadFacade exchanges,
            SupportFundGenesisEvidence genesisEvidence,SupportFundExchangeEvidence exchangeEvidence,
            SupportDeviceReadFacade devices,SupportStakingReadFacade staking,SupportFundDeviceEvidence deviceEvidence,SupportFundStakingEvidence stakingEvidence) {
        this(mapper,auth,payments,codec,dataSource,earningsEvidence,genesis,exchanges,genesisEvidence,exchangeEvidence,devices,staking,deviceEvidence,stakingEvidence,
            null,null,new SupportFundCommissionEvidence(codec,new ObjectMapper()),new SupportFundDirectReferralEvidence(codec,new ObjectMapper()));
    }
    @Autowired
    public SupportFundReadService(SupportFundReadMapper mapper,SupportFundBirthReadFacade auth,
            FinanceSupportPaymentFactsFacade payments,SupportFundJournalBindingService codec,DataSource dataSource,
            SupportFundEarningsEvidence earningsEvidence,SupportGenesisReadFacade genesis,SupportExchangeReadFacade exchanges,
            SupportFundGenesisEvidence genesisEvidence,SupportFundExchangeEvidence exchangeEvidence,
            SupportDeviceReadFacade devices,SupportStakingReadFacade staking,SupportFundDeviceEvidence deviceEvidence,SupportFundStakingEvidence stakingEvidence,
            SupportCommissionReadFacade commissions,SupportDirectReferralReadFacade direct,SupportFundCommissionEvidence commissionEvidence,SupportFundDirectReferralEvidence directEvidence) {
        this.mapper=mapper;this.auth=auth;this.payments=payments;this.codec=codec;this.dataSource=dataSource;
        this.earningsEvidence=earningsEvidence;this.genesis=genesis;this.exchanges=exchanges;
        this.genesisEvidence=genesisEvidence;this.exchangeEvidence=exchangeEvidence;
        this.devices=devices;this.staking=staking;this.deviceEvidence=deviceEvidence;this.stakingEvidence=stakingEvidence;
        this.commissions=commissions;this.direct=direct;this.commissionEvidence=commissionEvidence;this.directEvidence=directEvidence;
    }
    public NetSnapshot read(Collection<Long> requested) {
        if(requested==null || requested.stream().anyMatch(id->id==null || id<=0))throw new IllegalArgumentException("Explicit positive customer scope required");
        var roots=List.copyOf(new TreeSet<>(requested));
        if(roots.isEmpty())return new NetSnapshot(List.of(),List.of(),List.of());
        Connection connection=snapshot();
        try {
            if(!Boolean.TRUE.equals(mapper.schemaReady()))return unknown(roots,List.of(Gap.RAW_HISTORY));
            var scope=new TreeSet<Long>(roots);List<Long> opIds;List<Map<String,Object>> partyRows;
            do {
                int previous=scope.size();opIds=mapper.operationIds(List.copyOf(scope));
                if(opIds==null || opIds.stream().anyMatch(id->id==null || id<=0))throw bad(Gap.RECEIPT);
                partyRows=opIds.isEmpty()?List.of():mapper.parties(opIds);
                for(var row:partyRows) {require(opIds.contains(number(row,"operationId")) &&number(row,"customerId")>0,Gap.RECEIPT);scope.add(number(row,"customerId"));}
                if(scope.size()>MAX_COMPONENT_CUSTOMERS)return unknown(roots,List.of(Gap.CLOSURE_LIMIT));
                if(previous==scope.size())break;
            }while(true);
            var ids=List.copyOf(scope);var ops=opIds.isEmpty()?List.<Map<String,Object>>of():mapper.operations(opIds);
            final var partyIndex=indexParties(partyRows);
            var legs=opIds.isEmpty()?List.<Map<String,Object>>of():mapper.legs(opIds);
            require(ops.stream().map(row->number(row,"id")).toList().equals(opIds),Gap.RECEIPT);
            for(var leg:legs)require(opIds.contains(number(leg,"operationId")),Gap.RAW_BINDING);
            var wallets=mapper.wallets(ids);var walletIds=wallets.stream().map(row->number(row,"id")).toList();
            for(var wallet:wallets)require(scope.contains(number(wallet,"userId")),Gap.WALLET);
            var raw=mapper.raw(ids,walletIds.isEmpty()?List.of(-1L):walletIds);
            var birth=auth.read(ids);var paymentHistory=payments.readHistory(ids);
            var withdrawalRows=mapper.withdrawals(ids);var ledgers=mapper.ledgers(ids);
            Integer withdrawalPrecision=withdrawalRows.isEmpty()?Integer.valueOf(0):mapper.withdrawalSuccessPrecision();
            var audits=mapper.withdrawalAudits(ids);var banks=mapper.bankReservations(ids);var attempts=mapper.withdrawalAttempts(ids);
            var paymentLedgers=mapper.paymentLedgers(ids);var earnings=mapper.earningsSources(ids);
            var earningsRecoveries=mapper.earningsRecoveries(ids);
            var earningsInput=new SupportFundEarningsEvidence.Inputs(earnings,earningsRecoveries,mapper.earningsAppliedProofs(ids),
                mapper.earningsAudits(ids),raw,earnings.isEmpty() &&earningsRecoveries.isEmpty()?Map.of():earningsPrecisions());
            var paymentSources=new HashMap<SupportPaymentFacts.Source,List<Map<String,Object>>>();
            for(var source:SupportPaymentFacts.Source.values())if(source!=SupportPaymentFacts.Source.FREE_TRIAL &&source!=SupportPaymentFacts.Source.UNMATCHED_LEDGER
                    &&source!=SupportPaymentFacts.Source.GENESIS_PRIMARY &&source!=SupportPaymentFacts.Source.GENESIS_SECONDARY)
                paymentSources.put(source,mapper.paymentSources(source,ids));
            var paymentAudits=mapper.paymentAudits(ids);
            var trials=mapper.trialSources(ids);
            var genesisInput=new SupportFundGenesisEvidence.Inputs(paymentHistory,genesis==null?List.of():genesis.readByCustomers(ids),raw,ops,partyRows,legs);
            var exchangeSources=exchanges==null?List.<SupportExchangeReadFacade.Source>of():exchanges.readByCustomers(ids);
            var exchangeInput=new SupportFundExchangeEvidence.Inputs(exchangeSources,raw,ops,partyRows,legs,
                exchangeSources.isEmpty()?Map.of():exchanges.timePrecisions());
            var deviceSources=devices==null?new SupportDeviceReadFacade.FundEarnings(List.of(),List.of(),List.of(),List.of()):devices.readFundEarnings(ids);
            require(deviceSources!=null,Gap.SOURCE);
            boolean hasDeviceSources=!deviceSources.receipts().isEmpty() ||!deviceSources.tasks().isEmpty()
                ||!deviceSources.ledgers().isEmpty() ||!deviceSources.earningEvents().isEmpty();
            var deviceInput=new SupportFundDeviceEvidence.Inputs(deviceSources,raw,ops,partyRows,legs,hasDeviceSources?devices.timePrecisions():Map.of());
            var stakingSources=staking==null?List.<SupportStakingReadFacade.Source>of():staking.readByCustomers(ids);
            require(stakingSources!=null,Gap.SOURCE);
            var stakingInput=new SupportFundStakingEvidence.Inputs(stakingSources,raw,ops,partyRows,legs,
                stakingSources.isEmpty()?Map.of():staking.timePrecisions(),earningsInput);
            var commissionSources=commissions==null?new SupportCommissionReadFacade.Sources(List.of(),List.of()):commissions.readFundCommissions(ids);
            require(commissionSources!=null,Gap.SOURCE);
            var commissionInput=new SupportFundCommissionEvidence.Inputs(commissionSources,raw,ops,partyRows,legs,
                commissionSources.events().isEmpty() &&commissionSources.ledgers().isEmpty()?Map.of():commissions.timePrecisions());
            var directSources=direct==null?List.<SupportDirectReferralReadFacade.Source>of():direct.readByCustomers(ids);
            require(directSources!=null,Gap.SOURCE);
            var directInput=new SupportFundDirectReferralEvidence.Inputs(directSources,ops,partyRows,legs,raw,
                directSources.isEmpty()?Map.of():direct.timePrecisions());
            require(snapshot()==connection,Gap.READ_FAILED);
            var result=new ArrayList<NetCustomer>();
            var publicOrigins=new ArrayList<ContributionOrigin>();var publicChanges=new ArrayList<ContributionChange>();
            // Validate peers internally. Only the caller's roots and their original contributions leave this service.
            for(var component:components(scope,partyRows)) {
                var componentRoots=roots.stream().filter(component::contains).toList();if(componentRoots.isEmpty())continue;
                var gaps=new ArrayList<Gap>();var tail=new HashMap<Long,Cash>();var verified=new ArrayList<Operation>();
                var originFacts=new HashMap<String,String>();var holdings=new ArrayList<Holding>();
                try {
                    var componentOps=ops.stream().filter(row->parties(number(row,"id"),partyIndex).stream().anyMatch(component::contains)).toList();
                    var componentRaw=raw.stream().filter(row->component.contains(number(row,"newUserId"))
                        ||row.get("oldUserId")!=null && component.contains(number(row,"oldUserId"))).toList();
                    validateRaw(component,wallets,componentRaw,componentOps,partyIndex,legs,birth,tail);
                    SupportFundDirectReferralEvidence.ValidatedReversals directReversals;
                    try {directReversals=directEvidence.validate(component,directInput);}
                    catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                    var componentEarnings=new SupportFundEarningsEvidence.Inputs(earningsInput.entries(),earningsInput.recoveries(),earningsInput.appliedProofs(),
                        earningsInput.audits(),earningsInput.raw(),earningsInput.precisions(),directReversals);
                    var represented=new HashSet<String>();var withdrawalPhases=new HashSet<String>();
                    var representedEarnings=new HashSet<String>();var earningsPhases=new HashSet<String>();var exchangePhases=new HashSet<String>();
                    var devicePhases=new HashSet<String>();var stakingPhases=new HashSet<String>();var linkedEarnings=new HashSet<String>();
                    var commissionPhases=new HashSet<String>();var directPhases=new HashSet<String>();
                    for(var row:componentOps) {
                        long id=number(row,"id");var parties=parties(id,partyIndex);var opLegs=legs(id,legs);
                        var events=codec.decodeReceipt(row,parties,opLegs);var source=codec.read(text(row,"sourceSnapshotJson"));
                        var proof=codec.read(text(row,"sourceProofJson"));
                        if("AUTH_FUND_BIRTH".equals(row.get("businessType"))) {
                            require(events.isEmpty(),Gap.AUTH_BIRTH);continue;
                        }
                        Stamp stamp;List<Checkpoint> checkpoints=List.of();List<Point> sourcePoints=null;
                        switch(text(row,"businessType")) {
                            case "SUPPORT_CREGIS_REORG" -> {
                                try {stamp=SupportFundCregisReorgEvidence.verify(codec,row,source,proof,events,mapper.cregisReorgOperations(parties));}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                            }
                            case "SUPPORT_PAYMENT" -> stamp=payment(row,source,proof,events,paymentHistory,paymentLedgers,paymentSources,paymentAudits,trials,earnings,representedEarnings,originFacts,represented);
                            case "SUPPORT_WITHDRAWAL" -> stamp=withdrawal(row,source,proof,events,withdrawalRows,ledgers,audits,banks,attempts,withdrawalPhases,withdrawalPrecision,componentOps);
                            case "SUPPORT_EARNINGS" -> {
                                SupportFundEarningsEvidence.Verified earning;
                                try {earning=earningsEvidence.verify(row,source,proof,events,componentEarnings);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(earningsPhases.add(earning.coveredPhase()) &&parties.equals(List.of(source.path("entry").path("userId").longValue())),Gap.SOURCE);
                                stamp=earning.stamp();checkpoints=earning.checkpoints();
                            }
                            case "SUPPORT_GENESIS" -> {
                                SupportFundGenesisEvidence.Verified consumption;
                                try {consumption=genesisEvidence.verify(row,source,proof,events,genesisInput);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(represented.add(consumption.factId()),Gap.SOURCE);
                                originFacts.put("CONSUME:"+consumption.factId(),consumption.factId());
                                stamp=consumption.stamp();sourcePoints=consumption.points();
                            }
                            case "SUPPORT_EXCHANGE" -> {
                                SupportFundExchangeEvidence.Verified exchange;
                                try {exchange=exchangeEvidence.verify(row,source,proof,events,exchangeInput);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(exchangePhases.add(exchange.coveredPhase()),Gap.SOURCE);
                                stamp=exchange.stamp();sourcePoints=exchange.points();
                            }
                            case "SUPPORT_DEVICE_EARNING" -> {
                                SupportFundDeviceEvidence.Verified device;
                                try {device=deviceEvidence.verify(row,source,proof,events,deviceInput);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(devicePhases.add(device.coveredPhase()),Gap.SOURCE);
                                stamp=device.stamp();sourcePoints=device.points();
                            }
                            case "SUPPORT_STAKING" -> {
                                SupportFundStakingEvidence.Verified position;
                                try {position=stakingEvidence.verify(row,source,proof,events,stakingInput);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(stakingPhases.add(position.coveredPhase()),Gap.SOURCE);
                                for(String no:position.representedEarnings())require(linkedEarnings.add(no),Gap.SOURCE);
                                stamp=position.stamp();sourcePoints=position.points();
                            }
                            case "SUPPORT_COMMISSION" -> {
                                SupportFundCommissionEvidence.Verified commission;
                                try {commission=commissionEvidence.verify(row,source,proof,events,commissionInput);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(commissionPhases.add(commission.coveredPhase()),Gap.SOURCE);
                                stamp=commission.stamp();sourcePoints=commission.points();
                            }
                            case "SUPPORT_DIRECT_REFERRAL" -> {
                                SupportFundDirectReferralEvidence.Verified recovery;
                                try {recovery=directEvidence.verify(row,source,proof,events,directInput);}
                                catch(IllegalArgumentException ex){throw bad(Gap.SOURCE);}
                                require(directPhases.add(recovery.coveredPhase()),Gap.SOURCE);
                                stamp=recovery.stamp();sourcePoints=recovery.points();checkpoints=recovery.checkpoints();
                            }
                            default -> throw bad(Gap.UNSUPPORTED_SOURCE);
                        }
                        require(checkpoints.isEmpty() ||opLegs.isEmpty(),Gap.CASH_TRACE);
                        verified.add(new Operation(id,text(row,"operationKey"),parties,events,
                            sourcePoints!=null?sourcePoints:checkpoints.isEmpty()?points(opLegs,proof,componentRaw,events):List.of(),stamp,checkpoints));
                    }
                    // Source enumeration is independent of receipt enumeration; zero-cash omissions cannot hide here.
                    if(ops.stream().anyMatch(op->"SUPPORT_CREGIS_REORG".equals(op.get("businessType")))) {
                        for(var held:mapper.cregisReorgHoldings(component.stream().sorted().toList()))
                            holdings.add(new Holding(new Account(number(held,"userId"),"USDT","risk:CR-"+number(held,"incidentId")),
                                decimal(held.get("heldAmount")),"nx_cregis_reorg_resolution:"+number(held,"incidentId")));
                    }
                    for(var fact:paymentHistory.facts())if(component.contains(fact.customerId()))
                        require(represented.contains(fact.factId()),Gap.SOURCE_COVERAGE);
                    for(var entry:paymentSources.entrySet())for(var sourceRow:entry.getValue())if(component.contains(number(sourceRow,"customerId"))) {
                        if(sourceRow.containsKey("excludedEnvironment") &&flag(sourceRow.get("excludedEnvironment"))==1)continue;
                        if(!entry.getKey().name().equals(sourceRow.get("source")))continue;
                        if(decimal(sourceRow.get("amount")).signum()==0)continue;
                        String factId=switch(text(sourceRow,"kind")) {
                            case "DEPOSIT"->"DEPOSIT:"+number(sourceRow,"ledgerId");
                            case "DEVICE_PURCHASE"->"PURCHASE:"+text(sourceRow,"orderNo");
                            case "DEVICE_PURCHASE_REFUND"->"ORDER_REFUND:"+number(sourceRow,"ledgerId");
                            default->throw bad(Gap.UNSUPPORTED_SOURCE);
                        };
                        require(represented.contains(factId),Gap.SOURCE_COVERAGE);
                    }
                    for(var issue:paymentHistory.issues())if(issue.customerId()==null || component.contains(issue.customerId()))throw bad(Gap.SOURCE_COVERAGE);
                    representedEarnings.forEach(no->require(earningsPhases.add(no+":CREDIT"),Gap.SOURCE_COVERAGE));
                    linkedEarnings.forEach(no->require(earningsPhases.contains(no+":CREDIT") &&!representedEarnings.contains(no),Gap.SOURCE_COVERAGE));
                    try {earningsEvidence.verifyCoverage(component,componentEarnings,earningsPhases);}
                    catch(IllegalArgumentException ex){throw bad(Gap.SOURCE_COVERAGE);}
                    try {
                        genesisEvidence.verifyCoverage(component,genesisInput,represented);
                        holdings.addAll(exchangeEvidence.verifyCoverage(component,exchangeInput,exchangePhases));
                        deviceEvidence.verifyCoverage(component,deviceInput,devicePhases);
                        holdings.addAll(stakingEvidence.verifyCoverage(component,stakingInput,stakingPhases));
                        commissionEvidence.verifyCoverage(component,commissionInput,commissionPhases);
                        directEvidence.verifyCoverage(component,directInput,directPhases);
                    }catch(IllegalArgumentException ex){throw bad(Gap.SOURCE_COVERAGE);}
                    for(var order:withdrawalRows)if(component.contains(number(order,"userId"))) {
                        String no=text(order,"withdrawalNo");
                        require(withdrawalPhases.contains(no+":RESERVE"),Gap.SOURCE_COVERAGE);
                        String status=text(order,"status");
                        if("CONFIRMED".equals(status))require(withdrawalPhases.contains(no+":CONFIRM"),Gap.SOURCE_COVERAGE);
                        if(Set.of("FAILED","REFUNDED","REVIEW_REJECTED").contains(status))require(withdrawalPhases.contains(no+":RETURN"),Gap.SOURCE_COVERAGE);
                    }
                }catch(EvidenceFailure ex) {gaps.add(ex.gap);}
                catch(IllegalArgumentException | IllegalStateException ex) {gaps.add(Gap.RECEIPT);}
                var replay=new SupportFundReplay().replay(component,verified,tail,originFacts,gaps,holdings);
                for(long root:componentRoots) {
                    var total=replay.totals().get(root);boolean ready=replay.state()==State.READY;
                    result.add(new NetCustomer(root,ready?EvidenceState.READY:EvidenceState.UNKNOWN,
                        replay.reasons().stream().map(Enum::name).toList(),ready?total.get("USDT"):null,ready?total.get("NEX"):null));
                }
                for(var origin:replay.origins().values())if(componentRoots.contains(origin.customerId())) {
                    var at=origin.formedAt();publicOrigins.add(new ContributionOrigin(origin.contributionId(),origin.customerId(),origin.paymentFactId(),
                        at.succeededAt(),at.businessZone(),at.fractionalSecondDigits(),at.sourceReference()));
                }
                for(var change:replay.changes())if(componentRoots.contains(change.originCustomerId())) {
                    var at=change.occurredAt();publicChanges.add(new ContributionChange(change.contributionId(),change.originCustomerId(),change.currency(),change.amount(),
                        at.succeededAt(),at.businessZone(),at.fractionalSecondDigits(),at.sourceReference()));
                }
            }
            result.sort(Comparator.comparingLong(NetCustomer::customerId));
            return new NetSnapshot(result,publicOrigins,publicChanges);
        }catch(EvidenceFailure ex) {return unknown(roots,List.of(ex.gap));}
        catch(org.springframework.dao.DataAccessException ex) {return unknown(roots,List.of(Gap.READ_FAILED));}
        catch(IllegalArgumentException | IllegalStateException | ArithmeticException ex) {return unknown(roots,List.of(Gap.READ_FAILED));}
    }
    private Map<String,Integer> earningsPrecisions() {
        var precisions=new HashMap<String,Integer>();
        for(var row:mapper.earningsTimePrecisions()) {
            String key=text(row,"tableName")+"."+text(row,"columnName");long precision=number(row,"precision");
            require(precision>=0 &&precision<=6 &&precisions.put(key,(int)precision)==null,Gap.SOURCE);
        }
        return Map.copyOf(precisions);
    }
    private void validateRaw(Set<Long> component,List<Map<String,Object>> wallets,List<Map<String,Object>> raw,
            List<Map<String,Object>> ops,Map<Long,List<Long>> partyIndex,List<Map<String,Object>> allLegs,
            SupportFundBirthReadFacade.Evidence births,Map<Long,Cash> tails) {
        var covered=new HashSet<String>();
        for(var op:ops) {
            var parties=parties(number(op,"id"),partyIndex);require(component.containsAll(parties),Gap.RAW_BINDING);
            codec.decodeReceipt(op,parties,legs(number(op,"id"),allLegs));
            for(var leg:legs(number(op,"id"),allLegs))require(covered.add(legKey(leg)),Gap.RAW_BINDING);
        }
        for(long customer:component) {
            var current=wallets.stream().filter(row->number(row,"userId")==customer).toList();require(current.size()==1,Gap.WALLET);
            var wallet=current.get(0);var tuple=SupportFundJournalBindingService.tuple(wallet,"");
            require(tuple.sandbox()==0 && tuple.isDeleted()==0,Gap.WALLET);
            String incarnation=text(wallet,"incarnation");long revision=number(wallet,"revision");
            var history=raw.stream().filter(row->number(row,"newUserId")==customer || row.get("oldUserId")!=null && number(row,"oldUserId")==customer
                || number(row,"newId")==tuple.id() ||row.get("oldId")!=null && number(row,"oldId")==tuple.id()).toList();
            require(history.size()==revision+1,Gap.RAW_HISTORY);
            SupportFundJournalBindingService.Tuple previous=null;
            for(int index=0;index<history.size();index++) {
                var row=history.get(index);require(incarnation.equals(row.get("incarnation")) && number(row,"revision")==index
                    && RAW_SHA.equals(row.get("protocolSha256")),Gap.RAW_HISTORY);
                require(covered.remove(legKey(row)),Gap.RAW_BINDING);
                var next=SupportFundJournalBindingService.tuple(row,"new");
                require(next.id()==tuple.id() && next.userId()==customer && next.sandbox()==0 && next.isDeleted()==0
                    && next.createdAt().equals(tuple.createdAt()),Gap.RAW_HISTORY);
                if(index==0) {
                    require("NEW_ZERO".equals(row.get("observation")) && next.version()==0 && zero(next),Gap.AUTH_BIRTH);
                    for(String field:List.of("Id","UserId","UsdtAvailable","NexAvailable","PendingWithdraw","CregisRiskHeld","Version","Sandbox","IsDeleted","CreatedAt"))
                        require(row.get("old"+field)==null,Gap.AUTH_BIRTH);
                    authBirth(customer,incarnation,row,ops,partyIndex,allLegs,births);
                }else require("CHANGE".equals(row.get("observation")) && previous.equals(SupportFundJournalBindingService.tuple(row,"old"))
                    && !previous.equals(next),Gap.RAW_HISTORY);
                previous=next;
            }
            require(tuple.equals(previous),Gap.RAW_HISTORY);tails.put(customer,cash(tuple));
        }
        require(covered.isEmpty(),Gap.RAW_BINDING);
    }
    private void authBirth(long customer,String incarnation,Map<String,Object> raw,List<Map<String,Object>> ops,
            Map<Long,List<Long>> partyIndex,List<Map<String,Object>> allLegs,SupportFundBirthReadFacade.Evidence births) {
        var candidates=ops.stream().filter(op->"AUTH_FUND_BIRTH".equals(op.get("businessType"))
            &&parties(number(op,"id"),partyIndex).contains(customer)).toList();require(candidates.size()==1,Gap.AUTH_BIRTH);
        var op=candidates.get(0);var source=codec.read(text(op,"sourceSnapshotJson"));var proof=codec.read(text(op,"sourceProofJson"));
        require("REGISTERED".equals(op.get("phase")) && "nx_event_outbox".equals(op.get("sourceTable"))
            &&("AUTH_FUND_BIRTH:"+customer).equals(op.get("operationKey"))
            &&parties(number(op,"id"),partyIndex).equals(List.of(customer)),Gap.AUTH_BIRTH);
        keys(source,Set.of("creator","customerId","customerCreatedAt","sandbox","isDeleted","eventId","outboxId","eventTs","aggregateType","eventName","schemaRevision","actualUserInsert","expectedWalletIncarnation","wallet","birthJournal"));
        keys(proof,Set.of("protocol","sourceEnvironment","actualAuthInsertTokenVerified","registrationOutboxReadbackVerified","customerIds"));
        require(number(source.path("sandbox"),Gap.AUTH_BIRTH)==0
            &&number(source.path("isDeleted"),Gap.AUTH_BIRTH)==0
            &&number(source.path("actualUserInsert"),Gap.AUTH_BIRTH)==1
            &&"USER_REGISTRATION".equals(source.path("aggregateType").asText()) &&"auth.register_completed".equals(source.path("eventName").asText())
            &&"PRODUCTION".equals(proof.path("sourceEnvironment").asText()) &&proof.path("customerIds").isArray()
            &&proof.path("customerIds").size()==1
            &&number(proof.path("customerIds").get(0),Gap.AUTH_BIRTH)==customer,Gap.AUTH_BIRTH);
        for(String field:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","sandbox","isDeleted","createdAt")) {
            String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);
            require(walletFieldEquals(field,source.path("wallet").path(field),raw.get("new"+cap)),Gap.AUTH_BIRTH);
        }
        require(incarnation.equals(source.path("wallet").path("incarnation").asText())
            &&number(source.path("wallet").path("revision"),Gap.AUTH_BIRTH)==0,Gap.AUTH_BIRTH);
        var legs=legs(number(op,"id"),allLegs);require(legs.size()==1 && incarnation.equals(legs.get(0).get("incarnation")) && number(legs.get(0),"revision")==0,Gap.AUTH_BIRTH);
        var identities=births.customers().stream().filter(row->number(row,"id")==customer).toList();require(identities.size()==1,Gap.AUTH_BIRTH);
        var identity=identities.get(0);
        require(flag(identity.get("sandbox"))==0 && flag(identity.get("isDeleted"))==0 && "ACTIVE".equals(identity.get("status"))
            && time(identity.get("createdAt")).equals(parseTime(source.path("customerCreatedAt")))
            &&number(source.path("customerId"),Gap.AUTH_BIRTH)==customer
            &&incarnation.equals(source.path("expectedWalletIncarnation").asText())
            &&"AUTH_NEW_ACCOUNT_REGISTRATION".equals(source.path("creator").asText())
            &&"AUTH_FUND_BIRTH_V1".equals(proof.path("protocol").asText()),Gap.AUTH_BIRTH);
        String eventId=source.path("eventId").asText();require(eventId.equals(op.get("sourceKey")),Gap.AUTH_BIRTH);
        var events=births.registrationEvents().stream().filter(row->String.valueOf(customer).equals(row.get("aggregateId"))).toList();
        require(events.size()==1,Gap.AUTH_BIRTH);var event=events.get(0);
        require(eventId.equals(event.get("eventId")) &&number(event,"id")==number(source.path("outboxId"),Gap.AUTH_BIRTH)
            &&number(event,"schemaRevision")==number(source.path("schemaRevision"),Gap.AUTH_BIRTH) &&number(event,"schemaRevision")>0
            &&"USER_REGISTRATION".equals(event.get("aggregateType"))
            &&"auth.register_completed".equals(event.get("eventType")) &&"auth.register_completed".equals(event.get("eventName"))
            &&truth(event.get("serverAuthoritative")) &&truth(event.get("schemaRegistered")) &&truth(event.get("analyticsEvent"))
            &&flag(event.get("isDeleted"))==0 &&time(event.get("eventTs")).equals(parseTime(source.path("eventTs"))),Gap.AUTH_BIRTH);
        var payload=codec.read(text(event,"payload"));require(number(payload.path("userId"),Gap.AUTH_BIRTH)==customer
            &&number(payload.path("user_id"),Gap.AUTH_BIRTH)==customer
            &&eventId.equals(payload.path("event_id").asText()) &&"auth.register_completed".equals(payload.path("event_name").asText())
            &&payload.path("is_server_authoritative").isBoolean() &&payload.path("is_server_authoritative").booleanValue()
            &&number(payload.path("schema_revision"),Gap.AUTH_BIRTH)==number(event,"schemaRevision"),Gap.AUTH_BIRTH);
        require(codec.canonical(codec.read(text(op,"sourceSnapshotJson"))).path("birthJournal").equals(tree(raw)),Gap.AUTH_BIRTH);
    }
    private Stamp payment(Map<String,Object> op,JsonNode source,JsonNode proof,List<Event> events,SupportPaymentFacts.Snapshot history,
            List<Map<String,Object>> ledgers,
            Map<SupportPaymentFacts.Source,List<Map<String,Object>>> sources,List<Map<String,Object>> audits,
            List<Map<String,Object>> trials,List<Map<String,Object>> earnings,Set<String> representedEarnings,
            Map<String,String> origins,Set<String> represented) {
        require("SETTLED".equals(op.get("phase")) && "SUPPORT_PAYMENT_FUND_V1".equals(source.path("schemaVersion").asText()),Gap.SOURCE);
        var sourceKeys=new HashSet<>(Set.of("schemaVersion","customerId","factId","source","stableBusinessKey","sourcePartition","orderNo","amount","currency","succeededAt","settledSource","freshLedger","rewards","rewardLedgers","trialSource"));
        if("ORDER_REFUND".equals(source.path("source").asText()))sourceKeys.add("originalPurchase");keys(source,sourceKeys);
        keys(proof,Set.of("schemaVersion","sourceAuthority","cashBefore","cashAfter","cashTrace","originalConsume","originalFundEvidence"));
        String factId=source.path("factId").asText();long customer=source.path("customerId").longValue();
        boolean trial="TRIAL_CONVERT".equals(source.path("source").asText());
        if(trial &&source.path("amount").isNumber() &&source.path("amount").decimalValue().signum()==0)
            return zeroTrial(op,source,proof,events,sources,audits,trials,earnings,ledgers,representedEarnings,represented);
        var facts=history.facts().stream().filter(f->f.customerId()==customer &&factId.equals(f.factId())).toList();require(facts.size()==1,Gap.SOURCE);
        var fact=facts.get(0);require(represented.add(factId),Gap.SOURCE);
        require(fact.source().name().equals(source.path("source").asText()) &&fact.sourceBusinessId().equals(source.path("stableBusinessKey").asText())
            &&fact.currency().equals(source.path("currency").asText()) &&source.path("amount").isNumber()
            &&fact.amount().compareTo(source.path("amount").decimalValue())==0 &&fact.succeededAt().equals(parseTime(source.path("succeededAt"))),Gap.SOURCE);
        if(!trial)require(source.path("rewards").isArray() &&source.path("rewards").isEmpty()
            &&source.path("rewardLedgers").isArray() &&source.path("rewardLedgers").isEmpty()
            &&source.path("trialSource").isNull(),Gap.UNSUPPORTED_SOURCE);
        require(!events.isEmpty(),Gap.SOURCE);Event event=events.get(0);
        String key="PAYMENT:"+fact.source()+":"+sha(fact.source()+"|"+(source.path("sourcePartition").isNull()?"":source.path("sourcePartition").asText())+"|"+factId);
        require(key.equals(op.get("operationKey")),Gap.SOURCE);
        String table=switch(fact.source()) {
            case DEPOSIT_ORDER->"nx_deposit_order";case CARD_TOPUP->"nx_payment_record";case VIETQR->"nx_vietqr_reconciliation";
            case HDPAY->"nx_hdpay_payin_order";case TRIAL_CONVERT->"nx_trial_claim";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,ORDER_REFUND->"nx_order";default->throw bad(Gap.UNSUPPORTED_SOURCE);
        };
        require(table.equals(op.get("sourceTable")) &&(fact.source()+":"+sha(fact.sourceBusinessId()+"|"
            +(source.path("sourcePartition").isNull()?"":source.path("sourcePartition").asText()))).equals(op.get("sourceKey")),Gap.SOURCE);
        var matchingLedgers=ledgers.stream().filter(row->number(row,"id")==fact.ledgerId()).toList();
        require(matchingLedgers.size()==1 &&nodeEquals(source.path("freshLedger"),matchingLedgers.get(0)),Gap.SOURCE);
        var actualSources=sources.getOrDefault(fact.source(),List.of()).stream().filter(row->number(row,"customerId")==customer
            &&number(row,"ledgerId")==fact.ledgerId()).toList();
        require(actualSources.size()==1 &&nodeEquals(source.path("settledSource"),actualSources.get(0)),Gap.SOURCE);
        if(fact.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE) {
            var sourceAudits=audits.stream().filter(row->auditIdentity(row,"customerId")==customer &&Objects.equals(fact.orderNo(),row.get("orderNo"))
                &&number(row,"validDigest")==1 &&auditIdentity(row,"actorId")==customer &&"USER".equals(row.get("actorType"))
                &&"SUCCESS".equals(row.get("result")) &&nodeEquals(proof.path("sourceAuthority"),row)).toList();
            require(sourceAudits.size()==1,Gap.SOURCE);
        }else require(nodeEquals(proof.path("sourceAuthority"),actualSources.get(0)),Gap.SOURCE);
        require("SUPPORT_PAYMENT_FUND_V1".equals(proof.path("schemaVersion").asText()),Gap.SOURCE);
        Action action=switch(fact.kind()) {case DEPOSIT->Action.DEPOSIT;case DEVICE_PURCHASE->Action.CONSUME;case DEVICE_PURCHASE_REFUND->Action.REFUND;case CONSUMPTION->throw bad(Gap.UNSUPPORTED_SOURCE);};
        String id=action==Action.CONSUME?"CONSUME:"+factId:action+":"+key;
        var account=new Account(customer,fact.currency(),"available");
        var expected=new Event(id,action,action==Action.CONSUME?account:null,action==Action.DEPOSIT?account:null,
            action==Action.REFUND?"CONSUME:"+fact.originalFactId():null,fact.amount(),null);
        require(expected.equals(event),Gap.SOURCE);origins.put(id,factId);
        require(proof.path("cashTrace").isArray() &&proof.path("cashTrace").size()>0
            &&("nx_wallet_ledger:"+fact.ledgerId()).equals(proof.path("cashTrace").get(0).path("sourceId").asText()),Gap.SOURCE);
        if(action==Action.REFUND) {
            var original=history.facts().stream().filter(f->f.customerId()==customer &&fact.originalFactId().equals(f.factId())).toList();
            require(original.size()==1 &&("CONSUME:"+fact.originalFactId()).equals(proof.path("originalConsume").asText()),Gap.ORIGIN);
            var purchase=original.get(0);var originalSnapshot=new LinkedHashMap<String,Object>();
            originalSnapshot.put("factId",purchase.factId());originalSnapshot.put("customerId",purchase.customerId());originalSnapshot.put("source",purchase.source().name());
            originalSnapshot.put("stableBusinessKey",purchase.sourceBusinessId());originalSnapshot.put("orderNo",purchase.orderNo());originalSnapshot.put("currency",purchase.currency());
            originalSnapshot.put("amount",purchase.amount());originalSnapshot.put("ledgerId",purchase.ledgerId());originalSnapshot.put("succeededAt",purchase.succeededAt());
            require(nodeEquals(source.path("originalPurchase"),originalSnapshot),Gap.ORIGIN);
        }else require(proof.path("originalConsume").isNull() &&proof.path("originalFundEvidence").isNull(),Gap.SOURCE);
        if(trial) {
            var expectedEvents=new ArrayList<Event>();expectedEvents.add(expected);
            expectedEvents.addAll(trialRewards(source,trials,earnings,ledgers,representedEarnings,proof.path("cashBefore").path("id").longValue()));
            require(expectedEvents.equals(events),Gap.SOURCE);
        }else require(events.size()==1,Gap.SOURCE);
        require(history.issues().stream().noneMatch(issue->issue.customerId()==null ||issue.customerId()==customer),Gap.SOURCE);
        return new Stamp(factId,fact.succeededAt(),history.businessZone(),fact.fractionalSecondDigits());
    }
    private Stamp zeroTrial(Map<String,Object> op,JsonNode source,JsonNode proof,List<Event> events,
            Map<SupportPaymentFacts.Source,List<Map<String,Object>>> sources,List<Map<String,Object>> audits,
            List<Map<String,Object>> trials,List<Map<String,Object>> earnings,List<Map<String,Object>> ledgers,
            Set<String> representedEarnings,Set<String> represented) {
        long customer=source.path("customerId").longValue();String factId=source.path("factId").asText();String order=source.path("orderNo").asText();
        String key=source.path("stableBusinessKey").asText(),partition=source.path("sourcePartition").isNull()?"":source.path("sourcePartition").asText();
        require(("PURCHASE:"+order).equals(factId) &&represented.add(factId) &&source.path("freshLedger").isNull()
            &&"USDT".equals(source.path("currency").asText()) &&"nx_trial_claim".equals(op.get("sourceTable"))
            &&("PAYMENT:TRIAL_CONVERT:"+sha("TRIAL_CONVERT|"+partition+"|"+factId)).equals(op.get("operationKey"))
            &&("TRIAL_CONVERT:"+sha(key+"|"+partition)).equals(op.get("sourceKey")),Gap.SOURCE);
        require(proof.path("cashTrace").isArray() &&proof.path("cashTrace").size()>0
            &&("nx_trial_claim:"+source.path("trialSource").path("claimId").longValue()).equals(proof.path("cashTrace").get(0).path("sourceId").asText())
            &&proof.path("originalConsume").isNull() &&proof.path("originalFundEvidence").isNull(),Gap.SOURCE);
        var actual=sources.getOrDefault(SupportPaymentFacts.Source.TRIAL_CONVERT,List.of()).stream().filter(row->number(row,"customerId")==customer
            &&order.equals(row.get("orderNo"))).toList();require(actual.size()==1 &&nodeEquals(source.path("settledSource"),actual.get(0)),Gap.SOURCE);
        require(decimal(actual.get(0).get("amount")).signum()==0 &&source.path("succeededAt").isTextual(),Gap.SOURCE);
        var authority=audits.stream().filter(row->auditIdentity(row,"customerId")==customer &&order.equals(row.get("orderNo"))
            &&number(row,"validDigest")==1 &&auditIdentity(row,"actorId")==customer &&"USER".equals(row.get("actorType"))
            &&"SUCCESS".equals(row.get("result")) &&nodeEquals(proof.path("sourceAuthority"),row)).toList();require(authority.size()==1,Gap.SOURCE);
        require(trialRewards(source,trials,earnings,ledgers,representedEarnings,proof.path("cashBefore").path("id").longValue()).equals(events),Gap.SOURCE);
        return null;
    }
    private List<Event> trialRewards(JsonNode source,List<Map<String,Object>> trials,List<Map<String,Object>> earnings,
            List<Map<String,Object>> ledgers,Set<String> represented,long walletId) {
        long customer=source.path("customerId").longValue();String key=source.path("stableBusinessKey").asText();
        require(key.endsWith(":CHARGE"),Gap.SOURCE);String claim=key.substring(0,key.length()-7);
        var candidates=trials.stream().filter(row->number(row,"customerId")==customer &&claim.equals(row.get("claimNo"))).toList();
        require(candidates.size()==1 &&nodeEquals(source.path("trialSource"),candidates.get(0)),Gap.SOURCE);var trial=candidates.get(0);
        require("REDEEMED".equals(trial.get("claimStatus")) &&"TRIAL_CONVERT".equals(trial.get("orderType"))
            &&"PAID".equals(trial.get("paymentStatus")) &&"PRODUCTION".equals(trial.get("deviceEnvironment"))
            &&"TRIAL".equals(trial.get("sourceChannel")) &&"ACTIVE".equals(trial.get("deviceStatus"))
            &&"OWNED".equals(trial.get("ownershipStatus")) &&"ACTIVE".equals(trial.get("userStatus"))
            &&decimal(trial.get("chargeUsdt")).compareTo(source.path("amount").decimalValue())==0
            &&decimal(trial.get("amountUsdt")).compareTo(source.path("amount").decimalValue())==0
            &&time(trial.get("paidAt")).equals(parseTime(source.path("succeededAt"))) &&trial.get("settledAt")!=null,Gap.SOURCE);
        for(String flag:List.of("userSandbox","userDeleted","claimDeleted","deviceDeleted","orderDeleted"))require(flag(trial.get(flag))==0,Gap.SOURCE);
        require(source.path("rewards").isArray() &&source.path("rewardLedgers").isArray()
            &&source.path("rewards").size()==source.path("rewardLedgers").size(),Gap.SOURCE);
        var events=new ArrayList<Event>();var assets=new HashSet<String>();int index=0;
        for(var reward:source.path("rewards")) {
            keys(reward,Set.of("entryNo","customerId","sourceType","sourceRef","currency","amount","bucket","status","sourceEnvironment","isDeleted","recoveredAmount","sourceWalletId","debitBaseline"));
            String no=reward.path("entryNo").asText(),asset=reward.path("currency").asText();require(assets.add(asset) &&represented.add(no),Gap.SOURCE);
            var entries=earnings.stream().filter(row->no.equals(row.get("entryNo")) &&number(row,"userId")==customer).toList();require(entries.size()==1,Gap.SOURCE);var entry=entries.get(0);
            for(String field:List.of("entryNo","sourceType","sourceRef","amount","status","sourceEnvironment","isDeleted","sourceWalletId","debitBaseline")) {
                if("isDeleted".equals(field))require(number(reward.path(field),Gap.SOURCE)==flag(entry.get(field)),Gap.SOURCE);
                else require(nodeEquals(reward.path(field),entry.get(field)),Gap.SOURCE);
            }
            String originalBucket=reward.path("bucket").asText();
            require(reward.path("recoveredAmount").isNumber() &&reward.path("recoveredAmount").decimalValue().signum()==0
                &&Set.of("withdrawable","pending_review","bonus_locked").contains(originalBucket),Gap.SOURCE);
            require(nodeEquals(reward.path("customerId"),entry.get("userId")) &&asset.equals(entry.get("asset"))
                &&"ACTIVE".equals(entry.get("status")) &&"PRODUCTION".equals(entry.get("sourceEnvironment")) &&flag(entry.get("isDeleted"))==0
                &&number(entry,"sourceWalletId")==walletId
                &&decimal(entry.get("recoveredAmount")).signum()>=0
                &&decimal(entry.get("recoveredAmount")).compareTo(decimal(entry.get("amount")))<=0,Gap.SOURCE);
            if(entry.get("releasedAt")==null)require(originalBucket.equals(entry.get("bucket")) &&entry.get("releaseSource")==null,Gap.SOURCE);
            else require(Set.of("pending_review","bonus_locked").contains(originalBucket) &&"withdrawable".equals(entry.get("bucket"))
                &&Set.of("manual","attest").contains(entry.get("releaseSource")),Gap.SOURCE);
            String suffix=asset.equals("USDT")?":REMAINDER":":NEX",type=asset.equals("USDT")?"H2_TRIAL_REMAINDER":"H2_TRIAL_BONUS";
            require(Set.of("USDT","NEX").contains(asset) &&(claim+suffix).equals(entry.get("sourceRef")) &&type.equals(entry.get("sourceType")),Gap.SOURCE);
            BigDecimal amount=decimal(entry.get("amount"));require(amount.signum()>0
                &&amount.compareTo(decimal(trial.get(asset.equals("USDT")?"remainderUsdt":"shadowNex")))==0,Gap.SOURCE);
            var sourceLedger=ledgers.stream().filter(row->(claim+suffix).equals(row.get("businessId")) &&number(row,"customerId")==customer).toList();
            require(sourceLedger.size()==1 &&nodeEquals(source.path("rewardLedgers").get(index++),sourceLedger.get(0)),Gap.SOURCE);var ledger=sourceLedger.get(0);
            require("TRIAL_BONUS".equals(ledger.get("ledgerType")) &&"POSTED".equals(ledger.get("status")) &&"IN".equals(ledger.get("direction"))
                &&asset.equals(ledger.get("currency")) &&flag(ledger.get("deleted"))==0 &&amount.compareTo(decimal(ledger.get("amount")))==0,Gap.SOURCE);
            events.add(new Event("EARNINGS:"+no,"withdrawable".equals(originalBucket)?Action.EARNINGS:Action.PROTECTED_EARNINGS,
                null,new Account(customer,asset,"available"),null,amount,null));
        }
        for(String asset:List.of("USDT","NEX"))require(decimal(trial.get(asset.equals("USDT")?"remainderUsdt":"shadowNex")).signum()==0?!assets.contains(asset):assets.contains(asset),Gap.SOURCE);
        return List.copyOf(events);
    }
    private Stamp withdrawal(Map<String,Object> op,JsonNode source,JsonNode proof,List<Event> events,List<Map<String,Object>> orders,
            List<Map<String,Object>> allLedgers,List<Map<String,Object>> audits,List<Map<String,Object>> banks,
            List<Map<String,Object>> attempts,Set<String> represented,Integer precision,List<Map<String,Object>> operations) {
        require("SUPPORT_WITHDRAWAL_FUND_V1".equals(source.path("schemaVersion").asText()) &&"nx_withdrawal_order".equals(op.get("sourceTable")),Gap.SOURCE);
        String no=source.path("withdrawalNo").asText(),phase=text(op,"phase");
        keys(source,Set.of("schemaVersion","customerId","withdrawalNo","phase","order","reservationLedgers","gross","netReceive","actualFee","nexBurned","succeededAt","bankReservation","submissionAudits"));
        keys(proof,Set.of("schemaVersion","cashBefore","cashAfter","cashTrace","reservationSource","originalReserve","originalFundEvidence","originalReserveOperation"));
        require("SUPPORT_WITHDRAWAL_FUND_V1".equals(proof.path("schemaVersion").asText()),Gap.SOURCE);
        require(no.equals(op.get("sourceKey")) &&phase.equals(source.path("phase").asText())
            &&SupportWithdrawalFundSourceService.operationKey(no,phase).equals(op.get("operationKey")) &&represented.add(no+":"+phase),Gap.SOURCE);
        require(proof.path("cashTrace").isArray() &&proof.path("cashTrace").size()==1
            &&("nx_withdrawal_order:"+no).equals(proof.path("cashTrace").get(0).path("sourceId").asText()),Gap.SOURCE);
        var candidates=orders.stream().filter(row->no.equals(row.get("withdrawalNo"))).toList();require(candidates.size()==1,Gap.SOURCE);var order=candidates.get(0);
        long customer=number(order,"userId");require(source.path("customerId").longValue()==customer &&flag(order.get("isDeleted"))==0 &&"USDT".equals(order.get("asset")),Gap.SOURCE);
        var recorded=source.path("order");
        for(String field:List.of("netReceive","actualFee","nexBurned"))require(nodeEquals(source.path(field),order.get(field)),Gap.SOURCE);
        require(nodeEquals(source.path("gross"),order.get("amount")),Gap.SOURCE);
        for(String field:List.of("id","userId","asset","chain","amount","netReceive","actualFee","nexBurned","idempotencyKey","createdAt"))
            require(nodeEquals(recorded.path(field),order.get(field)),Gap.SOURCE);
        var reserveLedgers=allLedgers.stream().filter(row->text(row,"bizNo").startsWith(no+":")
            &&Set.of("WITHDRAW_NET_PRINCIPAL","WITHDRAW_NETWORK_FEE","WITHDRAW_BANK_FEE","WITHDRAW_PENALTY_FEE","WITHDRAW_FEE_OFFSET").contains(row.get("bizType"))).toList();
        require(SupportWithdrawalFundSourceService.reservationLedgers(order,reserveLedgers),Gap.SOURCE);
        require(nodeEquals(source.path("reservationLedgers"),reserveLedgers),Gap.SOURCE);
        boolean reservation=false;
        for(var attempt:attempts)if(no.equals(attempt.get("withdrawalNo")) &&number(attempt,"userId")==customer
            &&Objects.equals(order.get("idempotencyKey"),attempt.get("idempotencyKey")) &&"COMMITTED".equals(attempt.get("status")))reservation=true;
        if("BANK-VND".equals(order.get("chain"))) {
            var matches=banks.stream().filter(row->no.equals(row.get("withdrawalNo")) &&number(row,"userId")==customer).toList();
            require(matches.size()==1,Gap.SOURCE);var bank=matches.get(0);
            reservation=nodeEquals(source.path("bankReservation"),bank)
                &&decimal(bank.get("amount")).compareTo(decimal(order.get("amount")))==0
                &&decimal(bank.get("fee")).compareTo(decimal(order.get("actualFee")))==0
                &&decimal(bank.get("net")).compareTo(decimal(order.get("netReceive")))==0;
        }else if(!reservation) {
            var matches=audits.stream().filter(row->no.equals(row.get("resourceId")) &&no.equals(row.get("bizNo"))).toList();
            if(matches.size()==1) {
                var audit=matches.get(0);var detail=codec.read(text(audit,"detailJson"));
                reservation=number(audit,"userId")==customer &&number(audit,"actorId")==customer &&flag(audit.get("isDeleted"))==0
                    &&"USER".equals(audit.get("actorType")) &&"SUCCESS".equals(audit.get("result"))
                    &&no.equals(detail.path("withdrawal_id").asText()) &&nodeEquals(detail.path("chain"),order.get("chain"));
                for(var entry:Map.of("amount_usdt","amount","net_receive","netReceive","actual_fee","actualFee","nex_burned","nexBurned").entrySet())
                    reservation &= nodeEquals(detail.path(entry.getKey()),order.get(entry.getValue()));
            }
        }
        require(reservation,Gap.SOURCE);
        require(SupportWithdrawalFundSourceService.events(customer,no,phase,decimal(order.get("amount")),decimal(order.get("nexBurned"))).equals(events),Gap.SOURCE);
        if(!"RESERVE".equals(phase)) {
            String originalKey=SupportWithdrawalFundSourceService.operationKey(no,"RESERVE");
            var original=operations.stream().filter(row->originalKey.equals(row.get("operationKey"))).toList();
            require(original.size()==1 &&originalKey.equals(proof.path("originalReserve").asText())
                &&nodeEquals(proof.path("originalReserveOperation"),original.get(0)),Gap.ORIGIN);
        }else require(proof.path("originalReserve").isNull() &&proof.path("originalFundEvidence").isNull() &&proof.path("originalReserveOperation").isNull(),Gap.SOURCE);
        String status=text(order,"status");
        LocalDateTime at;
        if("CONFIRM".equals(phase)) {
            require("CONFIRMED".equals(status),Gap.SOURCE);at=time(order.get("completedAt"));
            require(at.equals(parseTime(source.path("succeededAt"))),Gap.SOURCE);
        }else if("RETURN".equals(phase)) {require(Set.of("FAILED","REFUNDED","REVIEW_REJECTED").contains(status),Gap.SOURCE);
            // Return changes no contribution; its causal reference, not guessed success time, orders it.
            return null;
        }else {require("RESERVE".equals(phase),Gap.UNSUPPORTED_SOURCE);return null;}
        require(precision!=null &&precision>=0 &&precision<=6,Gap.SOURCE);
        return new Stamp(no,at,DateTimeFormatConfig.BUSINESS_ZONE.getId(),precision);
    }
    private List<Point> points(List<Map<String,Object>> legs,JsonNode proof,List<Map<String,Object>> raw,List<Event> events) {
        var trace=proof.path("cashTrace");require(trace.isArray() &&trace.size()==legs.size(),Gap.CASH_TRACE);
        var result=new ArrayList<Point>();var seen=new HashSet<String>();Map<String,Object> first=null,last=null;
        for(var item:trace) {
            keys(item,item.has("eventIds")?Set.of("sourceId","eventIds","journal"):Set.of("sourceId","eventId","journal"));
            var snapshot=item.path("journal");String incarnation=snapshot.path("incarnation").asText();long revision=snapshot.path("revision").longValue();
            var matches=raw.stream().filter(row->incarnation.equals(row.get("incarnation")) &&number(row,"revision")==revision).toList();require(matches.size()==1,Gap.CASH_TRACE);
            var row=matches.get(0);require(nodeEquals(snapshot,row) &&seen.add(incarnation+":"+revision)
                &&legs.stream().anyMatch(leg->incarnation.equals(leg.get("incarnation")) &&number(leg,"revision")==revision),Gap.CASH_TRACE);
            if(first==null)first=row;
            else require(incarnation.equals(last.get("incarnation")) &&revision==number(last,"revision")+1,Gap.CASH_TRACE);
            last=row;
            var ids=new ArrayList<String>();if(item.has("eventIds")) {require(item.path("eventIds").isArray(),Gap.CASH_TRACE);item.path("eventIds").forEach(id->{require(id.isTextual(),Gap.CASH_TRACE);ids.add(id.textValue());});}
            else {require(item.has("eventId"),Gap.CASH_TRACE);if(!item.path("eventId").isNull()) {require(item.path("eventId").isTextual(),Gap.CASH_TRACE);ids.add(item.path("eventId").textValue());}}
            for(String id:ids)require(events.stream().anyMatch(event->event.id().equals(id)),Gap.CASH_TRACE);
            require(number(row,"newVersion")==number(row,"oldVersion")+1,Gap.CASH_TRACE);
            result.add(new Point(incarnation,revision,number(row,"newUserId"),cash(SupportFundJournalBindingService.tuple(row,"old")),cash(SupportFundJournalBindingService.tuple(row,"new")),ids));
        }
        require(!result.isEmpty(),Gap.CASH_TRACE);
        require(walletEquals(proof.path("cashBefore"),walletSnapshot(first,"old",number(first,"revision")-1))
            &&walletEquals(proof.path("cashAfter"),walletSnapshot(last,"new",number(last,"revision"))),Gap.CASH_TRACE);
        return List.copyOf(result);
    }
    private static Map<String,Object> walletSnapshot(Map<String,Object> row,String prefix,long revision) {
        var wallet=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","sandbox","isDeleted","createdAt"))
            wallet.put(field,row.get(prefix+Character.toUpperCase(field.charAt(0))+field.substring(1)));
        wallet.put("incarnation",row.get("incarnation"));wallet.put("revision",revision);return wallet;
    }
    private Connection snapshot() {
        return codec.readSnapshot(dataSource);
    }
    private static List<Set<Long>> components(Set<Long> ids,List<Map<String,Object>> rows) {
        var partiesByOperation=indexParties(rows);var operationsByCustomer=new HashMap<Long,List<Long>>();
        partiesByOperation.forEach((operation,customers)->customers.forEach(customer->
            operationsByCustomer.computeIfAbsent(customer,id->new ArrayList<>()).add(operation)));
        var remaining=new TreeSet<>(ids);var result=new ArrayList<Set<Long>>();
        var visitedOperations=new HashSet<Long>();
        while(!remaining.isEmpty()) {
            var found=new HashSet<Long>();var pending=new ArrayDeque<Long>();
            found.add(remaining.first());pending.add(remaining.first());
            while(!pending.isEmpty()) {
                for(var operation:operationsByCustomer.getOrDefault(pending.removeFirst(),List.of())) {
                    if(!visitedOperations.add(operation))continue;
                    for(var customer:parties(operation,partiesByOperation))
                        if(found.add(customer))pending.addLast(customer);
                }
            }
            remaining.removeAll(found);result.add(Set.copyOf(found));
        }
        return result;
    }
    private static Map<Long,List<Long>> indexParties(List<Map<String,Object>> rows) {
        var index=new HashMap<Long,List<Long>>();
        for(var row:rows)index.computeIfAbsent(number(row,"operationId"),id->new ArrayList<>()).add(number(row,"customerId"));
        index.replaceAll((id,parties)->List.copyOf(parties));return Map.copyOf(index);
    }
    private static List<Long> parties(long id,Map<Long,List<Long>> index) {return index.getOrDefault(id,List.of());}
    private static List<Map<String,Object>> legs(long id,List<Map<String,Object>> rows) {return rows.stream().filter(row->number(row,"operationId")==id).toList();}
    private static String legKey(Map<String,Object> row) {return text(row,"incarnation")+":"+number(row,"revision");}
    private static Cash cash(SupportFundJournalBindingService.Tuple t) {return new Cash(t.usdt(),t.nex(),t.pending(),t.risk());}
    private static boolean zero(SupportFundJournalBindingService.Tuple t) {return t.usdt().signum()==0 &&t.nex().signum()==0 &&t.pending().signum()==0 &&t.risk().signum()==0;}
    private JsonNode tree(Object value) {return codec.canonical(codec.read(encode(value)));}
    private String encode(Object value) {
        try {return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(normalize(value));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw bad(Gap.RECEIPT);}
    }
    private static Object normalize(Object value) {
        if(value instanceof Timestamp t)return t.toLocalDateTime().toString();if(value instanceof LocalDateTime t)return t.toString();
        if(value instanceof Map<?,?> map) {var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),normalize(v)));return sorted;}
        if(value instanceof Collection<?> list)return list.stream().map(SupportFundReadService::normalize).toList();return value;
    }
    private boolean nodeEquals(JsonNode node,Object value) {return codec.canonical(node).equals(tree(value));}
    // Only these wallet columns differ in native JDBC representation across wallet and journal tables.
    // This comparison never changes stored source/raw JSON or the receipt's canonical digest.
    private boolean walletFieldEquals(String field,JsonNode node,Object value) {
        if(!Set.of("sandbox","isDeleted").contains(field))return nodeEquals(node,value);
        return jsonFlag(node)==flag(value);
    }
    private boolean walletEquals(JsonNode node,Map<String,Object> wallet) {
        keys(node,wallet.keySet());
        for(var field:wallet.entrySet())if(!walletFieldEquals(field.getKey(),node.path(field.getKey()),field.getValue()))return false;
        return true;
    }
    private static long jsonFlag(JsonNode node) {
        if(node.isBoolean())return node.booleanValue()?1:0;
        if(node.isNumber())return flag(node.numberValue());
        throw bad(Gap.SOURCE);
    }
    private static void keys(JsonNode node,Set<String> expected) {
        require(node.isObject(),Gap.SOURCE);var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);require(actual.equals(expected),Gap.SOURCE);
    }
    private static LocalDateTime parseTime(JsonNode node) {require(node.isTextual(),Gap.SOURCE);try{return LocalDateTime.parse(node.textValue());}catch(java.time.DateTimeException ex){throw bad(Gap.SOURCE);}}
    private static LocalDateTime time(Object value) {if(value instanceof LocalDateTime at)return at;if(value instanceof Timestamp at)return at.toLocalDateTime();throw bad(Gap.SOURCE);}
    private static BigDecimal decimal(Object value) {if(!(value instanceof Number))throw bad(Gap.SOURCE);return new BigDecimal(value.toString());}
    private static long number(Map<String,Object> row,String key) {Object value=row.get(key);if(!(value instanceof Number))throw bad(Gap.RECEIPT);try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException ex){throw bad(Gap.RECEIPT);}}
    // The hot/archive audit UNION exposes only these identity columns as numeric strings.
    private static long auditIdentity(Map<String,Object> row,String key) {
        Object value=row.get(key);
        if(value instanceof String text &&text.matches("[1-9][0-9]{0,18}")) {
            try {return Long.parseLong(text);}catch(NumberFormatException ex){throw bad(Gap.RECEIPT);}
        }
        long id=number(row,key);require(id>0,Gap.RECEIPT);return id;
    }
    private static long number(JsonNode value,Gap gap) {require(value.isNumber(),gap);try{return value.decimalValue().longValueExact();}catch(ArithmeticException ex){throw bad(gap);}}
    private static String text(Map<String,Object> row,String key) {if(!(row.get(key) instanceof String text))throw bad(Gap.RECEIPT);return text;}
    private static boolean truth(Object value) {return Boolean.TRUE.equals(value) ||value instanceof Number n &&n.intValue()==1;}
    private static long flag(Object value) {if(value instanceof Boolean bit)return bit?1:0;if(value instanceof Number n) {
        try {long v=new BigDecimal(n.toString()).longValueExact();if(v==0 ||v==1)return v;}
        catch(ArithmeticException | NumberFormatException ex) {throw bad(Gap.SOURCE);}}throw bad(Gap.SOURCE);}
    private static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static NetSnapshot unknown(List<Long> roots,List<Gap> gaps) {return new NetSnapshot(roots.stream().map(id->new NetCustomer(id,EvidenceState.UNKNOWN,gaps.stream().map(Enum::name).toList(),null,null)).toList(),List.of(),List.of());}
    private static void require(boolean valid,Gap gap) {if(!valid)throw bad(gap);}
    private static EvidenceFailure bad(Gap gap) {return new EvidenceFailure(gap);}
    private static final class EvidenceFailure extends RuntimeException {final Gap gap;EvidenceFailure(Gap gap){this.gap=gap;}}
}
