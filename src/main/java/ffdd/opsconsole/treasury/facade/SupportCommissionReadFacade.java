package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.*;

/** Treasury originals, read inside the caller's authorized snapshot. */
public interface SupportCommissionReadFacade extends DomainFacade {
    Sources readFundCommissions(Collection<Long> customerIds);
    Map<String,Integer> timePrecisions();
    record Sources(List<Map<String,Object>> events,List<Map<String,Object>> ledgers) {
        public Sources { events=copy(events);ledgers=copy(ledgers); }
        private static List<Map<String,Object>> copy(List<Map<String,Object>> rows) {
            return Objects.requireNonNull(rows).stream().map(row->Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(row)))).toList();
        }
    }
}
