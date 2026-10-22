package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** C1 owns search scope and audit; H4 only exposes the identity needed for a simulation. */
@Service
@RequiredArgsConstructor
public class PromotionAccountSampleService {
    private final OpsUserService users;

    private void requirePermissions() {
        if(!PromotionAdminService.has("growth_promotion_simulate") || !PromotionAdminService.has("user_c1_read"))
            throw new BizException(403,"PROMOTION_ACCOUNT_SAMPLE_FORBIDDEN");
    }
    public Map<String,Object> search(String keyword,String accountId,int pageNum) {
        requirePermissions();
        if(keyword!=null&&keyword.length()>200)throw new BizException(422,"PROMOTION_SAMPLE_QUERY_TOO_LONG");
        Long id=null;
        if(accountId!=null&&!accountId.isBlank())try{
            if(!accountId.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();
            id=Long.valueOf(accountId);if(id<1)throw new NumberFormatException();
        }catch(NumberFormatException invalid){throw new BizException(422,"C1_USER_ID_INVALID");}
        var query=new UserQueryRequest(keyword,null,null,pageNum,20,null,id,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        var result=users.profilePage(query);
        if(result.getCode()!=0)throw new BizException(result.getCode(),result.getMessage());
        var page=result.getData();
        var items=page.getRecords().stream().map(account->values("accountId",String.valueOf(account.id()),
                "label",text(account.userNo())+" · "+text(account.nickname()))).toList();
        return values("items",items,"total",page.getTotal(),"pageNum",page.getPageNum(),"pageSize",page.getPageSize());
    }
    /** Recheck at execution: a caller must not bypass the picker with a foreign internal ID. */
    public void requireReadable(long accountId) {
        requirePermissions();
        try {
            var result=users.profile(accountId);
            if(result.getCode()==404)throw new BizException(403,"PROMOTION_ACCOUNT_SAMPLE_FORBIDDEN");
            if(result.getCode()!=0)throw new BizException(result.getCode(),result.getMessage());
            if(result.getData()==null||!Long.valueOf(accountId).equals(result.getData().id()))
                throw new BizException(403,"PROMOTION_ACCOUNT_SAMPLE_FORBIDDEN");
        } catch(BizException failure) {
            if(failure.getCode()==404)throw new BizException(403,"PROMOTION_ACCOUNT_SAMPLE_FORBIDDEN");
            throw failure;
        }
    }
}
