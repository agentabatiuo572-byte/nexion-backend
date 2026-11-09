package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One committed, consistent-read history. Never publish a caller's uncommitted configuration. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class SimulatedTaskPolicyHistory {
    public static final int MAX_VERSIONS = 20000;
    public static final long MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private final SimulatedTaskPolicyMapper mapper;
    public record History(long epoch, List<TypeVersion> types, List<ProductVersion> products, List<E3Version> e3,
            boolean cacheable) {
        public History { types=List.copyOf(types); products=List.copyOf(products); e3=List.copyOf(e3); }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.REPEATABLE_READ, readOnly=true)
    public History load() {
        long epoch=mapper.epoch();
        // Bound retained and transient history. Oversized histories use the original indexed SQL resolver.
        if(mapper.historySize()>MAX_VERSIONS || mapper.historyPayloadBytes()>MAX_PAYLOAD_BYTES) {
            log.warn("SIM policy history guard exceeded epoch={}; resolving through SQL",epoch);
            return new History(epoch,List.of(),List.of(),List.of(),false);
        }
        return new History(epoch,mapper.typeHistory(),mapper.productHistory(),mapper.e3History(),true);
    }
}
