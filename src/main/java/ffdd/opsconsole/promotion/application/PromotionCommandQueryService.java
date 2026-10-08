package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionCommandQueryService {
    private final PromotionMapper db;
    private final AdminIdempotencyService idempotency;
    private final PromotionOrderService orders;
    public Map<String,Object> own(long owner,String operation,String target,String key){
        String scope=switch(operation){
            case "createOrder" -> "APP:ORDER_CREATE:USER:"+owner;
            case "createBundle" -> "APP:BUNDLE_ORDER_CREATE:USER:"+owner;
            case "payOrder" -> "APP:ORDER_PAYMENT:USER:"+owner;
            case "cancelOrder" -> "APP:ORDER_CANCEL:USER:"+owner;
            case "quote" -> "PROMOTION_QUOTE:"+owner;
            default -> throw new ffdd.opsconsole.shared.exception.BizException(422,"PROMOTION_COMMAND_OPERATION_INVALID");
        };
        require(key!=null&&!key.isBlank()&&key.length()<=128,"PROMOTION_COMMAND_KEY_INVALID");
        boolean creation=Set.of("createOrder","createBundle").contains(operation);
        if(creation||"quote".equals(operation)){
            Map<String,Object> quote=db.requiredRow("SELECT user_id FROM nx_promotion_quote WHERE quote_id=?",target);
            require(number(quote.get("user_id"))==owner,"PROMOTION_COMMAND_TARGET_FORBIDDEN");
        }else require(number(db.order(target,false).get("user_id"))==owner,"PROMOTION_COMMAND_TARGET_FORBIDDEN");
        Map<String,Object> row=db.one("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=? AND is_deleted=0",scope,key.trim());
        String now=Instant.now().toString();
        if(row==null)return receipt(operation,target,key,"NOT_FOUND",null,null,null,now,now);
        if(!creation&&!"quote".equals(operation))require(sha256(owner+"|"+target).equals(row.get("request_hash")),"PROMOTION_COMMAND_TARGET_MISMATCH");
        Object response,error=null;String state;
        if("quote".equals(operation)){
            var result=idempotency.recoveryResult(scope,key,text(row.get("request_hash")),Map.class);response=result.response();state=result.status().name();
        }else{
            var result=idempotency.recoveryResult(scope,key,text(row.get("request_hash")),ApiResult.class);state=result.status().name();
            ApiResult<?> saved=result.response();response=saved==null?null:saved.getData();
            if("SUCCEEDED".equals(state)&&saved!=null&&saved.getCode()!=0){
                state="FAILED";response=null;
                String message=text(saved.getMessage());
                error=values("code",saved.getCode()>=400&&saved.getCode()<=599?saved.getCode():500,
                    "message",message.matches("[A-Z][A-Z0-9_]{0,127}")?message:"ORDER_COMMAND_FAILED","data",null);
            }
            if("SUCCEEDED".equals(state)&&response==null)state="OUTCOME_UNKNOWN";
        }
        if(Set.of("UNKNOWN","MISMATCH").contains(state))state="OUTCOME_UNKNOWN";
        Map<String,Object> order=null;
        if(response!=null){
            Map<String,Object> data=map(response);
            if("quote".equals(operation))require(target.equals(data.get("quoteId")),"PROMOTION_COMMAND_TARGET_MISMATCH");
            else{
                String orderNo=required(data.get("orderNo"),"orderNo");
                require(number(db.order(orderNo,false).get("user_id"))==owner,"PROMOTION_COMMAND_TARGET_FORBIDDEN");
                require(creation?target.equals(data.get("promotionQuoteId")):target.equals(orderNo),"PROMOTION_COMMAND_TARGET_MISMATCH");
                order=new LinkedHashMap<>(data);order.putAll(orders.orderProjection(owner,orderNo));
            }
        }
        Map<String,Object> resource=order==null?null:values("type","ORDER","id",order.get("orderNo"),"revision",null);
        return receipt(operation,target,key,state,resource,order,error,instant(row.get("created_at")).toString(),instant(row.get("updated_at")).toString());
    }
    private Map<String,Object> receipt(String operation,String target,String key,String state,Object resource,Object order,Object error,String created,String updated){
        return values("commandId","PCQ-"+sha256(operation+":"+target+":"+key),"operation",operation,"targetId",target,"idempotencyKey",key,
            "status",state,"resource",resource,"order",order,"error",error,"createdAt",created,"updatedAt",updated);
    }
}
