package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.domain.ContentConversationMessagePage.*;
import ffdd.opsconsole.content.mapper.ConversationHistoryMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportConversationHistoryServiceTest {
    static final LocalDateTime T=LocalDateTime.of(2026,10,29,0,0,0,123456000);
    static final Comparator<ContentConversationMessageView> ORDER=Comparator.comparing(ContentConversationMessageView::createdAt).thenComparing(ContentConversationMessageView::id);
    static final class Fixture {
        final ConversationRepository repository=mock(ConversationRepository.class);
        final ConversationHistoryMapper mapper=mock(ConversationHistoryMapper.class);
        final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
        final ProductionSupportPathGuard production=mock(ProductionSupportPathGuard.class);
        final SupportConversationHistoryService service=new SupportConversationHistoryService(repository,mapper,ownership,production);
        final List<ContentConversationMessageView> source=new ArrayList<>();
        Fixture(){
            when(repository.findByConversationNo("CV-0")).thenReturn(Optional.of(new ContentConversationView(1L,"CV-0",42L,"advisor","OPEN","7","Advisor",0,"same",T,null,null,null,null,null,null,null,T,8L)));
            when(mapper.identity(42)).thenReturn(new CustomerIdentity(42,"U00000042","Customer","187****3778"));
            when(ownership.actorId()).thenReturn(7L);
            when(mapper.currentReadGrants(7L)).thenReturn(List.of(1L));
            when(mapper.cursor(eq(42L),anyLong())).thenAnswer(call->source.stream().filter(m->m.id().equals(call.getArgument(1))).findFirst().map(m->new ConversationHistoryMapper.Cursor(m.id(),m.createdAt())).orElse(null));
            when(mapper.page(eq(42L),any(),any())).thenAnswer(call->{
                LocalDateTime at=call.getArgument(1);Long id=call.getArgument(2);
                return source.stream().filter(m->id==null || m.createdAt().isBefore(at) || m.createdAt().equals(at)&&m.id()<id).sorted(ORDER.reversed()).limit(11).toList();
            });
            when(mapper.readContexts(eq(42L),anyCollection())).thenAnswer(call->{
                Collection<Long> ids=call.getArgument(1);var bySource=new TreeMap<String,Long>();
                source.stream().filter(m->ids.contains(m.id())).forEach(m->bySource.merge(m.conversationNo(),m.id(),Math::max));
                return bySource.entrySet().stream().map(e->new ReadContext(e.getKey(),"CLOSED",5,e.getValue(),false)).toList();
            });
            when(mapper.replyTargets(42)).thenReturn(List.of(new ReplyTarget("CV-1",2)));
        }
        Fixture messages(int count){for(long id=1;id<=count;id++)source.add(message(id,T));return this;}
    }
    static ContentConversationMessageView message(long id,LocalDateTime at){return new ContentConversationMessageView(id,id%9,"CV-"+(id%9),id%2==0?42L:7L,id%2==0?"user":"agent",id%2==0?"Customer":"Advisor","same",id%4==0?"read":"sent",at);}
    @BeforeEach void authenticate(){org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("7",null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m3_read"),new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m3_write"))));}
    @AfterEach void clear(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}

    @Test void sixtyNineMessagesAcrossNineSegmentsNeedSevenRealBoundedPagesWithoutLosingDuplicates(){
        var f=new Fixture().messages(69);var all=new ArrayList<ContentConversationMessageView>();Long before=null;int pages=0;
        do {
            var page=f.service.page("CV-0",before);pages++;assertThat(page.messages()).hasSize(Math.min(10,69-all.size()));
            assertThat(page.messages()).isSortedAccordingTo(ORDER);all.addAll(0,page.messages());
            assertThat(page.customerIdentity().userNo()).isEqualTo("U00000042");
            assertThat(page.replyContext()).isEqualTo(new ReplyContext(true,List.of(new ReplyTarget("CV-1",2))));
            for(var context:page.readContexts())assertThat(page.messages()).anyMatch(m->m.id()==context.lastSeenMessageId() && m.conversationNo().equals(context.conversationNo()) && "user".equals(m.senderType()));
            before=page.nextBeforeMessageId();assertThat(page.hasMore()).isEqualTo(before!=null);
        } while(before!=null);
        assertThat(pages).isEqualTo(7);assertThat(all).containsExactlyElementsOf(f.source);
        verify(f.ownership,times(14)).readConversation("CV-0");verify(f.repository,never()).messages(anyString());
    }
    @Test void databaseMicrosecondsAndIdAreTheCursorNotRoundedClientMilliseconds(){
        var f=new Fixture().messages(22);f.source.set(0,message(1,T.minusNanos(1000)));
        var first=f.service.page("CV-0",null);long cursor=first.nextBeforeMessageId();
        f.source.add(message(100,T.plusSeconds(1)));
        var older=f.service.page("CV-0",cursor);
        assertThat(older.messages()).extracting(ContentConversationMessageView::id).containsExactly(3L,4L,5L,6L,7L,8L,9L,10L,11L,12L);
        verify(f.mapper).page(42,T,cursor);assertThat(first.messages()).doesNotContainAnyElementsOf(older.messages());
    }
    @Test void foreignOrMissingCursorFailsBeforeLoadingAWindow(){
        var f=new Fixture().messages(11);
        assertThatThrownBy(()->f.service.page("CV-0",999L)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        verify(f.mapper,never()).page(anyLong(),any(),any());verify(f.mapper,never()).identity(anyLong());
    }
    @Test void everyPageRechecksCurrentAuthorizationAndARevokedOlderPageReadsNoMessages(){
        var f=new Fixture().messages(11);var first=f.service.page("CV-0",null);clearInvocations(f.mapper);
        doThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND")).when(f.ownership).readConversation("CV-0");
        assertThatThrownBy(()->f.service.page("CV-0",first.nextBeforeMessageId())).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");verifyNoInteractions(f.mapper);
    }
    @Test void emptyWindowIsARealEmptyResultWithoutAReadAllFallback(){
        var f=new Fixture();var page=f.service.page("CV-0",null);
        assertThat(page.messages()).isEmpty();assertThat(page.hasMore()).isFalse();assertThat(page.nextBeforeMessageId()).isNull();assertThat(page.readContexts()).isEmpty();
        verify(f.mapper,never()).readContexts(anyLong(),anyCollection());verify(f.repository,never()).messages(anyString());
    }
    @Test void sourceFailureIsNotAnEmptyPageAndReplyMetadataFailureDisablesSending(){
        var f=new Fixture().messages(12);doThrow(new org.springframework.dao.DataAccessResourceFailureException("metadata down")).when(f.mapper).replyTargets(42);
        assertThat(f.service.page("CV-0",null).replyContext().ready()).isFalse();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("messages down")).when(f.mapper).page(anyLong(),any(),any());
        assertThatThrownBy(()->f.service.page("CV-0",null)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void reviewOnlyReaderDoesNotReceiveWriterTargets(){
        var f=new Fixture().messages(1);doThrow(new BizException(404,"NOT_OWNER")).when(f.ownership).requireWriter(42L,false);
        var page=f.service.page("CV-0",null);assertThat(page.replyContext()).isEqualTo(new ReplyContext(true,List.of()));verify(f.mapper,never()).replyTargets(anyLong());
    }
    @Test void revokedApiGrantOverridesStaleJwtAuthorityBeforeAnyCursorOrBodyRead(){
        var f=new Fixture().messages(11);when(f.mapper.currentReadGrants(7L)).thenReturn(List.of());
        assertThatThrownBy(()->f.service.page("CV-0",1L)).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(f.repository);verify(f.mapper,never()).cursor(anyLong(),anyLong());verify(f.mapper,never()).page(anyLong(),any(),any());
    }
}
