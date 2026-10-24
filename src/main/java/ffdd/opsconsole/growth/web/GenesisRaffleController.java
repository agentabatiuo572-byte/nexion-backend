package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.GenesisRaffleService;
import ffdd.opsconsole.growth.application.OpsGrowthCommandBoundary;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class GenesisRaffleController {
    private final GenesisRaffleService service;
    private final OpsGrowthCommandBoundary commands;
    @GetMapping("/api/events/genesis-raffle")
    @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> state(Authentication auth){return service.state(user(auth));}
    @PostMapping("/api/events/genesis-raffle/{id}/enter")
    @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String,Object>> enter(@PathVariable String id,@RequestBody Entry request,
            @RequestHeader(name="Idempotency-Key",required=false) String key,Authentication auth){
        if(request==null||request.tickets()==null)throw new BizException(422,"RAFFLE_TICKETS_INVALID");
        return service.enter(user(auth),id,request.tickets(),key);
    }
    @GetMapping("/api/admin/growth/genesis-raffle")
    @PreAuthorize("hasAuthority('growth_h4_read')")
    public ApiResult<Map<String,Object>> admin(){return service.admin();}
    @PostMapping("/api/admin/growth/genesis-raffle")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String,Object>> create(@RequestBody GenesisRaffleRequest request,
            @RequestHeader(name="Idempotency-Key",required=false) String key){
        if(request==null)throw new BizException(422,"RAFFLE_CONFIG_INVALID");
        return commands.execute("H4","GENESIS_RAFFLE_CREATE",request.id(),key,request,()->service.save(request.id(),request,true));
    }
    @PatchMapping("/api/admin/growth/genesis-raffle/{id}")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String,Object>> update(@PathVariable String id,@RequestBody GenesisRaffleRequest request,
            @RequestHeader(name="Idempotency-Key",required=false) String key){
        return commands.execute("H4","GENESIS_RAFFLE_UPDATE",id,key,request,()->service.save(id,request,false));
    }
    private Long user(Authentication auth){
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> details)||!"USER".equals(details.get("subjectType")))
            throw new BizException(403,"USER_SUBJECT_REQUIRED");
        try{long id=Long.parseLong(String.valueOf(auth.getPrincipal()));if(id>0)return id;}catch(NumberFormatException ignored){ }
        throw new BizException(403,"USER_SUBJECT_REQUIRED");
    }
    public record Entry(Long tickets) { }
}
