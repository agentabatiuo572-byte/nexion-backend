package ffdd.opsconsole.finance.purchase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.facade.ExternalPurchaseCommandQuery;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Never calls provider observation or fulfilment from a command receipt GET. */
@Service
@RequiredArgsConstructor
public class ExternalPurchaseCommandQueryService implements ExternalPurchaseCommandQuery {
    private final PurchasePaymentMapper repo;
    private final ObjectMapper json;
    @Override public Map<String,Object> find(long userId,String kind,String key) {
        if(key==null||!key.matches("[A-Za-z0-9:_-]{8,128}")) throw new BizException(422,"PAYMENT_COMMAND_KEY_INVALID");
        String externalKind=switch(kind) {
            case "order-pay"->"ORDER_PAY"; case "trial-convert"->"TRIAL_CONVERT"; case "trade-in"->"TRADE_IN";
            case "capacity-replace"->"CAPACITY_REPLACE"; case "capacity-keep"->"CAPACITY_KEEP"; default->null;
        };
        if(externalKind==null) return null;
        Map<String,Object> row=repo.one("SELECT status,receipt_json,reason FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?",userId,externalKind,key);
        if(row==null) return null;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("kind",kind);result.put("commandKey",key);result.put("canResumeSameKey",false);
        Object receipt=row.get("receipt_json");
        if(receipt!=null) {
            try {
                Object data=json.readValue(String.valueOf(receipt),Object.class);
                if(!(data instanceof Map<?,?> map)||map.isEmpty()) throw new IllegalArgumentException("Invalid receipt");
                result.put("status","SUCCEEDED");result.put("code",0);result.put("receipt",data);return result;
            } catch(JsonProcessingException|IllegalArgumentException invalid) {
                result.put("status","INDETERMINATE");result.put("message","PURCHASE_PAYMENT_RECEIPT_UNAVAILABLE");return result;
            }
        }
        String state=String.valueOf(row.get("status"));
        result.put("status","EXPIRED".equals(state)?"REJECTED":"DELIVERED".equals(state)?"INDETERMINATE":"PROCESSING");
        result.put("message","EXPIRED".equals(state)?"PURCHASE_PAYMENT_EXPIRED":row.get("reason")==null?"PURCHASE_PAYMENT_PROCESSING":String.valueOf(row.get("reason")));
        return result;
    }
}
