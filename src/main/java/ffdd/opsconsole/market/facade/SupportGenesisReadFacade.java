package ffdd.opsconsole.market.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Internal original Genesis rows, including contradictory or deleted rows. No public customer projection. */
public interface SupportGenesisReadFacade extends DomainFacade {
    List<Source> readByCustomers(Collection<Long> customerIds);
    List<Source> readByOrderNos(Collection<String> orderNos);
    Map<String,Object> readHolding(String holdingNo);
    record Source(Map<String,Object> order,List<Map<String,Object>> ledgers) {
        public Source {
            order=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(order));
            ledgers=ledgers.stream().map(r->java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(r))).toList();
        }
    }
}
