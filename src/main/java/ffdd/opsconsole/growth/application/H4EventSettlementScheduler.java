package ffdd.opsconsole.growth.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static ffdd.opsconsole.growth.application.H4EventRuleService.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class H4EventSettlementScheduler {
    private final H4EventRuleRepository db;
    private final H4EventEngine engine;
    @Scheduled(fixedDelayString="${growth.h4.settlement-delay-ms:60000}")
    public void tick(){
        for(var row:db.rows("SELECT m.event_code,m.user_id FROM nx_growth_event_reminder m JOIN nx_growth_event_rule r ON r.event_code=m.event_code AND r.enabled=1 WHERE m.notified_at IS NULL AND r.starts_at<=UTC_TIMESTAMP(6) AND r.ends_at>UTC_TIMESTAMP(6) ORDER BY m.created_at LIMIT 100")){
            try{engine.notifyReminder(number(row.get("user_id")).longValue(),text(row.get("event_code")));}
            catch(RuntimeException ex){log.warn("H4 reminder failed for {} / {}: {}",row.get("event_code"),row.get("user_id"),ex.getMessage());}
        }
        for(var rule:db.rows("SELECT event_code FROM nx_growth_event_rule WHERE activated_at IS NOT NULL AND kind IN ('holding','regional') AND ends_at<=UTC_TIMESTAMP(6) AND settled_at IS NULL")){
            String code=text(rule.get("event_code"));
            try{engine.settle(code);}catch(RuntimeException ex){log.warn("H4 settlement failed for {}: {}",code,ex.getMessage());}
        }
        for(var row:db.rows("SELECT p.event_code,p.user_id FROM nx_growth_event_pool_result p JOIN nx_growth_event_rule r ON r.event_code=p.event_code AND r.settled_at IS NOT NULL WHERE p.status='READY' ORDER BY p.created_at,p.user_id LIMIT 100")){
            try{engine.payPool(text(row.get("event_code")),number(row.get("user_id")).longValue());}
            catch(RuntimeException ex){log.warn("H4 pool payment failed for {} / {}: {}",row.get("event_code"),row.get("user_id"),ex.getMessage());}
        }
    }
}
