package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import ffdd.opsconsole.market.mapper.SupportStakingSourceMapper;
import java.util.*;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupportStakingReadService implements SupportStakingReadFacade {
    private final SupportStakingSourceMapper mapper;
    @Override public List<Source> readByCustomers(Collection<Long> ids) {
        if(ids==null||ids.isEmpty())return List.of();
        if(ids.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("Invalid staking customer scope");
        return sources(mapper.byCustomers(new TreeSet<>(ids)));
    }
    @Override public List<Source> readByPositionNos(Collection<String> nos) {
        if(nos==null||nos.isEmpty())return List.of();
        if(nos.stream().anyMatch(no->no==null||!no.matches("[^\\s:]{1,96}")))throw new IllegalArgumentException("Invalid staking source scope");
        return sources(mapper.byPositionNos(new TreeSet<>(nos)));
    }
    private List<Source> sources(List<Map<String,Object>> positions) {
        if(positions.isEmpty())return List.of();
        var nos=positions.stream().map(p->{if(!(p.get("positionNo") instanceof String s))throw new IllegalArgumentException("Invalid staking source key");return s;}).distinct().toList();
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var row:mapper.ledgers(nos)) {if(!(row.get("positionNo") instanceof String no)||!nos.contains(no))throw new IllegalArgumentException("Foreign staking ledger");grouped.computeIfAbsent(no,k->new ArrayList<>()).add(row);}
        return positions.stream().map(p->new Source(p,grouped.getOrDefault(p.get("positionNo"),List.of()))).toList();
    }
    @Override public Map<String,Integer> timePrecisions() {
        var result=new LinkedHashMap<String,Integer>();
        for(var row:mapper.timePrecisions()) {
            if(!"nx_staking_position".equals(row.get("tableName"))||!Set.of("created_at","claimed_at","early_withdrawn_at").contains(row.get("columnName"))||!(row.get("precision") instanceof Number n))throw new IllegalArgumentException("Unproven staking time precision");
            int p=new BigDecimal(n.toString()).intValueExact();String key="nx_staking_position."+row.get("columnName");
            if(p<0||p>6||result.put(key,p)!=null)throw new IllegalArgumentException("Unproven staking time precision");
        }
        return Collections.unmodifiableMap(result);
    }
}
