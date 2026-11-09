package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskWakeMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Publication releases configuration/epoch locks before touching runtime rows. No new queue or thread. */
@Service
public class SimulatedTaskWakeService {
    private final SimulatedTaskWakeMapper mapper;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public SimulatedTaskWakeService(SimulatedTaskWakeMapper mapper,Clock clock,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.clock=clock;this.json=json;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void afterCommitProducts(List<String> products) {afterCommit(()->wakeProducts(products));}
    public void afterCommitPools(List<String> pools) {
        final String encoded;
        try {encoded=json.writeValueAsString(pools);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        afterCommit(()->wakeProducts(transaction.execute(s->mapper.products(encoded))));
    }
    private void afterCommit(Runnable action) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("SIM_WAKE_REQUIRES_PUBLICATION_TRANSACTION");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // A failed wake must not turn an already committed publication into a failed command.
                try {action.run();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(SimulatedTaskWakeService.class).warn("SIM post-commit wake failed; idle safety net will retry",e);}
            }
        });
    }
    private void wakeProducts(List<String> products) {
        for(var product:products) {
            long cursor=0;
            while(true) {
                final long after=cursor;
                var ids=transaction.execute(s->{
                    var batch=mapper.waiting(product,after);
                    if(!batch.isEmpty())mapper.wake(batch,LocalDateTime.now(clock));
                    return batch;
                });
                if(ids.size()<500)break;
                cursor=ids.get(ids.size()-1);
            }
        }
    }
    /** Preserve a whole pause/resume pair even if the due worker has not observed the pause yet. */
    public void pause(long user,Long device,LocalDateTime at) {mapper.pause(user,device,at);}
    public void resume(long user,Long device,LocalDateTime at) {mapper.resume(user,device,at);}
}
