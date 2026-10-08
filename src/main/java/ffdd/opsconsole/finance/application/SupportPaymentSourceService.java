package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql;
import static ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.Marker.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
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
    private static final DateTimeFormatter ISO6=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private final SupportPaymentSourceMapper mapper;
    private final SupportPaymentFactService history;
    private final DataSource dataSource;
    private final ObjectMapper json;

    @Override
    public SupportPaymentFacts.Snapshot readHistory(Collection<Long> customers) { return history.read(customers); }

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
        var matches=all.stream().filter(p -> number(p,"deleted")==0 && "NEXGRID_WALLET".equals(text(p,"provider"))
            && Objects.equals(text(p,"businessId"),text(row,"paymentNo"))
            && "USDT".equals(text(p,"currency")) && decimal(p,"amount")!=null
            && decimal(p,"amount").compareTo(decimal(row,"amount"))==0 && time(p,"successAt")!=null).toList();
        row.put("sourceLinked",matches.size()==1?1:0);
        row.put("sourceConfirmationAt",matches.size()==1?time(matches.get(0),"successAt"):null);
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
        if(stored==null || !"NEW_SUCCESS".equals(text(stored,"captureMode"))
            || !"NEW_SUCCESS".equals(text(stored,"evidenceCaptureMode"))
            || !"support-payment-attribution-v1".equals(text(stored,"schemaVersion"))
            || !"support-payment-attribution-v1".equals(text(stored,"evidenceSchemaVersion"))
            || !Objects.equals(text(stored,"sourcePartition"),partition)) return false;
        if(text(stored,"sourceFactJson")==null || text(stored,"beforeSourceJson")==null) return false;
        JsonNode saved,before;
        try {
            var reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            saved=reader.readTree(text(stored,"sourceFactJson"));
            before=reader.readTree(text(stored,"beforeSourceJson"));
        } catch(JsonProcessingException ex) { throw new IllegalStateException("INVALID_PERSISTED_SOURCE_PROOF",ex); }
        if(saved==null || before==null || !saved.isObject() || !before.isObject()) return false;
        String zone=DateTimeFormatConfig.BUSINESS_ZONE.getId();
        return equalText(saved,"factId",original.factId()) && equalText(saved,"kind",original.kind().name())
            && equalText(saved,"source",original.source().name()) && equalLong(saved,"customerId",original.customerId())
            && equalLong(saved,"ledgerId",original.ledgerId()) && equalText(saved,"sourceBusinessId",original.sourceBusinessId())
            && equalText(saved,"orderNo",original.orderNo()) && equalText(saved,"orderType",original.orderType())
            && equalText(saved,"originalFactId",original.originalFactId()) && equalText(saved,"currency",original.currency())
            && saved.path("amount").isNumber() && saved.path("amount").decimalValue().compareTo(original.amount())==0
            && equalTime(saved,"succeededAt",original.succeededAt()) && equalTime(saved,"ledgerRecordedAt",original.ledgerRecordedAt())
            && equalTime(saved,"sourceConfirmationAt",original.sourceConfirmationAt()) && equalTime(saved,"providerPaidAt",original.providerPaidAt())
            && equalText(saved,"successTimeField",original.successTimeField())
            && equalText(saved,"historicalEnvironmentStatus",original.historicalEnvironmentStatus().name())
            && equalLong(saved,"fractionalSecondDigits",original.fractionalSecondDigits()) && equalText(saved,"businessZone",zone)
            && equalText(saved,"succeededAtInstant",ISO6.format(original.succeededAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z")
            && Objects.equals(saved.get("sourceIds"),json.valueToTree(original.sourceIds()))
            && before.path("oldSource").isBoolean() && !before.path("oldSource").booleanValue()
            && equalLong(before,"customerId",original.customerId()) && equalText(before,"source",source.name())
            && equalText(before,"stableBusinessKey",original.sourceBusinessId()) && equalText(before,"sourcePartition",partition)
            && equalText(before,"existingLedgerId",null) && equalText(before,"existingFactId",null) && equalText(before,"existingSuccessAt",null)
            && equalText(before,"businessZone",zone) && equalText(before,"successTimeField",original.successTimeField())
            && equalLong(before,"fractionalSecondDigits",original.fractionalSecondDigits());
    }
    private static boolean equalText(JsonNode node,String key,String expected) {
        JsonNode value=node.get(key);
        return value!=null && (expected==null?value.isNull():value.isTextual() && expected.equals(value.textValue()));
    }
    private static boolean equalLong(JsonNode node,String key,long expected) {
        JsonNode value=node.get(key);
        return value!=null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue()==expected;
    }
    private static boolean equalTime(JsonNode node,String key,LocalDateTime expected) {
        return equalText(node,key,expected==null?null:ISO6.format(expected));
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
