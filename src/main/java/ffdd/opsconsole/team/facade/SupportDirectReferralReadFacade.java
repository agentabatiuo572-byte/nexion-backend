package ffdd.opsconsole.team.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Actual recovery originals; capture completeness is established by the finance reader. */
public interface SupportDirectReferralReadFacade extends DomainFacade {
    List<Source> readByCustomers(Collection<Long> ids);
    List<Source> readByKeys(Collection<String> keys);
    Map<String,Integer> timePrecisions();
    record Source(String key,Map<String,Object> subject,List<Map<String,Object>> events,
                  List<Map<String,Object>> entries,Map<String,Object> recovery,List<Map<String,Object>> ledgers) {
        public Source {
            subject=immutable(subject);events=events.stream().map(SupportDirectReferralReadFacade::immutable).toList();
            entries=entries.stream().map(SupportDirectReferralReadFacade::immutable).toList();
            recovery=recovery==null?null:immutable(recovery);ledgers=ledgers.stream().map(SupportDirectReferralReadFacade::immutable).toList();
        }
    }
    static Map<String,Object> immutable(Map<String,Object> row) {var result=new LinkedHashMap<String,Object>();row.forEach((k,v)->result.put(k,copyValue(v)));return Collections.unmodifiableMap(result);}
    private static Object copyValue(Object value) {if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),copyValue(v)));return Collections.unmodifiableMap(result);}if(value instanceof Collection<?> rows)return rows.stream().map(SupportDirectReferralReadFacade::copyValue).toList();return value;}
    static long customer(Source source) {return number(source.subject().get(source.key().startsWith("S:")?"beneficiary_user_id":"user_id"));}
    static long number(Object value) {if(!(value instanceof Number))throw new IllegalArgumentException("Invalid Direct recovery identifier");try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Invalid Direct recovery identifier",e);}}
    static BigDecimal amount(Object value) {if(!(value instanceof Number))throw new IllegalArgumentException("Invalid Direct recovery amount");return new BigDecimal(value.toString()).setScale(6,java.math.RoundingMode.UNNECESSARY);}
    static String operationKey(String key,long predecessor,Source after) {
        var state=new StringBuilder(key).append('\n').append(predecessor);
        if(key.startsWith("S:"))for(String field:List.of("cancelled_usdt","cancelled_nex","recovered_usdt","recovered_nex","recovery_pending_usdt","recovery_pending_nex","status","updated_at"))state.append('\n').append(stable(after.subject().get(field)));
        else {state.append('\n').append(number(after.subject().get("version")));for(String field:List.of("event_id","order_no","source_environment","run_id","user_id","asset","target_amount","recovered_amount","pending_amount","updated_at"))state.append('\n').append(stable(after.recovery().get(field)));}
        try{return "DIRECT_RECOVERY:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static Object stable(Object value) {if(value instanceof Number)return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().toString();if(value instanceof java.time.LocalDateTime t)return t.toString();return value;}
    static List<Event> events(Source before,Source after,String operationKey) {
        var events=new ArrayList<Event>();long user=customer(after);
        for(String asset:List.of("USDT","NEX")) {
            BigDecimal prior,current;String original;
            if(after.key().startsWith("S:")) {
                String suffix=asset.toLowerCase(Locale.ROOT);prior=amount(before.subject().get("recovered_"+suffix));current=amount(after.subject().get("recovered_"+suffix));
                var linked=before.entries().stream().filter(e->asset.equals(e.get("asset"))).toList();
                if(current.compareTo(prior)==0)continue;
                original=linked.size()==1?"EARNINGS:"+linked.get(0).get("entryNo"):"DIRECT_REFERRAL_UNVERIFIED:"+after.key()+":"+asset;
            } else {
                if(!asset.equals(after.subject().get("currency")))continue;
                prior=before.recovery()==null?BigDecimal.ZERO:amount(before.recovery().get("recovered_amount"));current=amount(after.recovery().get("recovered_amount"));
                original="COMMISSION:"+number(after.subject().get("id"))+":RELEASE";
            }
            BigDecimal take=current.subtract(prior);if(take.signum()<0)throw new IllegalArgumentException("Regressed Direct recovery");
            if(take.signum()>0)events.add(new Event(operationKey+":"+asset,Action.valueOf("CLAWBACK"),new Account(user,asset,"available"),null,original,take,null));
        }
        return List.copyOf(events);
    }
}
