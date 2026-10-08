package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.ConversationCustomerProfile;
import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.mapper.SupportCustomerProfileMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.application.OpsUserService;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** One service profile for conversations/workbench/360. Unknown never becomes a guessed fact. */
@ApplicationService
@RequiredArgsConstructor
public class SupportCustomerProfileService {
    private final SupportOwnershipService ownership;
    private final SupportBindingMapper bindings;
    private final SupportCustomerProfileMapper mapper;
    private final OpsUserService users;
    private final FinanceSupportReadService finance;
    private final OpsDeviceService devices;
    private final OpsRiskService risk;
    private final CustomerProfileRepository annotations;
    private final SupportMaintenanceService maintenance;
    private final ffdd.opsconsole.content.mapper.SupportWorkbenchMapper workbench;
    private final ffdd.opsconsole.shared.storage.ObjectStorageService storage;

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> profile(Long customer) {
        var scope=ownership.customerQueryScope(customer);
        String evaluatedAt=Instant.now().toString();
        var result=new LinkedHashMap<String,Object>();
        var identity=group(()-> {
            var response=users.profile(customer);
            if(response.getCode()!=0) throw new BizException(response.getCode(),"SUPPORT_IDENTITY_UNAVAILABLE");
            var user=response.getData();if(user==null)return null;
            var data=new LinkedHashMap<String,Object>();data.put("customerId",user.id());data.put("userNo",user.userNo());
            data.put("nickname",user.nickname());String avatar=mapper.avatar(customer);
            data.put("avatar",ownedAvatar(customer,avatar)?"/api/admin/content/support-workbench/customers/"+customer+"/avatar":null);
            data.put("level",first(user.vRank(),user.userLevel()));
            data.put("phoneMasked",user.phoneMasked());data.put("region",user.countryCode());data.put("registeredAt",businessUtc(user.registeredAt()));
            data.put("lastLoginAt",businessUtc(user.lastLoginAt()));data.put("accountStatus",user.status());return data;
        });
        result.put("identity",identity);result.put("finance",group(()-> {
            var data=new LinkedHashMap<>(finance.totals(customer));
            data.put("recentFlows",group(()->finance.flows(customer,1,20,null,null,null,null)));return data;
        }));
        result.put("devices",devices(customer,1,20));
        result.put("risk",group(()-> {
            if(!"READY".equals(identity.get("status")))return null;
            @SuppressWarnings("unchecked") var identityData=(Map<String,Object>)identity.get("data");
            var current=risk.currentScoreUser(String.valueOf(identityData.get("userNo")));
            if(current.getCode()==503 && "K4_RISK_SCORE_UNAVAILABLE".equals(current.getMessage()))return null;
            if(current.getCode()!=0)throw new BizException(current.getCode(),"SUPPORT_RISK_UNAVAILABLE");
            var score=current.getData();if(score==null || score.bandLabel()==null || score.bandLabel().isBlank())return null;
            var data=new LinkedHashMap<String,Object>();data.put("level",score.bandLabel());data.put("serviceExplanation",null);
            data.put("evaluatedAt",score.asOf());data.put("source","CURRENT_RISK_PROJECTION");
            data.put("fieldStatuses",Map.of("serviceExplanation","UNKNOWN"));return data;
        }));
        result.put("annotations",group(()-> {
            var data=new LinkedHashMap<String,Object>();var tags=new ArrayList<String>();
            var customerIdentity=data(result,"identity");var customerRisk=data(result,"risk");
            if(customerIdentity.get("level")!=null) tags.add(customerIdentity.get("level").toString());
            if(customerRisk.get("level")!=null) tags.add(customerRisk.get("level").toString());
            String state=text(customerIdentity,"accountStatus");
            if(state!=null)switch(state.toUpperCase(Locale.ROOT)){case "ACTIVE"->tags.add("账户正常");case "RESTRICTED"->tags.add("账户受限");case "FROZEN"->tags.add("账户冻结");case "BANNED"->tags.add("账户停用");default->{}}
            data.put("systemTags",tags);
            data.put("customTags",annotations.findCustomTags(customer));data.put("notes",annotations.findNotes(customer));return data;
        }));
        result.put("service",group(()-> {
            var data=new LinkedHashMap<>(mapper.service(customer));var assignment=bindings.current(customer);
            for(String field:List.of("lastServiceAt","conversationNo","conversationStatus","archived"))data.putIfAbsent(field,null);
            data.put("assignment",assignment);data.put("assignmentState",assignment==null?"UNBOUND":"BOUND");
            var rules=bindings.rules();var coverage=mapper.coverage();
            data.put("activityStatus","UNKNOWN");data.put("lastEffectiveAt",null);data.put("nextMaintenanceAt",null);
            if(assignment!=null && coverage!=null) {
                var q=SupportWorkbenchService.query(scope,customer,rules,coverage);
                q.put("filter","ALL");q.put("keyword",null);q.put("offset",0);q.put("limit",1);
                var rows=workbench.customers(q);
                if(rows.size()!=1)throw new IllegalStateException("SUPPORT_CUSTOMER_PROJECTION_MISSING");
                data.putAll(SupportWorkbenchService.customerView(rows.get(0),rules));
                data.put("nextMaintenanceAt",data.get("nextDueAt"));
                data.put("maintenanceEnabled",data.get("enabled"));
                data.put("evaluatedAt",coverage.observedThroughAt());
            } else data.put("maintenanceEnabled",null);
            return data;
        }));
        result.put("security",unavailable("FORBIDDEN"));result.put("riskCases",unavailable("FORBIDDEN"));
        result.values().forEach(value->{if(value instanceof Map<?,?> section && section.containsKey("status")) {
            @SuppressWarnings("unchecked") var mutable=(Map<String,Object>)section;mutable.put("evaluatedAt",evaluatedAt);
        }});
        var financeData=data(result,"finance");
        if(!financeData.isEmpty()) {
            financeData.put("evaluatedAt",evaluatedAt);
            @SuppressWarnings("unchecked") var recent=(Map<String,Object>)financeData.get("recentFlows");recent.put("evaluatedAt",evaluatedAt);
        }
        result.put("evaluatedAt",evaluatedAt);result.put("actions",actions());return SupportWorkbenchService.wire(result);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> devices(Long customer,long page,int size) {
        SupportWorkbenchService.requireSafeId(customer);SupportWorkbenchService.validatePage(page,size);ownership.requireRead(customer);
        return group(()-> {
            var response=devices.devices(new DeviceOpsQueryRequest(null,null,null,page,(long)size,customer,null,null));
            if(response.getCode()!=0)throw new BizException(response.getCode(),"SUPPORT_DEVICES_UNAVAILABLE");
            var rows=response.getData();if(rows==null)return null;
            var data=new LinkedHashMap<String,Object>();data.put("records",rows.getRecords().stream().map(row->{
                var record=new LinkedHashMap<String,Object>();record.put("id",row.id());record.put("userId",row.userId());record.put("instanceNo",row.instanceNo());
                record.put("name",row.name());record.put("status",row.status());record.put("runtimeStatus",row.runtimeStatus());record.put("datacenter",row.dcLocation());
                record.put("hashrate",null);record.put("hashrateUnit",null);record.put("storedHashrate",FinanceSupportReadService.decimal(row.hashrate()));
                record.put("dailyUsdt",null);record.put("dailyNex",null);record.put("estimatedDailyUsdt",FinanceSupportReadService.decimal(row.dailyUsdt()));record.put("estimatedDailyNex",FinanceSupportReadService.decimal(row.dailyNex()));
                record.put("hashrateSource","DEVICE_RECORD_MIXED_UNITS");record.put("earningsSource","DEVICE_CONFIGURATION");
                record.put("fieldStatuses",Map.of("hashrate","UNKNOWN","dailyUsdt","UNKNOWN","dailyNex","UNKNOWN"));
                record.put("lastSeenAt",businessUtc(row.lastSeenAt()));record.put("purchasedAt",businessUtc(row.purchasedAt()));
                record.put("activatedAt",businessUtc(row.activatedAt()));record.put("heartbeatAt",businessUtc(row.heartbeatAt()));return record;
            }).toList());data.put("total",rows.getTotal());
            var totals=mapper.deviceTotals(customer);if(totals==null)throw new IllegalStateException("SUPPORT_DEVICE_TOTALS_UNAVAILABLE");
            long unknown=((Number)totals.get("telemetryUnknownCount")).longValue();boolean empty=rows.getTotal()==0;
            data.put("pageNum",page);data.put("pageSize",size);data.putAll(totals);
            data.put("onlineCount",unknown==0?totals.get("observedOnlineCount"):null);
            data.put("hashrateTotal",empty?"0":null);data.put("idleCount",empty?0:null);
            data.put("fieldStatuses",Map.of("onlineCount",unknown==0?"READY":"UNKNOWN","hashrateTotal",empty?"READY":"UNKNOWN","idleCount",empty?"READY":"UNKNOWN"));return data;
        });
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> flows(Long customer,long page,int size,String currency,String status,String from,String to) {
        SupportWorkbenchService.requireSafeId(customer);SupportWorkbenchService.validatePage(page,size);ownership.requireRead(customer);
        if(currency!=null && !currency.matches("[A-Z][A-Z0-9]{0,15}") || status!=null && !status.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new BizException(422,"SUPPORT_FLOW_FILTER_INVALID");
        LocalDateTime start=parseTime(from),end=parseTime(to);
        if(start!=null && end!=null && !start.isBefore(end))throw new BizException(422,"SUPPORT_FLOW_RANGE_INVALID");
        return SupportWorkbenchService.wire(group(()->finance.flows(customer,page,size,currency,status,start,end)));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ConversationCustomerProfile conversationProfile(Long customer) {
        var groups=profile(customer);var identity=data(groups,"identity");var risk=data(groups,"risk");var annotations=data(groups,"annotations");
        var finance=data(groups,"finance");var devices=data(groups,"devices");var service=data(groups,"service");
        @SuppressWarnings("unchecked") List<Map<String,Object>> currencies=(List<Map<String,Object>>)finance.getOrDefault("byCurrency",List.of());
        var usdt=currencies.stream().filter(row->"USDT".equals(row.get("currency"))).findFirst().orElse(Map.of());
        @SuppressWarnings("unchecked") List<ConversationCustomerProfile.CustomerNote> notes=(List<ConversationCustomerProfile.CustomerNote>)annotations.getOrDefault("notes",List.of());
        return new ConversationCustomerProfile(text(identity,"userNo"),text(identity,"nickname"),text(identity,"phoneMasked"),text(identity,"level"),
            strings(annotations.get("systemTags")),strings(annotations.get("customTags")),text(risk,"level"),text(risk,"serviceExplanation"),
            amount(usdt,"creditedDepositTotal"),amount(usdt,"successfulWithdrawalPrincipalTotal"),amount(usdt,"availableBalance"),
            service.get("ticketCount") instanceof Number n?n.intValue():null,devices.get("total")==null?null:String.valueOf(devices.get("total")),
            null,null,text(identity,"region"),text(identity,"registeredAt"),text(identity,"lastLoginAt"),legacyLedger(finance),notes,groups);
    }
    private static Map<String,Object> actions() {
        var result=new LinkedHashMap<String,Object>();
        Map.of("resetPassword","user_c5_password_reset","freeze","user_c2_account_freeze","unfreeze","user_c2_account_unfreeze",
            "adjustBalance","user_c3_adjust_create").forEach((name,permission)->result.put(name,Map.of("allowed",SupportOwnershipService.hasAuthority(permission),
                "reason",SupportOwnershipService.hasAuthority(permission)?"":"PERMISSION_REQUIRED","permission",permission)));
        return result;
    }
    public static Map<String,Object> group(Supplier<?> read) {
        try {Object data=read.get();if(data==null)return unavailable("UNKNOWN");var result=new LinkedHashMap<String,Object>();
            result.put("status","READY");result.put("data",data);result.put("evaluatedAt",Instant.now().toString());return result;
        } catch(RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(SupportCustomerProfileService.class).warn("Service profile section unavailable: {}",ex.getClass().getSimpleName());
            return unavailable(ex instanceof BizException b && b.getCode()==403?"FORBIDDEN":"ERROR");
        }
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SupportAttachmentService.Content avatar(Long customer) {
        ownership.requireRead(customer);String key=mapper.avatar(customer);if(!ownedAvatar(customer,key))throw new BizException(404,"CUSTOMER_AVATAR_NOT_FOUND");
        // Existing customer-upload contract caps stored avatar bytes at 2 MiB.
        try(var stream=storage.get(key)) {
            byte[] bytes=stream.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new java.io.IOException();
            String mime=bytes.length>=8 && bytes[0]==(byte)0x89?"image/png":bytes.length>=3 && bytes[0]==(byte)0xff?"image/jpeg":bytes.length>=12 && bytes[0]=='R' && bytes[8]=='W'?"image/webp":null;
            if(mime==null)throw new java.io.IOException();return new SupportAttachmentService.Content(mime,bytes);
        }catch(java.io.IOException|RuntimeException ex){throw new BizException(503,"CUSTOMER_AVATAR_UNAVAILABLE");}
    }
    private static boolean ownedAvatar(Long customer,String key){return key!=null && key.startsWith("users/"+customer+"/avatar/") && !key.contains("..");}
    private static String businessUtc(LocalDateTime at){return at==null?null:at.atZone(ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toString();}
    @SuppressWarnings("unchecked") private static List<ConversationCustomerProfile.LedgerEntry> legacyLedger(Map<String,Object> finance) {
        var recent=finance.get("recentFlows");if(!(recent instanceof Map<?,?> section) || !(section.get("data") instanceof Map<?,?> data) || !(data.get("records") instanceof List<?> rows))return List.of();
        return rows.stream().map(value->{var row=(Map<String,Object>)value;String amount=row.get("principal")==null?null:row.get("principal")+" "+row.get("currency");
            return new ConversationCustomerProfile.LedgerEntry(text(row,"kind"),text(row,"createdAt"),amount,"DEPOSIT".equals(row.get("kind")),Set.of("SUBMITTED","PENDING","REVIEW_PENDING","PROCESSING").contains(text(row,"status")));}).toList();
    }
    private static Map<String,Object> unavailable(String status) {var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("data",null);result.put("evaluatedAt",Instant.now().toString());if("ERROR".equals(status))result.put("errorHint","Please retry this section");return result;}
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Map<String,Object> groups,String key){var group=(Map<String,Object>)groups.get(key);return group!=null && group.get("data") instanceof Map<?,?> data?(Map<String,Object>)data:Map.of();}
    private static String text(Map<String,Object> data,String key){return data.get(key)==null?null:data.get(key).toString();}
    private static String amount(Map<String,Object> data,String key){return data.get(key)==null?null:data.get(key)+" USDT";}
    private static String first(String a,String b){return a!=null && !a.isBlank()?a:b!=null && !b.isBlank()?b:null;}
    @SuppressWarnings("unchecked") private static List<String> strings(Object value){return value instanceof List<?>?(List<String>)value:List.of();}
    private static LocalDateTime parseTime(String value){if(value==null)return null;try{return LocalDateTime.ofInstant(Instant.parse(value),ZoneOffset.UTC);}catch(DateTimeException ex){throw new BizException(422,"SUPPORT_FLOW_RANGE_INVALID");}}
}
