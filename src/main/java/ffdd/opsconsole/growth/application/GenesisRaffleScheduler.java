package ffdd.opsconsole.growth.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GenesisRaffleScheduler {
    private final GenesisRaffleService service;
    @Scheduled(fixedDelay=30_000,initialDelay=30_000)
    public void tick(){
        for(String id:service.due()){
            try{
                service.draw(id);
                service.markAttempt(id);
                for(var award:service.pending(id)){
                    try{service.pay(id,String.valueOf(award.get("prize_id")),((Number)award.get("award_index")).intValue());}
                    catch(RuntimeException ex){log.error("Genesis raffle {} prize {} remains pending for its original winner",id,award.get("prize_id"),ex);}
                }
            }catch(RuntimeException ex){log.error("Genesis raffle {} remains pending",id,ex);}
        }
    }
}
