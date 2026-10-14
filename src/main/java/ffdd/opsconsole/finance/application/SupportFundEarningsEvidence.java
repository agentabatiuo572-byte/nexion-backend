package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Validates this finance-owned source protocol only; Reader still owns raw/receipt/closure and reverse coverage. */
@Service
@RequiredArgsConstructor
public class SupportFundEarningsEvidence {
    private final SupportFundJournalBindingService codec;
    private final ObjectMapper json;
    private static final Set<String> IMMUTABLE=Set.of("id","entryNo","userId","clusterId","sourceType","sourceRef","asset","amount",
        "idempotencyKey","sourceEnvironment","debitBaseline","sourceWalletId","createdAt");
    public record Inputs(List<Map<String,Object>> entries,List<Map<String,Object>> recoveries,
                         List<Map<String,Object>> appliedProofs,List<Map<String,Object>> audits,
                         List<Map<String,Object>> raw,Map<String,Integer> precisions,
                         SupportFundDirectReferralEvidence.ValidatedReversals directReversals) {
        public Inputs(List<Map<String,Object>> entries,List<Map<String,Object>> recoveries,
                      List<Map<String,Object>> appliedProofs,List<Map<String,Object>> audits,
                      List<Map<String,Object>> raw,Map<String,Integer> precisions) {
            this(entries,recoveries,appliedProofs,audits,raw,precisions,SupportFundDirectReferralEvidence.ValidatedReversals.empty());
        }
        public Inputs {Objects.requireNonNull(directReversals);}
    }
    public record Verified(Stamp stamp,String coveredPhase,List<Checkpoint> checkpoints) {
        public Verified {checkpoints=List.copyOf(checkpoints);}
    }
    public Verified verify(Map<String,Object> operation,JsonNode source,JsonNode proof,List<Event> events,Inputs input) {
        require("SUPPORT_EARNINGS".equals(operation.get("businessType")) &&"PRODUCTION".equals(operation.get("sourceEnvironment")));
        String phase=text(operation.get("phase")),key=text(operation.get("sourceKey"));
        require(text(operation.get("operationKey")).equals("EARNINGS:"+phase+":"+key));
        JsonNode entry=source.path("entry");String no=text(entry.path("entryNo"));
        var current=one(input.entries(),"entryNo",no);validEntry(entry,current,input);
        long customer=number(entry.path("userId"));String asset=text(entry.path("asset"));var account=new Account(customer,asset,"available");
        String eventId=SupportFundEarningsAdapter.eventId(no);List<Event> expected;
        String precisionKey;Object success;
        switch(phase) {
            case "CREDIT" -> {
                keys(source,Set.of("entry"));require(key.equals(no) &&"nx_earnings_release_entry".equals(operation.get("sourceTable")));
                require(entry.path("releasedAt").isNull() &&decimal(entry.path("recoveredAmount")).signum()==0);
                expected=List.of(new Event(eventId,"withdrawable".equals(text(entry.path("bucket")))?Action.EARNINGS:Action.PROTECTED_EARNINGS,
                    null,account,null,decimal(entry.path("amount")),null));
                success=current.get("createdAt");precisionKey="nx_earnings_release_entry.created_at";
            }
            case "RELEASE" -> {
                String authority=text(source.path("authority"));boolean janus=authority.equals("JANUS");require(janus ||authority.equals("MANUAL"));
                keys(source,janus?Set.of("beforeEntry","entry","audit","authority","appliedProof"):Set.of("beforeEntry","entry","audit","authority"));
                require(key.equals(no) &&"nx_earnings_release_entry".equals(operation.get("sourceTable")));
                var before=source.path("beforeEntry");validEntry(before,current,input);
                require(Set.of("pending_review","bonus_locked").contains(text(before.path("bucket"))) &&before.path("releasedAt").isNull()
                    &&"withdrawable".equals(text(entry.path("bucket"))) &&equal(entry.path("releasedAt"),current.get("releasedAt"))
                    &&equal(entry.path("releaseSource"),current.get("releaseSource"))
                    &&"withdrawable".equals(current.get("bucket"))
                    &&decimal(before.path("recoveredAmount")).compareTo(decimal(entry.path("recoveredAmount")))==0);
                JsonNode audit=source.path("audit");var actualAudit=one(input.audits(),"id",number(audit.path("id")));
                require(equal(audit,actualAudit) &&text(actualAudit.get("action")).equals(janus?"K1_EARNINGS_ATTESTATION_RELEASED":"K1_EARNINGS_MANUALLY_RELEASED")
                    &&no.equals(actualAudit.get("resourceId")) &&"EARNINGS_RELEASE_ENTRY".equals(actualAudit.get("resourceType"))
                    &&"SUCCESS".equals(actualAudit.get("result")) &&flag(actualAudit.get("isDeleted"))==0);
                JsonNode detail=codec.read(text(actualAudit.get("detailJson")));
                require(number(detail.path("userId"))==customer &&text(detail.path("before")).equals(text(before.path("bucket")))
                    &&"withdrawable".equals(text(detail.path("after"))));
                if(janus) {
                    var applied=source.path("appliedProof");var actual=one(input.appliedProofs(),"proofId",text(applied.path("proofId")));
                    require(equal(applied,actual) &&number(applied.path("userId"))==customer &&"PRODUCTION".equals(text(applied.path("executorMode")))
                        &&number(applied.path("commandVersion"))>0 &&text(applied.path("proofHash")).matches("[0-9a-f]{64}")
                        &&!applied.path("earningsConsumedAt").isNull() &&"attest".equals(text(entry.path("releaseSource")))
                        &&"JANUS_PRODUCTION_EXECUTOR".equals(text(detail.path("source")))
                        &&text(detail.path("deviceId")).equals(text(applied.path("deviceId"))));
                }else require("manual".equals(text(entry.path("releaseSource"))) &&!text(detail.path("reason")).isBlank()
                    &&detail.path("coverageAtSubmit").isNumber() &&detail.path("coverageRedline").isNumber());
                expected=List.of(new Event("EARNINGS_RELEASE:"+no,Action.RELEASE,account,null,eventId,null,null));
                success=current.get("releasedAt");precisionKey="nx_earnings_release_entry.released_at";
            }
            case "RECOVER" -> {
                keys(source,Set.of("beforeEntry","entry","recovery"));require("nx_earnings_source_recovery".equals(operation.get("sourceTable")));
                var before=source.path("beforeEntry");validEntry(before,current,input);
                var recovery=source.path("recovery");var actual=one(input.recoveries(),"recoveryNo",key);
                require(equal(recovery,actual) &&no.equals(text(recovery.path("entryNo"))) &&number(recovery.path("userId"))==customer
                    &&asset.equals(text(recovery.path("asset"))) &&"PRODUCTION".equals(text(recovery.path("sourceEnvironment")))
                    &&"PROMOTION_REWARD".equals(text(recovery.path("sourceType"))) &&equal(recovery.path("sourceType"),current.get("sourceType"))
                    &&equal(recovery.path("sourceRef"),current.get("sourceRef")) &&"TRACKED_CONSERVATIVE".equals(text(recovery.path("evidenceStatus")))
                    &&equal(recovery.path("debitBaseline"),current.get("debitBaseline")));
                BigDecimal recovered=decimal(recovery.path("recovered")),requested=decimal(recovery.path("requested"));
                require(recovered.signum()>=0 &&requested.signum()>0 &&decimal(recovery.path("outstanding")).signum()>=0
                    &&requested.compareTo(recovered.add(decimal(recovery.path("outstanding"))))==0
                    &&decimal(recovery.path("balanceBefore")).subtract(recovered).compareTo(decimal(recovery.path("balanceAfter")))==0
                    &&decimal(before.path("recoveredAmount")).add(recovered).compareTo(decimal(recovery.path("cumulativeRecovered")))==0
                    &&decimal(entry.path("recoveredAmount")).compareTo(decimal(recovery.path("cumulativeRecovered")))==0
                    &&decimal(entry.path("amount")).subtract(decimal(entry.path("recoveredAmount"))).compareTo(decimal(recovery.path("remainingEntitlement")))==0
                    &&decimal(current.get("recoveredAmount")).compareTo(decimal(entry.path("recoveredAmount")))>=0
                    &&text(recovery.path("bucket")).equals(text(before.path("bucket"))) &&text(entry.path("bucket")).equals(text(before.path("bucket")))
                    &&decimal(recovery.path("debitCounterBefore")).compareTo(decimal(recovery.path("debitBaseline")))>=0);
                BigDecimal remaining=decimal(before.path("amount")).subtract(decimal(before.path("recoveredAmount")));
                BigDecimal delta=decimal(recovery.path("debitCounterBefore")).subtract(decimal(recovery.path("debitBaseline")));
                BigDecimal excluded=otherRecoveries(input,no,customer,asset,number(before.path("sourceWalletId")),
                    decimal(recovery.path("debitBaseline")),decimal(recovery.path("debitCounterBefore")));
                require(requested.compareTo(remaining)<=0 &&excluded.signum()>=0 &&excluded.compareTo(delta)<=0);
                BigDecimal provable=decimal(before.path("amount")).subtract(delta.subtract(excluded)).max(BigDecimal.ZERO);
                require(recovered.compareTo(requested.min(remaining).min(provable).min(decimal(recovery.path("balanceBefore"))))==0);
                expected=recovered.signum()==0?List.of():List.of(new Event("EARNINGS_RECOVER:"+key,Action.RECOVER,account,null,eventId,recovered,null));
                success=actual.get("createdAt");precisionKey="nx_earnings_source_recovery.created_at";
            }
            default -> throw new IllegalArgumentException("Unsupported earnings phase");
        }
        require(expected.equals(events));Integer precision=input.precisions().get(precisionKey);require(precision!=null);
        var stamp=new Stamp(phase.equals("RECOVER")?key:no,time(success),DateTimeFormatConfig.BUSINESS_ZONE.getId(),precision);
        var checkpoints=new ArrayList<Checkpoint>();
        boolean zero=phase.equals("RELEASE") ||phase.equals("RECOVER") &&events.isEmpty();
        keys(proof,zero?Set.of("cashBefore","cashAfter","cashTrace","checkpoint"):Set.of("cashBefore","cashAfter","cashTrace"));
        require(proof.path("cashTrace").isArray());
        if(zero) {
            require(proof.path("cashTrace").isEmpty() &&equal(proof.path("cashBefore"),proof.path("cashAfter")));
            var checkpoint=proof.path("checkpoint");keys(checkpoint,Set.of("wallet","previousOperationId"));
            var wallet=checkpoint.path("wallet");require(equal(wallet,proof.path("cashAfter")) &&number(wallet.path("userId"))==customer);
            long revision=number(wallet.path("revision"));String incarnation=text(wallet.path("incarnation"));
            var rows=input.raw().stream().filter(r->incarnation.equals(r.get("incarnation")) &&number(r.get("revision"))==revision).toList();
            require(rows.size()==1);var raw=rows.get(0);
            for(String field:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","createdAt"))
                require(equal(wallet.path(field),raw.get("new"+Character.toUpperCase(field.charAt(0))+field.substring(1))));
            require(jsonFlag(wallet.path("sandbox"))==flag(raw.get("newSandbox")) &&jsonFlag(wallet.path("isDeleted"))==flag(raw.get("newIsDeleted"))
                &&jsonFlag(wallet.path("sandbox"))==0 &&jsonFlag(wallet.path("isDeleted"))==0);
            long predecessor=number(checkpoint.path("previousOperationId"));require(predecessor>0 &&predecessor<number(operation.get("id")));
            checkpoints.add(new Checkpoint(incarnation,revision,customer,new Cash(decimal(wallet.path("usdtAvailable")),decimal(wallet.path("nexAvailable")),
                decimal(wallet.path("pendingWithdraw")),decimal(wallet.path("cregisRiskHeld"))),predecessor,
                phase.equals("RELEASE")?ZeroPhase.RELEASE:ZeroPhase.RECOVERY_NO_CASH));
        }else require(!proof.path("cashTrace").isEmpty());
        return new Verified(stamp,(phase.equals("RECOVER")?key:no)+":"+phase,checkpoints);
    }
    /** Reverse source enumeration includes no-cash releases/recoveries and the exact cumulative recovery tail. */
    public void verifyCoverage(Set<Long> component,Inputs input,Set<String> coveredPhases) {
        for(var entry:input.entries())if(component.contains(number(entry.get("userId")))) {
            validCurrentState(entry);
            String no=text(entry.get("entryNo"));require(coveredPhases.contains(no+":CREDIT"));
            require((entry.get("releasedAt")!=null)==coveredPhases.contains(no+":RELEASE"));
            BigDecimal recovered=BigDecimal.ZERO;
            for(var recovery:input.recoveries())if(no.equals(recovery.get("entryNo"))) {
                require(number(recovery.get("userId"))==number(entry.get("userId")) &&coveredPhases.contains(text(recovery.get("recoveryNo"))+":RECOVER"));
                recovered=recovered.add(decimal(recovery.get("recovered")));
            }
            require(recovered.compareTo(decimal(entry.get("recoveredAmount")))==0);
        }
        for(var recovery:input.recoveries())if(component.contains(number(recovery.get("userId"))))
            require(input.entries().stream().anyMatch(e->Objects.equals(e.get("entryNo"),recovery.get("entryNo"))
                &&number(e.get("userId"))==number(recovery.get("userId"))) &&coveredPhases.contains(text(recovery.get("recoveryNo"))+":RECOVER"));
    }
    private void validEntry(JsonNode entry,Map<String,Object> current,Inputs input) {
        require(entry.isObject() &&"PRODUCTION".equals(text(entry.path("sourceEnvironment"))) &&"ACTIVE".equals(text(entry.path("status")))
            &&("ACTIVE".equals(current.get("status"))||input.directReversals().allows(current,codec,json)) &&jsonFlag(entry.path("isDeleted"))==0 &&flag(current.get("isDeleted"))==0
            &&equal(entry.path("isDeleted"),current.get("isDeleted"))
            &&Set.of("withdrawable","pending_review","bonus_locked").contains(text(entry.path("bucket")))
            &&decimal(entry.path("amount")).signum()>0 &&decimal(entry.path("recoveredAmount")).signum()>=0
            &&decimal(entry.path("amount")).compareTo(decimal(entry.path("recoveredAmount")))>=0
            &&number(entry.path("id"))>0 &&number(entry.path("userId"))>0 &&number(entry.path("sourceWalletId"))>0
            &&decimal(entry.path("debitBaseline")).signum()>=0 &&!text(entry.path("sourceType")).startsWith("MOCK"));
        for(String field:IMMUTABLE)require(equal(entry.path(field),current.get(field)));
        validCurrentState(current);
        require(decimal(current.get("recoveredAmount")).compareTo(decimal(entry.path("recoveredAmount")))>=0);
        if(entry.path("releasedAt").isNull()) {
            require(emptyReleaseSource(entry.path("releaseSource")));
            // Credit/recovery can precede a real later release. Without one, no bucket mutation is supported.
            if(current.get("releasedAt")==null)require(text(entry.path("bucket")).equals(current.get("bucket")));
            else require(Set.of("pending_review","bonus_locked").contains(text(entry.path("bucket"))));
        }else {
            // Release is one-way and occurs once; recovery never rewrites its authority or successful time.
            require("withdrawable".equals(text(entry.path("bucket"))) &&current.get("releasedAt")!=null
                &&equal(entry.path("releasedAt"),current.get("releasedAt"))
                &&equal(entry.path("releaseSource"),current.get("releaseSource")));
        }
    }
    private static void validCurrentState(Map<String,Object> entry) {
        String bucket=text(entry.get("bucket"));
        require(Set.of("withdrawable","pending_review","bonus_locked").contains(bucket));
        BigDecimal amount=decimal(entry.get("amount")),recovered=decimal(entry.get("recoveredAmount"));
        require(amount.signum()>0 &&recovered.signum()>=0 &&recovered.compareTo(amount)<=0);
        Object released=entry.get("releasedAt"),source=entry.get("releaseSource");
        if(released==null)require(source==null ||source instanceof String &&((String)source).isBlank());
        else {
            time(released);
            require("withdrawable".equals(bucket) &&source instanceof String &&Set.of("manual","attest").contains(source));
        }
    }
    private static boolean emptyReleaseSource(JsonNode source) {return source.isNull() ||source.isTextual() &&source.textValue().isBlank();}
    /** Same predicates as EarningsReleaseMapper.otherSourceRecoveriesSinceCredit, over the reader's full batch. */
    private static BigDecimal otherRecoveries(Inputs input,String no,long customer,String asset,long wallet,
                                              BigDecimal baseline,BigDecimal debited) {
        BigDecimal sum=BigDecimal.ZERO;
        for(var row:input.recoveries()) {
            if(no.equals(row.get("entryNo")) ||number(row.get("userId"))!=customer ||!asset.equals(row.get("asset"))
                ||!"PRODUCTION".equals(row.get("sourceEnvironment")) ||!"TRACKED_CONSERVATIVE".equals(row.get("evidenceStatus")))continue;
            BigDecimal recovered=decimal(row.get("recovered")),counter=decimal(row.get("debitCounterBefore"));
            if(recovered.signum()<=0 ||counter.compareTo(baseline)<0 ||counter.add(recovered).compareTo(debited)>0)continue;
            var entry=one(input.entries(),"entryNo",text(row.get("entryNo")));
            if(number(entry.get("userId"))==customer &&number(entry.get("sourceWalletId"))==wallet
                &&asset.equals(entry.get("asset")) &&"PRODUCTION".equals(entry.get("sourceEnvironment")))sum=sum.add(recovered);
        }
        return sum;
    }
    private boolean equal(JsonNode node,Object value) {return codec.canonical(node).equals(codec.canonical(json.valueToTree(normalize(value))));}
    private static Object normalize(Object value) {
        if(value instanceof Timestamp at)return at.toLocalDateTime().toString();if(value instanceof LocalDateTime at)return at.toString();
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),normalize(v)));return result;}
        if(value instanceof Collection<?> values)return values.stream().map(SupportFundEarningsEvidence::normalize).toList();return value;
    }
    private static Map<String,Object> one(List<Map<String,Object>> rows,String field,Object value) {
        var matching=rows.stream().filter(r->Objects.equals(r.get(field),value) ||r.get(field) instanceof Number &&value instanceof Number
            &&decimal(r.get(field)).compareTo(decimal(value))==0).toList();require(matching.size()==1);return matching.get(0);
    }
    private static String text(Object value) {require(value instanceof String &&!((String)value).isBlank());return (String)value;}
    private static String text(JsonNode value) {require(value.isTextual());return text(value.textValue());}
    private static BigDecimal decimal(Object value) {require(value instanceof Number);var amount=new BigDecimal(value.toString());require(amount.stripTrailingZeros().scale()<=6);return amount;}
    private static BigDecimal decimal(JsonNode value) {require(value.isNumber());return decimal(value.numberValue());}
    private static long number(Object value) {try{return decimal(value).longValueExact();}catch(ArithmeticException ex){throw new IllegalArgumentException("Invalid source identifier",ex);}}
    private static long number(JsonNode value) {require(value.isNumber());return number(value.numberValue());}
    private static int flag(Object value) {require(value!=null);return SupportPaymentFactService.flag(Map.of("flag",value),"flag");}
    private static int jsonFlag(JsonNode value) {if(value.isBoolean())return value.booleanValue()?1:0;require(value.isNumber());return flag(value.numberValue());}
    private static LocalDateTime time(Object value) {if(value instanceof Timestamp at)return at.toLocalDateTime();require(value instanceof LocalDateTime);return (LocalDateTime)value;}
    private static void keys(JsonNode node,Set<String> fields) {require(node.isObject());var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);require(actual.equals(fields));}
    private static void require(boolean value) {if(!value)throw new IllegalArgumentException("Unproven earnings source");}
}
