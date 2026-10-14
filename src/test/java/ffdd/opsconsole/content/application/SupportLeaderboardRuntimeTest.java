package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.dto.SupportGroupRequests;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real owned-group HTTP and production source reads. No fabricated money, coverage, clock or baseline. */
@EnabledIfEnvironmentVariable(named="CS_LEADERBOARD_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportLeaderboardRuntimeTest extends SupportAnalyticsRuntimeFixture {
    private static final String API="/api/admin/content/support-workbench/leaderboard";
    private static final String PUBLICATIONS="nx_support_leaderboard_publication";
    private static final String POINTERS="nx_support_leaderboard_latest";
    private static final Set<String> PUBLIC_ROW=Set.of("agentId","name","avatarUrl","groupName","qualification",
        "rank","isTied","rankMetricCoverage","firstPayment","customers","amount","movement","canViewCustomers");
    @Autowired SupportLeaderboardSourceService rankSource;
    @Autowired SupportLeaderboardPublicationService publications;
    private final List<Long> rankAgents=new ArrayList<>();
    private final Map<Long,List<Long>> rankCustomers=new LinkedHashMap<>();
    private final Map<String,Context> ownedStreams=new LinkedHashMap<>();
    private List<Map<String,Object>> originalPublications,originalPointers;
    private long rankGroup;

    @DynamicPropertySource static void boundary(DynamicPropertyRegistry r){SupportEnhancementPreparationTest.isolatedBoundary(r);}

    @BeforeEach void prepareExactOwnedRankGroup() throws Exception {
        originalPublications=fresh("SELECT * FROM "+PUBLICATIONS+" ORDER BY id");
        originalPointers=fresh("SELECT * FROM "+POINTERS+" ORDER BY stream_key");
        for(int i=0;i<4;i++)rankAgents.add(actor("rank"+i,"SUPPORT","DEDICATED"));
        fixture.asSuper(boss,()->rankGroup=fixture.create(manager,rankAgents,run+"rank").id());
        for(int i=0;i<4;i++){
            long agentId=rankAgents.get(i);var ids=new ArrayList<Long>();rankCustomers.put(agentId,ids);
            for(int n=0;n<(i==0?3:i==3?0:2);n++){long c=customer(false);ids.add(c);bind(c,agentId);}
        }
        assertThat(fixture.createdGroupIds()).contains(rankGroup);
        assertThat(fresh("SELECT id FROM "+PUBLICATIONS+" WHERE groups_key=?",Long.toString(rankGroup))).isEmpty();
        nativeObservations.put("ownedRankGroupId",rankGroup);
        nativeObservations.put("ownedRankActorReceipts",rankAgents.stream().map(actors::creationReference).toList());
    }

    @Test void realBoundCountsProduceCompetitionTiesPinnedPagesSelfAndSearch() throws Exception {
        long reader=rankAgents.get(0);JsonNode first=board(reader,Board.customers,"USDT","&pageSize=2");
        assertThat(first.path("state").asText()).isEqualTo("COMPLETE");
        assertThat(first.path("rankMonth").isNull()).isTrue();
        assertThat(first.path("total").asInt()).isEqualTo(4);
        assertThat(first.path("ranked").asInt()).isEqualTo(4);assertThat(first.path("unranked").asInt()).isZero();
        String version=version(first);JsonNode second=board(reader,Board.customers,"USDT","&pageSize=2&pageNum=2&expectedVersion="+encoded(version));
        assertThat(version(second)).isEqualTo(version);
        var rows=new ArrayList<JsonNode>();first.path("rows").forEach(rows::add);second.path("rows").forEach(rows::add);
        assertThat(rows.stream().map(r->r.path("agentId").asLong()).toList()).containsExactlyElementsOf(rankAgents);
        assertThat(rows.stream().map(r->r.path("rank").asInt()).toList()).containsExactly(1,2,2,4);
        assertThat(rows.stream().map(r->r.path("customers").path("value").asLong()).toList()).containsExactly(3L,2L,2L,0L);
        for(int i=0;i<rows.size();i++){publicRow(rows.get(i));assertThat(rows.get(i).path("isTied").asBoolean()).isEqualTo(i==1||i==2);}
        assertThat(first.path("self")).isEqualTo(second.path("self"));
        assertThat(first.path("self").path("row")).isEqualTo(rows.get(0));
        JsonNode located=board(reader,Board.customers,"USDT","&keyword="+rankAgents.get(3)+"&expectedVersion="+encoded(version));
        assertThat(located.path("matched").asInt()).isEqualTo(1);assertThat(located.path("rows").get(0).path("rank").asInt()).isEqualTo(4);
        JsonNode detail=summary(reader,rankAgents.get(1),Board.customers,"USDT",version);
        assertThat(detail.path("row")).isEqualTo(rows.get(1));
        rejectBoard(reader,Board.customers,"USDT","&pageNum=2",422);
        nativeObservations.put("competitionRanks",List.of(1,2,2,4));
        checked("Real assignment-source 3/2/2/0 customers produce 1/2/2/4 across pinned HTTP pages/self/search/detail; true zero retained");
    }

    @Test void actualAllSourceAndPublicOwnedSummaryNeverGrantForeignObjectRead() throws Exception {
        long reader=rankAgents.get(0),foreign=customer(false);bind(foreign,foreignAgent);
        var all=source(Board.customers,"USDT",Scope.all,Set.of());
        var allIds=all.candidates().stream().map(Candidate::agentId).toList();
        assertThat(allIds).doesNotHaveDuplicates().containsAll(rankAgents).contains(foreignAgent).doesNotContain(manager,foreignManager,boss,ownerless);
        JsonNode page=board(reader,Board.customers,"USDT","");String version=version(page);
        for(long colleague:rankAgents){JsonNode row=summary(reader,colleague,Board.customers,"USDT",version).path("row");publicRow(row);assertThat(row.path("canViewCustomers").asBoolean()).isEqualTo(colleague==reader);}
        JsonNode managerPage=managedBoard(manager,Board.customers,"USDT","");
        for(JsonNode row:managerPage.path("rows")){publicRow(row);assertThat(row.path("canViewCustomers").asBoolean()).isTrue();}
        rejected(request("GET","/api/admin/content/support-workbench/customers/"+foreign,reader,null,null),403,404);
        rejected(request("GET","/api/admin/content/support-workbench/customers/"+rankCustomers.get(rankAgents.get(1)).get(0),reader,null,null),403,404);
        deny(manager,"groupId="+foreignGroup,403,404);
        rejected(request("GET",API+"?board=customers&scope=managedGroups&groupId="+foreignGroup,manager,null,null),403);
        rejected(request("GET",API+"?board=customers&scope=ownGroup&groupId="+foreignGroup,reader,null,null),403);
        rejected(request("GET",API+"?board=customers&scope=all&customerId="+foreign,reader,null,null),422);
        rejected(request("GET",API+"?board=customers&scope=all&agentId="+foreignAgent,reader,null,null),422);
        rejected(request("GET",API+"?board=customers&scope=all&groupId="+rankGroup,reader,null,null),422);
        rejected(request("GET",API+"?board=customers&scope=ownGroup",ownerless,null,null),403);
        var legalAllQuery=ffdd.opsconsole.content.dto.SupportLeaderboardQuery.fromParameters(Map.of("board",List.of("customers"),"scope",List.of("all")));
        assertThat(legalAllQuery.normalize(now(),Set.of(month()),SupportAnalyticsPrivateQueryService.CURRENCIES,"USDT",Map.of(Scope.all,Set.of())).scope()).isEqualTo(Scope.all);
        nativeObservations.put("allOwnedSourceCandidates",allIds.stream().filter(actors.ownedIds()::contains).toList());
        checked("Production ALL aggregate source retains colleagues while owned-scope public HTTP whitelist and independent private customer/foreign-group denials preserve object authority; normal ALL HTTP not claimed");
    }

    @Test void realFourBoardsKeepTwoCurrenciesCurrentBindingsAndUnprovedMoneySeparate() throws Exception {
        long reader=rankAgents.get(0);Map<Long,Integer> countRanks=new LinkedHashMap<>();
        for(Board board:List.of(Board.netContribution,Board.firstPayment,Board.customers))for(String currency:List.of("USDT","NEX")){
            JsonNode page=board(reader,board,currency,"");
            assertThat(page.path("referenceMonth").asText()).isEqualTo(month().toString());
            assertThat(page.path("currency").asText()).isEqualTo(currency);
            assertThat(page.path("rows").size()).isEqualTo(4);
            for(JsonNode row:page.path("rows")){
                publicRow(row);long id=row.path("agentId").asLong();
                assertThat(row.path("customers").path("value").asLong()).isEqualTo(rankCustomers.get(id).size());
                assertThat(row.path("customers").path("coverage").asText()).isEqualTo("COMPLETE");
                assertThat(row.path("firstPayment").path("value").isNull()).isTrue();
                assertThat(row.path("firstPayment").path("coverage").asText()).isEqualTo("PARTIAL");
                assertThat(row.path("amount").path("value").isNull()).isTrue();
                assertThat(row.path("amount").path("coverage").asText()).isEqualTo("UNKNOWN");
                assertThat(row.path("amount").path("currency").asText()).isEqualTo(currency);
                assertThat(row.path("amount").path("month").asText()).isEqualTo(month().toString());
                assertThat(row.path("amount").path("kind").asText()).isEqualTo("NET_CONTRIBUTION");
                assertThat(row.path("movement").path("kind").asText()).isEqualTo("UNAVAILABLE");
                assertThat(row.path("movement").path("places").isNull()).isTrue();
                if(board==Board.customers){if(currency.equals("USDT"))countRanks.put(id,row.path("rank").asInt());else assertThat(row.path("rank").asInt()).isEqualTo(countRanks.get(id));}
                else {assertThat(row.path("rank").isNull()).isTrue();assertThat(row.path("isTied").asBoolean()).isFalse();}
            }
            if(board==Board.customers){assertThat(page.path("state").asText()).isEqualTo("COMPLETE");assertThat(page.path("rankMonth").isNull()).isTrue();}
            else {assertThat(page.path("state").asText()).isEqualTo("PROVISIONAL");assertThat(page.path("ranked").asInt()).isZero();assertThat(page.path("unranked").asInt()).isEqualTo(4);assertThat(page.path("self").path("gap").isNull()).isTrue();}
            assertThat(summary(reader,rankAgents.get(1),board,currency,version(page)).path("row")).isEqualTo(find(page,rankAgents.get(1)));
        }
        rejectBoard(reader,Board.customers,"USDT","&month="+month(),422);
        rejectBoard(reader,Board.deposit,"USDT","&month="+month().minusMonths(1),422);
        rejectBoard(reader,Board.deposit,"VND","",422);
        checked("Four real HTTP boards xUSDT/NEX keep current bound counts independent; uncaptured first histories and unproved refund/net money stay null, no authoritative monetary ranks or fake movement");
    }

    @Test void productionTransfersRecomputeOwnedVersionWithoutRewritingAnyPriorPublication() throws Exception {
        long reader=rankAgents.get(0),other=rankAgents.get(3),c=rankCustomers.get(reader).get(0);
        JsonNode first=board(reader,Board.customers,"USDT","");String oldVersion=version(first);
        var before=ownedPublicationRows();assertThat(before).isNotEmpty();
        bind(c,other);bind(c,reader);bind(c,other);
        var read=source(Board.customers,"USDT",Scope.ownGroup,Set.of(rankGroup));
        var calculated=SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates());
        var enriched=rr(()->SupportLeaderboard.withMovement(calculated,publications.previousBusinessDay(read.context())));
        registerStream(read.context());actors.assertBusinessEntry();var committed=publications.publish(enriched);
        assertThat(fresh("SELECT view_version FROM "+PUBLICATIONS+" WHERE id=?",committed.publicationId())).singleElement().satisfies(row->assertThat(row.get("view_version")).isEqualTo(committed.snapshot().viewVersion()));
        JsonNode after=board(reader,Board.customers,"USDT","");assertThat(version(after)).isEqualTo(committed.snapshot().viewVersion()).isNotEqualTo(oldVersion);
        assertThat(find(after,reader).path("customers").path("value").asLong()).isEqualTo(2);
        assertThat(find(after,other).path("customers").path("value").asLong()).isEqualTo(1);
        assertThat(fresh("SELECT COUNT(*) n FROM nx_support_agent_user_assignment WHERE user_id=? AND is_deleted=0 AND ends_at IS NULL",c)).singleElement().satisfies(row->assertThat(((Number)row.get("n")).longValue()).isEqualTo(1));
        var all=ownedPublicationRows();for(var old:before)assertThat(all).contains(old);
        rejectBoard(reader,Board.customers,"USDT","&pageNum=2&expectedVersion="+encoded(oldVersion),409);
        JsonNode summary=summary(reader,other,Board.customers,"USDT",version(after));assertThat(summary.path("row")).isEqualTo(find(after,other));
        for(JsonNode row:after.path("rows")){assertThat(row.path("movement").path("reason").asText()).isEqualTo("NO_BASELINE");assertThat(row.path("movement").path("baselineAt").isNull()).isTrue();assertThat(row.path("movement").path("places").isNull()).isTrue();}
        nativeObservations.put("ownedCommittedPublicationId",committed.publicationId());
        checked("Real repeated assignment transfers count only current binding; real source->publication commit advances owned stream, prior rows unchanged, pinned stale HTTP409 and detail same version; absent yesterday never becomes0");
    }

    @Test void sameTokenImmediatelyLosesOldGroupAndCustomerAfterOwnedProductionMove() throws Exception {
        long reader=rankAgents.get(0),c=rankCustomers.get(reader).get(0);
        board(reader,Board.customers,"USDT","");String oldToken=token(reader);
        bind(c,foreignAgent);
        fixture.asSuper(boss,()->{var before=groupMapper.memberCurrent(reader);String command=key();var move=new SupportGroupRequests.Move(foreignGroup,before.version(),groupMapper.group(rankGroup).version(),groupMapper.group(foreignGroup).version(),"Owned rank boundary move");exactFacts.command("GROUP_MEMBER",command,move,()->groups.move(reader,command,move));});
        assertThat(fresh("SELECT group_id FROM nx_support_group_member_history WHERE agent_admin_id=? AND ends_at IS NULL",reader)).singleElement().satisfies(row->assertThat(((Number)row.get("group_id")).longValue()).isEqualTo(foreignGroup));
        rejected(request("GET",API+"?board=customers&scope=ownGroup&groupId="+rankGroup+"&expectedVersion=slb-v1:"+"0".repeat(64),reader,null,null),403);
        rejected(request("GET","/api/admin/content/support-workbench/customers/"+c,reader,null,null),403,404);
        assertThat(token(reader)).isEqualTo(oldToken);
        var originalScope=source(Board.customers,"USDT",Scope.ownGroup,Set.of(rankGroup));assertThat(originalScope.candidates().stream().map(Candidate::agentId).toList()).doesNotContain(reader);
        checked("Original token denied old owned group before version and denied transferred customer immediately after real group/customer commands; original-scope source membership updated; no supervisor removal claim");
    }

    private JsonNode board(long actor,Board board,String currency,String extra)throws Exception{return scopedBoard(actor,board,currency,Scope.ownGroup,extra);}
    private JsonNode managedBoard(long actor,Board board,String currency,String extra)throws Exception{return scopedBoard(actor,board,currency,Scope.managedGroups,extra);}
    private JsonNode scopedBoard(long actor,Board board,String currency,Scope scope,String extra)throws Exception{
        registerStream(context(board,currency,scope,Set.of(rankGroup)));
        return ok(request("GET",API+"?"+query(board,currency,scope)+extra,actor,null,null));
    }
    private JsonNode summary(long actor,long colleague,Board board,String currency,String version)throws Exception{
        registerStream(context(board,currency,Scope.ownGroup,Set.of(rankGroup)));
        return ok(request("GET",API+"/"+colleague+"?"+query(board,currency,Scope.ownGroup)+"&expectedVersion="+encoded(version),actor,null,null));
    }
    private void rejectBoard(long actor,Board board,String currency,String extra,Integer... codes)throws Exception{
        rejected(request("GET",API+"?"+query(board,currency,Scope.ownGroup)+extra,actor,null,null),codes);
    }
    private String query(Board board,String currency,Scope scope){return "board="+board+"&currency="+currency+"&scope="+scope+"&groupId="+rankGroup;}
    private static String version(JsonNode page){String v=page.path("viewVersion").asText();assertThat(SupportLeaderboard.validVersion(v)).isTrue();assertThat(page.path("queryVersion").asText()).isEqualTo(v);return v;}
    private static JsonNode find(JsonNode page,long agent){for(JsonNode row:page.path("rows"))if(row.path("agentId").asLong()==agent)return row;throw new AssertionError("Owned candidate absent: "+agent);}
    private static void publicRow(JsonNode row){var fields=new HashSet<String>();row.fieldNames().forEachRemaining(fields::add);assertThat(fields).isEqualTo(PUBLIC_ROW);}
    private Instant now(){return Objects.requireNonNull(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",LocalDateTime.class)).toInstant(ZoneOffset.UTC);}
    private YearMonth month(){return YearMonth.from(now().atZone(SupportLeaderboard.BUSINESS_ZONE));}
    private Context context(Board board,String currency,Scope scope,Set<Long> approved){Instant at=now();YearMonth month=YearMonth.from(at.atZone(SupportLeaderboard.BUSINESS_ZONE));return new Context(board,board==Board.customers?null:month,month,currency,scope,approved,SupportLeaderboardPolicy.DEFINITION,at);}
    private <T>T rr(Supplier<T> action){var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(status->action.get());}
    private SupportLeaderboardSourceService.Read source(Board board,String currency,Scope scope,Set<Long> approved){actors.assertBusinessEntry();return rr(()->rankSource.readForAuthorizedLeaderboard(context(board,currency,scope,approved)));}

    private void registerStream(Context context){
        assertThat(context.scope()).isIn(Scope.ownGroup,Scope.managedGroups);
        assertThat(context.approvedGroupIds()).containsExactly(rankGroup);assertThat(fixture.createdGroupIds()).contains(rankGroup);
        String key=SupportLeaderboardPublicationService.streamKey(context);
        if(!ownedStreams.containsKey(key)){
            assertThat(fresh("SELECT * FROM "+PUBLICATIONS+" WHERE stream_key=?",key)).as("New owned stream has no previous publication").isEmpty();
            assertThat(fresh("SELECT * FROM "+POINTERS+" WHERE stream_key=?",key)).isEmpty();ownedStreams.put(key,context);
        }
    }
    private List<Map<String,Object>> ownedPublicationRows(){var rows=new ArrayList<Map<String,Object>>();for(String stream:ownedStreams.keySet())rows.addAll(fresh("SELECT * FROM "+PUBLICATIONS+" WHERE stream_key=? ORDER BY id",stream));return rows;}

    @AfterEach void exactPublicationCleanupBeforeInheritedCustomerGroupActorCleanup(){
        try{
            var cleanup=new ArrayList<Runnable>();var receipts=new ArrayList<Map<String,Object>>();var failures=new ArrayList<Map<String,Object>>();
            nativeObservations.put("ownedPublicationReceipts",receipts);nativeObservations.put("publicationCleanupFailures",failures);
            for(var entry:ownedStreams.entrySet()){
                cleanup.add(()->{try{
                assertThat(fixture.createdGroupIds()).contains(rankGroup);String stream=entry.getKey();
                receipts.addAll(fresh("SELECT * FROM "+PUBLICATIONS+" WHERE stream_key=? ORDER BY id",stream));
                var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                tx.executeWithoutResult(status->{
                    var pointer=jdbc.queryForList("SELECT * FROM "+POINTERS+" WHERE stream_key=? FOR UPDATE",stream);
                    var rows=jdbc.queryForList("SELECT * FROM "+PUBLICATIONS+" WHERE stream_key=? ORDER BY id FOR UPDATE",stream);
                    for(var row:rows){assertThat(row.get("groups_key")).isEqualTo(Long.toString(rankGroup));assertThat(row.get("scope")).isEqualTo(entry.getValue().scope().name());}
                    if(!pointer.isEmpty()){
                        assertThat(pointer).hasSize(1);Object id=pointer.get(0).get("publication_id");
                        if(id!=null)assertThat(rows.stream().map(row->((Number)row.get("id")).longValue()).toList()).contains(((Number)id).longValue());
                        assertThat(jdbc.update("DELETE FROM "+POINTERS+" WHERE stream_key=? AND publication_id <=> ?",stream,id)).isEqualTo(1);
                    }
                    for(var row:rows)assertThat(jdbc.update("DELETE FROM "+PUBLICATIONS+" WHERE id=? AND stream_key=? AND groups_key=?",row.get("id"),stream,Long.toString(rankGroup))).isEqualTo(1);
                });
                assertThat(fresh("SELECT * FROM "+POINTERS+" WHERE stream_key=?",stream)).isEmpty();
                assertThat(fresh("SELECT * FROM "+PUBLICATIONS+" WHERE stream_key=?",stream)).isEmpty();
                }catch(Throwable failure){failures.add(Map.of("streamKey",entry.getKey(),"errorClass",failure.getClass().getName(),"message",String.valueOf(failure.getMessage())));throw failure;}});
            }
            cleanup.add(()->{if(originalPublications!=null)assertThat(fresh("SELECT * FROM "+PUBLICATIONS+" ORDER BY id")).isEqualTo(originalPublications);});
            cleanup.add(()->{if(originalPointers!=null)assertThat(fresh("SELECT * FROM "+POINTERS+" ORDER BY stream_key")).isEqualTo(originalPointers);});
            SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
            nativeObservations.put("publicationCleanupComplete",true);
        }catch(Throwable failure){assertionEvidence=null;nativeObservations.put("publicationCleanupComplete",false);throw failure;}
    }
}
