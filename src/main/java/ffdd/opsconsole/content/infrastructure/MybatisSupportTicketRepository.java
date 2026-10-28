package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSupportTicketRepository implements SupportTicketRepository {
    @Override public PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        return scopedPage(request,scope);
    }
    private PageResult<SupportTicketView> scopedPage(SupportTicketQueryRequest r,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
        java.util.Objects.requireNonNull(scope,"Current scope required");
        long page=normalizePage(r==null?null:r.pageNum()),size=normalizeSize(r==null?null:r.pageSize());
        String tab=normalizeScope(r==null?null:r.scope()),status=r==null?null:trim(r.status()),category=r==null?null:trim(r.category()),priority=r==null?null:trim(r.priority()),keyword=r==null?null:trim(r.keyword());
        Long assigned=r==null?null:r.assignedAdminId(),user=r==null?null:r.userId();
        var reader=visibility();
        long total=ticketMapper.countTicketsScoped(tab,status,category,priority,assigned,user,keyword,reader,scope);
        var rows=total==0?List.<SupportTicketView>of():ticketMapper.pageTicketsScoped(tab,status,category,priority,assigned,user,keyword,size,(page-1)*size,reader,scope);
        return new PageResult<>(total,page,size,rows);
    }
    private final ffdd.opsconsole.content.application.SupportOwnershipService ownership;
    private final SupportTicketMapper ticketMapper;

    private SupportTicketMapper.Visibility visibility() {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details))
            return new SupportTicketMapper.Visibility(null,null,false,false);
        Long actor=ownership.actorId();
        if("USER".equals(details.get("subjectType"))) return new SupportTicketMapper.Visibility(null,actor,false,false);
        boolean privateRead=ffdd.opsconsole.content.application.SupportOwnershipService.hasAuthority("service_m3_read");
        boolean supervisor=privateRead && ownership.supervisor(actor);
        boolean eligible=supervisor;
        if(privateRead && !supervisor) {
            try {ownership.requireEligibleAgent();eligible=true;} catch(ffdd.opsconsole.shared.exception.BizException ex) {if(ex.getCode()!=403)throw ex;}
        }
        return new SupportTicketMapper.Visibility(actor,null,privateRead && eligible,supervisor);
    }

    private long normalizePage(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizeSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeScope(String value) {
        String scope = trim(value);
        return scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope) ? null : scope.toLowerCase();
    }
}
