package ffdd.opsconsole.finance.purchase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.cregis.CregisDepositPolicyService;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service @RequiredArgsConstructor
public class PurchasePaymentPolicyService {
    private final PurchasePaymentMapper db;
    private final CregisDepositPolicyService cregis;
    private final AdminIdempotencyService commands;
    private final AuditLogService audit;
    private final ObjectMapper jsonMapper;
    public Map<String,Object> current(){return view(db.one("SELECT * FROM nx_purchase_payment_policy WHERE id=1"));}
    public Map<String,Object> channel(String method){
        return maps(current().get("channels")).stream().filter(row->method.equals(row.get("paymentMethod")))
                .findFirst().orElseThrow(()->new BizException(422,"PURCHASE_PAYMENT_METHOD_INVALID"));
    }
    public BigDecimal topupMinimum(){return new BigDecimal(text(channel("HDPAY_BANK").get("minTopupPrincipal")));}
    @Transactional(rollbackFor=Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String,Object> update(String key,Map<String,Object> request){
        reason(request);String actor=AdminActorResolver.resolve("admin");
        return commands.executeRetained("D1:PURCHASE_PAYMENT_POLICY:"+actor,key,hash(request),Map.class,()->{
            var locked=db.one("SELECT * FROM nx_purchase_payment_policy WHERE id=1 FOR UPDATE");
            if(locked==null||number(locked.get("version"))!=number(request.get("expectedVersion")))
                throw new BizException(409,"PURCHASE_PAYMENT_POLICY_VERSION_CONFLICT");
            var rows=maps(request.get("channels"));
            if(rows.isEmpty()||rows.size()>3)throw invalid();
            var seen=new HashSet<String>();var valid=new ArrayList<Map<String,Object>>(maps(view(locked).get("channels")));
            for(var row:rows){
                String method=text(row.get("paymentMethod"));
                if(!Set.of("HDPAY_BANK","CREGIS_USDT_BSC","CREGIS_USDT_ERC20").contains(method)||!seen.add(method))throw invalid();
                BigDecimal rate=fee(row.get("channelFeeRatePct")),fixed=fee(row.get("channelFeeFixed")),network=fee(row.get("networkConfirmFeeUsdt"));
                if(rate.compareTo(new BigDecimal("100"))>0||"HDPAY_BANK".equals(method)&&network.signum()!=0)throw invalid();
                BigDecimal min=principal(row.get("minTopupPrincipal"));
                if(("CREGIS_USDT_BSC".equals(method)||"CREGIS_USDT_ERC20".equals(method))&&min.compareTo(cregis.current().minDepositUsdt())!=0)
                    throw new BizException(409,"CREGIS_DEPOSIT_POLICY_A2_REQUIRED");
                valid.removeIf(channel->method.equals(channel.get("paymentMethod")));
                valid.add(values("paymentMethod",method,"configured",true,"channelFeeRatePct",rate.toPlainString(),"channelFeeFixed",fixed.toPlainString(),
                        "networkConfirmFeeUsdt",network.toPlainString(),"minTopupPrincipal",min.toPlainString()));
            }
            if(db.write("UPDATE nx_purchase_payment_policy SET policy_json=?,version=version+1,updated_at=NOW(6) WHERE id=1 AND version=?",
                    json(valid),number(request.get("expectedVersion")))!=1)throw new BizException(409,"PURCHASE_PAYMENT_POLICY_VERSION_CONFLICT");
            var after=current();
            audit.recordRequired(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_POLICY_UPDATE").resourceType("PURCHASE_PAYMENT_POLICY")
                    .resourceId("1").actorType("ADMIN").actorUsername(actor).riskLevel("HIGH").result("SUCCESS")
                    .detail(values("before",view(locked),"after",after,"reason",request.get("reason"))).build());
            return after;
        });
    }
    private Map<String,Object> view(Map<String,Object> row){
        if(row==null)throw new BizException(503,"PURCHASE_PAYMENT_POLICY_MISSING");
        try{
            Object raw=row.get("policy_json");
            List<Map<String,Object>> channels=jsonMapper.readValue(raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):text(raw),new TypeReference<>(){});
            if(channels.stream().noneMatch(channel->"CREGIS_USDT_ERC20".equals(channel.get("paymentMethod"))))
                channels.add(values("paymentMethod","CREGIS_USDT_ERC20","configured",false,"channelFeeRatePct",null,"channelFeeFixed",null,"networkConfirmFeeUsdt",null,"minTopupPrincipal","1"));
            for(var channel:channels)if(Set.of("CREGIS_USDT_BSC","CREGIS_USDT_ERC20").contains(channel.get("paymentMethod")))
                channel.put("minTopupPrincipal",cregis.current().minDepositUsdt().setScale(0,RoundingMode.UNNECESSARY).toPlainString());
            for(var channel:channels){
                boolean configured=Boolean.TRUE.equals(channel.get("configured"));channel.put("configured",configured);
                if(!configured)for(String field:List.of("channelFeeRatePct","channelFeeFixed","networkConfirmFeeUsdt"))channel.put(field,null);
            }
            return values("version",row.get("version"),"channels",channels,"constraints",values(
                    "ratePct",values("min","0","max","100","scale",2),
                    "feeUsdt",values("min","0","max","1000000","scale",2),
                    "minTopupPrincipal",values("min","1","max","1000000000","integer",true)));
        }catch(java.io.IOException e){throw new IllegalStateException("PURCHASE_PAYMENT_POLICY_CORRUPT",e);}
    }
    public static BigDecimal money(Object value){return new BigDecimal(text(value)).setScale(2,RoundingMode.HALF_UP);}
    public static BigDecimal principal(Object value){
        try{BigDecimal amount=new BigDecimal(text(value));
            if(amount.signum()<=0||amount.stripTrailingZeros().scale()>0||amount.compareTo(new BigDecimal("1000000000"))>0)throw invalid();
            return amount.setScale(0,RoundingMode.UNNECESSARY);
        }catch(NumberFormatException|ArithmeticException e){throw invalid();}
    }
    private static BigDecimal fee(Object value){
        try{BigDecimal amount=new BigDecimal(text(value));
            if(amount.signum()<0||amount.stripTrailingZeros().scale()>2||amount.compareTo(new BigDecimal("1000000"))>0)throw invalid();
            return amount.setScale(2,RoundingMode.UNNECESSARY);
        }catch(NumberFormatException|ArithmeticException e){throw invalid();}
    }
    public static void reason(Map<String,Object> body){
        String reason=body==null?"":text(body.get("reason")).trim();
        if(reason.length()<10||reason.length()>300)throw new BizException(422,"PURCHASE_PAYMENT_REASON_REQUIRED");
    }
    private static BizException invalid(){return new BizException(422,"PURCHASE_PAYMENT_POLICY_INVALID");}
}
