package ffdd.opsconsole.market.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original exchange rows, including missing fees and contradictory/deleted rows, for internal verification. */
public interface SupportExchangeReadFacade extends DomainFacade {
    /** Expected events after the caller independently validates the originals. */
    List<ffdd.opsconsole.finance.domain.SupportFundAllocation.Event> events(Source source,String phase);
    Map<String,Integer> timePrecisions();
    List<Source> readByCustomers(Collection<Long> customerIds);
    List<Source> readByOrderNos(Collection<String> orderNos);
    record Source(Map<String,Object> order,List<Map<String,Object>> ledgers,List<Map<String,Object>> feeAllocations) {
        public Source {
            order=Collections.unmodifiableMap(new LinkedHashMap<>(order));
            ledgers=ledgers.stream().map(r->Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
            feeAllocations=feeAllocations.stream().map(r->Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
        }
    }
}
