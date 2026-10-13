package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.market.mapper.SupportExchangeSourceMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupportExchangeReadService implements SupportExchangeReadFacade {
    private final SupportExchangeSourceMapper mapper;
    @Override public Map<String,Integer> timePrecisions(){
        var result=new LinkedHashMap<String,Integer>();for(var row:mapper.timePrecisions()){
            if(!"nx_exchange_order".equals(row.get("tableName"))||!"updated_at".equals(row.get("columnName"))||!(row.get("precision") instanceof Number n))throw new IllegalArgumentException("Unproven exchange timestamp precision");
            int p=new java.math.BigDecimal(n.toString()).intValueExact();if(p<0||p>6||result.put("nx_exchange_order.updated_at",p)!=null)throw new IllegalArgumentException("Unproven exchange timestamp precision");
        }return Collections.unmodifiableMap(result);
    }
    @Override public List<ffdd.opsconsole.finance.domain.SupportFundAllocation.Event> events(Source source,String phase){
        var o=source.order();String no=(String)o.get("exchangeNo");
        boolean held=source.ledgers().stream().anyMatch(l->(no+"-HOLD").equals(l.get("bizNo"))&&"EXCHANGE_SWAP".equals(l.get("bizType"))&&"OUT".equals(l.get("direction"))&&"SUCCESS".equals(l.get("status"))&&zero(l.get("deleted")));
        if("CANCEL".equals(phase)&&!held)return List.of();
        if(source.feeAllocations().size()>1)throw new IllegalArgumentException("Ambiguous exchange fee source");
        var fee=Set.of("IMMEDIATE","COMPLETE").contains(phase)&&!source.feeAllocations().isEmpty()?amount(source.feeAllocations().get(0).get("totalFeeUsdt")):java.math.BigDecimal.ZERO;
        return SupportExchangeFundAdapter.events(new java.math.BigDecimal(o.get("customerId").toString()).longValueExact(),no,"COMPLETE".equals(phase)&&!held?"IMMEDIATE":phase,(String)o.get("fromAsset"),amount(o.get("fromAmount")),amount(o.get("toAmount")),fee);
    }
    private static java.math.BigDecimal amount(Object v){if(!(v instanceof Number))throw new IllegalArgumentException("Numeric exchange source required");return new java.math.BigDecimal(v.toString());}
    private static boolean zero(Object v){return Boolean.FALSE.equals(v)||v instanceof Number&&amount(v).signum()==0;}
    @Override public List<Source> readByCustomers(Collection<Long> ids){
        if(ids==null || ids.isEmpty())return List.of();
        if(ids.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("Invalid exchange customer scope");
        return sources(mapper.byCustomers(new TreeSet<>(ids)));
    }
    @Override public List<Source> readByOrderNos(Collection<String> ids){
        if(ids==null || ids.isEmpty())return List.of();
        if(ids.stream().anyMatch(id->id==null||id.isBlank()))throw new IllegalArgumentException("Invalid exchange source scope");
        return sources(mapper.byOrderNos(new TreeSet<>(ids)));
    }
    private List<Source> sources(List<Map<String,Object>> orders){
        if(orders.isEmpty())return List.of();
        var keys=orders.stream().map(o->o.get("exchangeNo").toString()).distinct().toList();
        var ledgers=group(mapper.ledgers(keys));var fees=group(mapper.fees(keys));
        return orders.stream().map(o->new Source(o,ledgers.getOrDefault(o.get("exchangeNo"),List.of()),fees.getOrDefault(o.get("exchangeNo"),List.of()))).toList();
    }
    private Map<String,List<Map<String,Object>>> group(List<Map<String,Object>> rows){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var row:rows)result.computeIfAbsent(row.get("exchangeNo").toString(),k->new ArrayList<>()).add(row);
        return result;
    }
}
