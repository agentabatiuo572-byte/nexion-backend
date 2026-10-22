package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

class PromotionAccountSampleServiceTest {
    final OpsUserService users=mock(OpsUserService.class);
    final PromotionAccountSampleService samples=new PromotionAccountSampleService(users);
    void auth(String... permissions){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("71","",Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void bothReadAuthoritiesAreRequiredBeforeSearching(){
        for(var permissions:List.of(new String[]{},new String[]{"growth_promotion_simulate"},new String[]{"user_c1_read"})){
            auth(permissions);assertEquals(403,assertThrows(BizException.class,()->samples.search("U29",null,1)).getCode());
        }
        verifyNoInteractions(users);
    }
    @Test void returnsOnlyScopedIdentityAndLabelWithoutGuessingTheId(){
        auth("growth_promotion_simulate","user_c1_read");
        var account=mock(UserAccountView.class);when(account.id()).thenReturn(29L);when(account.userNo()).thenReturn("U000000029");when(account.nickname()).thenReturn("sample");
        when(users.profilePage(any())).thenReturn(ApiResult.ok(new PageResult<>(31,2,20,List.of(account))));
        var result=samples.search("U000000029",null,2);var item=maps(result.get("items")).get(0);
        assertEquals(Set.of("accountId","label"),item.keySet());assertEquals("29",item.get("accountId"));assertEquals("U000000029 · sample",item.get("label"));
        assertEquals(2,number(result.get("pageNum")));assertEquals(31,number(result.get("total")));
        verify(users).profilePage(UserQueryRequest.basic("U000000029",null,null,2,20,null));
    }
    @Test void c1ValidationFailureCannotBecomeAnEmptySuccess(){
        auth("growth_promotion_simulate","user_c1_read");
        when(users.profilePage(any())).thenReturn(ApiResult.fail(422,"C1_RAW_PHONE_SEARCH_FORBIDDEN"));
        assertEquals("C1_RAW_PHONE_SEARCH_FORBIDDEN",assertThrows(BizException.class,()->samples.search("raw",null,1)).getMessage());
    }
    @Test void explicitInternalIdUsesC1ExactFilterRatherThanGuessingFromUserNo(){
        auth("growth_promotion_simulate","user_c1_read");
        when(users.profilePage(any())).thenReturn(ApiResult.ok(new PageResult<>(0,1,20,List.of())));
        samples.search("","60723153029",1);
        verify(users).profilePage(new UserQueryRequest("",null,null,1,20,null,60723153029L,null,null,null,null,null,null,null,null,null,null,null,null,null,null));
    }
    @Test void aCraftedSimulationSampleIsRecheckedAgainstTheC1VisibleScope(){
        auth("growth_promotion_simulate","user_c1_read");
        when(users.profile(29L)).thenReturn(ApiResult.fail(404,"USER_NOT_FOUND"));
        var admin=new PromotionAdminService(null,null,null,null,null,null,null,null,null,null,null,null,null,null,samples);
        assertEquals(403,assertThrows(BizException.class,()->admin.simulate("activity",values("sampleAccountId","29"))).getCode());
        verify(users).profile(29L);
    }
    @Test void visibleSampleUsesTheSameC1ProfileScopeAndPropagatesOutages(){
        auth("growth_promotion_simulate","user_c1_read");
        var account=mock(UserAccountView.class);when(account.id()).thenReturn(29L);
        when(users.profile(29L)).thenReturn(ApiResult.ok(account));samples.requireReadable(29L);
        when(users.profile(29L)).thenReturn(ApiResult.fail(503,"USER_PROFILE_UNAVAILABLE"));
        assertEquals(503,assertThrows(BizException.class,()->samples.requireReadable(29L)).getCode());
    }
    @Test void referralSimulationCannotExposeAnUnreadableInviter(){
        auth("growth_promotion_simulate","user_c1_read");
        var buyer=mock(UserAccountView.class);when(buyer.id()).thenReturn(29L);
        when(users.profile(29L)).thenReturn(ApiResult.ok(buyer));when(users.profile(30L)).thenReturn(ApiResult.fail(404,"USER_NOT_FOUND"));
        var db=mock(ffdd.opsconsole.promotion.mapper.PromotionMapper.class);
        when(db.activity("activity",false)).thenReturn(values());when(db.user(29L,false)).thenReturn(values("sponsor_user_id",30L));
        var admin=new PromotionAdminService(db,mock(PromotionContractValidator.class),null,null,null,null,null,null,null,null,null,null,null,null,samples);
        var audience=values("registrationAge",values("minDays",null,"maxDays",null),"lastValidPurchaseAge",values("minDays",null,"maxDays",null),"sponsor","ANY");
        var draft=values("template","DIRECT_REFERRAL","buyerAudience",audience,"inviterAudience",audience,"budgets",List.of(),"rules",List.of());
        assertEquals(403,assertThrows(BizException.class,()->admin.simulate("activity",values("sampleAccountId","29","draft",draft))).getCode());
        verify(users).profile(30L);
    }
    @Test void thrownOwnershipDenialHasTheSameResponseAsAnUnreadableProfile(){
        auth("growth_promotion_simulate","user_c1_read");
        when(users.profile(29L)).thenThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_READABLE"));
        var failure=assertThrows(BizException.class,()->samples.requireReadable(29L));
        assertEquals(403,failure.getCode());assertEquals("PROMOTION_ACCOUNT_SAMPLE_FORBIDDEN",failure.getMessage());
    }
}
