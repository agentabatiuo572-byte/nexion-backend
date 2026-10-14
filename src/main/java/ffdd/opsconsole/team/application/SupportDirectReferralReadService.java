package ffdd.opsconsole.team.application;

import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade;
import ffdd.opsconsole.team.mapper.SupportDirectReferralReadMapper;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupportDirectReferralReadService implements SupportDirectReferralReadFacade {
    private final SupportDirectReferralReadMapper mapper;
    @Override public List<Source> readByCustomers(Collection<Long> ids) {
        if(ids==null)throw new IllegalArgumentException("Invalid Direct recovery scope");
        if(ids.isEmpty())return List.of();
        if(ids.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("Invalid Direct recovery scope");
        return sources(mapper.settlementsByCustomers(new TreeSet<>(ids)),mapper.reissuesByCustomers(new TreeSet<>(ids)));
    }
    @Override public List<Source> readByKeys(Collection<String> keys) {
        if(keys==null)throw new IllegalArgumentException("Invalid Direct recovery source keys");
        if(keys.isEmpty())return List.of();var nos=new TreeSet<String>();var events=new TreeSet<Long>();
        for(String key:keys) {
            if(key!=null&&key.matches("S:[^\\s:]{1,96}"))nos.add(key.substring(2));
            else if(key!=null&&key.matches("R:[1-9][0-9]{0,18}"))events.add(Long.parseLong(key.substring(2)));
            else throw new IllegalArgumentException("Invalid Direct recovery source key");
        }
        return sources(nos.isEmpty()?List.of():mapper.settlementsByNos(nos),events.isEmpty()?List.of():mapper.eventsByIds(events));
    }
    private List<Source> sources(List<Map<String,Object>> settlements,List<Map<String,Object>> reissues) {
        var result=new ArrayList<Source>();
        for(var s:rows(settlements)) {
            String type=text(s,"source_type"),no=text(s,"settlement_no");
            s=new LinkedHashMap<>(s);s.put("support_root_orders",Set.of("direct_purchase","network").contains(type)?rows(mapper.orders(text(s,"source_ref"))):List.of());
            var ids=new TreeSet<Long>();
            for(String key:List.of("usdt_event_id","nex_event_id"))if(s.get(key)!=null)ids.add(SupportDirectReferralReadFacade.number(s.get(key)));
            result.add(new Source("S:"+no,s,ids.isEmpty()?List.of():rows(mapper.eventsByIds(ids)),rows(mapper.entries(no)),null,rows(mapper.ledgers(no+"-RELEASE",no+"-RECOVER-"))));
        }
        for(var e:rows(reissues)) {
            long id=SupportDirectReferralReadFacade.number(e.get("id"));var recovery=rows(mapper.recoveries(id));
            e=new LinkedHashMap<>(e);e.put("support_root_orders",rows(mapper.reissueRootOrders(id)));
            if(recovery.size()>1)throw new IllegalArgumentException("Ambiguous Direct event recovery");
            result.add(new Source("R:"+id,e,List.of(e),List.of(),recovery.isEmpty()?null:recovery.get(0),rows(mapper.ledgers("F5-COMMISSION-"+id+"-RELEASE","DR-REISSUE-"+id+"-RECOVER-"))));
        }
        return List.copyOf(result);
    }
    @Override public Map<String,Integer> timePrecisions() {
        var result=new TreeMap<String,Integer>();
        for(var row:rows(mapper.timePrecisions())) {
            String table=text(row,"tableName"),column=text(row,"columnName");
            if(!(Set.of("nx_direct_referral_settlement","nx_unilevel_event_recovery","nx_commission_event").contains(table)&&"updated_at".equals(column)||"nx_earnings_release_entry".equals(table)&&"created_at".equals(column))||!(row.get("precision") instanceof Number n))throw new IllegalArgumentException("Unproven Direct recovery time");
            int p=new BigDecimal(n.toString()).intValueExact();if(p<0||p>6||result.put(table+"."+column,p)!=null)throw new IllegalArgumentException("Unproven Direct recovery time");
        }
        return Collections.unmodifiableMap(result);
    }
    private static List<Map<String,Object>> rows(List<Map<String,Object>> rows) {
        if(rows==null)throw new IllegalStateException("Missing Direct recovery rows");
        for(Object row:rows) {
            if(!(row instanceof Map<?,?>))throw new IllegalStateException("Invalid Direct recovery row");
            checkCopyValue(row);
        }
        return rows;
    }
    private static void checkCopyValue(Object value) {
        if(value instanceof Map<?,?> map) {
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String))throw new IllegalStateException("Invalid Direct recovery column");
                checkCopyValue(entry.getValue());
            }
        } else if(value instanceof Collection<?> values)for(Object item:values)checkCopyValue(item);
    }
    private static String text(Map<String,Object> row,String field) {
        if(!(row.get(field) instanceof String value)||value.isBlank())throw new IllegalArgumentException("Invalid Direct recovery field: "+field);
        return value;
    }
}
