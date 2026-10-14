package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.treasury.facade.SupportCommissionReadFacade;
import ffdd.opsconsole.treasury.mapper.SupportCommissionReadMapper;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;

@ApplicationService @RequiredArgsConstructor
public class SupportCommissionReadService implements SupportCommissionReadFacade {
    private final SupportCommissionReadMapper mapper;
    @Override public Sources readFundCommissions(Collection<Long> customerIds) {
        if(customerIds==null||customerIds.size()>4096||customerIds.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("INVALID_SUPPORT_COMMISSION_SCOPE");
        var scope=List.copyOf(new TreeSet<>(customerIds));if(scope.isEmpty())return new Sources(List.of(),List.of());
        var events=mapper.fundEvents(scope);var ledgers=mapper.fundLedgers(scope);
        for(var rows:Arrays.asList(events,ledgers)){
            if(rows==null)throw new IllegalStateException("INVALID_SUPPORT_COMMISSION_ROWS");
            for(var row:rows)if(row==null||!(row.get("customerId") instanceof Number n)||!scope.contains(new BigDecimal(n.toString()).longValueExact()))throw new IllegalStateException("INVALID_SUPPORT_COMMISSION_ROWS");
        }
        return new Sources(events,ledgers);
    }
    @Override public Map<String,Integer> timePrecisions() {
        var rows=mapper.fundTimePrecisions();if(rows==null)throw new IllegalStateException("INVALID_SUPPORT_COMMISSION_PRECISION");
        var allowed=Set.of("nx_wallet_ledger.created_at","nx_commission_event.created_at","nx_commission_event.updated_at");var result=new TreeMap<String,Integer>();
        for(var row:rows){if(row==null||!(row.get("precision") instanceof Number n))throw new IllegalStateException("INVALID_SUPPORT_COMMISSION_PRECISION");String key=row.get("tableName")+"."+row.get("columnName");int p=new BigDecimal(n.toString()).intValueExact();if(!allowed.contains(key)||p<0||p>6||result.put(key,p)!=null)throw new IllegalStateException("INVALID_SUPPORT_COMMISSION_PRECISION");}
        return Map.copyOf(result);
    }
}
