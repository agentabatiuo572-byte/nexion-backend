package ffdd.opsconsole.market.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.market.facade.SupportGenesisReadFacade;
import ffdd.opsconsole.market.mapper.SupportGenesisSourceMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;

/** Fixed batch reads of owned original sources. The finance reader owns its physical snapshot. */
@ApplicationService
@RequiredArgsConstructor
public class SupportGenesisReadService implements SupportGenesisReadFacade {
    private final SupportGenesisSourceMapper mapper;
    public List<Source> readByCustomers(Collection<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.stream().anyMatch(id->id==null || id<=0))throw new IllegalArgumentException("Explicit Genesis customer scope required");
        return sources(mapper.ordersByCustomers(List.copyOf(new TreeSet<>(ids))));
    }
    public List<Source> readByOrderNos(Collection<String> nos) {
        if(nos==null || nos.isEmpty() || nos.stream().anyMatch(no->no==null || no.isBlank()))throw new IllegalArgumentException("Explicit Genesis order references required");
        return sources(mapper.ordersByNos(List.copyOf(new TreeSet<>(nos))));
    }
    public Map<String,Object> readHolding(String no) {
        if(no==null || no.isBlank())throw new IllegalArgumentException("Genesis holding reference required");
        var row=mapper.holding(no);return row==null?null:java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row));
    }
    private List<Source> sources(List<Map<String,Object>> orders) {
        if(orders==null)throw new org.springframework.dao.DataRetrievalFailureException("GENESIS_SOURCE_READ_MISSING");
        if(orders.isEmpty())return List.of();
        var refs=new TreeSet<String>();
        for(var row:orders) {
            if(row==null || !(row.get("orderNo") instanceof String no) || no.isBlank())throw new org.springframework.dao.DataRetrievalFailureException("GENESIS_SOURCE_IDENTITY_MISSING");
            refs.add(no);refs.add(no+"-BUY");refs.add(no+"-SELL");
        }
        var ledgers=mapper.ledgers(List.copyOf(refs));
        if(ledgers==null ||ledgers.stream().anyMatch(l->l==null ||!(l.get("businessId") instanceof String)))
            throw new org.springframework.dao.DataRetrievalFailureException("GENESIS_LEDGER_READ_MISSING");
        return orders.stream().map(row->{String no=(String)row.get("orderNo");
            return new Source(row,ledgers.stream().filter(l->java.util.Set.of(no,no+"-BUY",no+"-SELL").contains(l.get("businessId"))).toList());}).toList();
    }
}
