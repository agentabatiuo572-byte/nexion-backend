package ffdd.opsconsole.promotion.web;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.BusinessProductImages;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@RestController
@RequiredArgsConstructor
public class AppPromotionController {
    private final PromotionPublicService promotions;
    private final PromotionQuoteService quotes;
    private final PromotionRewardService rewards;
    private final AdminIdempotencyService idempotency;
    private final PromotionCommandQueryService commands;
    private final BusinessProductImages productImages;
    @GetMapping("/api/promotions")
    public ApiResult<Map<String,Object>> list(Authentication auth,@RequestParam(required=false) String cursor,
        @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String activityId,@RequestParam(required=false) String placement){
        var page=promotions.page(subject(auth,false),cursor,limit,activityId,placement);
        var items=maps(page.get("items"));
        attachProductImages(items);
        page.put("items",items);
        return ApiResult.ok(page);
    }
    @GetMapping("/api/promotions/{activityId}")
    public ApiResult<Map<String,Object>> get(Authentication auth,@PathVariable String activityId){
        var item=promotions.get(subject(auth,false),activityId);
        attachProductImages(List.of(item));
        return ApiResult.ok(item);
    }
    private void attachProductImages(List<Map<String,Object>> activities){
        List<Set<String>> activitySkus=new ArrayList<>();
        Set<String> allSkus=new LinkedHashSet<>();
        for(var activity:activities){
            Set<String> skus=new LinkedHashSet<>();
            for(var sku:(List<?>)activity.get("productNos"))skus.add(text(sku));
            for(var rule:maps(activity.get("rewardRules")))for(var role:List.of("buyerReward","inviterReward")){
                if(rule.get(role) instanceof Map<?,?> reward && "DEVICE".equals(reward.get("type")))skus.add(text(reward.get("giftProductNo")));
            }
            activitySkus.add(skus);
            allSkus.addAll(skus);
        }
        var images=productImages.urls(allSkus);
        for(int i=0;i<activities.size();i++){
            Map<String,String> visible=new LinkedHashMap<>();
            for(String sku:activitySkus.get(i))if(images.containsKey(sku))visible.put(sku,images.get(sku));
            activities.get(i).put("productImages",visible);
        }
    }
    @PostMapping("/api/orders/quote")
    @PromotionItemImages
    @SuppressWarnings("unchecked")
    public ApiResult<Map<String,Object>> quote(Authentication auth,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){
        Long user=subject(auth,true);
        return ApiResult.ok(idempotency.executeRetained("PROMOTION_QUOTE:"+user,key,hash(request),Map.class,()->quotes.quote(user,request)));
    }
    @GetMapping("/api/promotion-rewards")
    public ApiResult<Map<String,Object>> rewards(Authentication auth,@RequestParam(required=false) String cursor,
        @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String activityId,@RequestParam(required=false) String state,@RequestParam(required=false) String orderNo){
        return ApiResult.ok(rewards.page(subject(auth,true),false,cursor,limit,activityId,state,orderNo));
    }
    @GetMapping("/api/promotion-rewards/{obligationId}")
    public ApiResult<Map<String,Object>> reward(Authentication auth,@PathVariable String obligationId){return ApiResult.ok(rewards.get(subject(auth,true),obligationId,false));}
    @GetMapping("/api/promotions/{activityId}/referral-progress")
    public ApiResult<Map<String,Object>> referral(Authentication auth,@PathVariable String activityId){return ApiResult.ok(promotions.referral(subject(auth,true),activityId));}
    @GetMapping("/api/promotion-commands/{idempotencyKey}")
    @PromotionItemImages("order")
    public ApiResult<Map<String,Object>> command(Authentication auth,@PathVariable String idempotencyKey,@RequestParam String operation,@RequestParam String targetId){
        return ApiResult.ok(commands.own(subject(auth,true),operation,targetId,idempotencyKey));
    }
    public static Long subject(Authentication authentication,boolean required){
        Long id=null;
        if(authentication!=null&&authentication.isAuthenticated()&&authentication.getDetails() instanceof Map<?,?> details&&"USER".equals(details.get("subjectType"))){
            try{long value=Long.parseLong(String.valueOf(authentication.getPrincipal()));if(value>0)id=value;}catch(NumberFormatException ignored){}
        }
        if(required&&id==null)throw new BizException(403,"USER_SUBJECT_REQUIRED");return id;
    }
}
