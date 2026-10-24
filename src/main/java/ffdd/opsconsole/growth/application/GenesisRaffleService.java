package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest.Prize;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static ffdd.opsconsole.growth.application.H4EventRuleService.*;

@Service
@RequiredArgsConstructor
public class GenesisRaffleService {
    private final H4EventRuleRepository db;
    private final ObjectMapper json;
    private final Clock clock;
    private final AdminIdempotencyService idempotency;
    private final EarningsReleaseService earnings;
    private final TreasuryLedgerPostingFacade ledger;
    private final TreasuryCoverageFacade coverage;
    private final EventDeviceGiftService devices;
    private final AuditLogService audit;
    private final A2RuntimePolicy a2;
    private final SecureRandom random = new SecureRandom();
    // A position-backed ticket remains valid only while its originating entitlement survives.
    static final String VALID_TICKET = """
        (t.position_no IS NULL OR (EXISTS(SELECT 1 FROM nx_staking_position p
          WHERE p.position_no=t.position_no AND p.user_id=t.user_id AND p.is_deleted=0
          AND p.status IN ('ACTIVE','MATURE_UNCLAIMED','CLAIMED'))
        AND NOT EXISTS(SELECT 1 FROM nx_g7_repurchase_ticket g WHERE g.position_no=t.position_no
          AND g.user_id=t.user_id AND g.is_deleted=0 AND g.status='FORFEITED')))
        AND (t.order_no IS NULL OR EXISTS(SELECT 1 FROM nx_order o WHERE o.order_no=t.order_no
          AND o.user_id=t.user_id AND o.is_deleted=0 AND o.payment_status='PAID'
          AND o.order_status NOT IN ('REFUNDED','CHARGEBACK','CANCELLED')
          AND NOT EXISTS(SELECT 1 FROM nx_promotion_refund_hold h WHERE h.order_no=o.order_no
            AND h.status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED'))))
        """;

    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> grant(Long userId,String sourceType,String sourceId,long quantity,String positionNo) {
        return grant(userId,sourceType,sourceId,quantity,positionNo,null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> grant(Long userId,String sourceType,String sourceId,long quantity,String positionNo,String orderNo) {
        if(userId==null||sourceType==null||!sourceType.matches("[A-Z0-9_]{1,32}")||sourceId==null
                ||sourceId.isBlank()||sourceId.length()>192||quantity<=0||quantity>1_000_000
                ||positionNo!=null&&(positionNo.isBlank()||positionNo.length()>96)||orderNo!=null&&(orderNo.isBlank()||orderNo.length()>96))
            throw new BizException(422,"RAFFLE_TICKET_INVALID");
        db.write("INSERT IGNORE INTO nx_genesis_raffle_ticket(user_id,source_type,source_id,position_no,order_no,quantity) VALUES(?,?,?,?,?,?)",
                userId,sourceType,sourceId,positionNo,orderNo,quantity);
        var row=db.row("SELECT * FROM nx_genesis_raffle_ticket WHERE user_id=? AND source_type=? AND source_id=?",userId,sourceType,sourceId);
        if(number(row.get("quantity")).longValue()!=quantity||!Objects.equals(row.get("position_no"),positionNo)||!Objects.equals(row.get("order_no"),orderNo))
            throw new BizException(409,"RAFFLE_GRANT_CONFLICT");
        return values("receiptId","GRT-"+row.get("id"),"quantity",quantity);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> grant(Long id,String type,String source,long quantity){return grant(id,type,source,quantity,null);}
    public long getGranted(Long id,String type,String source){
        var row=db.row("SELECT quantity FROM nx_genesis_raffle_ticket WHERE user_id=? AND source_type=? AND source_id=?",id,type,source);
        return row==null?0:number(row.get("quantity")).longValue();
    }
    private void activeUser(Long id) {
        if(db.row("SELECT id FROM nx_user WHERE id=? AND status='ACTIVE' AND sandbox=0 AND is_deleted=0",id)==null)
            throw new BizException(403,"USER_NOT_FOUND_OR_INACTIVE");
    }
    private long available(Long id) {
        return number(db.row("SELECT COALESCE(SUM(t.quantity-t.used_quantity),0) amount FROM nx_genesis_raffle_ticket t WHERE t.user_id=? AND "+VALID_TICKET,id).get("amount")).longValueExact();
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String iso(Object value){return date(value).toInstant(ZoneOffset.UTC).toString();}
    private List<Prize> prizes(Map<String,Object> draw){
        try{return json.readValue(text(draw.get("prizes_json")),new TypeReference<List<Prize>>(){});}
        catch(Exception ex){throw new BizException(503,"RAFFLE_CONFIG_INVALID");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException(ex);}}
    private Map<String,Object> view(Map<String,Object> row,Long userId){
        String id=text(row.get("draw_id"));
        long total=number(db.row("SELECT COALESCE(SUM(quantity),0) amount FROM nx_genesis_raffle_entry WHERE draw_id=?",id).get("amount")).longValue();
        long mine=userId==null?0:number(db.row("SELECT COALESCE(SUM(quantity),0) amount FROM nx_genesis_raffle_entry WHERE draw_id=? AND user_id=?",id,userId).get("amount")).longValue();
        List<Map<String,Object>> results=new ArrayList<>();
        if(userId!=null)for(var result:db.rows("SELECT prize_id,receipt_json,status FROM nx_genesis_raffle_result WHERE draw_id=? AND user_id=? ORDER BY award_index",id,userId)){
            try{Map<String,Object> item=json.readValue(text(result.get("receipt_json")),new TypeReference<>(){});item.put("prizeId",result.get("prize_id"));item.put("status",result.get("status"));results.add(item);}
            catch(Exception ex){throw new BizException(503,"RAFFLE_RECEIPT_INVALID");}
        }
        return values("id",id,"titleZh",row.get("title_zh"),"titleEn",row.get("title_en"),"titleVi",row.get("title_vi"),
                "revision",row.get("revision"),"startsAt",iso(row.get("starts_at")),"drawAt",iso(row.get("draw_at")),
                "status",row.get("status"),"prizes",prizes(row),"myEntries",mine,"totalEntries",total,"myResults",results);
    }
    public ApiResult<Map<String,Object>> state(Long userId){
        activeUser(userId);
        return ApiResult.ok(values("availableTickets",available(userId),"draws",db.rows("SELECT * FROM nx_genesis_raffle_draw WHERE status<>'DRAFT' ORDER BY draw_at DESC LIMIT 20")
                .stream().map(row->view(row,userId)).toList()));
    }
    public ApiResult<Map<String,Object>> admin(){return ApiResult.ok(Map.of("draws",db.rows("SELECT * FROM nx_genesis_raffle_draw ORDER BY created_at DESC LIMIT 50").stream().map(row->view(row,null)).toList()));}

    static void validate(GenesisRaffleRequest request) {
        if(request==null||request.id()==null||!request.id().matches("[A-Za-z0-9_-]{1,64}"))throw new BizException(422,"RAFFLE_ID_INVALID");
        for(String title:Arrays.asList(request.titleZh(),request.titleEn(),request.titleVi()))
            if(title==null||title.isBlank()||title.length()>160||title.matches("(?s).*<[^>]*>.*"))throw new BizException(422,"RAFFLE_TITLE_REQUIRED");
        LocalDateTime start=parseDate(request.startsAt()),draw=parseDate(request.drawAt());
        if(start==null||draw==null||!draw.isAfter(start))throw new BizException(422,"RAFFLE_DATE_INVALID");
        if(request.prizes()==null||request.prizes().isEmpty()||request.prizes().size()>20)throw new BizException(422,"RAFFLE_PRIZES_REQUIRED");
        Set<String> ids=new HashSet<>();int count=0;
        for(Prize prize:request.prizes()){
            if(prize==null||prize.id()==null||!prize.id().matches("[A-Za-z0-9_-]{1,64}")||!ids.add(prize.id())
                    ||prize.rewardType()==null||!Set.of("NEX","USDT","DEVICE").contains(prize.rewardType())||prize.quantity()<1||prize.quantity()>100)
                throw new BizException(422,"RAFFLE_PRIZE_INVALID");
            for(String name:Arrays.asList(prize.nameZh(),prize.nameEn(),prize.nameVi()))
                if(name==null||name.isBlank()||name.length()>160)throw new BizException(422,"RAFFLE_PRIZE_NAME_REQUIRED");
            if(prize.amount()==null||prize.amount().signum()<=0||prize.amount().scale()>6||prize.amount().compareTo(new BigDecimal("1000000"))>0)
                throw new BizException(422,"RAFFLE_AMOUNT_INVALID");
            if("DEVICE".equals(prize.rewardType())&&(prize.amount().compareTo(BigDecimal.ONE)!=0||prize.productNo()==null||!prize.productNo().matches("[A-Za-z0-9_-]{1,64}")))
                throw new BizException(422,"RAFFLE_DEVICE_INVALID");
            count+=prize.quantity();
        }
        if(count>100)throw new BizException(422,"RAFFLE_PRIZE_LIMIT");
    }
    private void covered(){
        var snapshot=coverage.snapshot();
        if(snapshot==null||!snapshot.reliable()||snapshot.coverageRatio()==null||snapshot.redlinePct()==null
                ||snapshot.coverageRatio().compareTo(snapshot.redlinePct())<0)throw new BizException(409,"COVERAGE_REDLINE");
    }
    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> save(String id,GenesisRaffleRequest request,boolean create){
        validate(request);
        if(!id.equals(request.id())||request.reason()==null||request.reason().trim().length()<a2.reasonMinChars()||request.reason().length()>200)
            throw new BizException(422,"REASON_REQUIRED");
        var current=db.row("SELECT * FROM nx_genesis_raffle_draw WHERE draw_id=? FOR UPDATE",id);
        if(create&&current!=null)throw new BizException(409,"RAFFLE_ALREADY_EXISTS");
        if(!create&&(current==null||request.expectedRevision()==null||number(current.get("revision")).longValue()!=request.expectedRevision()))
            throw new BizException(409,"RAFFLE_CONFIG_STALE");
        boolean enabled=!create&&Boolean.TRUE.equals(request.enabled());
        if(current!=null&&!"DRAFT".equals(current.get("status"))){
            long entered=number(db.row("SELECT COALESCE(SUM(quantity),0) amount FROM nx_genesis_raffle_entry WHERE draw_id=?",id).get("amount")).longValue();
            if(!"OPEN".equals(current.get("status"))||enabled||entered>0)throw new BizException(409,"RAFFLE_ALREADY_OPEN");
        }
        if(enabled){
            if(!parseDate(request.drawAt()).isAfter(now()))throw new BizException(422,"RAFFLE_DATE_ENDED");
            covered();
            Map<String,Integer> requiredStock=new TreeMap<>();
            for(Prize prize:request.prizes())if("DEVICE".equals(prize.rewardType()))requiredStock.merge(prize.productNo(),prize.quantity(),Integer::sum);
            requiredStock.forEach(devices::validatePrize);
        }
        if(create)db.write("INSERT INTO nx_genesis_raffle_draw(draw_id,title_zh,title_en,title_vi,starts_at,draw_at,prizes_json) VALUES(?,?,?,?,?,?,?)",
                id,request.titleZh(),request.titleEn(),request.titleVi(),parseDate(request.startsAt()),parseDate(request.drawAt()),encode(request.prizes()));
        else db.write("UPDATE nx_genesis_raffle_draw SET title_zh=?,title_en=?,title_vi=?,starts_at=?,draw_at=?,prizes_json=?,status=?,opened_at=?,revision=revision+1 WHERE draw_id=?",
                request.titleZh(),request.titleEn(),request.titleVi(),parseDate(request.startsAt()),parseDate(request.drawAt()),encode(request.prizes()),enabled?"OPEN":"DRAFT",enabled?now():null,id);
        audit.recordRequired(AuditLogWriteRequest.builder().action("H4_GENESIS_RAFFLE_CONFIGURED").resourceType("GENESIS_RAFFLE").resourceId(id).result("SUCCESS").riskLevel("HIGH")
                .detail(values("reason",request.reason(),"enabled",enabled,"prizes",request.prizes())).build());
        return ApiResult.ok(view(db.row("SELECT * FROM nx_genesis_raffle_draw WHERE draw_id=?",id),null));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public ApiResult<Map<String,Object>> enter(Long userId,String drawId,long tickets,String key){
        if(tickets<1||tickets>1_000_000)throw new BizException(422,"RAFFLE_TICKETS_INVALID");
        return (ApiResult)idempotency.executeRetained("GENESIS_RAFFLE_ENTER:"+userId+":"+drawId,key,drawId+":"+tickets,ApiResult.class,()->{
            activeUser(userId);
            var draw=db.row("SELECT * FROM nx_genesis_raffle_draw WHERE draw_id=? FOR UPDATE",drawId);
            if(draw==null||!"OPEN".equals(draw.get("status"))||now().isBefore(date(draw.get("starts_at")))||!now().isBefore(date(draw.get("draw_at"))))
                throw new BizException(409,"RAFFLE_NOT_OPEN");
            var available=db.rows("SELECT t.* FROM nx_genesis_raffle_ticket t WHERE t.user_id=? AND t.quantity>t.used_quantity AND "+VALID_TICKET+" ORDER BY t.id FOR UPDATE",userId);
            long total=available.stream().mapToLong(row->number(row.get("quantity")).longValue()-number(row.get("used_quantity")).longValue()).sum();
            if(total<tickets)throw new BizException(409,"RAFFLE_TICKETS_INSUFFICIENT");
            long remaining=tickets;
            for(var ticket:available){
                long use=Math.min(remaining,number(ticket.get("quantity")).longValue()-number(ticket.get("used_quantity")).longValue());
                if(use==0)break;
                db.write("UPDATE nx_genesis_raffle_ticket SET used_quantity=used_quantity+? WHERE id=?",use,ticket.get("id"));
                db.write("INSERT INTO nx_genesis_raffle_entry(draw_id,ticket_id,user_id,quantity) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity)",drawId,ticket.get("id"),userId,use);
                remaining-=use;
            }
            return ApiResult.ok(values("drawId",drawId,"enteredTickets",tickets,"availableTickets",total-tickets));
        });
    }

    /** Weighted sampling without replacement: one ticket can win only one prize in this draw. */
    static int choose(long[] counts,long offset){
        if(offset<0)throw new IllegalArgumentException("negative offset");
        for(int i=0;i<counts.length;i++){if(offset<counts[i])return i;offset-=counts[i];}
        throw new IllegalArgumentException("offset outside ticket population");
    }
    /** Fix winners before attempting payout; a payout failure must never change the draw. */
    @Transactional(rollbackFor=Exception.class)
    public void draw(String id){
        var draw=db.row("SELECT * FROM nx_genesis_raffle_draw WHERE draw_id=? FOR UPDATE",id);
        if(draw==null||!"OPEN".equals(draw.get("status"))||now().isBefore(date(draw.get("draw_at"))))return;
        var entries=db.rows("SELECT e.ticket_id,e.user_id,e.quantity,t.position_no,t.order_no FROM nx_genesis_raffle_entry e JOIN nx_genesis_raffle_ticket t ON t.id=e.ticket_id WHERE e.draw_id=? ORDER BY e.ticket_id",id);
        long[] remaining=entries.stream().mapToLong(row->number(row.get("quantity")).longValue()).toArray();
        int winners=0;
        for(Prize prize:prizes(draw))for(int awardIndex=0;awardIndex<prize.quantity();awardIndex++){
            long total=Arrays.stream(remaining).sum();if(total==0)break;
            int winner=choose(remaining,random.nextLong(total));
            var entry=entries.get(winner);Long userId=number(entry.get("user_id")).longValue();
            if(!lockEligible(entry)){remaining[winner]=0;awardIndex--;continue;}
            remaining[winner]--;
            String reference=java.util.UUID.nameUUIDFromBytes((id+":"+prize.id()+":"+awardIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            var receipt=values("rewardType",prize.rewardType(),"amount",prize.amount(),"productNo",prize.productNo(),"quantity",1,"receiptId","GR:"+reference);
            db.write("INSERT INTO nx_genesis_raffle_result(draw_id,prize_id,award_index,user_id,ticket_id,receipt_json) VALUES(?,?,?,?,?,?)",
                    id,prize.id(),awardIndex,userId,entry.get("ticket_id"),encode(receipt));
            winners++;
        }
        db.write("UPDATE nx_genesis_raffle_draw SET status=?,drawn_at=?,revision=revision+1 WHERE draw_id=?",winners==0?"DRAWN":"PROCESSING",now(),id);
        audit.recordRequired(AuditLogWriteRequest.builder().action("H4_GENESIS_RAFFLE_DRAWN").resourceType("GENESIS_RAFFLE").resourceId(id).result("SUCCESS").riskLevel("HIGH").detail(Map.of("eligibleEntries",entries.size())).build());
    }
    private boolean lockEligible(Map<String,Object> ticket){
        Long id=number(ticket.get("user_id")).longValue();
        var user=db.row("SELECT status,sandbox,is_deleted FROM nx_user WHERE id=? FOR UPDATE",id);
        if(user==null||!"ACTIVE".equals(user.get("status"))||number(user.get("sandbox")).signum()!=0||number(user.get("is_deleted")).signum()!=0)return false;
        if(ticket.get("position_no")!=null){
            var position=db.row("SELECT status,user_id,is_deleted FROM nx_staking_position WHERE position_no=? FOR UPDATE",ticket.get("position_no"));
            if(position==null||number(position.get("user_id")).longValue()!=id||number(position.get("is_deleted")).signum()!=0
                    ||!Set.of("ACTIVE","MATURE_UNCLAIMED","CLAIMED").contains(text(position.get("status"))))return false;
            var base=db.row("SELECT status FROM nx_g7_repurchase_ticket WHERE position_no=? AND user_id=? AND is_deleted=0 FOR UPDATE",ticket.get("position_no"),id);
            if(base!=null&&"FORFEITED".equals(base.get("status")))return false;
        }
        if(ticket.get("order_no")!=null){
            var order=db.row("SELECT user_id,payment_status,order_status,is_deleted FROM nx_order WHERE order_no=? FOR UPDATE",ticket.get("order_no"));
            if(order==null||number(order.get("user_id")).longValue()!=id||number(order.get("is_deleted")).signum()!=0||!"PAID".equals(order.get("payment_status"))
                    ||Set.of("REFUNDED","CHARGEBACK","CANCELLED").contains(text(order.get("order_status"))))return false;
            if(!db.rows("SELECT status FROM nx_promotion_refund_hold WHERE order_no=? AND status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED') FOR UPDATE",ticket.get("order_no")).isEmpty())return false;
        }
        return true;
    }
    @Transactional(rollbackFor=Exception.class)
    public void pay(String drawId,String prizeId,int index){
        var draw=db.row("SELECT status FROM nx_genesis_raffle_draw WHERE draw_id=? FOR UPDATE",drawId);
        if(draw==null||!"PROCESSING".equals(draw.get("status")))return;
        var result=db.row("SELECT * FROM nx_genesis_raffle_result WHERE draw_id=? AND prize_id=? AND award_index=? FOR UPDATE",drawId,prizeId,index);
        if(result==null||"PAID".equals(result.get("status")))return;
        Map<String,Object> receipt;
        try{receipt=json.readValue(text(result.get("receipt_json")),new TypeReference<>(){});}catch(Exception ex){throw new BizException(503,"RAFFLE_RECEIPT_INVALID");}
        Long userId=number(result.get("user_id")).longValue();String reference=text(receipt.get("receiptId")),asset=text(receipt.get("rewardType"));
        covered();
        if("DEVICE".equals(asset)){
            var gift=devices.award(userId,reference,text(receipt.get("productNo")),1);
            receipt.put("instances",gift.get("instances"));
        }else{
            BigDecimal amount=number(receipt.get("amount"));
            earnings.creditReward(userId,"EVENT_REWARD",reference,asset,amount,reference);
            ledger.postLedgerEntry(reference,userId,"EVENT_REWARD",asset,"IN",amount,"SUCCESS","Genesis raffle reward");
        }
        db.write("UPDATE nx_genesis_raffle_result SET status='PAID',receipt_json=? WHERE draw_id=? AND prize_id=? AND award_index=?",encode(receipt),drawId,prizeId,index);
        if(db.row("SELECT 1 FROM nx_genesis_raffle_result WHERE draw_id=? AND status='PENDING' LIMIT 1",drawId)==null)
            db.write("UPDATE nx_genesis_raffle_draw SET status='DRAWN',revision=revision+1 WHERE draw_id=?",drawId);
    }
    public List<Map<String,Object>> pending(String id){return db.rows("SELECT prize_id,award_index FROM nx_genesis_raffle_result WHERE draw_id=? AND status='PENDING' ORDER BY prize_id,award_index",id);}
    @Transactional
    public void markAttempt(String id){db.write("UPDATE nx_genesis_raffle_draw SET last_attempt_at=? WHERE draw_id=? AND status='PROCESSING'",now(),id);}
    public List<String> due(){
        Set<String> ids=new LinkedHashSet<>();
        db.rows("SELECT draw_id FROM nx_genesis_raffle_draw WHERE status='OPEN' AND draw_at<=? ORDER BY draw_at LIMIT 10",now()).forEach(row->ids.add(text(row.get("draw_id"))));
        db.rows("SELECT draw_id FROM nx_genesis_raffle_draw WHERE status='PROCESSING' ORDER BY last_attempt_at,draw_at LIMIT 10").forEach(row->ids.add(text(row.get("draw_id"))));
        return List.copyOf(ids);
    }
}
