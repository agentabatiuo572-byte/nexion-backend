package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CregisExceptionAdminGuard {
    private final AdminOperatorRoleResolver admins;
    public long require(boolean write) {
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null || !a.isAuthenticated() || !(a.getDetails() instanceof Map<?,?> d) || !"ADMIN".equals(d.get("subjectType"))
                || !a.getAuthorities().stream().anyMatch(g->"finance_d1_read".equals(g.getAuthority()))
                || write && !a.getAuthorities().stream().anyMatch(g->"finance_d1_channel_manage".equals(g.getAuthority()))) throw denied();
        long id;
        try {id=Long.parseLong(String.valueOf(a.getPrincipal()));} catch(NumberFormatException e){throw denied();}
        if(!admins.isActiveAdministrator(id)) throw denied();
        return id;
    }
    public void requireActive(long id) {
        if(id<=0 || !admins.isActiveAdministrator(id)) throw denied();
    }
    private BizException denied(){return new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");}
}
