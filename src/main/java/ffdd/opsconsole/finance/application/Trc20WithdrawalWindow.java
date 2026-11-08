package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** D2 approval and first dispatch enforce the same original TRC20 phase window. */
@Service @RequiredArgsConstructor
class Trc20WithdrawalWindow {
    private final WithdrawalPayoutMapper mapper;
    private final ObjectMapper json;
    record Window(LocalDateTime deadline,Integer days,boolean originalSnapshot,String unavailableReason) { }

    Window read(String withdrawalNo,Long userId,LocalDateTime createdAt) {
        var originals=mapper.originalSubmissionDetails(withdrawalNo,userId);
        int days;
        if(originals.isEmpty())return new Window(null,null,false,"TRC20_WINDOW_SNAPSHOT_UNAVAILABLE");
        else {
            if(originals.size()!=1)return invalid();
            try {
                var snapshot=json.readTree(originals.get(0));
                var value=snapshot==null?null:snapshot.get("cooldown_days");
                if(value==null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<0)return invalid();
                days=value.intValue();
            } catch(JsonProcessingException invalid) { return invalid(); }
        }
        if(createdAt==null)return invalid();
        return new Window(createdAt.plusDays(days),days,!originals.isEmpty(),null);
    }
    private Window invalid() { return new Window(null,null,true,"TRC20_WINDOW_SNAPSHOT_INVALID"); }
}
