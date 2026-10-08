package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.Envelope;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql;
import static ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.Marker.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
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
@RequiredArgsConstructor
public class SupportPaymentSourceService implements FinanceSupportPaymentFactsFacade {
    private final SupportPaymentSourceMapper mapper;
    private final SupportPaymentFactService history;
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final SupportPaymentCaptureHistoryFacade capturedHistory;

    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SupportPaymentFacts.Snapshot readHistory(Collection<Long> customers) {
        if(customers==null || customers.isEmpty() || customers.stream().anyMatch(id -> id==null || id<=0))
            throw new IllegalArgumentException("Explicit positive customer scope required");
        requireHistorySnapshot();
        var ids=List.copyOf(new TreeSet<>(customers));
        var candidates=new ArrayList<VerifiedCandidate>();
        var issues=new ArrayList<Issue>();
        var conflicts=new HashSet<String>();
        List<Envelope> envelopes;
        try { envelopes=capturedHistory.readNewFinancialProofs(ids); }
        catch(DataAccessException ex) {
            historyReadFailure(issues);
            return history.readWithCapturedHistory(ids,new CapturedFinancialHistory(candidates,issues,conflicts));
        } catch(IllegalStateException ex) {
            if(!Set.of("INVALID_CAPTURE_HISTORY_SCHEMA","INVALID_CAPTURE_HISTORY_ROW").contains(Objects.toString(ex.getMessage(),"")))throw ex;
            for(Source source:historySources())issues.add(new Issue(source,null,"INVALID_PERSISTED_SOURCE_PROOF"));
            return history.readWithCapturedHistory(ids,new CapturedFinancialHistory(candidates,issues,conflicts));
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
                    Source source=historySource(envelope.identity().source());
                    if(source==null)throw failure("INVALID_PERSISTED_SOURCE_PROOF");
                    issues.add(new Issue(source,envelope.identity().factId(),"CAPTURED_SOURCE_PROOF_MISMATCH"));continue;
                }
                validateKey(fact.customerId(),fact.source(),fact.sourceBusinessId());
                if(fact.sourceBusinessId().startsWith("USER:") || !validHistoryPartition(fact.source(),envelope.sourcePartition()))
                    throw failure("INVALID_PERSISTED_SOURCE_PROOF");
                String successField=switch(fact.source()) {
                    case DEPOSIT_ORDER -> "nx_deposit_order.credited_at";case HDPAY -> "nx_hdpay_payin_order.settled_at";
                    case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT -> "nx_order.paid_at";
                    default -> "nx_wallet_ledger.created_at";
                };
                if(!successField.equals(fact.successTimeField()) || fact.fractionalSecondDigits()!=(fact.kind()==Kind.DEVICE_PURCHASE?6:0)
                    || fact.historicalEnvironmentStatus()!=Status.UNKNOWN)throw failure("INVALID_PERSISTED_SOURCE_PROOF");
                decoded.put(envelope,fact);
            } catch(IllegalArgumentException | IllegalStateException ex) {
                Source source=historySource(envelope.identity().source());
                if(source==null) for(Source supported:historySources())issues.add(new Issue(supported,null,"INVALID_PERSISTED_SOURCE_PROOF"));
                else issues.add(new Issue(source,envelope.identity().factId(),"INVALID_PERSISTED_SOURCE_PROOF"));
            }
        }
        if(!decoded.isEmpty()) {
            List<Map<String,Object>> ledgers;
            try { ledgers=mapper.historyLedgers(ids,decoded.values().stream().map(Fact::ledgerId).distinct().sorted().toList()); }
            catch(DataAccessException ex) {
                historyReadFailure(issues);
                return history.readWithCapturedHistory(ids,new CapturedFinancialHistory(candidates,issues,conflicts));
            }
            for(var entry:decoded.entrySet()) {
                var fact=entry.getValue();
                var matches=ledgers.stream().filter(row -> number(row,"id")==fact.ledgerId()).toList();
                String problem=matches.isEmpty()?"MISSING_SETTLEMENT_LEDGER":matches.size()!=1?"SETTLEMENT_MISMATCH":ledgerProblem(matches.get(0),fact);
                if(problem==null) {
                    try { problem=historySourceProblem(fact,entry.getKey().sourcePartition()); }
                    catch(DataAccessException ex) { problem="SOURCE_READ_FAILED"; }
                }
                if(problem!=null) {
                    issues.add(new Issue(fact.source(),fact.factId(),problem));
                    if(!Set.of("SOURCE_READ_FAILED","MISSING_SETTLEMENT_LEDGER","MISSING_AUTHORITATIVE_SOURCE",
                        "MISSING_INTENT_IDENTITY","MISSING_CARD_SETTLEMENT","MISSING_SOURCE_CONFIRMATION_TIME").contains(problem))
                        conflicts.add(fact.factId());
                } else candidates.add(new VerifiedCandidate(fact,fact.source()==Source.VIETQR?entry.getKey().sourcePartition():fact.sourceBusinessId()));
            }
        }
        return history.readWithCapturedHistory(ids,new CapturedFinancialHistory(candidates,issues,conflicts));
    }

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
            Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT,Source.ORDER_REFUND);
    }
    private static void historyReadFailure(List<Issue> issues) {
        for(var source:historySources())issues.add(new Issue(source,null,"SOURCE_READ_FAILED"));
    }
    private static Source historySource(String source) {
        try { Source value=Source.valueOf(source);return historySources().contains(value)?value:null; }
        catch(IllegalArgumentException | NullPointerException ex) { return null; }
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
        if(number(row,"customerId")!=fact.customerId() || !Objects.equals(text(row,"businessId"),fact.sourceBusinessId())
            || !"USDT".equals(text(row,"currency")) || !"USDT".equals(fact.currency()) || decimal(row,"amount")==null
            || decimal(row,"amount").compareTo(fact.amount())!=0 || !Objects.equals(time(row,"successAt"),fact.ledgerRecordedAt()))
            return "SETTLEMENT_MISMATCH";
        if(!SupportPaymentSourceSql.ledgerType(fact.source()).equals(text(row,"ledgerType")))return "SETTLEMENT_TYPE_MISMATCH";
        if(!SupportPaymentSourceSql.ledgerDirection(fact.source()).equals(text(row,"direction")) || number(row,"deleted")!=0
            || !(fact.source()==Source.TRIAL_CONVERT?"POSTED":"SUCCESS").equals(text(row,"status")))return "UNSUCCESSFUL_SETTLEMENT";
        return null;
    }
    private String historySourceProblem(Fact f,String partition) {
        Source source=f.source();String key=f.sourceBusinessId();
        var roots=mapper.before(source,f.customerId(),key);
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
            var events=mapper.cregisEvents(project,cid);
            if(events.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            if(events.size()!=1)return "BROKEN_CREGIS_EVENT_LINK";
            var event=events.get(0);
            if(number(event,"projectId")!=project || number(event,"cid")!=cid || number(event,"customerId")!=f.customerId()
                || number(event,"ledgerId")!=f.ledgerId() || !"CREDITED".equals(text(event,"status"))
                || !amountMatches(event,f))return "BROKEN_CREGIS_EVENT_LINK";
        } else if(source==Source.VIETQR || source==Source.HDPAY) {
            String intentKey=source==Source.VIETQR?partition:key;
            var intents=mapper.intent(intentKey);
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
            var payments=mapper.payments(f.orderNo());
            var confirmations=payments.stream().filter(p -> walletConfirmation(p,f.customerId(),f.orderNo(),text(root,"paymentNo"),f.amount(),f.ledgerId())
                && Objects.equals(time(p,"successAt"),f.sourceConfirmationAt()) && f.sourceConfirmationAt()!=null).toList();
            if(confirmations.size()!=1)return payments.isEmpty()?"MISSING_SOURCE_CONFIRMATION_TIME":"SETTLEMENT_MISMATCH";
        } else if(source==Source.CARD_TOPUP) {
            var settlements=mapper.cardSettlements(key);
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
            var devices=mapper.device(number(root,"deviceId"));
            if(devices==null || devices.isEmpty())return "MISSING_AUTHORITATIVE_SOURCE";
            if(devices.size()!=1)return "BROKEN_SOURCE_LINK";
            var device=devices.get(0);
            if(!"REDEEMED".equals(text(root,"status")) || !amountMatches(root,f)
                || !Objects.equals(time(root,"successAt"),f.sourceConfirmationAt()) || device==null
                || number(root,"deviceId")<=0 || number(device,"id")!=number(root,"deviceId")
                || number(device,"customerId")!=f.customerId() || !Objects.equals(text(device,"orderNo"),f.orderNo()))return "BROKEN_SOURCE_LINK";
            var orders=mapper.before(Source.WALLET_ORDER,f.customerId(),f.orderNo());
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
        var projections=mapper.settled(source,List.of(f.customerId()),key);
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
        var ledgers=currentMarkers(LEDGER,mapper.ledgers(key));
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
    public FreshLedgerReceipt insertFreshPaymentLedger(BeforeSource before,BigDecimal amount,BigDecimal balanceAfter,String remark) {
        Token token=checkedToken(before);var c=token.context;
        if(c.old() || c.key().startsWith("USER:") || token.receipt.get()!=null) throw failure("FRESH_LEDGER_NOT_ALLOWED");
        if(amount==null || amount.signum()<=0 || balanceAfter==null) throw new IllegalArgumentException("Actual positive payment required");
        var write=new HashMap<String,Object>();
        write.put("customer",c.customer());write.put("key",c.key());write.put("amount",amount);
        write.put("balanceAfter",balanceAfter);write.put("remark",remark);
        if(mapper.insertLedger(c.source(),write)!=1) throw failure("FRESH_LEDGER_INSERT_REQUIRED");
        long id=number(write,"id");if(id<=0) throw failure("FRESH_LEDGER_GENERATED_KEY_REQUIRED");
        var actual=mapper.currentMarker(LEDGER,id);
        if(actual==null || number(actual,"id")!=id || number(actual,"customerId")!=c.customer()
            || !c.key().equals(text(actual,"businessId")) || !SupportPaymentSourceSql.ledgerType(c.source()).equals(text(actual,"ledgerType"))
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
        if(!(before instanceof Token token) || token.owner!=this || token.resource!=resource || !token.guard.active.get())
            throw failure("INVALID_BEFORE_SOURCE_TRANSACTION");
        return token;
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
        if(facts.isEmpty()) return Optional.empty();
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
        if(c.source()==Source.ORDER_REFUND) checkRefund(c,result);
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
    private void checkRefund(CaptureContext c,Fact refund) {
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
    private List<Map<String,Object>> currentBefore(Source source,long customer,String key) {
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
        if(r.isEmpty()) return switch(source) {case DEPOSIT_ORDER,CARD_TOPUP,WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,ORDER_REFUND->true;default->false;};
        if(number(r,"deleted")!=0) return false;
        if(source==Source.ORDER_REFUND) return !"REFUNDED".equals(text(r,"status"));
        if(source==Source.CARD_TOPUP) return number(r,"ledgerId")==0;
        if(succeededMarker(r) || (source==Source.VIETQR || source==Source.HDPAY || source==Source.TRIAL_CONVERT) && positiveSettlement(r)) return false;
        return Set.of("PENDING","CREATED","UNSETTLED","ACTIVE","GRACE","EXTENDED","RISK_HOLD","PROCESSING","AWAITING_PAYMENT","MATCHED","OPEN","MANUAL_REVIEW").contains(Objects.toString(r.get("status"),""));
    }
    private static void requireSettledState(Source source,Map<String,Object> r) {
        if(number(r,"deleted")!=0) throw failure("DELETED_SOURCE");
        String status=text(r,"status");
        boolean settled=switch(source) {
            case CARD_TOPUP -> true;
            case DEPOSIT_ORDER -> Set.of("CONFIRMED","CREDITED","SUCCESS").contains(Objects.toString(status,""));
            case VIETQR,HDPAY -> "CREDITED".equals(status);
            case TRIAL_CONVERT -> "REDEEMED".equals(status);
            case ORDER_REFUND -> "REFUNDED".equals(status);
            default -> Set.of("PAID","REFUNDED","SUCCESS","CONFIRMED").contains(Objects.toString(status,""));
        };
        if(!settled) throw failure("UNSUCCESSFUL_SOURCE");
    }
    private static List<String> sourceReferences(Source source,List<Map<String,Object>> rows) {
        String table=switch(source){case DEPOSIT_ORDER->"nx_deposit_order";case CARD_TOPUP->"nx_payment_record";
            case VIETQR->"nx_vietqr_reconciliation";case HDPAY->"nx_hdpay_payin_order";
            case TRIAL_CONVERT->"nx_trial_claim";default->"nx_order";};
        return rows.stream().map(r -> text(r,"sourceId")==null?table+":"+number(r,"id"):text(r,"sourceId")).distinct().sorted().toList();
    }
    private static String successField(Source source) {
        return switch(source) {case DEPOSIT_ORDER->"nx_deposit_order.credited_at";case HDPAY->"nx_hdpay_payin_order.settled_at";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT->"nx_order.paid_at";default->"nx_wallet_ledger.created_at";};
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
        private Token(Object owner,Object resource,Guard guard,CaptureContext context) {
            this.owner=owner;this.resource=resource;this.guard=guard;this.context=context;
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
