package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.Envelope;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.BirthEvidence;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput;
import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Account;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import static ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.Marker.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import static ffdd.opsconsole.finance.application.SupportPaymentFactService.*;

/** The before proof is private, tied to this service and the actual physical transaction resource. */
@ApplicationService
public class SupportPaymentSourceService implements FinanceSupportPaymentFactsFacade {
    private final SupportPaymentSourceMapper mapper;
    private final SupportPaymentFactService history;
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final SupportPaymentCaptureHistoryFacade capturedHistory;
    private final SupportFundJournalFacade fund;
    private final SupportFundJournalMapper fundMapper;
    private final EarningsReleaseMapper earnings;
    private final SupportGenesisReadFacade genesis;
    @org.springframework.beans.factory.annotation.Autowired
    public SupportPaymentSourceService(SupportPaymentSourceMapper mapper,SupportPaymentFactService history,DataSource dataSource,
            ObjectMapper json,SupportPaymentCaptureHistoryFacade capturedHistory,SupportFundJournalFacade fund,
            SupportFundJournalMapper fundMapper,EarningsReleaseMapper earnings,SupportGenesisReadFacade genesis) {
        this.mapper=mapper;this.history=history;this.dataSource=dataSource;this.json=json;this.capturedHistory=capturedHistory;
        this.fund=fund;this.fundMapper=fundMapper;this.earnings=earnings;this.genesis=genesis;
    }
    public SupportPaymentSourceService(SupportPaymentSourceMapper mapper,SupportPaymentFactService history,DataSource dataSource,
            ObjectMapper json,SupportPaymentCaptureHistoryFacade capturedHistory,SupportFundJournalFacade fund,
            SupportFundJournalMapper fundMapper,EarningsReleaseMapper earnings) {
        this(mapper,history,dataSource,json,capturedHistory,fund,fundMapper,earnings,null);
    }

    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SupportPaymentFacts.Snapshot readHistory(Collection<Long> customers) {
        if(customers==null || customers.isEmpty() || customers.stream().anyMatch(id -> id==null || id<=0))
            throw new IllegalArgumentException("Explicit positive customer scope required");
        requireHistorySnapshot();
        var ids=List.copyOf(new TreeSet<>(customers));
        var registration=registrationBoundary(ids);
        var candidates=new ArrayList<VerifiedCandidate>();
        var issues=new ArrayList<Issue>();
        var conflicts=new HashSet<String>();
        var production=new HashSet<String>();
        List<Map<String,Object>> paymentAudits=List.of(),refundOrigins=List.of();
        try {paymentAudits=mapper.historyPaymentAudits(ids);}
        catch(DataAccessException ex) {
            for(var source:List.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT))
                issues.add(new Issue(source,null,"HISTORICAL_ENVIRONMENT_SOURCE_READ_FAILED"));
        }
        try {refundOrigins=mapper.historyRefundOrigins(ids);}
        catch(DataAccessException ex) {issues.add(new Issue(Source.ORDER_REFUND,null,"BACKEND_REFUND_SOURCE_READ_FAILED"));}
        for(var row:refundOrigins)if(row==null || !ids.contains(number(row,"customerId")))throw failure("CAPTURED_CUSTOMER_OUTSIDE_SCOPE");
        List<Envelope> envelopes;
        try { envelopes=capturedHistory.readNewFinancialProofs(ids); }
        catch(DataAccessException ex) {
            historyReadFailure(issues);
            return history.readWithCapturedHistory(ids,captured(candidates,issues,conflicts,registration,production,refundOrigins));
        } catch(IllegalStateException ex) {
            if(!Set.of("INVALID_CAPTURE_HISTORY_SCHEMA","INVALID_CAPTURE_HISTORY_ROW").contains(Objects.toString(ex.getMessage(),"")))throw ex;
            for(Source source:historySources())issues.add(new Issue(source,null,"INVALID_PERSISTED_SOURCE_PROOF"));
            return history.readWithCapturedHistory(ids,captured(candidates,issues,conflicts,registration,production,refundOrigins));
        }
        // A returned out-of-scope actor remains a hard boundary failure, never partial financial data.
        for(var envelope:envelopes)
            if(envelope==null || envelope.identity()==null || !ids.contains(envelope.identity().customerId()))
                throw failure("CAPTURED_CUSTOMER_OUTSIDE_SCOPE");
        var decoded=new LinkedHashMap<Envelope,Fact>();
        for(var envelope:envelopes) {
            try {
                var fact=SupportPaymentCapturedSourceProof.decodeSourceFact(envelope.sourceFactJson(),json);
                if(!matchesIdentity(envelope,fact) || envelope.captureDbUtc()==null
                    || !SupportPaymentCapturedSourceProof.matchesExpected(fact,envelope.sourcePartition(),proof(envelope),json)
                    || !validBeforeTypes(envelope.beforeSourceJson())) {
                    Source source=historyIssueSource(envelope);
                    if(source==Source.ORDER_REFUND && fact.kind()!=Kind.DEVICE_PURCHASE_REFUND)source=null;
                    issues.add(new Issue(source,envelope.identity().factId(),"CAPTURED_SOURCE_PROOF_MISMATCH",envelope.identity().customerId()));continue;
                }
                validateKey(fact.customerId(),fact.source(),fact.sourceBusinessId());
                if(fact.sourceBusinessId().startsWith("USER:") || !validHistoryPartition(fact.source(),envelope.sourcePartition()))
                    throw failure("INVALID_PERSISTED_SOURCE_PROOF");
                String successField=switch(fact.source()) {
                    case DEPOSIT_ORDER -> "nx_deposit_order.credited_at";case HDPAY -> "nx_hdpay_payin_order.settled_at";
                    case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT -> "nx_order.paid_at";
                    case GENESIS_PRIMARY,GENESIS_SECONDARY -> "nx_genesis_order.paid_at";
                    default -> "nx_wallet_ledger.created_at";
                };
                if(!successField.equals(fact.successTimeField()) || fact.fractionalSecondDigits()!=(fact.kind()==Kind.DEVICE_PURCHASE?6:0)
                    || fact.historicalEnvironmentStatus()!=Status.UNKNOWN)throw failure("INVALID_PERSISTED_SOURCE_PROOF");
                decoded.put(envelope,fact);
            } catch(IllegalArgumentException | IllegalStateException ex) {
                Source source=historyIssueSource(envelope);
                if(source==null) for(Source supported:historySources())issues.add(new Issue(supported,null,"INVALID_PERSISTED_SOURCE_PROOF",envelope.identity().customerId()));
                else issues.add(new Issue(source,envelope.identity().factId(),"INVALID_PERSISTED_SOURCE_PROOF",envelope.identity().customerId()));
            }
        }
        if(!decoded.isEmpty()) {
            List<Map<String,Object>> ledgers;
            try { ledgers=mapper.historyLedgers(ids,decoded.values().stream().map(Fact::ledgerId).distinct().sorted().toList()); }
            catch(DataAccessException ex) {
                historyReadFailure(issues);
                return history.readWithCapturedHistory(ids,captured(candidates,issues,conflicts,registration,production,refundOrigins));
            }
            var sourceReads=historySourceReads(ids,decoded);
            for(var entry:decoded.entrySet()) {
                var fact=entry.getValue();
                var matches=ledgers.stream().filter(row -> number(row,"id")==fact.ledgerId()).toList();
                String problem=matches.isEmpty()?"MISSING_SETTLEMENT_LEDGER":matches.size()!=1?"SETTLEMENT_MISMATCH":ledgerProblem(matches.get(0),fact);
                if(problem==null) {
                    try { problem=historySourceProblem(fact,entry.getKey().sourcePartition(),sourceReads); }
                    catch(DataAccessException ex) { problem="SOURCE_READ_FAILED"; }
                }
                if(problem!=null) {
                    issues.add(new Issue(fact.source(),fact.factId(),problem,fact.customerId()));
                    // FactService quarantines real contradictions by this customer/source/canonical tuple.
                    // A rejected capture cannot own its claimed canonical ID globally or evict another customer.
                } else {
                    candidates.add(new VerifiedCandidate(fact,fact.source()==Source.VIETQR?entry.getKey().sourcePartition():fact.sourceBusinessId()));
                    var birth=registration.births().get(fact.customerId());
                    String lifecycle=birth==null?null:lifecycleProblem(birth,entry.getKey(),fact);
                    if(lifecycle==null && registration.readAtUtc()!=null && entry.getKey().captureDbUtc().isAfter(registration.readAtUtc()))
                        lifecycle="CAPTURE_AFTER_HISTORY_SNAPSHOT";
                    if(lifecycle!=null) {
                        if(fact.kind()==Kind.DEVICE_PURCHASE_REFUND)issues.add(new Issue(Source.ORDER_REFUND,fact.factId(),lifecycle,fact.customerId()));
                        else registration.problems().get(fact.customerId()).add(lifecycle);
                    }
                    if(birth!=null && lifecycle==null && registration.problems().get(fact.customerId()).isEmpty()
                        && historicalPurchaseProduction(fact,entry.getKey(),paymentAudits))production.add(fact.factId());
                }
            }
        }
        return history.readWithCapturedHistory(ids,captured(candidates,issues,conflicts,registration,production,refundOrigins));
    }

    private static CapturedFinancialHistory captured(List<VerifiedCandidate> candidates,List<Issue> issues,Set<String> conflicts,
            RegistrationBoundary registration,Set<String> production,List<Map<String,Object>> refunds) {
        var births=new HashMap<Long,LocalDateTime>();
        registration.births().forEach((id,birth) -> {
            if(registration.problems().get(id).isEmpty())births.put(id,birth.birthDbUtc().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(DateTimeFormatConfig.BUSINESS_ZONE).toLocalDateTime());
        });
        return new CapturedFinancialHistory(candidates,issues,conflicts,registration.problems(),births,production,refunds);
    }
    // Specific success audits belong to existing production-only writers; H2 additionally persists its environment.
    // Generic NEW_SUCCESS and today's sandbox flag are not environment proof.
    private boolean historicalPurchaseProduction(Fact fact,Envelope envelope,List<Map<String,Object>> audits) {
        if(fact.kind()!=Kind.DEVICE_PURCHASE)return false;
        var matches=new HashSet<Long>();
        for(var row:audits) {
            if(row==null || auditNumber(row,"customerId")!=fact.customerId() || auditNumber(row,"validDigest")!=1
                || !Objects.equals(text(row,"orderNo"),fact.orderNo())
                || !"USER".equals(text(row,"actorType")) || !"SUCCESS".equals(text(row,"result"))
                || auditNumber(row,"actorId")!=fact.customerId() || text(row,"detail")==null || time(row,"auditAt")==null
                || !Objects.equals(time(row,"paidAt"),fact.succeededAt()) || !amountMatches(row,fact))continue;
            var at=time(row,"auditAt").atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant();
            if(at.isBefore(fact.ledgerRecordedAt().withNano(0).atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant())
                || at.isAfter(envelope.captureDbUtc().toInstant(ZoneOffset.UTC)))continue;
            try {
                var detail=json.readTree(text(row,"detail"));
                if(detail==null || !productionAuditMatches(fact,row,detail))continue;
                if(auditNumber(row,"id")>0)matches.add(auditNumber(row,"id"));
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { /* Absent/malformed history remains UNKNOWN. */ }
        }
        return matches.size()==1;
    }
    private static boolean productionAuditMatches(Fact fact,Map<String,Object> row,com.fasterxml.jackson.databind.JsonNode detail) {
        String action=text(row,"action"),path=text(row,"path"),type=text(row,"resourceType");
        if(fact.source()==Source.TRIAL_CONVERT) {
            if(!fact.sourceBusinessId().endsWith(":CHARGE"))return false;
            String claim=fact.sourceBusinessId().substring(0,fact.sourceBusinessId().length()-":CHARGE".length());
            return "H2_TRIAL_CONVERTED".equals(action) && "TRIAL_CLAIM".equals(type)
                && Objects.equals(text(row,"resourceId"),claim) && Objects.equals(text(row,"bizNo"),claim)
                && "PRODUCTION".equals(detail.path("sourceEnvironment").asText()) && detail.path("serverCanonical").isBoolean() && detail.path("serverCanonical").booleanValue()
                && detail.path("runId").isTextual() && detail.path("runId").textValue().isEmpty()
                && "NEXION_USDT_WALLET".equals(detail.path("paymentRail").asText())
                && Objects.equals(detail.path("orderNo").asText(),fact.orderNo()) && auditAmount(detail.path("amountUsdt"),fact)
                && auditNumber(row,"deviceMatched")==1;
        }
        if(!"POST".equals(text(row,"method")) || !Objects.equals(text(row,"bizNo"),fact.orderNo()))return false;
        if(fact.source()==Source.WALLET_ORDER) {
            return Set.of("SINGLE","BUNDLE").contains(fact.orderType()) && "APP_ORDER_WALLET_PAYMENT".equals(action)
                && "ORDER".equals(type) && Objects.equals(text(row,"resourceId"),fact.orderNo())
                && Objects.equals(path,"/api/orders/"+fact.orderNo()+"/pay") && detail.path("canonical").isBoolean() && detail.path("canonical").booleanValue()
                && "NEXGRID_WALLET".equals(detail.path("paymentRail").asText()) && detail.path("paymentNo").isTextual()
                && Objects.equals(detail.path("paymentNo").textValue(),text(row,"paymentNo")) && auditAmount(detail.path("amountUsdt"),fact);
        }
        if(auditNumber(row,"deviceMatched")!=1)return false;
        if(fact.source()==Source.TRADE_IN && "TRADE_IN".equals(fact.orderType())) {
            if("USER_TRADEIN_COMPLETED".equals(action) && "TRADEIN_APPLICATION".equals(type) && "/api/app/trade-in/submit".equals(path))
                return Objects.equals(detail.path("pricing").path("orderNo").asText(),fact.orderNo())
                    && auditAmount(detail.path("pricing").path("walletDebitUsdt"),fact);
            return "USER_CAPACITY_REPLACEMENT_COMPLETED".equals(action) && "TRADEIN_APPLICATION".equals(type)
                && "/api/app/trade-in/capacity-replace".equals(path) && "server".equals(detail.path("decisionSource").asText());
        }
        return fact.source()==Source.CAPACITY_KEEP && "CAPACITY_KEEP".equals(fact.orderType())
            && "USER_CAPACITY_KEEP_PURCHASE_COMPLETED".equals(action) && "DEVICE_ORDER".equals(type)
            && "/api/app/trade-in/capacity-keep".equals(path) && "server".equals(detail.path("decisionSource").asText())
            && Objects.equals(detail.path("after").path("orderNo").asText(),fact.orderNo())
            && auditAmount(detail.path("after").path("walletDebitUsdt"),fact);
    }
    private static boolean auditAmount(com.fasterxml.jackson.databind.JsonNode amount,Fact fact) {
        return amount.isNumber() && amount.decimalValue().compareTo(fact.amount())==0;
    }
    private static long auditNumber(Map<String,Object> row,String key) {
        try{return number(row,key);} catch(NumberFormatException ex) {return 0;}
    }

    private RegistrationBoundary registrationBoundary(List<Long> ids) {
        var births=new HashMap<Long,BirthEvidence>();
        var problems=new TreeMap<Long,List<String>>();
        for(Long id:ids)problems.put(id,new ArrayList<>(List.of("NEW_ACCOUNT_BIRTH_NOT_PROVEN")));
        final LocalDateTime readAt;
        try {readAt=mapper.historyNowUtc();}
        catch(DataAccessException ex) {return birthFailure(births,problems,"BIRTH_CLOCK_SOURCE_READ_FAILED");}
        if(readAt==null || readAt.getYear()<1 || readAt.getYear()>9999 || readAt.getNano()%1000!=0)
            return birthFailure(births,problems,"BIRTH_CLOCK_NOT_PROVEN");
        final List<BirthEvidence> rows;
        try { rows=capturedHistory.readBirths(ids); }
        catch(DataAccessException ex) { return birthFailure(births,problems,"BIRTH_SOURCE_READ_FAILED"); }
        catch(IllegalStateException ex) {
            if(!"INVALID_CAPTURE_HISTORY_BIRTH".equals(ex.getMessage()))throw ex;
            return birthFailure(births,problems,"INVALID_NEW_ACCOUNT_BIRTH");
        }
        if(rows==null)return birthFailure(births,problems,"INVALID_NEW_ACCOUNT_BIRTH");
        for(var row:rows) {
            if(row==null || row.customerId()==null || !ids.contains(row.customerId()))
                throw failure("CAPTURED_CUSTOMER_OUTSIDE_SCOPE");
            if(births.putIfAbsent(row.customerId(),row)!=null)
                return birthFailure(births,problems,"INVALID_NEW_ACCOUNT_BIRTH");
            var reasons=problems.get(row.customerId());reasons.clear();
            if(!"support-payment-attribution-v1".equals(row.captureProtocol())
                    || !"AUTH_NEW_ACCOUNT_REGISTRATION".equals(row.birthOrigin()) || row.birthDbUtc()==null
                    || row.birthDbUtc().getYear()<1 || row.birthDbUtc().getYear()>9999
                    || row.birthDbUtc().getNano()%1000!=0
                    || !Integer.valueOf(0).equals(row.sandboxAtBirth()) || !"PRODUCTION".equals(row.environmentStatus()))
                reasons.add("INVALID_NEW_ACCOUNT_BIRTH");
            else if(row.birthDbUtc().isAfter(readAt))reasons.add("NEW_ACCOUNT_BIRTH_AFTER_HISTORY_SNAPSHOT");
        }
        return new RegistrationBoundary(births,problems,readAt);
    }
    private static RegistrationBoundary birthFailure(Map<Long,BirthEvidence> births,Map<Long,List<String>> problems,String reason) {
        problems.values().forEach(values -> {values.clear();values.add(reason);});
        return new RegistrationBoundary(births,problems,null);
    }
    private static String lifecycleProblem(BirthEvidence birth,Envelope envelope,Fact fact) {
        if(birth.birthDbUtc()==null)return "INVALID_NEW_ACCOUNT_BIRTH";
        var born=birth.birthDbUtc().toInstant(ZoneOffset.UTC);
        if(envelope.captureDbUtc().toInstant(ZoneOffset.UTC).isBefore(born))return "CAPTURE_PREDATES_NEW_ACCOUNT";
        var at=fact.succeededAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant();
        long quantum=1;for(int p=fact.fractionalSecondDigits();p<9;p++)quantum*=10;
        // Overlap is accepted only here, after the persisted causal NEW proof and real source have both verified.
        return !at.plusNanos(quantum).isAfter(born)?"PAYMENT_PREDATES_NEW_ACCOUNT":null;
    }
    private record RegistrationBoundary(Map<Long,BirthEvidence> births,Map<Long,List<String>> problems,LocalDateTime readAtUtc) { }

    private void requireHistorySnapshot() {
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
            || !(resource instanceof ConnectionHolder holder))throw failure("SUPPORT_PAYMENT_HISTORY_SNAPSHOT_REQUIRED");
        try {
            if(holder.getConnection().getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)
                throw failure("SUPPORT_PAYMENT_HISTORY_SNAPSHOT_REQUIRED");
        } catch(SQLException ex) { throw new IllegalStateException("SUPPORT_PAYMENT_HISTORY_SNAPSHOT_REQUIRED",ex); }
    }
    private static List<Source> historySources() {
        return List.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY,Source.WALLET_ORDER,
            Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT,Source.ORDER_REFUND,Source.GENESIS_PRIMARY,Source.GENESIS_SECONDARY);
    }
    private static void historyReadFailure(List<Issue> issues) {
        for(var source:historySources())issues.add(new Issue(source,null,"SOURCE_READ_FAILED"));
    }
    private static Source historySource(String source) {
        try { Source value=Source.valueOf(source);return historySources().contains(value)?value:null; }
        catch(IllegalArgumentException | NullPointerException ex) { return null; }
    }
    private static Source historyIssueSource(Envelope envelope) {
        Source source=historySource(envelope.identity().source());
        // Only a defined refund kind/source can isolate a malformed capture to refund completeness.
        if(source==Source.ORDER_REFUND && !Kind.DEVICE_PURCHASE_REFUND.name().equals(envelope.identity().kind()))return null;
        return source;
    }
    private static boolean validHistoryPartition(Source source,String partition) {
        return source==Source.DEPOSIT_ORDER?partition!=null && partition.matches("[1-9][0-9]*")
            :source==Source.VIETQR?partition!=null && !partition.isBlank() && partition.length()<=192:partition==null;
    }
    private static Map<String,Object> proof(Envelope e) {
        var result=new HashMap<String,Object>();result.put("captureMode",e.captureMode());result.put("schemaVersion",e.captureSchemaVersion());
        result.put("evidenceCaptureMode",e.evidenceCaptureMode());result.put("evidenceSchemaVersion",e.evidenceSchemaVersion());
        result.put("sourcePartition",e.sourcePartition());result.put("sourceFactJson",e.sourceFactJson());result.put("beforeSourceJson",e.beforeSourceJson());
        return result;
    }
    private boolean validBeforeTypes(String beforeJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(beforeJson);
            var version=node.get("sourceVersion");var sourceIds=node.get("sourceIds");
            if(version==null || !version.isNull() && !version.isTextual() || sourceIds==null || !sourceIds.isArray())return false;
            for(var id:sourceIds)if(!id.isTextual() || id.textValue().isBlank())return false;
            return true;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("INVALID_PERSISTED_SOURCE_PROOF");}
    }
    private static boolean matchesIdentity(Envelope e,Fact f) {
        var i=e.identity();
        return Objects.equals(i.factId(),f.factId()) && i.customerId()==f.customerId() && Objects.equals(i.kind(),f.kind().name())
            && Objects.equals(i.source(),f.source().name()) && i.ledgerId()==f.ledgerId()
            && Objects.equals(i.sourceBusinessId(),f.sourceBusinessId()) && Objects.equals(i.orderNo(),f.orderNo())
            && Objects.equals(i.orderType(),f.orderType()) && Objects.equals(i.originalFactId(),f.originalFactId())
            && Objects.equals(i.currency(),f.currency()) && i.amount()!=null && i.amount().compareTo(f.amount())==0
            && Objects.equals(i.succeededAt(),f.succeededAt()) && Objects.equals(i.sourceBusinessZone(),DateTimeFormatConfig.BUSINESS_ZONE.getId())
            && Objects.equals(i.successTimeField(),f.successTimeField()) && i.fractionalSecondDigits()==f.fractionalSecondDigits();
    }
    private static String ledgerProblem(Map<String,Object> row,Fact fact) {
        if(number(row,"customerId")!=fact.customerId() || !Objects.equals(text(row,"businessId"),SupportPaymentSourceSql.ledgerKey(fact.source(),fact.sourceBusinessId()))
            || !"USDT".equals(text(row,"currency")) || !"USDT".equals(fact.currency()) || decimal(row,"amount")==null
            || decimal(row,"amount").compareTo(fact.amount())!=0 || !Objects.equals(time(row,"successAt"),fact.ledgerRecordedAt()))
            return "SETTLEMENT_MISMATCH";
        if(!SupportPaymentSourceSql.ledgerType(fact.source()).equals(text(row,"ledgerType")))return "SETTLEMENT_TYPE_MISMATCH";
        if(!SupportPaymentSourceSql.ledgerDirection(fact.source()).equals(text(row,"direction")) || number(row,"deleted")!=0
            || !(fact.source()==Source.TRIAL_CONVERT?"POSTED":"SUCCESS").equals(text(row,"status")))return "UNSUCCESSFUL_SETTLEMENT";
        return null;
    }
    private record CregisReference(long projectId,long cid) {}
    private record ProjectionReference(long customerId,String businessId) {}
    private static Source historyRootSource(Source source) {
        return switch(source) {case TRADE_IN,CAPACITY_KEEP,ORDER_REFUND -> Source.WALLET_ORDER;default -> source;};
    }
    /** Per physical snapshot only. Duplicate rows and read failures remain visible at the old validation step. */
    private record HistoryRows(Map<Object,List<Map<String,Object>>> byReference,DataAccessException failure) {
        private static HistoryRows load(java.util.function.Supplier<List<Map<String,Object>>> query,
                java.util.function.Function<Map<String,Object>,Object> reference) {
            try {
                var index=new HashMap<Object,List<Map<String,Object>>>();
                for(var row:query.get())index.computeIfAbsent(reference.apply(row),ignored -> new ArrayList<>()).add(row);
                return new HistoryRows(index,null);
            } catch(DataAccessException ex) {return new HistoryRows(Map.of(),ex);}
        }
        private List<Map<String,Object>> get(Object reference) {
            if(failure!=null)throw failure;
            return byReference.getOrDefault(reference,List.of());
        }
    }
    private record HistorySourceReads(Map<Source,HistoryRows> roots,Map<Source,HistoryRows> projections,
            Map<SupportPaymentSourceMapper.Marker,HistoryRows> markers) {
        private List<Map<String,Object>> roots(Source source,String key) {return roots.get(historyRootSource(source)).get(key);}
        private List<Map<String,Object>> projections(Source source,ProjectionReference key) {return projections.get(source).get(key);}
        private List<Map<String,Object>> markers(SupportPaymentSourceMapper.Marker marker,Object key) {
            var rows=markers.get(marker);return rows==null?List.of():rows.get(key);
        }
    }
    private HistorySourceReads historySourceReads(List<Long> ids,Map<Envelope,Fact> decoded) {
        var rootKeys=new EnumMap<Source,Set<String>>(Source.class);
        var projectionKeys=new EnumMap<Source,Set<String>>(Source.class);
        var markerRefs=new EnumMap<SupportPaymentSourceMapper.Marker,Set<Map<String,Object>>>(SupportPaymentSourceMapper.Marker.class);
        for(var entry:decoded.entrySet()) {
            var f=entry.getValue();var source=f.source();String key=f.sourceBusinessId();
            rootKeys.computeIfAbsent(historyRootSource(source),ignored -> new LinkedHashSet<>()).add(source==Source.ORDER_REFUND?f.orderNo():key);
            projectionKeys.computeIfAbsent(source,ignored -> new LinkedHashSet<>()).add(key);
            switch(source) {
                case DEPOSIT_ORDER -> {
                    try {
                        long project=Long.parseLong(entry.getKey().sourcePartition()),cid=Long.parseLong(key.substring(3));
                        if(project>0 && cid>0)addMarkerReference(markerRefs,CREGIS_EVENT,Map.of("projectId",project,"cid",cid));
                    } catch(NumberFormatException ex) { /* Original link predicate reports this at its existing step. */ }
                }
                case VIETQR,HDPAY -> addMarkerReference(markerRefs,INTENT,Map.of("key",source==Source.VIETQR?entry.getKey().sourcePartition():key));
                case WALLET_ORDER -> addMarkerReference(markerRefs,PAYMENT,Map.of("key",f.orderNo()));
                case CARD_TOPUP -> addMarkerReference(markerRefs,CARD_SETTLEMENT,Map.of("key",key));
                case TRIAL_CONVERT -> rootKeys.computeIfAbsent(Source.WALLET_ORDER,ignored -> new LinkedHashSet<>()).add(f.orderNo());
                default -> { }
            }
        }
        var roots=new EnumMap<Source,HistoryRows>(Source.class);
        rootKeys.forEach((source,keys) -> roots.put(source,HistoryRows.load(
            () -> SupportPaymentFacts.genesis(source)?genesisBefore(List.copyOf(keys)):mapper.historyBeforeBatch(source,ids,List.copyOf(keys)),row -> text(row,"businessId"))));
        var trials=roots.get(Source.TRIAL_CONVERT);
        if(trials!=null && trials.failure()==null)for(var rows:trials.byReference().values())for(var row:rows) {
            long device=number(row,"deviceId");
            if(device>0)addMarkerReference(markerRefs,DEVICE,Map.of("id",device));
        }
        var markers=new EnumMap<SupportPaymentSourceMapper.Marker,HistoryRows>(SupportPaymentSourceMapper.Marker.class);
        markerRefs.forEach((marker,refs) -> markers.put(marker,HistoryRows.load(
            () -> mapper.historyMarkerBatch(marker,ids,List.copyOf(refs)),row -> switch(marker) {
                case CREGIS_EVENT -> new CregisReference(number(row,"projectId"),number(row,"cid"));
                case DEVICE -> number(row,"id");case PAYMENT -> text(row,"orderNo");default -> text(row,"businessId");
            })));
        var projections=new EnumMap<Source,HistoryRows>(Source.class);
        projectionKeys.forEach((source,keys) -> projections.put(source,HistoryRows.load(
            () -> SupportPaymentFacts.genesis(source)?genesisSettled(source,List.copyOf(keys)):mapper.historySettledBatch(source,ids,List.copyOf(keys)),row -> new ProjectionReference(number(row,"customerId"),text(row,"businessId")))));
        return new HistorySourceReads(roots,projections,markers);
    }
    private static void addMarkerReference(Map<SupportPaymentSourceMapper.Marker,Set<Map<String,Object>>> refs,
            SupportPaymentSourceMapper.Marker marker,Map<String,Object> ref) {
        refs.computeIfAbsent(marker,ignored -> new LinkedHashSet<>()).add(ref);
    }
    private String historySourceProblem(Fact f,String partition,HistorySourceReads reads) {
        Source source=f.source();String key=f.sourceBusinessId();
        if(SupportPaymentFacts.genesis(source)) {
            var rows=reads.projections(source,new ProjectionReference(f.customerId(),key));
            if(rows.size()!=1)return rows.isEmpty()?"MISSING_AUTHORITATIVE_SOURCE":"CONFLICTING_CAPTURED_SOURCE_PROJECTION";
            String problem=invalidNewSource(rows.get(0),source);
            return problem!=null?problem:samePayment(f,fact(rows.get(0),source)) && f.sourceIds().equals(List.of(text(rows.get(0),"sourceId")))?null:"SETTLEMENT_MISMATCH";
        }
        var roots=reads.roots(source,source==Source.ORDER_REFUND?f.orderNo():key);
        if(roots.size()>1)return "CONFLICTING_CAPTURED_SOURCE_PROJECTION";
        var root=roots.isEmpty()?Map.<String,Object>of():roots.get(0);
        if(!root.isEmpty() && (number(root,"customerId")!=f.customerId()
            || !Objects.equals(text(root,"businessId"),source==Source.ORDER_REFUND?f.orderNo():key)))return "SETTLEMENT_MISMATCH";
        String prefix=switch(source) {
            case DEPOSIT_ORDER -> "nx_deposit_order:";case CARD_TOPUP -> "nx_payment_record:";
            case VIETQR -> "nx_vietqr_reconciliation:";case HDPAY -> "nx_hdpay_payin_order:";
            case ORDER_REFUND -> "nx_wallet_ledger:";default -> "nx_order:";
        };
        if(f.sourceIds().size()!=1 || !f.sourceIds().get(0).matches(prefix+"[1-9][0-9]*"))return "MISSING_AUTHORITATIVE_SOURCE";
        if(!root.isEmpty() && source!=Source.TRIAL_CONVERT && source!=Source.ORDER_REFUND
            && !f.sourceIds().contains(prefix+number(root,"id")))return "SETTLEMENT_MISMATCH";
        if(source==Source.WALLET_ORDER || source==Source.TRADE_IN || source==Source.CAPACITY_KEEP) {
            if(root.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            boolean eligibleType=source==Source.WALLET_ORDER
                ? "SINGLE".equals(f.orderType()) || "BUNDLE".equals(f.orderType()) : source.name().equals(f.orderType());
            if(!amountMatches(root,f) || !Objects.equals(time(root,"successAt"),f.succeededAt())
                || !Objects.equals(text(root,"orderNo"),f.orderNo()) || !Objects.equals(f.sourceBusinessId(),f.orderNo())
                || !Objects.equals(text(root,"orderType"),f.orderType()) || !eligibleType)return "SETTLEMENT_MISMATCH";
        }
        if(source==Source.DEPOSIT_ORDER) {
            if(root.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            if(!amountMatches(root,f) || !Objects.equals(text(root,"currency"),f.currency())
                || number(root,"ledgerId")!=f.ledgerId() || !Objects.equals(time(root,"successAt"),f.succeededAt()))return "SETTLEMENT_MISMATCH";
            long project,cid;
            try {project=Long.parseLong(partition);cid=Long.parseLong(key.substring(3));}
            catch(NumberFormatException ex) {return "BROKEN_CREGIS_EVENT_LINK";}
            var events=reads.markers(CREGIS_EVENT,new CregisReference(project,cid));
            if(events.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            if(events.size()!=1)return "BROKEN_CREGIS_EVENT_LINK";
            var event=events.get(0);
            if(number(event,"projectId")!=project || number(event,"cid")!=cid || number(event,"customerId")!=f.customerId()
                || number(event,"ledgerId")!=f.ledgerId() || !"CREDITED".equals(text(event,"status"))
                || !amountMatches(event,f))return "BROKEN_CREGIS_EVENT_LINK";
        } else if(source==Source.VIETQR || source==Source.HDPAY) {
            String intentKey=source==Source.VIETQR?partition:key;
            var intents=reads.markers(INTENT,intentKey);
            if(intents.isEmpty())return "MISSING_INTENT_IDENTITY";
            if(intents.size()!=1 || root.isEmpty())return "BROKEN_INTENT_IDENTITY";
            var intent=intents.get(0);
            if(number(intent,"customerId")!=f.customerId() || !Objects.equals(text(intent,"businessId"),intentKey)
                || number(intent,"deleted")!=0 || !"CREDITED".equals(text(intent,"status"))
                || !(source==Source.VIETQR?"MANUAL":"HDPAY").equals(text(intent,"rail"))
                || !"WALLET_TOPUP".equals(text(intent,"target")) || !amountMatches(intent,f)
                || !amountMatches(root,f) || source==Source.VIETQR && (!Objects.equals(text(root,"intentNo"),partition)
                    || !Objects.equals(time(root,"providerPaidAt"),f.providerPaidAt()))
                || source==Source.HDPAY && (!Objects.equals(text(root,"ledgerBusinessId"),key)
                    || !Objects.equals(time(root,"successAt"),f.succeededAt())))return "BROKEN_INTENT_IDENTITY";
        } else if(source==Source.WALLET_ORDER) {
            var payments=reads.markers(PAYMENT,f.orderNo());
            var confirmations=payments.stream().filter(p -> walletConfirmation(p,f.customerId(),f.orderNo(),text(root,"paymentNo"),f.amount(),f.ledgerId())
                && Objects.equals(time(p,"successAt"),f.sourceConfirmationAt()) && f.sourceConfirmationAt()!=null).toList();
            if(confirmations.size()!=1)return payments.isEmpty()?"MISSING_SOURCE_CONFIRMATION_TIME":"SETTLEMENT_MISMATCH";
        } else if(source==Source.CARD_TOPUP) {
            var settlements=reads.markers(CARD_SETTLEMENT,key);
            if(settlements.isEmpty())return "MISSING_CARD_SETTLEMENT";
            if(root.isEmpty() || settlements.size()!=1)return "SETTLEMENT_MISMATCH";
            var settlement=settlements.get(0);
            if(number(settlement,"customerId")!=f.customerId() || number(settlement,"deleted")!=0
                || !"SETTLED".equals(text(settlement,"status")) || !Objects.equals(text(settlement,"businessId"),key)
                || !Objects.equals(text(settlement,"orderNo"),text(root,"orderNo")) || !amountMatches(settlement,f)
                || !amountMatches(root,f) || !Objects.equals(text(root,"currency"),f.currency())
                || text(root,"provider")==null || text(root,"providerPaymentId")==null
                || !Objects.equals(text(settlement,"provider"),text(root,"provider"))
                || !Objects.equals(text(settlement,"providerPaymentId"),text(root,"providerPaymentId"))
                || number(root,"ledgerId")!=f.ledgerId() || !Objects.equals(time(root,"providerPaidAt"),f.providerPaidAt()))return "SETTLEMENT_MISMATCH";
        } else if(source==Source.TRIAL_CONVERT) {
            if(root.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            var devices=reads.markers(DEVICE,number(root,"deviceId"));
            if(devices==null || devices.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            if(devices.size()!=1)return "BROKEN_SOURCE_LINK";
            var device=devices.get(0);
            if(!"REDEEMED".equals(text(root,"status")) || !amountMatches(root,f)
                || !Objects.equals(time(root,"successAt"),f.sourceConfirmationAt()) || device==null
                || number(root,"deviceId")<=0 || number(device,"id")!=number(root,"deviceId")
                || number(device,"customerId")!=f.customerId() || !Objects.equals(text(device,"orderNo"),f.orderNo()))return "BROKEN_SOURCE_LINK";
            var orders=reads.roots(Source.WALLET_ORDER,f.orderNo());
            if(orders.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            if(orders.size()!=1)return "CONFLICTING_CAPTURED_SOURCE_PROJECTION";
            var order=orders.get(0);
            if(number(order,"customerId")!=f.customerId() || !Objects.equals(text(order,"businessId"),f.orderNo())
                || !Objects.equals(text(order,"orderNo"),f.orderNo()) || !"TRIAL_CONVERT".equals(text(order,"orderType"))
                || !Objects.equals(text(order,"orderType"),f.orderType())
                || !f.sourceIds().contains("nx_order:"+number(order,"id")) || !amountMatches(order,f)
                || !Objects.equals(time(order,"successAt"),f.succeededAt()))return "SETTLEMENT_MISMATCH";
        }
        // Ordinary projections add independent financial checks when retained. Their mutable version is not history.
        var projections=reads.projections(source,new ProjectionReference(f.customerId(),key));
        for(var input:projections) {
            var row=new HashMap<>(input);
            if(source==Source.WALLET_ORDER) {row.put("sourceLinked",1);row.put("sourceConfirmationAt",f.sourceConfirmationAt());}
            String invalid=invalidNewSource(row,source);
            if(invalid!=null)return invalid;
            Fact actual=fact(row,source);
            if(!samePayment(f,actual) || actual.source()!=f.source() || !actual.sourceIds().equals(f.sourceIds())
                || !Objects.equals(actual.sourceBusinessId(),key) || !Objects.equals(actual.orderType(),f.orderType())
                || !Objects.equals(actual.originalFactId(),f.originalFactId()) || !Objects.equals(actual.successTimeField(),f.successTimeField())
                || actual.fractionalSecondDigits()!=f.fractionalSecondDigits() || !Objects.equals(actual.providerPaidAt(),f.providerPaidAt()))
                return "CONFLICTING_CAPTURED_SOURCE_PROJECTION";
        }
        return null;
    }
    private static boolean amountMatches(Map<String,Object> row,Fact fact) {
        return decimal(row,"amount")!=null && decimal(row,"amount").compareTo(fact.amount())==0;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public BeforeSource beforeSource(long customer,Source source,String stableBusinessKey) {
        return beforeSource(customer,source,stableBusinessKey,null);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public BeforeSource beforeSource(long customer,Source source,String stableBusinessKey,String sourcePartition) {
        Object resource=transactionResource();
        validateKey(customer,source,stableBusinessKey);
        if(source==Source.DEPOSIT_ORDER ? sourcePartition==null || !sourcePartition.matches("[1-9][0-9]*")
            :source==Source.VIETQR ? sourcePartition==null || sourcePartition.isBlank() || sourcePartition.length()>192 :sourcePartition!=null)
            throw new IllegalArgumentException("Invalid source partition");
        var rows=currentBefore(source,customer,stableBusinessKey);
        if(rows.isEmpty() && (source==Source.WALLET_ORDER || source==Source.VIETQR || source==Source.HDPAY
                || source==Source.ORDER_REFUND || source==Source.TRIAL_CONVERT && !stableBusinessKey.startsWith("USER:")))
            throw failure("MISSING_SOURCE_IDENTITY");
        if(source==Source.VIETQR) {
            for(var r:rows) {
                boolean orphan=number(r,"customerId")==0 && text(r,"intentNo")==null && "ORPHAN".equals(text(r,"viewType"));
                if(!orphan && (number(r,"customerId")!=customer || !sourcePartition.equals(text(r,"intentNo"))))
                    throw failure("SOURCE_CUSTOMER_MISMATCH");
            }
        } else checkOwners(rows,customer);
        if (rows.size()>1) throw failure("AMBIGUOUS_SOURCE");
        String key=stableBusinessKey;
        if (source==Source.TRIAL_CONVERT && key.startsWith("USER:") && !rows.isEmpty()) key=text(rows.get(0),"businessId");
        var primary=rows.isEmpty()?Map.<String,Object>of():rows.get(0);
        boolean old=!pending(source,primary);
        var ledgers=currentMarkers(LEDGER,mapper.ledgers(SupportPaymentSourceSql.ledgerKey(source,key)));
        checkOwners(ledgers,customer);
        old|=!ledgers.isEmpty() || number(primary,"ledgerId")>0;
        var markers=new ArrayList<Map<String,Object>>(rows);
        markers.addAll(ledgers);
        if (source==Source.DEPOSIT_ORDER) {
            var events=currentMarkers(CREGIS_EVENT,mapper.cregisEvents(Long.parseLong(sourcePartition),Long.parseLong(key.substring(3))));
            checkOwners(events,customer);
            if(events.size()>1) throw failure("AMBIGUOUS_CREGIS_EVENT");
            old|=events.stream().anyMatch(r -> time(r,"successAt")!=null || number(r,"ledgerId")>0 || "CREDITED".equals(text(r,"status")));
            markers.addAll(events);
        } else if (source==Source.CARD_TOPUP) {
            var receipts=currentMarkers(CARD_SETTLEMENT,mapper.cardSettlements(key));checkOwners(receipts,customer);
            old|=receipts.stream().anyMatch(r -> "SETTLED".equals(text(r,"status")) || number(r,"deleted")!=0);
            markers.addAll(receipts);
        } else if (source==Source.WALLET_ORDER) {
            var payments=currentMarkers(PAYMENT,mapper.payments(key));checkOwners(payments,customer);
            old|=payments.stream().anyMatch(SupportPaymentSourceService::succeededMarker);
            markers.addAll(payments);
        } else if (source==Source.VIETQR || source==Source.HDPAY) {
            String intent=source==Source.HDPAY?key:sourcePartition;
            if (intent==null) throw failure("MISSING_INTENT_IDENTITY");
            var intents=currentMarkers(INTENT,mapper.intent(intent));checkOwners(intents,customer);
            if(intents.size()!=1) throw failure("MISSING_INTENT_IDENTITY");
            var canonical=intents.get(0);
            if(number(canonical,"deleted")!=0 || !(source==Source.HDPAY?"HDPAY":"MANUAL").equals(text(canonical,"rail"))
                || !"WALLET_TOPUP".equals(text(canonical,"target"))) throw failure("BROKEN_INTENT_IDENTITY");
            old|=intents.stream().anyMatch(r -> succeededMarker(r) || positiveSettlement(r));
            markers.addAll(intents);
            var intentLedgers=currentMarkers(LEDGER,mapper.ledgers(intent));checkOwners(intentLedgers,customer);old|=!intentLedgers.isEmpty();
        } else if (source==Source.TRIAL_CONVERT && number(primary,"deviceId")>0) {
            var devices=currentMarkers(DEVICE,mapper.device(number(primary,"deviceId")));checkOwners(devices,customer);
            if(devices.size()!=1) throw failure("BROKEN_TRIAL_DEVICE");
            String order=text(devices.get(0),"orderNo");
            if(order!=null) {
                var linked=currentBefore(Source.WALLET_ORDER,customer,order);checkOwners(linked,customer);
                old|=linked.stream().anyMatch(SupportPaymentSourceService::succeededMarker);
                markers.addAll(linked);
            }
        } else if (source==Source.ORDER_REFUND) {
            var bills=currentMarkers(REFUND_BILL,mapper.refundBills("E4-BILL-"+key.substring("E4-REFUND-".length())));
            checkOwners(bills,customer);old|=!bills.isEmpty();markers.addAll(bills);
            var payments=currentMarkers(PAYMENT,mapper.payments(key.substring("E4-REFUND-".length())));checkOwners(payments,customer);
            old|=payments.stream().anyMatch(r -> "REFUNDED".equals(text(r,"status")));markers.addAll(payments);
        }
        var settled=!old || key.startsWith("USER:")?List.<Map<String,Object>>of():settled(source,customer,key);
        Long ledger=ledgers.isEmpty()?(number(primary,"ledgerId")>0?Long.valueOf(number(primary,"ledgerId")):null)
            :Long.valueOf(number(ledgers.get(0),"id"));
        Map<String,Object> prior=settled.size()==1?new HashMap<>(settled.get(0)):null;
        if(prior!=null && source==Source.WALLET_ORDER) confirmPayment(prior,customer);
        Fact existing=prior!=null && invalid(prior,source)==null?fact(prior,source):null;
        LocalDateTime existingAt=existing!=null?existing.succeededAt():!ledgers.isEmpty()?time(ledgers.get(0),"successAt")
            :source==Source.ORDER_REFUND || source==Source.TRIAL_CONVERT?null:time(primary,"successAt");
        var context=new CaptureContext(customer,source,key,sourcePartition,old,ledger,existing==null?null:existing.factId(),
            existingAt,sourceReferences(source,markers),
            text(primary,"sourceVersion"),successField(source),precision(source),
            rows.stream().map(r -> Collections.unmodifiableMap(new HashMap<>(r))).toList());
        var guard=new Guard();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { guard.active.set(false); }
        });
        return new Token(this,resource,guard,context);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Fact> readSettled(BeforeSource before) {
        return readSettled(checkedToken(before),null);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void beforeCash(BeforeSource before) {
        Token token=checkedToken(before);
        if(SupportPaymentFacts.genesis(token.context.source()))throw failure("GENESIS_COMPLETE_PARTIES_REQUIRED");
        if(token.context.old() || token.cash!=null || token.bound!=null)throw failure("PAYMENT_FUND_CASH_NOT_ALLOWED");
        requireCashTransaction(token);
        Map<String,Object> wallet=paymentWallet(token.context.customer());
        if(token.context.source()==Source.TRIAL_CONVERT) {
            String claim=claim(token.context);
            if(earnings.findBySource("H2_TRIAL_REMAINDER",claim+":REMAINDER",token.context.customer())!=null
                || earnings.findBySource("H2_TRIAL_BONUS",claim+":NEX",token.context.customer())!=null)
                throw failure("PAYMENT_FUND_PREEXISTING_TRIAL_REWARD");
        }
        token.cash=new Cash(fund.begin(List.of(token.context.customer())),immutable(wallet));
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void beforeGenesisCash(BeforeSource before,Collection<Long> customers,String holdingNo) {
        Token token=checkedToken(before);var c=token.context;
        if(!SupportPaymentFacts.genesis(c.source()) || c.old() || token.cash!=null || token.bound!=null
                || customers==null || customers.isEmpty() || customers.stream().anyMatch(id->id==null || id<=0))
            throw failure("GENESIS_BEFORE_CASH_INVALID");
        requireCashTransaction(token);var parties=new TreeSet<>(customers);
        Map<String,Object> holding=null;SupportGenesisReadFacade.Source acquisition=null;
        if(c.source()==Source.GENESIS_PRIMARY) {
            if(!parties.equals(Set.of(c.customer())) || holdingNo!=null)throw failure("GENESIS_COMPLETE_PARTIES_REQUIRED");
        } else {
            if(genesis==null || holdingNo==null)throw failure("GENESIS_HOLDING_SOURCE_REQUIRED");
            holding=genesis.readHolding(holdingNo);
            if(holding==null || !holdingNo.equals(text(holding,"holdingNo")) || !"LISTED".equals(text(holding,"status"))
                    || !zeroBit(holding.get("deleted")) || genesisId(holding,"id")<=0 || genesisId(holding,"customerId")<=0 || genesisId(holding,"customerId")==c.customer()
                    || !parties.equals(Set.of(c.customer(),genesisId(holding,"customerId"))))throw failure("GENESIS_COMPLETE_PARTIES_REQUIRED");
            if(text(holding,"orderNo")==null)throw failure("GENESIS_ORIGINAL_HOLDING_ORDER_MISSING");
            var originals=genesis.readByOrderNos(List.of(text(holding,"orderNo")));
            if(originals.size()!=1)throw failure("GENESIS_ORIGINAL_HOLDING_ORDER_MISSING");
            acquisition=originals.get(0);var originalRows=genesisRows(acquisition);
            if(originalRows.size()!=1 || invalidNewSource(originalRows.get(0),Source.valueOf(text(originalRows.get(0),"source")))!=null
                    || genesisId(acquisition.order(),"customerId")!=genesisId(holding,"customerId")
                    || !Objects.equals(text(acquisition.order(),"seriesCode"),text(holding,"seriesCode"))
                    || !Objects.equals(time(acquisition.order(),"paidAt"),time(holding,"acquiredAt"))
                    || decimal(holding,"acquiredPrice")==null || decimal(holding,"acquiredPrice").compareTo(decimal(acquisition.order(),"unitPrice"))!=0)
                throw failure("GENESIS_ORIGINAL_HOLDING_ORDER_MISMATCH");
        }
        var wallets=new TreeMap<Long,Map<String,Object>>();for(long id:parties)wallets.put(id,immutable(paymentWallet(id)));
        token.cash=new Cash(fund.begin(parties),wallets.get(c.customer()));
        token.genesisCash=new GenesisCash(Collections.unmodifiableMap(new TreeMap<>(wallets)),holding==null?null:immutable(holding),acquisition);
    }

    private record GenesisCash(Map<Long,Map<String,Object>> wallets,Map<String,Object> holding,SupportGenesisReadFacade.Source acquisition) {}
    private void bindGenesis(Token token,Fact fact,Receipt receipt) {
        var c=token.context;var captured=token.genesisCash;
        if(captured==null || token.cash==null || receipt==null)throw failure("GENESIS_BEFORE_CASH_REQUIRED");
        var sources=genesis.readByOrderNos(List.of(c.key()));
        if(sources.size()!=1)throw failure("GENESIS_SETTLEMENT_SOURCE_MISSING");
        var settlement=sources.get(0);var rows=genesisRows(settlement);
        if(rows.size()!=1 || invalidNewSource(rows.get(0),c.source())!=null || !samePayment(fact,fact(rows.get(0),c.source())))
            throw failure("GENESIS_SETTLEMENT_SOURCE_MISMATCH");
        var order=settlement.order();var buyer=new Account(c.customer(),"USDT","available");
        var events=new ArrayList<Event>();var deltas=new TreeMap<Long,BigDecimal>();
        deltas.put(c.customer(),fact.amount().negate());
        if(c.source()==Source.GENESIS_PRIMARY) {
            if(!captured.wallets().keySet().equals(Set.of(c.customer())))throw failure("GENESIS_COMPLETE_PARTIES_REQUIRED");
            events.add(new Event(consumeId(fact.factId()),Action.CONSUME,buyer,null,null,fact.amount(),null));
        } else {
            long seller=genesisId(order,"sellerId");BigDecimal fee=decimal(order,"royalty"),net=fact.amount().subtract(fee);
            if(captured.holding()==null || seller!=genesisId(captured.holding(),"customerId")
                    || !Objects.equals(text(order,"holdingNo"),text(captured.holding(),"holdingNo"))
                    || !Objects.equals(text(order,"seriesCode"),text(captured.holding(),"seriesCode"))
                    || decimal(captured.holding(),"listingPrice")==null || fact.amount().compareTo(decimal(captured.holding(),"listingPrice"))!=0
                    || !captured.wallets().keySet().equals(Set.of(c.customer(),seller)))throw failure("GENESIS_ORIGINAL_OWNER_MISMATCH");
            events.add(new Event(consumeId(fact.factId()),Action.TRADE,buyer,new Account(seller,"USDT","available"),null,fact.amount(),net));
            deltas.put(seller,net);
        }
        var after=new TreeMap<Long,Map<String,Object>>();var trace=new ArrayList<Map<String,Object>>();
        var eventIds=events.stream().map(Event::id).toList();
        for(var entry:captured.wallets().entrySet()) {
            long id=entry.getKey();var before=entry.getValue();var current=immutable(paymentWallet(id));after.put(id,current);
            String incarnation=text(before,"incarnation");
            if(!Objects.equals(incarnation,text(current,"incarnation")) || number(before,"id")!=number(current,"id")
                    || number(current,"revision")!=number(before,"revision")+1 || number(current,"version")!=number(before,"version")+1)
                throw failure("GENESIS_CASH_TRACE_MISMATCH");
            var rawRows=fundMapper.journal(incarnation,number(before,"revision"),number(current,"revision"));
            if(rawRows==null || rawRows.size()!=1)throw failure("GENESIS_CASH_TRACE_MISMATCH");
            var raw=rawRows.get(0);
            if(!"CHANGE".equals(text(raw,"observation")) || !incarnation.equals(text(raw,"incarnation"))
                    || number(raw,"revision")!=number(current,"revision") || number(raw,"oldId")!=number(before,"id")
                    || number(raw,"newId")!=number(current,"id") || number(raw,"oldUserId")!=id || number(raw,"newUserId")!=id
                    || number(raw,"oldVersion")!=number(before,"version") || number(raw,"newVersion")!=number(current,"version")
                    || flag(raw,"oldSandbox")!=0 || flag(raw,"newSandbox")!=0 || flag(raw,"oldIsDeleted")!=0 || flag(raw,"newIsDeleted")!=0)
                throw failure("GENESIS_CASH_TRACE_MISMATCH");
            for(String field:List.of("usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld")) {
                String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);
                if(decimal(raw,"old"+cap)==null || decimal(raw,"new"+cap)==null
                        || decimal(raw,"old"+cap).compareTo(decimal(before,field))!=0 || decimal(raw,"new"+cap).compareTo(decimal(current,field))!=0
                        || decimal(current,field).subtract(decimal(before,field)).compareTo(field.equals("usdtAvailable")?deltas.get(id):BigDecimal.ZERO)!=0)
                    throw failure("GENESIS_CASH_TRACE_MISMATCH");
            }
            var ledger=settlement.ledgers().stream().filter(l->genesisId(l,"customerId")==id).toList();
            if(ledger.size()!=1 || decimal(ledger.get(0),"balanceAfter")==null
                    || decimal(ledger.get(0),"balanceAfter").compareTo(decimal(current,"usdtAvailable"))!=0)throw failure("GENESIS_LEDGER_CASH_MISMATCH");
            trace.add(Map.of("customerId",id,"incarnation",incarnation,"revision",number(current,"revision"),"eventIds",eventIds,
                "raw",immutable(raw),"sourceIds",List.of("nx_wallet_ledger:"+genesisId(ledger.get(0),"id"))));
        }
        var source=new LinkedHashMap<String,Object>();source.put("schemaVersion","SUPPORT_GENESIS_FUND_V1");
        source.put("customerId",c.customer());source.put("factId",fact.factId());source.put("source",c.source().name());
        source.put("orderNo",c.key());source.put("currency",fact.currency());source.put("amount",fact.amount());
        source.put("succeededAt",fact.succeededAt());source.put("fractionalSecondDigits",0);source.put("businessZone",factBusinessZone());source.put("settlement",genesisSnapshot(settlement));
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","SUPPORT_GENESIS_FUND_V1");
        proof.put("beforeHolding",captured.holding());proof.put("acquisitionSource",captured.acquisition()==null?null:genesisSnapshot(captured.acquisition()));
        proof.put("cashBefore",captured.wallets());proof.put("cashAfter",after);proof.put("cashTrace",trace);
        String key="GENESIS:"+c.key();var input=new ReceiptInput(key,"SUPPORT_GENESIS",text(order,"orderType"),"nx_genesis_order",c.key(),"PRODUCTION",encodeFund(events),encodeFund(source),encodeFund(proof));
        if(token.bound!=null) {
            if(!sameInput(token.boundInput,input))throw failure("GENESIS_COMPLETED_RECEIPT_CHANGED");
            return;
        }
        token.bound=fund.bind(token.cash.token(),input);token.boundInput=input;
        if(token.bound==null || !key.equals(token.bound.operationKey()) || token.bound.journalLegs()!=captured.wallets().size())throw failure("GENESIS_BOUND_RECEIPT_MISMATCH");
    }
    private static String factBusinessZone() {return DateTimeFormatConfig.BUSINESS_ZONE.getId();}
    private static Map<String,Object> genesisSnapshot(SupportGenesisReadFacade.Source source) {
        return Map.of("order",source.order(),"ledgers",source.ledgers());
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void trialReward(BeforeSource before,String entryNo) {
        Token token=checkedToken(before);
        if(token.context.source()!=Source.TRIAL_CONVERT || token.cash==null || token.bound!=null || entryNo==null)
            throw failure("PAYMENT_FUND_TRIAL_REWARD_INVALID");
        var entry=earnings.lockRecoveryEntry(entryNo);
        validateReward(token,entry);
        if(token.rewards.stream().anyMatch(r->r.entryNo().equals(entryNo) || r.asset().equals(entry.asset())))
            throw failure("PAYMENT_FUND_TRIAL_REWARD_DUPLICATE");
        if(!token.rewards.isEmpty() && !"NEX".equals(entry.asset()))throw failure("PAYMENT_FUND_TRIAL_REWARD_ORDER");
        token.rewards.add(entry);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public FreshLedgerReceipt insertFreshPaymentLedger(BeforeSource before,BigDecimal amount,BigDecimal balanceAfter,String remark) {
        Token token=checkedToken(before);var c=token.context;
        if(c.old() || c.key().startsWith("USER:") || token.receipt.get()!=null) throw failure("FRESH_LEDGER_NOT_ALLOWED");
        if(amount==null || amount.signum()<=0 || balanceAfter==null) throw new IllegalArgumentException("Actual positive payment required");
        if(token.cash==null)throw failure("PAYMENT_FUND_BEFORE_CASH_REQUIRED");
        var write=new HashMap<String,Object>();
        write.put("customer",c.customer());write.put("key",SupportPaymentSourceSql.ledgerKey(c.source(),c.key()));write.put("amount",amount);
        write.put("balanceAfter",balanceAfter);write.put("remark",remark);
        if(mapper.insertLedger(c.source(),write)!=1) throw failure("FRESH_LEDGER_INSERT_REQUIRED");
        long id=number(write,"id");if(id<=0) throw failure("FRESH_LEDGER_GENERATED_KEY_REQUIRED");
        var actual=mapper.currentMarker(LEDGER,id);
        if(actual==null || number(actual,"id")!=id || number(actual,"customerId")!=c.customer()
            || !SupportPaymentSourceSql.ledgerKey(c.source(),c.key()).equals(text(actual,"businessId")) || !SupportPaymentSourceSql.ledgerType(c.source()).equals(text(actual,"ledgerType"))
            || !SupportPaymentSourceSql.ledgerDirection(c.source()).equals(text(actual,"direction"))
            || !(c.source()==Source.TRIAL_CONVERT?"POSTED":"SUCCESS").equals(text(actual,"status"))
            || !"USDT".equals(text(actual,"currency")) || number(actual,"deleted")!=0
            || decimal(actual,"amount")==null || decimal(actual,"amount").compareTo(amount)!=0
            || decimal(actual,"balanceAfter")==null || decimal(actual,"balanceAfter").compareTo(balanceAfter)!=0
            || !Objects.equals(text(actual,"remark"),remark) || time(actual,"successAt")==null || time(actual,"updatedAt")==null)
            throw failure("FRESH_LEDGER_WRITE_MISMATCH");
        Receipt receipt=new Receipt(token,id,Collections.unmodifiableMap(new HashMap<>(actual)));
        token.receipt.set(receipt);
        return receipt;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Fact> readSettled(BeforeSource before,FreshLedgerReceipt receipt) {
        Token token=checkedToken(before);
        if(!(receipt instanceof Receipt actual) || actual.token!=token || token.receipt.get()!=actual || token.context.old())
            throw failure("INVALID_FRESH_LEDGER_RECEIPT");
        var current=mapper.currentMarker(LEDGER,actual.id);
        if(!actual.tuple.equals(current)) throw failure("FRESH_LEDGER_WRITE_MISMATCH");
        return readSettled(token,actual);
    }

    private Token checkedToken(BeforeSource before) {
        Object resource=transactionResource();
        if(!(before instanceof Token token) || token.owner!=this || token.resource!=resource || token.thread!=Thread.currentThread() || !token.guard.active.get())
            throw failure("INVALID_BEFORE_SOURCE_TRANSACTION");
        if(token.cash!=null)requireCashTransaction(token);
        return token;
    }

    private void requireCashTransaction(Token token) {
        if(!(token.resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction() || token.connection==null)
            throw failure("PAYMENT_FUND_PHYSICAL_TRANSACTION_REQUIRED");
        try {
            if(holder.getConnection()!=token.connection || token.connection.isClosed() || token.connection.isReadOnly() || token.connection.getAutoCommit())
                throw failure("PAYMENT_FUND_PHYSICAL_TRANSACTION_REQUIRED");
        } catch(SQLException ex) {throw new IllegalStateException("PAYMENT_FUND_CONNECTION_FAILED",ex);}
    }

    private Optional<Fact> readSettled(Token token,Receipt receipt) {
        var c=token.context;
        if(c.key().startsWith("USER:")) throw failure("MISSING_TRIAL_CLAIM");
        var current=currentBefore(c.source(),c.customer(),c.key());
        checkOwners(current,c.customer());
        if(current.size()!=1 && c.source()!=Source.ORDER_REFUND) throw failure("MISSING_OR_AMBIGUOUS_SOURCE");
        if(!c.rows().isEmpty() && (current.size()!=1 || number(current.get(0),"id")!=number(c.rows().get(0),"id")))
            throw failure("SOURCE_IDENTITY_CHANGED");
        if(!current.isEmpty()) requireSettledState(c.source(),current.get(0));
        if(c.source()==Source.VIETQR && !c.partition().equals(text(current.get(0),"intentNo")))
            throw failure("SOURCE_INTENT_CHANGED");
        var rows=settled(c.source(),c.customer(),c.key());
        if(rows.isEmpty()) throw failure("MISSING_SETTLED_SOURCE");
        var facts=new LinkedHashMap<String,Fact>();
        boolean excluded=false;
        for(var input:rows) {
            var row=new HashMap<>(input);
            if (!c.source().name().equals(text(row,"source")) || number(row,"customerId")!=c.customer()
                    || !c.key().equals(text(row,"businessId"))) throw failure("SOURCE_IDENTITY_MISMATCH");
            if(c.source()==Source.WALLET_ORDER) confirmPayment(row,c.customer());
            if(c.source()==Source.CARD_TOPUP) confirmCard(row,c.customer());
            if(number(row,"excludedEnvironment")==1 || (Kind.DEVICE_PURCHASE.name().equals(text(row,"kind"))
                    && decimal(row,"amount")!=null && decimal(row,"amount").signum()==0
                    && (decimal(row,"ledgerAmount")==null || decimal(row,"ledgerAmount").signum()==0))) {
                excluded=true;continue;
            }
            if(!c.old() && receipt==null) throw failure("FRESH_LEDGER_RECEIPT_REQUIRED");
            if(receipt!=null && (number(row,"ledgerId")!=receipt.id
                || decimal(row,"ledgerAmount")==null || decimal(row,"ledgerAmount").compareTo(decimal(receipt.tuple,"amount"))!=0
                || !Objects.equals(time(row,"ledgerRecordedAt"),time(receipt.tuple,"successAt"))))
                throw failure("FRESH_LEDGER_SOURCE_MISMATCH");
            String problem=c.old()?invalid(row,c.source()):invalidNewSource(row,c.source());
            if(c.old() && "CONFLICTING_SUCCESS_TIME".equals(problem) && trustedCapturedSourceProof(row,c.source(),c.partition()))
                problem=invalidNewSource(row,c.source());
            if(problem!=null) throw failure(problem);
            Fact candidate=fact(row,c.source());
            Fact prior=facts.putIfAbsent(candidate.factId(),candidate);
            if(prior!=null && !samePayment(prior,candidate)) throw failure("CONFLICTING_FACT_PROJECTION");
        }
        if(excluded && !facts.isEmpty()) throw failure("CONFLICTING_SOURCE_ENVIRONMENT");
        if(facts.isEmpty()) {
            if(c.source()==Source.TRIAL_CONVERT && !c.old() && token.cash!=null)bindTrialZero(token,rows);
            return Optional.empty();
        }
        if(facts.size()!=1) throw failure("DUPLICATE_SETTLEMENT_LEDGER");
        Fact result=facts.values().iterator().next();
        if(c.source()==Source.DEPOSIT_ORDER) {
            var events=currentMarkers(CREGIS_EVENT,mapper.cregisEvents(Long.parseLong(c.partition()),Long.parseLong(c.key().substring(3))));
            checkOwners(events,c.customer());
            if(events.size()!=1 || !"CREDITED".equals(text(events.get(0),"status"))
                || number(events.get(0),"projectId")!=Long.parseLong(c.partition())
                || number(events.get(0),"cid")!=Long.parseLong(c.key().substring(3))
                || number(events.get(0),"ledgerId")!=result.ledgerId()
                || decimal(events.get(0),"amount")==null || decimal(events.get(0),"amount").compareTo(result.amount())!=0)
                throw failure("BROKEN_CREGIS_EVENT_LINK");
        }
        if(c.source()==Source.VIETQR || c.source()==Source.HDPAY) confirmIntent(c,result,rows.get(0));
        Fact original=c.source()==Source.ORDER_REFUND?checkRefund(c,result):null;
        if(!c.old()) {
            if(SupportPaymentFacts.genesis(c.source()))bindGenesis(token,result,receipt);
            else bindPayment(token,result,rows.get(0),receipt,original);
        } else if(!SupportPaymentFacts.genesis(c.source()))verifyOldFund(result,c);
        return Optional.of(result);
    }

    private void confirmPayment(Map<String,Object> row,long customer) {
        var all=currentMarkers(PAYMENT,mapper.payments(text(row,"orderNo")));checkOwners(all,customer);
        var matches=all.stream().filter(p -> walletConfirmation(p,customer,text(row,"orderNo"),text(row,"paymentNo"),
            decimal(row,"amount"),number(row,"ledgerId"))).toList();
        row.put("sourceLinked",matches.size()==1?1:0);
        row.put("sourceConfirmationAt",matches.size()==1?time(matches.get(0),"successAt"):null);
    }
    private static boolean walletConfirmation(Map<String,Object> payment,long customer,String order,String paymentNo,BigDecimal amount,long ledger) {
        return number(payment,"customerId")==customer && number(payment,"deleted")==0
            && "NEXGRID_WALLET".equals(text(payment,"provider")) && "USDT".equals(text(payment,"currency"))
            && Objects.equals(text(payment,"orderNo"),order) && Objects.equals(text(payment,"businessId"),paymentNo)
            && Set.of("PAID","CONFIRMED","SUCCESS","REFUNDED").contains(Objects.toString(payment.get("status"),""))
            && (payment.get("ledgerId")==null || number(payment,"ledgerId")>0 && number(payment,"ledgerId")==ledger)
            && amount!=null && decimal(payment,"amount")!=null && decimal(payment,"amount").compareTo(amount)==0
            && time(payment,"successAt")!=null;
    }
    private void confirmCard(Map<String,Object> row,long customer) {
        var receipts=currentMarkers(CARD_SETTLEMENT,mapper.cardSettlements(text(row,"businessId")));checkOwners(receipts,customer);
        var matches=receipts.stream().filter(r -> number(r,"deleted")==0 && "SETTLED".equals(text(r,"status"))
            && Objects.equals(text(r,"orderNo"),text(row,"sourceOrderNo"))
            && Objects.equals(text(r,"provider"),text(row,"provider"))
            && Objects.equals(text(r,"providerPaymentId"),text(row,"providerPaymentId"))
            && decimal(r,"amount")!=null && decimal(r,"amount").compareTo(decimal(row,"amount"))==0).toList();
        if(matches.size()!=1) throw failure("MISSING_CARD_SETTLEMENT");
    }
    private void confirmIntent(CaptureContext c,Fact fact,Map<String,Object> row) {
        String intent=c.source()==Source.VIETQR?c.partition():c.key();
        if(!intent.equals(text(row,"duplicateKey"))) throw failure("BROKEN_INTENT_IDENTITY");
        var intents=currentMarkers(INTENT,mapper.intent(intent));checkOwners(intents,c.customer());
        if(intents.size()!=1) throw failure("MISSING_INTENT_IDENTITY");
        var i=intents.get(0);
        if(number(i,"deleted")!=0 || !"CREDITED".equals(text(i,"status"))
            || !(c.source()==Source.VIETQR?"MANUAL":"HDPAY").equals(text(i,"rail"))
            || !"WALLET_TOPUP".equals(text(i,"target")) || decimal(i,"amount")==null
            || decimal(i,"amount").compareTo(fact.amount())!=0) throw failure("BROKEN_INTENT_IDENTITY");
        if(c.source()==Source.VIETQR) {
            // The canonical ledger is already complete; an old RR view must not hide a counterpart.
            var counterpart=mapper.settledVietqrCounterpart(intent);checkOwners(counterpart,c.customer());
            if(!counterpart.isEmpty()) throw failure("DUPLICATE_PAYMENT_SOURCE");
        }
    }
    private Fact checkRefund(CaptureContext c,Fact refund) {
        var orders=currentBefore(Source.WALLET_ORDER,c.customer(),refund.orderNo());checkOwners(orders,c.customer());
        if(orders.size()!=1) throw failure("UNPROVEN_ORIGINAL_PAYMENT");
        String type=text(orders.get(0),"orderType");
        Source source=switch(type) {case "TRADE_IN"->Source.TRADE_IN;case "CAPACITY_KEEP"->Source.CAPACITY_KEEP;
            case "TRIAL_CONVERT"->Source.TRIAL_CONVERT;case "SINGLE","BUNDLE"->Source.WALLET_ORDER;
            default->throw failure("UNPROVEN_ORIGINAL_PAYMENT");};
        var originalRows=source==Source.TRIAL_CONVERT?currentSettlements(source,c.customer(),mapper.trialOrder(List.of(c.customer()),refund.orderNo()))
            :settled(source,c.customer(),refund.orderNo());
        if(originalRows.size()!=1) throw failure("UNPROVEN_ORIGINAL_PAYMENT");
        var originalRow=new HashMap<>(originalRows.get(0));
        if(source==Source.WALLET_ORDER) confirmPayment(originalRow,c.customer());
        String problem=invalid(originalRow,source);
        if("CONFLICTING_SUCCESS_TIME".equals(problem) && trustedCapturedSourceProof(originalRow,source,null))
            problem=invalidNewSource(originalRow,source);
        if(problem!=null) throw failure(problem);
        Fact original=fact(originalRow,source);
        if(original.customerId()!=refund.customerId() || !original.currency().equals(refund.currency())
            || !original.factId().equals(refund.originalFactId())) throw failure("UNPROVEN_ORIGINAL_PAYMENT");
        if(source==Source.TRIAL_CONVERT && settled(source,c.customer(),original.sourceBusinessId()).size()!=1)
            throw failure("DUPLICATE_SETTLEMENT_LEDGER");
        var refunds=new LinkedHashMap<String,Fact>();
        for(var row:currentSettlements(Source.ORDER_REFUND,c.customer(),mapper.refunds(List.of(c.customer()),refund.orderNo()))) {
            problem=invalid(row,Source.ORDER_REFUND);
            if(problem!=null) throw failure(problem);
            Fact related=fact(row,Source.ORDER_REFUND);
            if(related.customerId()!=original.customerId() || !related.currency().equals(original.currency())
                    || !related.originalFactId().equals(original.factId())) throw failure("UNPROVEN_ORIGINAL_PAYMENT");
            if(related.succeededAt().withNano(0).isBefore(original.succeededAt().withNano(0)))
                throw failure("REFUND_PREDATES_ORIGINAL_PAYMENT");
            Fact previous=refunds.putIfAbsent(related.factId(),related);
            if(previous!=null && !samePayment(previous,related)) throw failure("CONFLICTING_FACT_PROJECTION");
        }
        if(!refunds.containsKey(refund.factId())) throw failure("MISSING_REFUND_SOURCE");
        BigDecimal sum=refunds.values().stream().map(Fact::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(sum.compareTo(original.amount())>0) throw failure("REFUND_EXCEEDS_ORIGINAL_AMOUNT");
        return original;
    }
    private static final String FUND_PROOF="SUPPORT_PAYMENT_FUND_V1";
    private record Cash(SupportFundJournalFacade.Before token,Map<String,Object> wallet) {}
    private record CashStep(String sourceId,String eventId,BigDecimal usdt,BigDecimal nex) {}
    private enum OriginalFundEvidence { VERIFIED, UNKNOWN }

    private Map<String,Object> paymentWallet(long customer) {
        var rows=fundMapper.lockWallet(customer);
        if(rows==null || rows.size()!=1)throw failure("PAYMENT_FUND_WALLET_MISSING");
        var wallet=rows.get(0);
        if(number(wallet,"userId")!=customer || number(wallet,"id")<=0 || flag(wallet,"sandbox")!=0
            || flag(wallet,"isDeleted")!=0 || text(wallet,"incarnation")==null || number(wallet,"revision")<0)
            throw failure("PAYMENT_FUND_WALLET_IDENTITY_INVALID");
        for(String field:List.of("usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld"))
            if(decimal(wallet,field)==null || decimal(wallet,field).signum()<0)throw failure("PAYMENT_FUND_WALLET_AMOUNT_INVALID");
        return wallet;
    }

    private static String claim(CaptureContext c) {
        if(c.key().startsWith("USER:") || !c.key().endsWith(":CHARGE"))throw failure("MISSING_TRIAL_CLAIM");
        return c.key().substring(0,c.key().length()-7);
    }

    private void validateReward(Token token,EarningsReleaseMapper.RecoveryEntry entry) {
        var c=token.context;
        if(entry==null || !Objects.equals(entry.userId(),c.customer()) || entry.entryNo()==null
            || !Objects.equals(entry.sourceWalletId(),number(token.cash.wallet(),"id"))
            || !"PRODUCTION".equals(entry.sourceEnvironment()) || !"ACTIVE".equals(entry.status())
            || !Integer.valueOf(0).equals(entry.isDeleted()) || entry.amount()==null || entry.amount().signum()<=0
            || entry.recoveredAmount()==null || entry.recoveredAmount().signum()!=0
            || !Set.of("withdrawable","pending_review","bonus_locked").contains(entry.bucket()))
            throw failure("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");
        String suffix,type;
        if("USDT".equals(entry.asset())) {suffix=":REMAINDER";type="H2_TRIAL_REMAINDER";}
        else if("NEX".equals(entry.asset())) {suffix=":NEX";type="H2_TRIAL_BONUS";}
        else throw failure("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");
        if(!type.equals(entry.sourceType()) || !(claim(c)+suffix).equals(entry.sourceRef()))
            throw failure("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");
    }

    private void bindTrialZero(Token token,List<Map<String,Object>> rows) {
        if(rows.size()!=1)throw failure("PAYMENT_FUND_ZERO_TRIAL_SOURCE_MISMATCH");
        var row=rows.get(0);var c=token.context;
        if(!"TRIAL_CONVERT".equals(text(row,"orderType")) || number(row,"excludedEnvironment")!=0
            || number(row,"sourceLinked")!=1 || number(row,"customerId")!=c.customer()
            || !c.key().equals(text(row,"businessId")) || !"USDT".equals(text(row,"currency"))
            || decimal(row,"amount")==null || decimal(row,"amount").signum()!=0
            || number(row,"ledgerId")!=0 || time(row,"succeededAt")==null || time(row,"sourceConfirmationAt")==null
            || number(row,"claimId")<=0 || number(row,"deviceId")<=0 || number(row,"sourceRootId")<=0)
            throw failure("PAYMENT_FUND_ZERO_TRIAL_SOURCE_MISMATCH");
        var devices=currentMarkers(DEVICE,mapper.device(number(row,"deviceId")));checkOwners(devices,c.customer());
        var orders=currentBefore(Source.WALLET_ORDER,c.customer(),text(row,"orderNo"));checkOwners(orders,c.customer());
        if(devices.size()!=1 || orders.size()!=1 || number(orders.get(0),"id")!=number(row,"sourceRootId")
            || !Objects.equals(text(devices.get(0),"orderNo"),text(row,"orderNo"))
            || !"TRIAL_CONVERT".equals(text(orders.get(0),"orderType")) || decimal(orders.get(0),"amount")==null
            || decimal(orders.get(0),"amount").signum()!=0)throw failure("PAYMENT_FUND_ZERO_TRIAL_SOURCE_MISMATCH");
        requireSettledState(Source.TRIAL_CONVERT,currentBefore(Source.TRIAL_CONVERT,c.customer(),c.key()).get(0));
        requireSettledState(Source.WALLET_ORDER,orders.get(0));
        bindPayment(token,fact(row,Source.TRIAL_CONVERT),row,null,null);
    }

    private void bindPayment(Token token,Fact fact,Map<String,Object> settledRow,Receipt receipt,Fact original) {
        var c=token.context;
        if(token.cash==null || (fact.amount().signum()>0 && receipt==null))throw failure("PAYMENT_FUND_BEFORE_CASH_REQUIRED");
        var events=new ArrayList<Event>();var steps=new ArrayList<CashStep>();
        var rewardSources=new ArrayList<Map<String,Object>>();var rewardLedgers=new ArrayList<Map<String,Object>>();
        Map<String,Object> trialSource=c.source()==Source.TRIAL_CONVERT?trialSource(token,fact,settledRow):null;
        var account=new Account(c.customer(),"USDT","available");
        String eventId=null;
        OriginalFundEvidence originalEvidence=fact.kind()==Kind.DEVICE_PURCHASE_REFUND?originalConsumeEvidence(original):null;
        if(fact.amount().signum()>0) {
            Action action=switch(fact.kind()) {case DEPOSIT->Action.DEPOSIT;case DEVICE_PURCHASE->Action.CONSUME;case DEVICE_PURCHASE_REFUND->Action.REFUND;case CONSUMPTION->throw failure("GENESIS_BINDER_REQUIRED");};
            eventId=action==Action.CONSUME?consumeId(fact.factId()):action.name()+":"+operationKey(c.source(),c.partition(),fact.factId());
            events.add(new Event(eventId,action,action==Action.CONSUME?account:null,action==Action.DEPOSIT?account:null,
                action==Action.REFUND?consumeId(original.factId()):null,fact.amount(),null));
        }
        steps.add(new CashStep(receipt==null?"nx_trial_claim:"+number(settledRow,"claimId"):"nx_wallet_ledger:"+receipt.id,
            eventId,fact.kind()==Kind.DEVICE_PURCHASE?fact.amount().negate():fact.amount(),BigDecimal.ZERO));
        if(c.source()==Source.TRIAL_CONVERT) {
            for(var issued:token.rewards) {
                var actual=earnings.lockRecoveryEntry(issued.entryNo());validateReward(token,actual);
                if(!issued.equals(actual))throw failure("PAYMENT_FUND_TRIAL_REWARD_CHANGED");
                var ledgers=currentMarkers(LEDGER,mapper.ledgers(actual.sourceRef()));checkOwners(ledgers,c.customer());
                if(ledgers.size()!=1)throw failure("PAYMENT_FUND_TRIAL_REWARD_LEDGER_MISMATCH");
                var ledger=ledgers.get(0);
                if(!"TRIAL_BONUS".equals(text(ledger,"ledgerType")) || !"POSTED".equals(text(ledger,"status"))
                    || !actual.asset().equals(text(ledger,"currency")) || !"IN".equals(text(ledger,"direction"))
                    || number(ledger,"deleted")!=0 || !actual.sourceRef().equals(text(ledger,"businessId"))
                    || decimal(ledger,"amount")==null || decimal(ledger,"amount").compareTo(actual.amount())!=0
                    || time(ledger,"successAt")==null)throw failure("PAYMENT_FUND_TRIAL_REWARD_LEDGER_MISMATCH");
                String rewardId="EARNINGS:"+actual.entryNo();
                Action action="withdrawable".equals(actual.bucket())?Action.EARNINGS:Action.PROTECTED_EARNINGS;
                events.add(new Event(rewardId,action,null,new Account(c.customer(),actual.asset(),"available"),null,actual.amount(),null));
                steps.add(new CashStep("nx_earnings_release_entry:"+actual.entryNo(),rewardId,
                    "USDT".equals(actual.asset())?actual.amount():BigDecimal.ZERO,"NEX".equals(actual.asset())?actual.amount():BigDecimal.ZERO));
                rewardSources.add(rewardSnapshot(actual));rewardLedgers.add(immutable(ledger));
            }
        } else if(!token.rewards.isEmpty())throw failure("PAYMENT_FUND_UNEXPECTED_REWARDS");
        var authority=sourceAuthority(fact,settledRow);
        var after=paymentWallet(c.customer());
        var trace=cashTrace(token,after,steps,receipt,rewardLedgers);
        String key=operationKey(c.source(),c.partition(),fact.factId());
        var source=new LinkedHashMap<String,Object>();
        source.put("schemaVersion",FUND_PROOF);source.put("customerId",c.customer());source.put("factId",fact.factId());
        source.put("source",c.source().name());source.put("stableBusinessKey",c.key());source.put("sourcePartition",c.partition());
        source.put("orderNo",fact.orderNo());source.put("amount",fact.amount());source.put("currency",fact.currency());
        source.put("succeededAt",fact.succeededAt());source.put("settledSource",settledRow);
        source.put("freshLedger",receipt==null?null:receipt.tuple);source.put("rewards",rewardSources);source.put("rewardLedgers",rewardLedgers);
        source.put("trialSource",trialSource);
        if(original!=null) {
            var origin=new LinkedHashMap<String,Object>();origin.put("factId",original.factId());origin.put("customerId",original.customerId());
            origin.put("source",original.source().name());origin.put("stableBusinessKey",original.sourceBusinessId());origin.put("orderNo",original.orderNo());
            origin.put("currency",original.currency());origin.put("amount",original.amount());origin.put("ledgerId",original.ledgerId());origin.put("succeededAt",original.succeededAt());
            source.put("originalPurchase",origin);
        }
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion",FUND_PROOF);proof.put("sourceAuthority",authority);
        proof.put("cashBefore",token.cash.wallet());proof.put("cashAfter",after);proof.put("cashTrace",trace);
        proof.put("originalConsume",original==null?null:consumeId(original.factId()));
        proof.put("originalFundEvidence",originalEvidence);
        var input=new ReceiptInput(key,"SUPPORT_PAYMENT","SETTLED",sourceTable(c.source()),c.source().name()+":"+hash(c.key()+"|"+Objects.toString(c.partition(),"")),
            "PRODUCTION",encodeFund(events),encodeFund(source),encodeFund(proof));
        if(token.bound!=null) {
            if(!sameInput(token.boundInput,input))throw failure("PAYMENT_FUND_COMPLETED_RECEIPT_CHANGED");
            exactFundOperation(fundMapper.operation(key),input,c.customer(),trace.size(),token.bound.receiptSha256());
            return;
        }
        token.bound=fund.bind(token.cash.token(),input);token.boundInput=input;
        if(token.bound==null || !key.equals(token.bound.operationKey()) || token.bound.journalLegs()!=trace.size())
            throw failure("PAYMENT_FUND_BOUND_RECEIPT_MISMATCH");
        exactFundOperation(fundMapper.operation(key),input,c.customer(),trace.size(),token.bound.receiptSha256());
    }

    private List<Map<String,Object>> cashTrace(Token token,Map<String,Object> after,List<CashStep> steps,Receipt receipt,
            List<Map<String,Object>> rewardLedgers) {
        var before=token.cash.wallet();String incarnation=text(before,"incarnation");
        if(!Objects.equals(incarnation,text(after,"incarnation")) || number(before,"id")!=number(after,"id")
            || number(after,"revision")-number(before,"revision")!=steps.size())throw failure("PAYMENT_FUND_CASH_TRACE_MISMATCH");
        var rows=fundMapper.journal(incarnation,number(before,"revision"),number(after,"revision"));
        if(rows==null || rows.size()!=steps.size())throw failure("PAYMENT_FUND_CASH_TRACE_MISMATCH");
        var trace=new ArrayList<Map<String,Object>>();Map<String,Object> preceding=before;
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);var step=steps.get(i);
            if(!"CHANGE".equals(text(row,"observation")) || !incarnation.equals(text(row,"incarnation"))
                || number(row,"revision")!=number(before,"revision")+i+1
                || number(row,"oldId")!=number(before,"id") || number(row,"newId")!=number(before,"id")
                || number(row,"oldUserId")!=token.context.customer() || number(row,"newUserId")!=token.context.customer()
                || number(row,"oldVersion")!=number(preceding,"version") || number(row,"newVersion")!=number(row,"oldVersion")+1
                || flag(row,"oldSandbox")!=0 || flag(row,"newSandbox")!=0 || flag(row,"oldIsDeleted")!=0 || flag(row,"newIsDeleted")!=0)
                throw failure("PAYMENT_FUND_CASH_TRACE_MISMATCH");
            var next=new HashMap<>(preceding);
            for(String field:List.of("usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld")) {
                String cap=Character.toUpperCase(field.charAt(0))+field.substring(1);
                BigDecimal oldAmount=decimal(row,"old"+cap),newAmount=decimal(row,"new"+cap);
                BigDecimal delta=field.equals("usdtAvailable")?step.usdt():field.equals("nexAvailable")?step.nex():BigDecimal.ZERO;
                if(oldAmount==null || newAmount==null || oldAmount.compareTo(decimal(preceding,field))!=0
                    || newAmount.subtract(oldAmount).compareTo(delta)!=0)throw failure("PAYMENT_FUND_CASH_TRACE_MISMATCH");
                next.put(field,newAmount);
            }
            next.put("version",number(row,"newVersion"));next.put("revision",number(row,"revision"));
            if(i==0 && receipt!=null && decimal(receipt.tuple,"balanceAfter").compareTo(decimal(next,"usdtAvailable"))!=0)
                throw failure("PAYMENT_FUND_LEDGER_CASH_MISMATCH");
            if(i>0 && token.context.source()==Source.TRIAL_CONVERT) {
                var ledger=rewardLedgers.get(i-1);String field="USDT".equals(text(ledger,"currency"))?"usdtAvailable":"nexAvailable";
                if(decimal(ledger,"balanceAfter")==null || decimal(ledger,"balanceAfter").compareTo(decimal(next,field))!=0)
                    throw failure("PAYMENT_FUND_LEDGER_CASH_MISMATCH");
            }
            var item=new LinkedHashMap<String,Object>();item.put("sourceId",step.sourceId());item.put("eventId",step.eventId());item.put("journal",row);
            trace.add(immutable(item));preceding=next;
        }
        for(String field:List.of("usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld"))
            if(decimal(preceding,field).compareTo(decimal(after,field))!=0)throw failure("PAYMENT_FUND_CASH_TRACE_MISMATCH");
        if(number(preceding,"version")!=number(after,"version"))throw failure("PAYMENT_FUND_CASH_TRACE_MISMATCH");
        return List.copyOf(trace);
    }

    private Object sourceAuthority(Fact fact,Map<String,Object> row) {
        if(fact.kind()!=Kind.DEVICE_PURCHASE)return immutable(row);
        var matches=new ArrayList<Map<String,Object>>();
        for(var audit:mapper.historyPaymentAudits(List.of(fact.customerId()))) {
            if(audit==null || number(audit,"customerId")!=fact.customerId() || number(audit,"id")<=0 || number(audit,"validDigest")!=1
                || number(audit,"actorId")!=fact.customerId() || !"USER".equals(text(audit,"actorType"))
                || !"SUCCESS".equals(text(audit,"result")) || !Objects.equals(text(audit,"orderNo"),fact.orderNo())
                || !Objects.equals(time(audit,"paidAt"),fact.succeededAt()) || !amountMatches(audit,fact) || text(audit,"detail")==null)continue;
            try { if(productionAuditMatches(fact,audit,json.readTree(text(audit,"detail"))))matches.add(audit); }
            catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("PAYMENT_FUND_SOURCE_AUTHORITY_INVALID");}
        }
        if(matches.size()!=1)throw failure("PAYMENT_FUND_SOURCE_AUTHORITY_MISSING");
        return immutable(matches.get(0));
    }

    private Map<String,Object> trialSource(Token token,Fact fact,Map<String,Object> projection) {
        var c=token.context;var sources=mapper.trialFundSource(c.customer(),claim(c));
        if(sources==null || sources.size()!=1)throw failure("PAYMENT_FUND_TRIAL_SOURCE_MISSING");
        var source=sources.get(0);
        if(number(source,"claimId")!=number(projection,"claimId") || number(source,"deviceId")!=number(projection,"deviceId")
            || number(source,"orderId")!=number(projection,"sourceRootId") || number(source,"customerId")!=c.customer()
            || !claim(c).equals(text(source,"claimNo")) || !fact.orderNo().equals(text(source,"orderNo"))
            || !"REDEEMED".equals(text(source,"claimStatus")) || !"TRIAL_CONVERT".equals(text(source,"orderType"))
            || !"PAID".equals(text(source,"paymentStatus")) || !"PRODUCTION".equals(text(source,"deviceEnvironment"))
            || !"TRIAL".equals(text(source,"sourceChannel")) || !"ACTIVE".equals(text(source,"deviceStatus"))
            || !"OWNED".equals(text(source,"ownershipStatus")) || !"ACTIVE".equals(text(source,"userStatus"))
            || flag(source,"userSandbox")!=0 || flag(source,"userDeleted")!=0 || flag(source,"deviceDeleted")!=0
            || flag(source,"claimDeleted")!=0 || flag(source,"orderDeleted")!=0
            || decimal(source,"chargeUsdt")==null || decimal(source,"chargeUsdt").compareTo(fact.amount())!=0
            || decimal(source,"amountUsdt")==null || decimal(source,"amountUsdt").compareTo(fact.amount())!=0
            || !Objects.equals(time(source,"paidAt"),fact.succeededAt()) || time(source,"settledAt")==null)
            throw failure("PAYMENT_FUND_TRIAL_SOURCE_MISMATCH");
        for(String asset:List.of("USDT","NEX")) {
            BigDecimal amount=decimal(source,asset.equals("USDT")?"remainderUsdt":"shadowNex");
            var actual=token.rewards.stream().filter(r->asset.equals(r.asset())).toList();
            if(amount==null || amount.signum()<0 || (amount.signum()==0?!actual.isEmpty():actual.size()!=1 || actual.get(0).amount().compareTo(amount)!=0))
                throw failure("PAYMENT_FUND_TRIAL_REWARD_SOURCE_MISMATCH");
        }
        return immutable(source);
    }

    private OriginalFundEvidence originalConsumeEvidence(Fact original) {
        if(original==null)throw failure("PAYMENT_FUND_ORIGINAL_CONSUME_MISSING");
        var operation=fundMapper.operation(operationKey(original.source(),null,original.factId()));
        if(operation==null)return OriginalFundEvidence.UNKNOWN;
        validateStoredFund(operation,original,null);
        if(!"PRODUCTION".equals(text(operation,"sourceEnvironment")))throw failure("PAYMENT_FUND_ORIGINAL_CONSUME_MISMATCH");
        try {
            var source=fundRead(text(operation,"sourceSnapshotJson"));
            if(!FUND_PROOF.equals(source.path("schemaVersion").asText()) || !original.factId().equals(source.path("factId").asText())
                || source.path("customerId").asLong()!=original.customerId() || !original.source().name().equals(source.path("source").asText())
                || !original.currency().equals(source.path("currency").asText()) || source.path("amount").decimalValue().compareTo(original.amount())!=0)
                throw failure("PAYMENT_FUND_ORIGINAL_CONSUME_MISMATCH");
            int count=0;
            for(var node:fundRead(text(operation,"orderedEventsJson"))) {
                Event event=json.treeToValue(node,Event.class);SupportFundAllocation.validate(event);
                if(consumeId(original.factId()).equals(event.id())) {
                    if(event.action()!=Action.CONSUME || !new Account(original.customerId(),original.currency(),"available").equals(event.from())
                        || event.amount().compareTo(original.amount())!=0)throw failure("PAYMENT_FUND_ORIGINAL_CONSUME_MISMATCH");
                    count++;
                }
            }
            long id=number(operation,"id");var parties=fundMapper.parties(id);var legs=fundMapper.legs(id);
            if(count!=1 || parties==null || parties.size()!=1 || number(parties.get(0),"customerId")!=original.customerId()
                || number(parties.get(0),"operationId")!=id || legs==null || legs.isEmpty())throw failure("PAYMENT_FUND_ORIGINAL_CONSUME_MISMATCH");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("PAYMENT_FUND_ORIGINAL_CONSUME_MISMATCH");}
        return OriginalFundEvidence.VERIFIED;
    }

    private void verifyOldFund(Fact fact,CaptureContext context) {
        // Existing v1 facts remain v1. Absence is deliberately not repaired or promoted to a fund receipt.
        var stored=fundMapper.operation(operationKey(context.source(),context.partition(),fact.factId()));
        if(stored==null)return;
        validateStoredFund(stored,fact,context.partition());
        if(fact.kind()==Kind.DEVICE_PURCHASE)originalConsumeEvidence(fact);
        try {
            var source=fundRead(text(stored,"sourceSnapshotJson"));
            if(!FUND_PROOF.equals(source.path("schemaVersion").asText()) || !fact.factId().equals(source.path("factId").asText())
                || source.path("customerId").asLong()!=fact.customerId() || !Objects.equals(context.partition(),source.path("sourcePartition").isNull()?null:source.path("sourcePartition").asText())
                || source.path("amount").decimalValue().compareTo(fact.amount())!=0)throw failure("PAYMENT_FUND_OLD_RECEIPT_MISMATCH");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("PAYMENT_FUND_OLD_RECEIPT_MISMATCH");}
    }

    private void validateStoredFund(Map<String,Object> operation,Fact fact,String partition) {
        if(operation==null || number(operation,"id")<=0 || !operationKey(fact.source(),partition,fact.factId()).equals(text(operation,"operationKey"))
            || !"SUPPORT_PAYMENT".equals(text(operation,"businessType")) || !"SETTLED".equals(text(operation,"phase"))
            || !sourceTable(fact.source()).equals(text(operation,"sourceTable"))
            || !(fact.source().name()+":"+hash(fact.sourceBusinessId()+"|"+Objects.toString(partition,""))).equals(text(operation,"sourceKey"))
            || !"PRODUCTION".equals(text(operation,"sourceEnvironment"))
            || !SupportFundJournalBindingService.PROTOCOL.equals(text(operation,"protocolVersion")))throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
        try {
            var source=fundRead(text(operation,"sourceSnapshotJson"));var proof=fundRead(text(operation,"sourceProofJson"));
            var events=fundRead(text(operation,"orderedEventsJson"));var trace=proof.path("cashTrace");
            if(!FUND_PROOF.equals(source.path("schemaVersion").asText()) || !FUND_PROOF.equals(proof.path("schemaVersion").asText())
                || !fact.factId().equals(source.path("factId").asText()) || source.path("customerId").asLong()!=fact.customerId()
                || !fact.source().name().equals(source.path("source").asText()) || !fact.sourceBusinessId().equals(source.path("stableBusinessKey").asText())
                || !Objects.equals(partition,source.path("sourcePartition").isNull()?null:source.path("sourcePartition").asText())
                || !fact.currency().equals(source.path("currency").asText()) || !source.path("amount").isNumber()
                || source.path("amount").decimalValue().compareTo(fact.amount())!=0
                || !Objects.equals(fact.orderNo(),source.path("orderNo").isNull()?null:source.path("orderNo").asText())
                || !Objects.equals(fact.succeededAt().toString(),source.path("succeededAt").asText())
                || source.path("freshLedger").path("id").asLong()!=fact.ledgerId()
                || !events.isArray() || !trace.isArray() || trace.isEmpty())
                throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
            long id=number(operation,"id");var parties=fundMapper.parties(id);var legs=fundMapper.legs(id);
            if(parties==null || parties.size()!=1 || number(parties.get(0),"operationId")!=id || number(parties.get(0),"customerId")!=fact.customerId()
                || legs==null || legs.size()!=trace.size())throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
            var digest=new LinkedHashMap<String,Object>();
            for(String key:List.of("operationKey","businessType","phase","sourceTable","sourceKey","sourceEnvironment","protocolVersion"))digest.put(key,text(operation,key));
            digest.put("orderedEventsJson",canonicalFund(events).toString());digest.put("sourceSnapshotJson",canonicalFund(source).toString());
            digest.put("sourceProofJson",canonicalFund(proof).toString());digest.put("customerIds",List.of(fact.customerId()));
            var digestLegs=new ArrayList<Map<String,Object>>();var eventIds=new HashSet<String>();
            for(var node:events) {
                Event event=json.treeToValue(node,Event.class);SupportFundAllocation.validate(event);
                if(!eventIds.add(event.id()) || event.from()!=null && event.from().customerId()!=fact.customerId()
                    || event.to()!=null && event.to().customerId()!=fact.customerId())throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
            }
            long last=0;String incarnation=null;var tracedEvents=new HashSet<String>();
            for(int i=0;i<legs.size();i++) {
                var leg=legs.get(i);var raw=trace.get(i).path("journal");long revision=number(leg,"revision");String inc=text(leg,"incarnation");
                if(number(leg,"operationId")!=id || revision<=0 || inc==null || !inc.equals(raw.path("incarnation").asText())
                    || revision!=raw.path("revision").asLong() || i>0 && (!inc.equals(incarnation) || revision!=last+1))
                    throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
                var actual=fundMapper.journal(inc,revision-1,revision);
                if(actual==null || actual.size()!=1 || !sameJson(encodeFund(actual.get(0)),raw.toString()))throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
                var eventId=trace.get(i).path("eventId");
                if(!eventId.isNull() && (!eventId.isTextual() || !eventIds.contains(eventId.asText()) || !tracedEvents.add(eventId.asText())))
                    throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
                digestLegs.add(Map.of("incarnation",inc,"revision",revision));last=revision;incarnation=inc;
            }
            if(!tracedEvents.equals(eventIds))throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
            digest.put("legs",digestLegs);
            if(!hash(canonicalFund(json.valueToTree(digest)).toString()).equals(text(operation,"receiptSha256")))
                throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("PAYMENT_FUND_STORED_RECEIPT_MISMATCH");}
    }

    private void exactFundOperation(Map<String,Object> row,ReceiptInput input,long customer,int expectedLegs,String expectedDigest) {
        if(row==null || number(row,"id")<=0 || !input.operationKey().equals(text(row,"operationKey"))
            || !input.businessType().equals(text(row,"businessType")) || !input.phase().equals(text(row,"phase"))
            || !input.sourceTable().equals(text(row,"sourceTable")) || !input.sourceKey().equals(text(row,"sourceKey"))
            || !input.sourceEnvironment().equals(text(row,"sourceEnvironment"))
            || !Objects.equals(expectedDigest,text(row,"receiptSha256"))
            || !sameJson(input.orderedEventsJson(),text(row,"orderedEventsJson")) || !sameJson(input.sourceSnapshotJson(),text(row,"sourceSnapshotJson"))
            || !sameJson(input.sourceProofJson(),text(row,"sourceProofJson")))throw failure("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");
        long id=number(row,"id");var parties=fundMapper.parties(id);var legs=fundMapper.legs(id);
        if(parties==null || parties.size()!=1 || number(parties.get(0),"operationId")!=id || number(parties.get(0),"customerId")!=customer
            || legs==null || legs.size()!=expectedLegs)throw failure("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");
        try {
            var trace=fundRead(input.sourceProofJson()).path("cashTrace");
            if(!trace.isArray() || trace.size()!=expectedLegs)throw failure("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");
            for(int i=0;i<legs.size();i++)if(number(legs.get(i),"operationId")!=id
                || !trace.get(i).path("journal").path("incarnation").asText().equals(text(legs.get(i),"incarnation"))
                || trace.get(i).path("journal").path("revision").asLong()!=number(legs.get(i),"revision"))
                throw failure("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("PAYMENT_FUND_RECEIPT_READBACK_MISMATCH");}
    }
    private boolean sameInput(ReceiptInput a,ReceiptInput b) {
        return a.operationKey().equals(b.operationKey()) && a.sourceTable().equals(b.sourceTable()) && a.sourceKey().equals(b.sourceKey())
            && sameJson(a.orderedEventsJson(),b.orderedEventsJson()) && sameJson(a.sourceSnapshotJson(),b.sourceSnapshotJson()) && sameJson(a.sourceProofJson(),b.sourceProofJson());
    }
    private boolean sameJson(String a,String b) {
        try {return a!=null && b!=null && canonicalFund(fundRead(a)).equals(canonicalFund(fundRead(b)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw failure("PAYMENT_FUND_RECEIPT_JSON_INVALID");}
    }
    private com.fasterxml.jackson.databind.JsonNode fundRead(String value) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(value==null)throw failure("PAYMENT_FUND_RECEIPT_JSON_INVALID");
        return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(value);
    }
    private com.fasterxml.jackson.databind.JsonNode canonicalFund(com.fasterxml.jackson.databind.JsonNode node) {
        if(node==null)throw failure("PAYMENT_FUND_RECEIPT_JSON_INVALID");
        if(node.isObject()) {
            var result=json.createObjectNode();var fields=new TreeSet<String>();node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field->result.set(field,canonicalFund(node.get(field))));return result;
        }
        if(node.isArray()) {
            var result=json.createArrayNode();node.forEach(child->result.add(canonicalFund(child)));return result;
        }
        return node.isNumber()?com.fasterxml.jackson.databind.node.DecimalNode.valueOf(node.decimalValue().stripTrailingZeros()):node;
    }
    private static String consumeId(String factId) {return "CONSUME:"+factId;}
    private static String operationKey(Source source,String partition,String factId) {return "PAYMENT:"+source.name()+":"+hash(source.name()+"|"+Objects.toString(partition,"")+"|"+factId);}
    private static String sourceTable(Source source) {return switch(source) {case DEPOSIT_ORDER->"nx_deposit_order";case CARD_TOPUP->"nx_payment_record";
        case VIETQR->"nx_vietqr_reconciliation";case HDPAY->"nx_hdpay_payin_order";case TRIAL_CONVERT->"nx_trial_claim";default->"nx_order";};}
    private static String hash(String value) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private static Map<String,Object> rewardSnapshot(EarningsReleaseMapper.RecoveryEntry e) {
        var row=new LinkedHashMap<String,Object>();row.put("entryNo",e.entryNo());row.put("customerId",e.userId());row.put("sourceType",e.sourceType());
        row.put("sourceRef",e.sourceRef());row.put("currency",e.asset());row.put("amount",e.amount());row.put("bucket",e.bucket());row.put("status",e.status());
        row.put("sourceEnvironment",e.sourceEnvironment());row.put("isDeleted",e.isDeleted());row.put("recoveredAmount",e.recoveredAmount());
        row.put("sourceWalletId",e.sourceWalletId());row.put("debitBaseline",e.debitBaseline());return row;
    }
    private static Map<String,Object> immutable(Map<String,Object> row) {return Collections.unmodifiableMap(new LinkedHashMap<>(row));}
    private String encodeFund(Object value) {
        try {return json.writeValueAsString(fundJsonValue(value));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("PAYMENT_FUND_SERIALIZATION_FAILED",ex);}
    }
    private static Object fundJsonValue(Object value) {
        if(value instanceof LocalDateTime time)return time.toString();
        if(value instanceof java.sql.Timestamp time)return time.toLocalDateTime().toString();
        if(value instanceof Map<?,?> map) {var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),fundJsonValue(v)));return result;}
        if(value instanceof Collection<?> list)return list.stream().map(SupportPaymentSourceService::fundJsonValue).toList();
        return value;
    }

    // A replay or refund reuses persisted causal evidence; neither attests a prior source anew.
    private boolean trustedCapturedSourceProof(Map<String,Object> originalRow,Source source,String partition) {
        if(invalidNewSource(originalRow,source)!=null) return false;
        Fact original=fact(originalRow,source);
        var candidate=mapper.originalSourceProof(original.factId());
        var stored=candidate==null?null:mapper.currentSourceProof(original.factId());
        return SupportPaymentCapturedSourceProof.matchesExpected(original,partition,stored,json);
    }
    // Nonlocking results only plan IDs; source tables retain physical rows through soft deletion.
    private List<Map<String,Object>> genesisBefore(List<String> nos) {
        if(genesis==null)throw new org.springframework.dao.DataRetrievalFailureException("GENESIS_SOURCE_UNAVAILABLE");
        return genesis.readByOrderNos(nos).stream().<Map<String,Object>>map(s->{var order=s.order();var row=new LinkedHashMap<String,Object>(order);
            row.put("businessId",order.get("orderNo"));row.put("successAt",order.get("paidAt"));
            row.put("sourceId","nx_genesis_order:"+genesisId(order,"id"));row.put("ledgerId",order.get("walletLedgerId"));return row;}).toList();
    }
    private List<Map<String,Object>> genesisSettled(Source source,List<String> nos) {
        if(genesis==null)throw new org.springframework.dao.DataRetrievalFailureException("GENESIS_SOURCE_UNAVAILABLE");
        return genesis.readByOrderNos(nos).stream().flatMap(s->genesisRows(s).stream()).filter(r->source.name().equals(text(r,"source"))).toList();
    }
    static List<Map<String,Object>> genesisRows(SupportGenesisReadFacade.Source source) {
        var order=source.order();boolean primary="PRIMARY".equals(text(order,"orderType"));
        Source kind=primary?Source.GENESIS_PRIMARY:Source.GENESIS_SECONDARY;String no=text(order,"orderNo");
        BigDecimal amount=decimal(order,"amount"),unit=decimal(order,"unitPrice"),fee=decimal(order,"royalty");
        long buyer=genesisId(order,"customerId"),seller=genesisId(order,"sellerId"),quantity=genesisId(order,"quantity");
        boolean linked=genesisId(order,"id")>0 && buyer>0 && no!=null && !no.isBlank()
            && "USDT".equals(text(order,"currency")) && "COMPLETED".equals(text(order,"status"))
            && zeroBit(order.get("deleted")) && zeroBit(order.get("buyerSandbox")) && zeroBit(order.get("buyerDeleted"))
            && amount!=null && amount.signum()>0 && unit!=null && unit.signum()>0 && quantity>0
            && unit.multiply(BigDecimal.valueOf(quantity)).compareTo(amount)==0 && fee!=null && fee.signum()>=0
            && time(order,"paidAt")!=null && time(order,"paidAt").getNano()==0 && Objects.equals(time(order,"paidAt"),time(order,"completedAt"));
        if(primary)linked&=seller==0 && order.get("holdingNo")==null && fee!=null && fee.signum()==0 && source.ledgers().size()==1;
        else {
            var sellers=source.ledgers().stream().filter(l->Objects.equals(text(l,"businessId"),no+"-SELL")).toList();
            linked&="SECONDARY".equals(text(order,"orderType")) && seller>0 && seller!=buyer && quantity==1
                && text(order,"holdingNo")!=null && zeroBit(order.get("sellerSandbox")) && zeroBit(order.get("sellerDeleted"))
                && fee!=null && amount!=null && amount.subtract(fee).signum()>0 && source.ledgers().size()==2 && sellers.size()==1;
            if(sellers.size()==1) {
                var l=sellers.get(0);linked&=genesisId(l,"id")>0 && genesisId(l,"customerId")==seller && "USDT".equals(text(l,"currency"))
                    && "GENESIS_SECONDARY_SELL".equals(text(l,"ledgerType")) && "IN".equals(text(l,"direction")) && "SUCCESS".equals(text(l,"status"))
                    && zeroBit(l.get("deleted")) && decimal(l,"amount")!=null && amount!=null && fee!=null
                    && decimal(l,"amount").compareTo(amount.subtract(fee))==0 && time(l,"successAt")!=null;
            }
        }
        var buyers=source.ledgers().stream().filter(l->Objects.equals(text(l,"businessId"),primary?no:no+"-BUY")).toList();
        if(buyers.isEmpty())buyers=Collections.singletonList(null);
        var result=new ArrayList<Map<String,Object>>();
        for(var ledger:buyers) {
            var row=new LinkedHashMap<String,Object>();row.put("kind",Kind.CONSUMPTION.name());row.put("source",kind.name());
            row.put("sourceId","nx_genesis_order:"+genesisId(order,"id"));row.put("sourceRootId",genesisId(order,"id"));
            row.put("customerId",buyer);row.put("businessId",no);row.put("duplicateKey",no);row.put("orderNo",no);row.put("orderType",text(order,"orderType"));
            row.put("amount",amount);row.put("currency",text(order,"currency"));row.put("succeededAt",time(order,"paidAt"));
            row.put("successTimeField","nx_genesis_order.paid_at");row.put("fractionalSecondDigits",0);
            row.put("sourceLinked",linked && ledger!=null && zeroBit(ledger.get("deleted"))
                && (order.get("walletLedgerId")==null || genesisId(order,"walletLedgerId")==genesisId(ledger,"id"))?1:0);
            row.put("excludedEnvironment",oneBit(order.get("buyerSandbox"))?1:0);row.put("providerPaidAt",null);row.put("sourceVersion",null);row.put("sourceConfirmationAt",null);
            row.put("ledgerId",ledger==null?0:genesisId(ledger,"id"));row.put("ledgerCustomerId",ledger==null?0:genesisId(ledger,"customerId"));
            for(String[] field:List.of(new String[]{"ledgerBusinessId","businessId"},new String[]{"ledgerCurrency","currency"},new String[]{"ledgerAmount","amount"},
                new String[]{"ledgerType","ledgerType"},new String[]{"ledgerDirection","direction"},new String[]{"ledgerStatus","status"},new String[]{"ledgerDeleted","deleted"},new String[]{"ledgerRecordedAt","successAt"}))
                row.put(field[0],ledger==null?null:ledger.get(field[1]));
            if(ledger!=null)row.put("ledgerDeleted",zeroBit(ledger.get("deleted"))?0:oneBit(ledger.get("deleted"))?1:null);
            result.add(row);
        }
        return result;
    }
    private static boolean zeroBit(Object value) {
        if(value instanceof Boolean bit)return !bit;
        if(value instanceof Number)try{return new BigDecimal(value.toString()).compareTo(BigDecimal.ZERO)==0;}catch(NumberFormatException ignored){return false;}
        return false;
    }
    private static boolean oneBit(Object value) {
        if(value instanceof Boolean bit)return bit;
        if(value instanceof Number)try{return new BigDecimal(value.toString()).compareTo(BigDecimal.ONE)==0;}catch(NumberFormatException ignored){return false;}
        return false;
    }
    private static long genesisId(Map<String,Object> row,String key) {
        Object value=row.get(key);if(value==null)return 0;
        if(!(value instanceof Number))throw failure("GENESIS_SOURCE_ID_INVALID");
        try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException | NumberFormatException ex){throw failure("GENESIS_SOURCE_ID_INVALID");}
    }
    private List<Map<String,Object>> currentBefore(Source source,long customer,String key) {
        if(SupportPaymentFacts.genesis(source))return genesisBefore(List.of(key));
        var result=new ArrayList<Map<String,Object>>();
        for(var candidate:mapper.before(source,customer,key)) {
            long id=number(candidate,"id");if(id<=0) throw failure("MISSING_SOURCE_IDENTITY");
            var rows=mapper.currentBefore(source,customer,key,id);
            if(rows.size()!=1 || number(rows.get(0),"id")!=id) throw failure("SOURCE_IDENTITY_CHANGED");
            String business=text(rows.get(0),"businessId");
            if(!key.startsWith("USER:") && !Objects.equals(business,source==Source.ORDER_REFUND?SupportPaymentSourceSql.rawKey(source,key):key))
                throw failure("SOURCE_IDENTITY_CHANGED");
            result.add(rows.get(0));
        }
        return result;
    }
    private List<Map<String,Object>> currentMarkers(SupportPaymentSourceMapper.Marker marker,List<Map<String,Object>> candidates) {
        var result=new ArrayList<Map<String,Object>>();
        for(var candidate:candidates) {
            long id=number(candidate,"id");if(id<=0) throw failure("MISSING_SOURCE_IDENTITY");
            var row=mapper.currentMarker(marker,id);
            if(row==null || number(row,"id")!=id) throw failure("SOURCE_IDENTITY_CHANGED");
            for(String key:List.of("businessId","orderNo","projectId","cid"))
                if(candidate.containsKey(key) && !Objects.equals(candidate.get(key),row.get(key)))
                    throw failure("SOURCE_IDENTITY_CHANGED");
            result.add(row);
        }
        return result;
    }
    private List<Map<String,Object>> settled(Source source,long customer,String key) {
        if(SupportPaymentFacts.genesis(source))return genesisSettled(source,List.of(key));
        return currentSettlements(source,customer,mapper.settled(source,List.of(customer),key));
    }
    private List<Map<String,Object>> currentSettlements(Source source,long customer,List<Map<String,Object>> candidates) {
        var result=new ArrayList<Map<String,Object>>();
        for(var candidate:candidates) {
            if(number(candidate,"excludedEnvironment")==1 || (Kind.DEVICE_PURCHASE.name().equals(text(candidate,"kind"))
                && decimal(candidate,"amount")!=null && decimal(candidate,"amount").signum()==0
                && (decimal(candidate,"ledgerAmount")==null || decimal(candidate,"ledgerAmount").signum()==0))) {
                result.add(candidate);continue;
            }
            if(number(candidate,"ledgerId")<=0 || number(candidate,"sourceRootId")<=0
                || source==Source.HDPAY && number(candidate,"intentId")<=0
                || source==Source.TRIAL_CONVERT && (number(candidate,"deviceId")<=0 || number(candidate,"claimId")<=0))
                throw failure("MISSING_SETTLED_SOURCE");
            var rows=mapper.currentSettled(source,List.of(customer),text(candidate,"businessId"),candidate);
            if(rows.size()!=1) throw failure("SOURCE_IDENTITY_CHANGED");
            result.add(rows.get(0));
        }
        return result;
    }
    private Object transactionResource() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw failure("WRITABLE_TRANSACTION_REQUIRED");
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        if(resource==null) throw failure("TRANSACTION_DATASOURCE_REQUIRED");
        return resource;
    }
    private static void validateKey(long customer,Source source,String key) {
        if(customer<=0 || source==null || key==null || key.isBlank() || key.length()>192
            || source==Source.FREE_TRIAL || source==Source.UNMATCHED_LEDGER) throw new IllegalArgumentException("Canonical payment scope required");
        if(key.startsWith("USER:") && (source!=Source.TRIAL_CONVERT || !key.equals("USER:"+customer)))
            throw new IllegalArgumentException("Invalid trial customer alias");
        if(source==Source.DEPOSIT_ORDER && (!key.startsWith("CR-") || !key.substring(3).matches("[1-9][0-9]*")))
            throw new IllegalArgumentException("Invalid Cregis payment key");
    }
    private static void checkOwners(List<Map<String,Object>> rows,long customer) {
        for(var row:rows) if(number(row,"customerId")!=customer) throw failure("SOURCE_CUSTOMER_MISMATCH");
    }
    private static boolean succeededMarker(Map<String,Object> r) {
        return time(r,"successAt")!=null || number(r,"ledgerId")>0
            || Set.of("CREDITED","CONFIRMED","SUCCESS","PAID","REFUNDED","REDEEMED","SETTLED").contains(Objects.toString(r.get("status"),""));
    }
    private static boolean positiveSettlement(Map<String,Object> r) { return decimal(r,"amount")!=null && decimal(r,"amount").signum()>0; }
    private static boolean pending(Source source,Map<String,Object> r) {
        if(r.isEmpty()) return switch(source) {case DEPOSIT_ORDER,CARD_TOPUP,WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,ORDER_REFUND,GENESIS_PRIMARY,GENESIS_SECONDARY->true;default->false;};
        if(number(r,"deleted")!=0) return false;
        if(source==Source.ORDER_REFUND) return !"REFUNDED".equals(text(r,"status"));
        if(source==Source.CARD_TOPUP) return number(r,"ledgerId")==0;
        if(succeededMarker(r) || (source==Source.VIETQR || source==Source.HDPAY || source==Source.TRIAL_CONVERT) && positiveSettlement(r)) return false;
        return Set.of("PENDING","CREATED","UNSETTLED","ACTIVE","GRACE","EXTENDED","RISK_HOLD","PROCESSING","AWAITING_PAYMENT","MATCHED","OPEN","MANUAL_REVIEW").contains(Objects.toString(r.get("status"),""));
    }
    private static void requireSettledState(Source source,Map<String,Object> r) {
        if(SupportPaymentFacts.genesis(source)?!zeroBit(r.get("deleted")):number(r,"deleted")!=0) throw failure("DELETED_SOURCE");
        String status=text(r,"status");
        boolean settled=switch(source) {
            case CARD_TOPUP -> true;
            case DEPOSIT_ORDER -> Set.of("CONFIRMED","CREDITED","SUCCESS").contains(Objects.toString(status,""));
            case VIETQR,HDPAY -> "CREDITED".equals(status);
            case TRIAL_CONVERT -> "REDEEMED".equals(status);
            case ORDER_REFUND -> "REFUNDED".equals(status);
            case GENESIS_PRIMARY,GENESIS_SECONDARY -> "COMPLETED".equals(status);
            default -> Set.of("PAID","REFUNDED","SUCCESS","CONFIRMED").contains(Objects.toString(status,""));
        };
        if(!settled) throw failure("UNSUCCESSFUL_SOURCE");
    }
    private static List<String> sourceReferences(Source source,List<Map<String,Object>> rows) {
        String table=switch(source){case DEPOSIT_ORDER->"nx_deposit_order";case CARD_TOPUP->"nx_payment_record";
            case VIETQR->"nx_vietqr_reconciliation";case HDPAY->"nx_hdpay_payin_order";
            case TRIAL_CONVERT->"nx_trial_claim";case GENESIS_PRIMARY,GENESIS_SECONDARY->"nx_genesis_order";default->"nx_order";};
        return rows.stream().map(r -> text(r,"sourceId")==null?table+":"+number(r,"id"):text(r,"sourceId")).distinct().sorted().toList();
    }
    private static String successField(Source source) {
        return switch(source) {case DEPOSIT_ORDER->"nx_deposit_order.credited_at";case HDPAY->"nx_hdpay_payin_order.settled_at";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT->"nx_order.paid_at";case GENESIS_PRIMARY,GENESIS_SECONDARY->"nx_genesis_order.paid_at";default->"nx_wallet_ledger.created_at";};
    }
    private static int precision(Source source) {return switch(source){case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT->6;default->0;};}
    private static IllegalStateException failure(String reason) { return new IllegalStateException(reason); }
    private record CaptureContext(long customer,Source source,String key,String partition,boolean old,Long ledger,String fact,
        LocalDateTime success,List<String> references,String version,String field,int precision,List<Map<String,Object>> rows) {}
    private record Receipt(Token token,long id,Map<String,Object> tuple) implements FreshLedgerReceipt {
        public long ledgerId() {return id;}
    }
    private static final class Guard { private final AtomicBoolean active=new AtomicBoolean(true); }
    private static final class Token implements BeforeSource {
        private final Object owner;
        private final Object resource;
        private final Guard guard;
        private final CaptureContext context;
        private final AtomicReference<Receipt> receipt=new AtomicReference<>();
        private final Thread thread=Thread.currentThread();
        private final Connection connection;
        private Cash cash;
        private GenesisCash genesisCash;
        private final List<EarningsReleaseMapper.RecoveryEntry> rewards=new ArrayList<>();
        private SupportFundJournalFacade.BoundReceipt bound;
        private ReceiptInput boundInput;
        private Token(Object owner,Object resource,Guard guard,CaptureContext context) {
            this.owner=owner;this.resource=resource;this.guard=guard;this.context=context;
            this.connection=resource instanceof ConnectionHolder holder?holder.getConnection():null;
        }
        public long customerId(){return context.customer();}
        public Source source(){return context.source();}
        public String stableBusinessKey(){return context.key();}
        public String sourcePartition(){return context.partition();}
        public boolean oldSource(){return context.old();}
        public Long existingLedgerId(){return context.ledger();}
        public String existingFactId(){return context.fact();}
        public LocalDateTime existingSuccessAt(){return context.success();}
        public List<String> sourceIds(){return context.references();}
        public String sourceVersion(){return context.version();}
        public String successTimeField(){return context.field();}
        public int fractionalSecondDigits(){return context.precision();}
        public String businessZone(){return DateTimeFormatConfig.BUSINESS_ZONE.getId();}
    }
}
