package ffdd.opsconsole.market.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Internal originals for G1 and G7; no claim of capture completeness. */
public interface SupportStakingReadFacade extends DomainFacade {
    List<Source> readByCustomers(Collection<Long> ids);
    List<Source> readByPositionNos(Collection<String> nos);
    Map<String,Integer> timePrecisions();
    record Source(Map<String,Object> position,List<Map<String,Object>> ledgers) {
        public Source {
            var row=new LinkedHashMap<>(position);
            // MyBatis Map results omit SQL NULLs unless callSettersOnNulls is enabled.
            // These two columns are nullable in the fixed projection; identities and flags get no defaults.
            row.putIfAbsent("claimedAt",null);row.putIfAbsent("earlyWithdrawnAt",null);
            position=Collections.unmodifiableMap(row);
            ledgers=ledgers.stream().map(r->Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
        }
    }
    static String operationKey(String no,String phase) {
        try{return "STAKING:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((no+"\n"+phase).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** A single factory shared by the capture and its independent source validator. */
    static List<Event> events(Map<String,Object> p,String phase) {
        if(!(p.get("customerId") instanceof Number n)||!(p.get("positionNo") instanceof String no)||!no.matches("[^\\s:]{1,96}"))throw new IllegalArgumentException("Invalid staking identity");
        long customer=new BigDecimal(n.toString()).longValueExact();
        BigDecimal principal=money(p.get("amountUsdt")),interest=money(p.get("estimatedInterestUsdt"));
        if(principal.signum()<=0||interest.signum()<0)throw new IllegalArgumentException("Invalid staking amount");
        var a=new Account(customer,"USDT","available");var h=new Account(customer,"USDT","held:staking-"+no);
        String id=operationKey(no,phase);
        switch(phase) {
            case "OPEN": return List.of(new Event(id+":0",Action.MOVE,a,h,null,principal,null));
            case "CLAIM": {
                var result=new ArrayList<Event>();result.add(new Event(id+":0",Action.MOVE,h,a,null,principal,null));
                if("REPURCHASE_90D".equals(p.get("productCode"))&&interest.signum()>0)result.add(new Event(id+":1",Action.EARNINGS,null,a,null,interest,null));
                return List.copyOf(result);
            }
            case "EARLY": {
                BigDecimal penalty=penalty(p);if(penalty.signum()<0||penalty.compareTo(principal)>0)throw new IllegalArgumentException("Invalid staking penalty");
                var result=new ArrayList<Event>();
                if(penalty.signum()>0)result.add(new Event(id+":0",Action.FEE,h,null,null,penalty,null));
                if(penalty.compareTo(principal)<0)result.add(new Event(id+":"+result.size(),Action.MOVE,h,a,null,principal.subtract(penalty),null));
                return List.copyOf(result);
            }
            default: throw new IllegalArgumentException("Unsupported staking phase");
        }
    }
    static BigDecimal penalty(Map<String,Object> p) {
        var pct=money(p.get("earlyPenaltyBps")).divide(BigDecimal.valueOf(100),6,RoundingMode.HALF_UP);
        return money(p.get("amountUsdt")).multiply(pct).divide(BigDecimal.valueOf(100),6,RoundingMode.HALF_UP);
    }
    private static BigDecimal money(Object v){if(!(v instanceof Number))throw new IllegalArgumentException("Numeric staking amount required");return new BigDecimal(v.toString()).setScale(6,RoundingMode.UNNECESSARY);}
}
