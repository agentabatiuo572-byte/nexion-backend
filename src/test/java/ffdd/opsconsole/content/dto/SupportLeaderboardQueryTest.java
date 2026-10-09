package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportLeaderboardQueryTest {
    private static final Instant NOW=Instant.parse("2026-10-09T01:00:00Z");
    private static final Set<YearMonth> MONTHS=Set.of(YearMonth.of(2026,9),YearMonth.of(2026,10));
    private SupportLeaderboardQuery.Normalized query(Map<String,String> p) {
        Map<String,List<String>> raw=new HashMap<>(); p.forEach((k,v)->raw.put(k,List.of(v)));
        return SupportLeaderboardQuery.fromParameters(raw).normalize(NOW,MONTHS,Set.of("USDT","NEX"),"USDT",
            Map.of(Scope.all,Set.of(),Scope.ownGroup,Set.of(10L),Scope.managedGroups,Set.of(10L)));
    }
    private void invalid(Map<String,String> p) {assertEquals(422,assertThrows(BizException.class,()->query(p)).getCode());}
    @Test void defaultMonthAndCurrencyComeFromServerAndShanghaiBusinessMonth() {
        var q=query(Map.of()); assertEquals(Board.firstPayment,q.board()); assertEquals(YearMonth.of(2026,10),q.rankMonth());
        assertEquals("USDT",q.currency()); assertEquals(Scope.all,q.scope()); assertEquals(1,q.pageNum()); assertEquals(20,q.pageSize());
        var boundary=SupportLeaderboardQuery.fromParameters(Map.of()).normalize(Instant.parse("2026-09-30T16:00:00Z"),MONTHS,Set.of("NEX"),"NEX",Map.of(Scope.all,Set.of()));
        assertEquals(YearMonth.of(2026,10),boundary.rankMonth()); assertEquals("NEX",boundary.currency());
    }
    @Test void unknownDuplicateRoleAgentSortAndUnitParametersAreRejected() {
        for(String key:List.of("agentId","adminId","role","businessZone","sortKey","direction","unit","from","to"))
            assertEquals(422,assertThrows(BizException.class,()->SupportLeaderboardQuery.fromParameters(Map.of(key,List.of("1")))).getCode());
        assertThrows(BizException.class,()->SupportLeaderboardQuery.fromParameters(Map.of("board",List.of("deposit","purchase"))));
        assertThrows(BizException.class,()->SupportLeaderboardQuery.fromParameters(Map.of("month",List.of())));
    }
    @Test void fourExactBoardsMonthsAndCurrenciesRejectInvalidOrUncoveredInputs() {
        for(Board b:Board.values()) assertEquals(b,query(Map.of("board",b.name())).board());
        for(String m:List.of("2026-13","2026-9","2026-09-01","0000-01","2026-11","2025-01","2026-10 ")) invalid(Map.of("month",m));
        for(String c:List.of("USD","usdt","USDT,NEX",""," USDT")) invalid(Map.of("currency",c));
        invalid(Map.of("board","FIRST_PAYMENT"));
        assertEquals("NEX",query(Map.of("currency","NEX")).currency());
    }
    @Test void customersRejectsHiddenMonthAndUsesCurrentReferenceMonth() {
        var q=query(Map.of("board","customers","currency","NEX")); assertNull(q.rankMonth());
        assertEquals(YearMonth.of(2026,10),q.referenceMonth()); invalid(Map.of("board","customers","month","2026-09"));
    }
    @Test void pageAndIdsRequireBoundedSafeIntegersAndCrossPageVersion() {
        for(String p:List.of("0","-1","1.0","1e2"," 1","9007199254740992","9223372036854775808")) invalid(Map.of("pageNum",p));
        for(String p:List.of("0","101","1.5","-1")) invalid(Map.of("pageSize",p));
        invalid(Map.of("pageNum","2")); invalid(Map.of("expectedVersion","saq-v1:"+"0".repeat(64)));
        invalid(Map.of("scope","ownGroup","groupId","9007199254740992"));
        var q=query(Map.of("pageNum","9007199254740991","pageSize","100","expectedVersion","slb-v1:"+"0".repeat(64)));
        assertEquals(9007199254740991L,q.pageNum());
    }
    @Test void scopeAndGroupMustBeServerApprovedAndAllCannotContainGroup() {
        assertEquals(10L,query(Map.of("scope","ownGroup","groupId","10")).groupId());
        assertEquals(403,assertThrows(BizException.class,()->query(Map.of("scope","managedGroups","groupId","20"))).getCode());
        invalid(Map.of("scope","all","groupId","10")); invalid(Map.of("scope","ALL"));
        var raw=SupportLeaderboardQuery.fromParameters(Map.of("scope",List.of("ownGroup")));
        assertEquals(403,assertThrows(BizException.class,()->raw.normalize(NOW,MONTHS,Set.of("USDT"),"USDT",Map.of(Scope.all,Set.of()))).getCode());
        assertEquals(403,assertThrows(BizException.class,()->raw.normalize(NOW,MONTHS,Set.of("USDT"),"USDT",Map.of(Scope.ownGroup,Set.of()))).getCode());
        var otherGroup=SupportLeaderboardQuery.fromParameters(Map.of("scope",List.of("ownGroup"),"groupId",List.of("20")));
        assertEquals(403,assertThrows(BizException.class,()->otherGroup.normalize(NOW,MONTHS,Set.of("USDT"),"USDT",
            Map.of(Scope.ownGroup,Set.of(10L),Scope.managedGroups,Set.of(20L)))).getCode());
    }
    @Test void keywordIsOnlyBoundedSearchAndServerPolicyFailureIsNotAClientFallback() {
        assertEquals("Agent 1",query(Map.of("keyword"," Agent 1 ")).keyword());
        assertNull(query(Map.of("keyword","  ")).keyword()); invalid(Map.of("keyword","x".repeat(201))); invalid(Map.of("keyword","A\nB"));
        assertThrows(IllegalArgumentException.class,()->SupportLeaderboardQuery.fromParameters(Map.of()).normalize(NOW,MONTHS,Set.of("NEX"),"USDT",Map.of(Scope.all,Set.of())));
    }
}
