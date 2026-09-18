package ffdd.opsconsole.content.terms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegalTermsControllerAuthenticationTest {
    private final LegalTermsService service=mock(LegalTermsService.class);
    private final AuthSessionMapper sessions=mock(AuthSessionMapper.class);
    private final UserOpsMapper users=mock(UserOpsMapper.class);
    private final JwtTokenProvider tokens=new JwtTokenProvider(new JwtProperties());
    private MockMvc mvc;
    @BeforeEach void setup() {
        var environment=new MockEnvironment(); environment.setActiveProfiles("dev");
        var filter=new JwtAuthenticationFilter(tokens,sessions,users,environment,new GatewaySecurityProperties(),
                mock(AdminSessionRegistry.class),mock(AdminPermissionCache.class),mock(ImpersonationSessionVerifier.class),mock(PlatformConfigFacade.class));
        mvc=MockMvcBuilders.standaloneSetup(new LegalTermsController(service)).addFilters(filter,
            (request,response,chain)->chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest)request) {
                @Override public Principal getUserPrincipal() { return SecurityContextHolder.getContext().getAuthentication(); }
            },response)).build();
        when(service.current("zh","GLOBAL",null)).thenReturn(ApiResult.ok(null));
        when(service.current("zh","GLOBAL",7L)).thenReturn(ApiResult.ok(null));
        var user=new UserEntity(); user.setId(7L);user.setCountryCode("+86");user.setPhone("13800000007");user.setSandbox(0);
        when(users.selectById(7L)).thenReturn(user);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    private String token(String session) { return tokens.createUserToken(7L,"13800000007",List.of(),session,Duration.ofHours(1),UserAuthEnvironment.PRODUCTION); }
    @Test void revokedPreRotationBearerIsNotProjectedAsAnonymous() throws Exception {
        when(sessions.touchActiveUserSession(eq("old"),eq(7L),anyInt())).thenReturn(0);
        mvc.perform(get("/api/legal/terms/current").param("locale","zh").header("Authorization","Bearer "+token("old")))
                .andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.message").value("USER_AUTH_REQUIRED"));
        verifyNoInteractions(service);
    }
    @Test void malformedSuppliedIdentityIsNotAnonymous() throws Exception {
        mvc.perform(get("/api/legal/terms/current").param("locale","zh").header("Authorization","Bearer invalid"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }
    @Test void anonymousRegistrationCanStillReadPublishedTerms() throws Exception {
        mvc.perform(get("/api/legal/terms/current").param("locale","zh")).andExpect(jsonPath("$.code").value(0));
        verify(service).current("zh","GLOBAL",null);
    }
    @Test void authenticatedNonUserCannotBecomeAnAnonymousLegalRead() throws Exception {
        var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("1",null,List.of());
        authentication.setDetails(java.util.Map.of("subjectType","ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        mvc.perform(get("/api/legal/terms/current").param("locale","zh")).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }
    @Test void ordinaryAnonymousSecurityPrincipalRemainsCompatible() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "fixture","anonymousUser",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        mvc.perform(get("/api/legal/terms/current").param("locale","zh")).andExpect(jsonPath("$.code").value(0));
        verify(service).current("zh","GLOBAL",null);
    }
    @Test void validRefreshedBearerKeepsItsUserIdentity() throws Exception {
        when(sessions.touchActiveUserSession(eq("new"),eq(7L),anyInt())).thenReturn(1);
        mvc.perform(get("/api/legal/terms/current").param("locale","zh").header("Authorization","Bearer "+token("new")))
                .andExpect(jsonPath("$.code").value(0));
        verify(service).current("zh","GLOBAL",7L);
    }
}

