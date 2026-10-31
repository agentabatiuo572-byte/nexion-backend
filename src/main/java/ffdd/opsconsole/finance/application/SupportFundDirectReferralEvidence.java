package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade.Source;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Validates only actual Direct recovery state chains, never a reconstructed central recovery. */
@Service
@RequiredArgsConstructor
public class SupportFundDirectReferralEvidence {
    private final SupportFundJournalBindingService codec;
    private final ObjectMapper json;
    private static final String SCHEMA="SUPPORT_DIRECT_REFERRAL_V1";
    private static final Set<String> SETTLEMENT_MUTABLE=Set.of("cancelled_usdt","cancelled_nex","recovered_usdt","recovered_nex","recovery_pending_usdt","recovery_pending_nex","reversal_recorded","refund_ratio","status","updated_at");
    private static final Set<String> ENTRY_MUTABLE=Set.of("status","updatedAt");
    private static final Set<String> EVENT_MUTABLE=Set.of("status","version","updated_at");
    private static final Set<String> REFUNDABLE_ORDER_STATUSES=Set.of("PAID","PROVISIONING","PROVISIONING_FAILED","COMPLETED");
    private static final Map<String,Set<String>> PAID_ORDER_ADVANCES=Map.of("PAID",Set.of("PROVISIONING","PROVISIONING_FAILED","COMPLETED"),"PROVISIONING",Set.of("PROVISIONING_FAILED","COMPLETED"));
    private static final Set<String> CREDIT_IMMUTABLE=Set.of("id","entryNo","userId","clusterId","sourceType","sourceRef","asset","amount","idempotencyKey","sourceEnvironment","debitBaseline","sourceWalletId","createdAt");
    public record Inputs(List<Source> sources,List<Map<String,Object>> operations,List<Map<String,Object>> parties,
                         List<Map<String,Object>> legs,List<Map<String,Object>> raw,Map<String,Integer> precisions) {}
    public record Verified(Stamp stamp,List<Point> points,List<Checkpoint> checkpoints,String coveredPhase) {
        public Verified {points=List.copyOf(points);checkpoints=List.copyOf(checkpoints);}
    }
    /** Issued only after full actual-source validation; callers cannot provide an allow flag or entry set. */
    public static final class ValidatedReversals {
        private final Map<String,String> fingerprints;
        private ValidatedReversals(Map<String,String> fingerprints) {this.fingerprints=Map.copyOf(fingerprints);}
        public static ValidatedReversals empty() {return new ValidatedReversals(Map.of());}
        boolean allows(Map<String,Object> current,SupportFundJournalBindingService codec,ObjectMapper json) {
            return "DIRECT_REFERRAL".equals(current.get("sourceType"))&&"REVERSED".equals(current.get("status"))
                &&Objects.equals(fingerprints.get(current.get("entryNo")),fingerprint(current,codec,json));
        }
    }
    public ValidatedReversals validate(Set<Long> component,Inputs input) {
        return validate(component,input,indexParties(input));
    }
    private ValidatedReversals validate(Set<Long> component,Inputs input,Map<Long,List<Map<String,Object>>> partyIndex) {
        var fingerprints=new HashMap<String,String>();var sourceKeys=new HashSet<String>();
        for(Source current:input.sources())if(component.contains(SupportDirectReferralReadFacade.customer(current))) {
            require(sourceKeys.add(current.key()));validateCurrent(current);
            var operations=input.operations().stream().filter(o->"SUPPORT_DIRECT_REFERRAL".equals(o.get("businessType"))&&current.key().equals(o.get("sourceKey"))).sorted(Comparator.comparingLong(o->number(o.get("id")))).toList();
            Source tail=null;var coveredLedgers=new HashSet<Long>();
            for(var op:operations) {
                var source=codec.readCurrentProof(text(op.get("sourceSnapshotJson")));var proof=codec.readCurrentProof(text(op.get("sourceProofJson")));
                var before=source(source.path("before"));var after=source(source.path("after"));
                require(current.key().equals(before.key())&&current.key().equals(after.key()));
                if(tail==null)initial(before);else require(sourceFollows(tail,before,Set.of()));
                verified(op,source,proof,decode(op,input),input,before,after,coveredLedgers);
                tail=after;
            }
            if(changed(current))require(tail!=null);
            if(tail!=null)require(sourceFollows(tail,current,Set.of()));
            for(var ledger:current.ledgers())if(directOut(current,ledger))require(coveredLedgers.contains(number(ledger.get("id"))));
            for(var entry:current.entries())if("REVERSED".equals(entry.get("status"))) {
                require(tail!=null&&"DIRECT_REFERRAL".equals(entry.get("sourceType"))&&fingerprints.put(text(entry.get("entryNo")),fingerprint(entry,codec,json))==null);
            }
        }
        for(var op:input.operations())if("SUPPORT_DIRECT_REFERRAL".equals(op.get("businessType"))) {
            var parties=parties(op,partyIndex);if(parties.stream().anyMatch(component::contains))require(parties.size()==1&&component.containsAll(parties)&&sourceKeys.contains(text(op.get("sourceKey"))));
        }
        return new ValidatedReversals(fingerprints);
    }
    public Verified verify(Map<String,Object> operation,JsonNode originalSource,JsonNode originalProof,List<Event> events,Inputs input) {
        JsonNode source=ffdd.opsconsole.common.domain.LegacyUvtProof.current(originalSource);
        JsonNode proof=ffdd.opsconsole.common.domain.LegacyUvtProof.current(originalProof);
        Source before=source(source.path("before")),after=source(source.path("after"));
        validate(Set.of(SupportDirectReferralReadFacade.customer(after)),input);
        require(events.equals(decode(operation,input))&&equal(codec.readCurrentProof(text(operation.get("sourceSnapshotJson"))),source)&&equal(codec.readCurrentProof(text(operation.get("sourceProofJson"))),proof));
        return verified(operation,source,proof,events,input,before,after,new HashSet<>());
    }
    public void verifyCoverage(Set<Long> component,Inputs input,Set<String> coveredPhases) {
        var partyIndex=indexParties(input);validate(component,input,partyIndex);
        for(var op:input.operations())if("SUPPORT_DIRECT_REFERRAL".equals(op.get("businessType"))&&parties(op,partyIndex).stream().anyMatch(component::contains))require(coveredPhases.contains(text(op.get("operationKey"))));
    }
    private Verified verified(Map<String,Object> op,JsonNode source,JsonNode proof,List<Event> events,Inputs input,Source before,Source after,Set<Long> coveredLedgers) {
        keys(source,Set.of("schemaVersion","before","after","phaseAt"));keys(proof,Set.of("schemaVersion","cashBefore","cashAfter","cashTrace","previousOperationId"));
        require(SCHEMA.equals(text(source.path("schemaVersion")))&&SCHEMA.equals(text(proof.path("schemaVersion")))&&"PRODUCTION".equals(op.get("sourceEnvironment")));
        boolean settlement=after.key().startsWith("S:"),failure="FAILURE".equals(op.get("phase"));String phase=failure?"FAILURE":settlement?"RECOVERY":"REISSUE",table=settlement?"nx_direct_referral_settlement":"nx_unilevel_event_recovery";
        require(!failure||settlement);
        long predecessor=number(proof.path("previousOperationId"));String operationKey=SupportDirectReferralReadFacade.operationKey(after.key(),predecessor,after);
        require(phase.equals(op.get("phase"))&&table.equals(op.get("sourceTable"))&&after.key().equals(op.get("sourceKey"))&&operationKey.equals(op.get("operationKey")));
        require(parties(op,input).equals(List.of(SupportDirectReferralReadFacade.customer(after)))&&predecessor>=0&&predecessor<number(op.get("id")));
        long actualPredecessor=input.operations().stream().filter(o->number(o.get("id"))<number(op.get("id"))&&parties(o,input).contains(SupportDirectReferralReadFacade.customer(after))).mapToLong(o->number(o.get("id"))).max().orElse(0);
        require(predecessor==actualPredecessor);
        require(number(proof.path("cashBefore").path("userId"))==SupportDirectReferralReadFacade.customer(after)&&number(proof.path("cashAfter").path("userId"))==SupportDirectReferralReadFacade.customer(after));
        transition(before,after,proof,input,failure);require(events.equals(SupportDirectReferralReadFacade.events(before,after,operationKey))&&(!failure||events.isEmpty()));
        var added=after.ledgers().stream().filter(l->before.ledgers().stream().noneMatch(b->number(b.get("id"))==number(l.get("id")))).toList();
        require(added.size()==events.size());
        for(var event:events) {
            var ledger=one(added,"asset",event.from().currency());
            require("OUT".equals(ledger.get("direction"))&&"SUCCESS".equals(ledger.get("status"))&&"TEAM_COMMISSION".equals(ledger.get("bizType"))&&flag(ledger.get("deleted"))==0&&number(ledger.get("customerId"))==event.from().customerId()&&amount(ledger.get("amount")).compareTo(event.amount())==0);
            String suffix=("USDT".equals(event.from().currency())?"usdt":"nex");BigDecimal cumulative=settlement?amount(after.subject().get("recovered_"+suffix)):amount(after.recovery().get("recovered_amount"));
            String biz=settlement?after.key().substring(2)+"-RECOVER-"+("USDT".equals(event.from().currency())?"USDT":"NEX")+"-"+cumulative.stripTrailingZeros().toPlainString():"DR-REISSUE-"+after.key().substring(2)+"-RECOVER-"+cumulative.stripTrailingZeros().toPlainString();
            String actual=text(ledger.get("bizNo"));require(actual.startsWith(biz.substring(0,biz.lastIndexOf('-')+1))&&new BigDecimal(actual.substring(actual.lastIndexOf('-')+1)).compareTo(cumulative)==0);
            require(amount(ledger.get("balanceAfter")).compareTo(amount(proof.path("cashAfter").path(event.from().currency().equals("USDT")?"usdtAvailable":"nexAvailable")))==0&&coveredLedgers.add(number(ledger.get("id"))));
        }
        for(var old:before.ledgers())require(after.ledgers().stream().filter(l->number(l.get("id"))==number(old.get("id"))).count()==1&&after.ledgers().stream().anyMatch(l->equal(json.valueToTree(normalize(old)),l)));
        var points=new ArrayList<Point>();var checkpoints=new ArrayList<Checkpoint>();cash(op,proof,events,input,points,checkpoints);
        String precisionKey=table+".updated_at";Integer precision=input.precisions().get(precisionKey);require(precision!=null&&precision>=0&&precision<=6);
        var at=time(source.path("phaseAt"));require(at.equals(time(settlement?after.subject().get("updated_at"):after.recovery().get("updated_at"))));
        return new Verified(new Stamp(after.key(),at,DateTimeFormatConfig.BUSINESS_ZONE.getId(),precision),points,checkpoints,operationKey);
    }
    private void transition(Source before,Source after,JsonNode proof,Inputs input,boolean failure) {
        require(before.key().equals(after.key()));validateCurrent(before);validateCurrent(after);
        boolean settlement=after.key().startsWith("S:");require(subjectFollows(before.subject(),after.subject(),settlement?SETTLEMENT_MUTABLE:EVENT_MUTABLE));
        if(settlement) {
            require(before.recovery()==null&&after.recovery()==null&&before.entries().size()==after.entries().size()&&before.events().size()==after.events().size());
            for(var entry:before.entries()) {
                var current=one(after.entries(),"entryNo",entry.get("entryNo"));sameExcept(entry,current,ENTRY_MUTABLE);originalCredit(entry,input);
                String asset=text(entry.get("asset"));BigDecimal target=amount(after.subject().get("cancelled_"+("USDT".equals(asset)?"usdt":"nex")));
                require((!failure&&target.signum()>0?"REVERSED":entry.get("status")).equals(current.get("status")));
            }
            boolean complete=amount(after.subject().get("cancelled_usdt")).compareTo(amount(after.subject().get("amount_usdt")))>=0&&amount(after.subject().get("cancelled_nex")).compareTo(amount(after.subject().get("amount_nex")))>=0;
            require(flag(after.subject().get("reversal_recorded"))==(complete?1:0)&&amount(after.subject().get("refund_ratio")).compareTo(complete?BigDecimal.ONE:BigDecimal.ZERO)==0);
            for(String asset:List.of("USDT","UVT")) {
                String s=("USDT".equals(asset)?"usdt":"nex");BigDecimal target=amount(after.subject().get("cancelled_"+s)),prior=amount(before.subject().get("recovered_"+s)),current=amount(after.subject().get("recovered_"+s));
                require(target.compareTo(amount(before.subject().get("cancelled_"+s)))>=0&&target.compareTo(amount(after.subject().get("amount_"+s)))<=0);
                boolean credited=before.subject().get("credited_"+s+"_at")!=null||(before.subject().get("credited_at")!=null&&before.subject().get("credited_usdt_at")==null&&before.subject().get("credited_nex_at")==null);
                if(credited&&amount(before.subject().get("amount_"+s)).signum()>0)require(before.entries().stream().filter(e->asset.equals(e.get("asset"))).count()==1);
                BigDecimal due=credited?target.subtract(prior).max(BigDecimal.ZERO):BigDecimal.ZERO,take=failure?BigDecimal.ZERO:due.min(amount(proof.path("cashBefore").path(asset.equals("USDT")?"usdtAvailable":"nexAvailable")).max(BigDecimal.ZERO));
                require(current.compareTo(prior.add(take))==0&&amount(after.subject().get("recovery_pending_"+s)).compareTo(due.subtract(take))==0);
                Object eventId=before.subject().get(s+"_event_id");if(eventId!=null) {
                    var old=one(before.events(),"id",eventId);var event=one(after.events(),"id",eventId);sameExcept(old,event,EVENT_MUTABLE);
                    require(number(event.get("version"))==number(old.get("version"))+(failure||target.signum()>0?1:0));
                    require((failure||target.signum()>0?(due.subtract(take).signum()>0?"RECOVERY_PENDING":"REVERSED"):old.get("status")).equals(event.get("status")));
                }
            }
            String expected=complete?(amount(after.subject().get("recovery_pending_usdt")).signum()>0||amount(after.subject().get("recovery_pending_nex")).signum()>0?"RECOVERY_PENDING":"REVERSED"):text(before.subject().get("status"));require(expected.equals(after.subject().get("status")));
        } else {
            require(before.entries().isEmpty()&&after.entries().isEmpty()&&before.events().size()==1&&after.events().size()==1&&equal(json.valueToTree(normalize(after.subject())),after.events().get(0)));
            var recovery=after.recovery();require(recovery!=null&&number(recovery.get("event_id"))==number(after.subject().get("id"))&&number(recovery.get("user_id"))==SupportDirectReferralReadFacade.customer(after)&&"PRODUCTION".equals(recovery.get("source_environment"))&&"".equals(recovery.get("run_id"))&&recovery.get("asset").equals(after.subject().get("currency")));
            require(text(recovery.get("order_no")).equals(originalOrder(after,true)));
            String release="F5-COMMISSION-"+after.key().substring(2)+"-RELEASE",reverse="F5-COMMISSION-"+after.key().substring(2)+"-REVERSE";var releases=before.ledgers().stream().filter(l->release.equals(l.get("bizNo"))).toList();
            require(releases.size()<=1);boolean credited=!releases.isEmpty()&&"SUCCESS".equals(releases.get(0).get("status"))&&"IN".equals(releases.get(0).get("direction"));
            boolean reversed=before.ledgers().stream().anyMatch(l->reverse.equals(l.get("bizNo"))&&"SUCCESS".equals(l.get("status"))&&"OUT".equals(l.get("direction")));
            BigDecimal target=credited&&!reversed?amount(before.subject().get("currency").equals("USDT")?before.subject().get("amount_usdt"):before.subject().get("amount_nex")):BigDecimal.ZERO;
            BigDecimal prior=before.recovery()==null?BigDecimal.ZERO:amount(before.recovery().get("recovered_amount")),due=target.subtract(prior).max(BigDecimal.ZERO);
            BigDecimal take=due.min(amount(proof.path("cashBefore").path(before.subject().get("currency").equals("USDT")?"usdtAvailable":"nexAvailable")).max(BigDecimal.ZERO));
            require(amount(recovery.get("target_amount")).compareTo(target)==0&&amount(recovery.get("recovered_amount")).compareTo(prior.add(take))==0&&amount(recovery.get("pending_amount")).compareTo(due.subtract(take))==0&&number(after.subject().get("version"))==number(before.subject().get("version"))+1&&(due.subtract(take).signum()>0?"RECOVERY_PENDING":"REVERSED").equals(after.subject().get("status")));
            if(before.recovery()!=null)sameExcept(before.recovery(),recovery,Set.of("recovered_amount","pending_amount","updated_at"));
            if(credited)originalCommission(before,releases.get(0),input);
        }
    }
    private void originalCredit(Map<String,Object> entry,Inputs input) {
        require("DIRECT_REFERRAL".equals(entry.get("sourceType"))&&"PRODUCTION".equals(entry.get("sourceEnvironment"))&&flag(entry.get("isDeleted"))==0&&amount(entry.get("recoveredAmount")).signum()==0);
        var op=one(input.operations(),"operationKey","EARNINGS:CREDIT:"+text(entry.get("entryNo")));var events=decode(op,input);var original=codec.readCurrentProof(text(op.get("sourceSnapshotJson"))).path("entry");
        require("SUPPORT_EARNINGS".equals(op.get("businessType"))&&"CREDIT".equals(op.get("phase"))&&"nx_earnings_release_entry".equals(op.get("sourceTable"))&&entry.get("entryNo").equals(op.get("sourceKey"))&&"PRODUCTION".equals(op.get("sourceEnvironment"))&&parties(op,input).equals(List.of(number(entry.get("userId"))))&&"ACTIVE".equals(text(original.path("status")))&&"DIRECT_REFERRAL".equals(text(original.path("sourceType"))));
        for(String key:CREDIT_IMMUTABLE)require(equal(original.path(key),entry.get(key)));
        require(events.size()==1&&events.get(0).id().equals("EARNINGS:"+entry.get("entryNo"))&&Set.of(Action.EARNINGS,Action.PROTECTED_EARNINGS).contains(events.get(0).action())&&events.get(0).to().equals(new Account(number(entry.get("userId")),text(entry.get("asset")),"available"))&&events.get(0).amount().compareTo(amount(entry.get("amount")))==0);
        // The first ACTIVE before-state is an actual historical row in this validated chain, not an ACTIVE override.
        if("ACTIVE".equals(entry.get("status"))) {
            var historical=new LinkedHashMap<>(entry);for(String field:List.of("createdAt","updatedAt","releasedAt"))if(historical.get(field)!=null)historical.put(field,time(historical.get(field)));
            new SupportFundEarningsEvidence(codec,json).verify(op,codec.readCurrentProof(text(op.get("sourceSnapshotJson"))),codec.readCurrentProof(text(op.get("sourceProofJson"))),events,new SupportFundEarningsEvidence.Inputs(List.of(historical),List.of(),List.of(),List.of(),input.raw(),input.precisions()));
        }
    }
    private void originalCommission(Source source,Map<String,Object> release,Inputs input) {
        String id=source.key().substring(2);var matches=input.operations().stream().filter(o->"SUPPORT_COMMISSION".equals(o.get("businessType"))&&"RELEASE".equals(o.get("phase"))&&id.equals(o.get("sourceKey"))).toList();require(matches.size()==1);
        var events=decode(matches.get(0),input);require(events.size()==1&&("COMMISSION:"+id+":RELEASE").equals(events.get(0).id())&&Set.of(Action.EARNINGS,Action.PROTECTED_EARNINGS).contains(events.get(0).action())&&events.get(0).amount().compareTo(amount(release.get("amount")))==0&&events.get(0).to().equals(new Account(SupportDirectReferralReadFacade.customer(source),text(source.subject().get("currency")),"available")));
        var snapshot=codec.readCurrentProof(text(matches.get(0).get("sourceSnapshotJson")));require(equal(snapshot.path("ledger"),release));
    }
    private void validateCurrent(Source source) {
        require(source.key().matches("S:[^\\s:]{1,96}|R:[1-9][0-9]{0,18}")&&SupportDirectReferralReadFacade.customer(source)>0&&flag(source.subject().get("support_user_sandbox"))==0&&flag(source.subject().get("support_user_deleted"))==0);
        if(source.key().startsWith("S:")) {
            require(source.key().substring(2).equals(source.subject().get("settlement_no"))&&"PRODUCTION".equals(source.subject().get("source_environment"))&&"".equals(source.subject().get("run_id")));
            if(Set.of("direct_purchase","network").contains(source.subject().get("source_type")))require(text(source.subject().get("source_ref")).equals(originalOrder(source,false)));
            for(String s:List.of("usdt","nex"))for(String prefix:List.of("amount_","cancelled_","recovered_","recovery_pending_"))require(amount(source.subject().get(prefix+s)).signum()>=0);
            var eventIds=new HashSet<Long>();for(String asset:List.of("USDT","UVT")) {
                String suffix=("USDT".equals(asset)?"usdt":"nex");Object id=source.subject().get(suffix+"_event_id");if(id==null)continue;
                require(number(id)>0&&eventIds.add(number(id)));var event=one(source.events(),"id",id);
                require(number(event.get("user_id"))==SupportDirectReferralReadFacade.customer(source)&&asset.equals(event.get("currency"))&&flag(event.get("is_deleted"))==0&&flag(event.get("support_user_sandbox"))==0&&flag(event.get("support_user_deleted"))==0);
                require(amount(event.get("amount_"+suffix)).compareTo(amount(source.subject().get("amount_"+suffix)))==0&&amount(event.get("amount_"+(asset.equals("USDT")?"nex":"usdt"))).signum()==0);
            }
            require(eventIds.size()==source.events().size());
            var assets=new HashSet<String>();for(var e:source.entries())require(assets.add(text(e.get("asset")))&&Set.of("USDT","UVT").contains(e.get("asset"))&&number(e.get("id"))>0&&number(e.get("sourceWalletId"))>0&&number(e.get("userId"))==SupportDirectReferralReadFacade.customer(source)&&text(e.get("sourceRef")).equals(source.key().substring(2)+":"+("USDT".equals(e.get("asset"))?"USDT":"NEX"))&&amount(e.get("amount")).compareTo(amount(source.subject().get("amount_"+("USDT".equals(e.get("asset"))?"usdt":"nex"))))==0);
        }else require(number(source.subject().get("id"))==Long.parseLong(source.key().substring(2))&&flag(source.subject().get("is_deleted"))==0&&Set.of("USDT","UVT").contains(source.subject().get("currency")));
        var seen=new HashSet<Long>();for(var l:source.ledgers())require(seen.add(number(l.get("id"))));
    }
    private String originalOrder(Source source,boolean requireRefund) {
        var roots=maps(json.valueToTree(normalize(source.subject().get("support_root_orders"))));require(!roots.isEmpty());
        for(var root:roots)validRootOrder(root);
        if(source.key().startsWith("R:"))for(var root:roots)require(number(root.get("support_traversal_max_depth"))<32&&flag(root.get("support_invalid_ancestor"))==0&&number(root.get("support_missing_ancestor"))==0&&number(root.get("support_max_parent_count"))==1&&number(root.get("support_initial_parent_count"))==1);
        long depth=roots.stream().mapToLong(r->number(r.get("support_root_depth"))).max().orElseThrow();require(depth<32);
        var selected=roots.stream().filter(r->number(r.get("support_root_depth"))==depth).toList();require(selected.size()==1);var order=selected.get(0);
        if(source.key().startsWith("R:"))require(depth==number(order.get("support_traversal_max_depth")));
        require(flag(order.get("is_deleted"))==0&&(!requireRefund||"REFUNDED".equals(order.get("payment_status"))||"REFUNDED".equals(order.get("order_status"))));return text(order.get("order_no"));
    }
    /** Only the owning order's actual paid lifecycle may advance outside this Direct transaction. */
    private boolean sourceFollows(Source before,Source after,Set<String> mutable) {
        if(!subjectFollows(before.subject(),after.subject(),mutable))return false;
        var a=new LinkedHashMap<String,Object>((Map<String,Object>)normalize(before));var b=new LinkedHashMap<String,Object>((Map<String,Object>)normalize(after));
        a.remove("subject");b.remove("subject");return equal(json.valueToTree(a),b);
    }
    private boolean subjectFollows(Map<String,Object> before,Map<String,Object> after,Set<String> mutable) {
        var previous=maps(json.valueToTree(normalize(before.get("support_root_orders"))));var current=maps(json.valueToTree(normalize(after.get("support_root_orders"))));
        if(previous.size()!=current.size())return false;
        for(int i=0;i<previous.size();i++) {
            var old=previous.get(i);var now=current.get(i);validRootOrder(old);validRootOrder(now);
            if(equal(json.valueToTree(normalize(old)),now))continue;
            boolean refund="PAID".equals(old.get("payment_status"))&&REFUNDABLE_ORDER_STATUSES.contains(old.get("order_status"))&&"REFUNDED".equals(now.get("payment_status"))&&"REFUNDED".equals(now.get("order_status"));
            boolean paidAdvance="PAID".equals(old.get("payment_status"))&&"PAID".equals(now.get("payment_status"))&&PAID_ORDER_ADVANCES.getOrDefault(old.get("order_status"),Set.of()).contains(now.get("order_status"));
            if(!refund&&!paidAdvance)return false;
            var a=new LinkedHashMap<>(old);var b=new LinkedHashMap<>(now);for(String field:List.of("payment_status","order_status")){a.remove(field);b.remove(field);}if(!equal(json.valueToTree(normalize(a)),b))return false;
        }
        var a=new LinkedHashMap<>(before);var b=new LinkedHashMap<>(after);a.remove("support_root_orders");b.remove("support_root_orders");mutable.forEach(k->{a.remove(k);b.remove(k);});return equal(json.valueToTree(normalize(a)),b);
    }
    private void validRootOrder(Map<String,Object> order) {
        require(number(order.get("id"))>0&&number(order.get("user_id"))>0&&flag(order.get("is_deleted"))==0);text(order.get("order_no"));time(order.get("paid_at"));
        require("PAID".equals(order.get("payment_status"))&&REFUNDABLE_ORDER_STATUSES.contains(order.get("order_status"))||"REFUNDED".equals(order.get("payment_status"))&&"REFUNDED".equals(order.get("order_status")));
    }
    private void initial(Source before) {
        if(before.key().startsWith("S:")) {
            for(String s:List.of("usdt","nex"))for(String prefix:List.of("cancelled_","recovered_","recovery_pending_"))require(amount(before.subject().get(prefix+s)).signum()==0);
            require(flag(before.subject().get("reversal_recorded"))==0);for(var e:before.entries())require("ACTIVE".equals(e.get("status")));
        }else require(before.recovery()==null&&!"RECOVERY_PENDING".equals(before.subject().get("status"))&&(!"REVERSED".equals(before.subject().get("status"))||before.ledgers().stream().anyMatch(l->("F5-COMMISSION-"+before.key().substring(2)+"-REVERSE").equals(l.get("bizNo")))));
        require(before.ledgers().stream().noneMatch(l->directOut(before,l)));
    }
    private boolean changed(Source s) {if(s.key().startsWith("R:"))return s.recovery()!=null||s.ledgers().stream().anyMatch(l->directOut(s,l))||Set.of("RECOVERY_PENDING","REVERSED").contains(s.subject().get("status"))&&s.ledgers().stream().noneMatch(l->("F5-COMMISSION-"+s.key().substring(2)+"-REVERSE").equals(l.get("bizNo")));return flag(s.subject().get("reversal_recorded"))==1||Set.of("RECOVERY_PENDING","REVERSED").contains(s.subject().get("status"))||List.of("cancelled_usdt","cancelled_nex","recovered_usdt","recovered_nex","recovery_pending_usdt","recovery_pending_nex").stream().anyMatch(k->amount(s.subject().get(k)).signum()>0)||s.entries().stream().anyMatch(e->"REVERSED".equals(e.get("status")));}
    private static boolean directOut(Source s,Map<String,Object> l) {return "OUT".equals(l.get("direction"))&&text(l.get("bizNo")).startsWith(s.key().startsWith("S:")?s.key().substring(2)+"-RECOVER-":"DR-REISSUE-"+s.key().substring(2)+"-RECOVER-");}
    private void cash(Map<String,Object> op,JsonNode proof,List<Event> events,Inputs input,List<Point> points,List<Checkpoint> checkpoints) {
        var before=proof.path("cashBefore");var after=proof.path("cashAfter");String incarnation=text(before.path("incarnation"));long revision=number(before.path("revision")),user=number(before.path("userId"));
        require(incarnation.equals(text(after.path("incarnation")))&&number(after.path("userId"))==user&&flagNode(before.path("sandbox"))==0&&flagNode(after.path("sandbox"))==0&&flagNode(before.path("isDeleted"))==0&&flagNode(after.path("isDeleted"))==0);
        require(proof.path("cashTrace").isArray());var rows=input.raw().stream().filter(r->incarnation.equals(r.get("incarnation"))&&number(r.get("revision"))>revision&&number(r.get("revision"))<=number(after.path("revision"))).toList();
        var legs=input.legs().stream().filter(l->number(l.get("operationId"))==number(op.get("id"))).toList();
        if(events.isEmpty()) {
            require(equal(before,after)&&rows.isEmpty()&&legs.isEmpty()&&proof.path("cashTrace").isEmpty());var anchors=input.raw().stream().filter(r->incarnation.equals(r.get("incarnation"))&&number(r.get("revision"))==revision).toList();require(anchors.size()==1);walletRaw(after,anchors.get(0),"new");
            long predecessor=number(proof.path("previousOperationId"));require(predecessor>0&&parties(one(input.operations(),"id",predecessor),input).contains(user));
            checkpoints.add(new Checkpoint(incarnation,revision,user,cash(after),predecessor,ZeroPhase.RECOVERY_NO_CASH));
        }else {
            require(rows.size()==1&&legs.size()==1&&number(after.path("revision"))==revision+1&&number(legs.get(0).get("revision"))==revision+1&&incarnation.equals(legs.get(0).get("incarnation"))&&proof.path("cashTrace").size()==1);
            var row=rows.get(0);walletRaw(before,row,"old");walletRaw(after,row,"new");var trace=proof.path("cashTrace").get(0);keys(trace,Set.of("sourceId","eventIds","journal"));require((op.get("sourceTable")+":"+op.get("sourceKey")).equals(text(trace.path("sourceId")))&&equal(trace.path("journal"),row)&&equal(trace.path("eventIds"),events.stream().map(Event::id).toList()));
            Cash b=cash(before),a=cash(after);BigDecimal u=events.stream().filter(e->e.from().currency().equals("USDT")).map(Event::amount).reduce(BigDecimal.ZERO,BigDecimal::add),n=events.stream().filter(e->e.from().currency().equals("UVT")).map(Event::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            require(a.usdt().compareTo(b.usdt().subtract(u))==0&&a.nex().compareTo(b.nex().subtract(n))==0&&a.pending().equals(b.pending())&&a.risk().equals(b.risk()));points.add(new Point(incarnation,revision+1,user,b,a,events.stream().map(Event::id).toList()));
        }
    }
    private void walletRaw(JsonNode wallet,Map<String,Object> raw,String prefix) {for(String k:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","createdAt","sandbox","isDeleted")){Object actual=raw.get(prefix+Character.toUpperCase(k.charAt(0))+k.substring(1));require(Set.of("sandbox","isDeleted").contains(k)?flagNode(wallet.path(k))==flag(actual):equal(wallet.path(k),actual));}}
    private Cash cash(JsonNode wallet) {return new Cash(amount(wallet.path("usdtAvailable")),amount(wallet.path("nexAvailable")),amount(wallet.path("pendingWithdraw")),amount(wallet.path("cregisRiskHeld")));}
    private List<Event> decode(Map<String,Object> op,Inputs input) {var stored=input.legs().stream().filter(l->number(l.get("operationId"))==number(op.get("id"))).sorted(Comparator.comparing((Map<String,Object> l)->text(l.get("incarnation"))).thenComparingLong(l->number(l.get("revision")))).toList();return codec.decodeReceipt(op,parties(op,input),stored);}
    private List<Long> parties(Map<String,Object> op,Inputs input) {return input.parties().stream().filter(p->number(p.get("operationId"))==number(op.get("id"))).map(p->number(p.get("customerId"))).sorted().toList();}
    private Map<Long,List<Map<String,Object>>> indexParties(Inputs input) {
        if(input.operations().stream().noneMatch(op->"SUPPORT_DIRECT_REFERRAL".equals(op.get("businessType"))))return Map.of();
        var index=new HashMap<Long,List<Map<String,Object>>>();
        for(var row:input.parties())index.computeIfAbsent(number(row.get("operationId")),id->new ArrayList<>()).add(row);
        return index;
    }
    private List<Long> parties(Map<String,Object> op,Map<Long,List<Map<String,Object>>> index) {
        if(index.isEmpty())return List.of();
        return index.getOrDefault(number(op.get("id")),List.of()).stream().map(p->number(p.get("customerId"))).sorted().toList();
    }
    private Source source(JsonNode node) {keys(node,Set.of("key","subject","events","entries","recovery","ledgers"));return new Source(text(node.path("key")),map(node.path("subject")),maps(node.path("events")),maps(node.path("entries")),node.path("recovery").isNull()?null:map(node.path("recovery")),maps(node.path("ledgers")));}
    private Map<String,Object> map(JsonNode n) {require(n.isObject());return json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).convertValue(n,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private List<Map<String,Object>> maps(JsonNode n) {require(n.isArray());var r=new ArrayList<Map<String,Object>>();n.forEach(v->r.add(map(v)));return r;}
    private void sameExcept(Map<String,Object> before,Map<String,Object> after,Set<String> mutable) {var a=new LinkedHashMap<>(before);var b=new LinkedHashMap<>(after);mutable.forEach(k->{a.remove(k);b.remove(k);});require(equal(json.valueToTree(normalize(a)),b));}
    private boolean equal(JsonNode a,Object b) {return codec.canonical(a).equals(codec.canonical(json.valueToTree(normalize(b))));}
    private static String fingerprint(Map<String,Object> entry,SupportFundJournalBindingService codec,ObjectMapper json) {var projection=new LinkedHashMap<>(entry);for(String key:List.of("clusterId","releasedAt","releaseSource"))projection.putIfAbsent(key,null);return codec.canonical(json.valueToTree(normalize(projection))).toString();}
    private static Object normalize(Object value) {if(value instanceof Source s){var r=new LinkedHashMap<String,Object>();r.put("key",s.key());r.put("subject",normalize(s.subject()));r.put("events",normalize(s.events()));r.put("entries",normalize(s.entries()));r.put("recovery",normalize(s.recovery()));r.put("ledgers",normalize(s.ledgers()));return r;}if(value instanceof Timestamp t)return t.toLocalDateTime().toString();if(value instanceof LocalDateTime t)return t.toString();if(value instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,v)->r.put(k.toString(),normalize(v)));return r;}if(value instanceof Collection<?> rows)return rows.stream().map(SupportFundDirectReferralEvidence::normalize).toList();return value;}
    private static Map<String,Object> one(List<Map<String,Object>> rows,String key,Object value) {var matches=rows.stream().filter(r->Objects.equals(r.get(key),value)||r.get(key) instanceof Number&&value instanceof Number&&amount(r.get(key)).compareTo(amount(value))==0).toList();require(matches.size()==1);return matches.get(0);}
    private static String text(Object v) {require(v instanceof String&&!((String)v).isBlank());return (String)v;}
    private static String text(JsonNode v) {require(v.isTextual());return text(v.textValue());}
    private static BigDecimal amount(Object v) {require(v instanceof Number);var a=new BigDecimal(v.toString());require(a.stripTrailingZeros().scale()<=6);return a;}
    private static BigDecimal amount(JsonNode v) {require(v.isNumber());return amount(v.numberValue());}
    private static long number(Object v) {try{return amount(v).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Invalid Direct source identifier",e);}}
    private static long number(JsonNode v) {require(v.isNumber());return number(v.numberValue());}
    private static int flag(Object v) {require(v!=null);return SupportPaymentFactService.flag(Map.of("flag",v),"flag");}
    private static int flagNode(JsonNode v) {if(v.isBoolean())return v.booleanValue()?1:0;require(v.isNumber());return flag(v.numberValue());}
    private static LocalDateTime time(Object v) {if(v instanceof Timestamp t)return t.toLocalDateTime();if(v instanceof LocalDateTime t)return t;if(v instanceof String s)return LocalDateTime.parse(s);throw new IllegalArgumentException("Invalid Direct source time");}
    private static LocalDateTime time(JsonNode v) {return time(text(v));}
    private static void keys(JsonNode n,Set<String> fields) {require(n.isObject());var actual=new HashSet<String>();n.fieldNames().forEachRemaining(actual::add);require(actual.equals(fields));}
    private static void require(boolean valid) {if(!valid)throw new IllegalArgumentException("Unproven Direct referral recovery");}
}
