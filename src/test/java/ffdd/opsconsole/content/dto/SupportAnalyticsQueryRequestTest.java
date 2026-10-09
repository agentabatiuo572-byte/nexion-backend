package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.*;

class SupportAnalyticsQueryRequestTest {
    private static final Set<Sort> SORTS = EnumSet.allOf(Sort.class);
    private Normalized request(Map<String, String> values) {
        var parameters = new HashMap<String,List<String>>(); values.forEach((k,v) -> parameters.put(k,List.of(v)));
        return fromParameters(parameters).normalize(Set.of("USDT", "NEX"), SORTS);
    }
    private void invalid(Map<String,String> values, int code) {
        assertThatThrownBy(() -> request(values)).isInstanceOf(BizException.class)
            .satisfies(e -> assertThat(((BizException)e).getCode()).isEqualTo(code));
    }
    @Test void defaultsPreserveApprovedServiceFilterAndSeparateCurrentHistoryFromPeriod() {
        var q = request(Map.of());
        assertThat(q.filter()).isEqualTo(ServiceFilter.ALL);
        assertThat(q.sortKey()).isEqualTo(Sort.LAST_ACTIVE_AT);
        assertThat(q.pageNum()).isEqualTo(1); assertThat(q.pageSize()).isEqualTo(20);
        assertThat(q.from()).isNull(); assertThat(q.to()).isNull();
        assertThat(request(Map.of("filter","WAITING_REPLY")).sortKey()).isEqualTo(Sort.WAITING_SINCE_AT);
        assertThat(request(Map.of("filter","DORMANT")).direction()).isEqualTo(Direction.ASC);
    }
    @Test void datesUseOriginalInstantParametersAndFixedBusinessZone() {
        var q = request(Map.of("basis","PERIOD_EVENT", "from","2026-10-01T00:00:00Z", "to","2026-10-02T00:00:00Z"));
        var internal = q.toStatsQuery(new ReadScope(1L, ReadMode.PERSONAL,null,null));
        assertThat(internal.businessZone()).isEqualTo("Asia/Shanghai");
        assertThat(internal.fromInclusive()).isEqualTo(LocalDateTime.of(2026,10,1,8,0));
        invalid(Map.of("from","2026-10-01T00:00:00Z","to","2026-10-02T00:00:00Z"),422);
        invalid(Map.of("basis","PERIOD_EVENT"),422);
        invalid(Map.of("basis","PERIOD_EVENT","from","2026-10-01","to","2026-10-02"),422);
        invalid(Map.of("basis","PERIOD_EVENT","from","2026-10-02T00:00:00Z","to","2026-10-01T00:00:00Z"),422);
        invalid(Map.of("businessZone","Asia/Tokyo"),422);
    }
    @Test void unknownEnumCurrencySortUnitsAndClientAuthorityCannotBeSilentlyIgnored() {
        for (String key : List.of("view","category","firstState","filter","basis","sortKey","direction")) invalid(Map.of(key,"FUTURE"),422);
        invalid(Map.of("currency","USD"),422); invalid(Map.of("currency","usdt"),422);
        invalid(Map.of("sortKey","PERSONAL_DEPOSIT"),422);
        invalid(Map.of("sortKey","WAITING_SINCE_AT"),422);
        for (String key : List.of("unit","role","scope","actorId","readMode")) invalid(Map.of(key,"ALL"),422);
        assertThatThrownBy(() -> fromParameters(Map.of("groupId",List.of("1","2")))).isInstanceOf(BizException.class);
        var raw = fromParameters(Map.of("sortKey",List.of("TEAM_DEPOSIT"),"currency",List.of("USDT")));
        assertThatThrownBy(() -> raw.normalize(Set.of("USDT"), Set.of(Sort.LAST_ACTIVE_AT))).isInstanceOf(BizException.class);
    }
    @Test void pageAndIdConstraintsRejectCoercionOverflowAndMissingVersion() {
        for (String value : List.of("0","-1","1.0","1e2"," 1","9007199254740992","99999999999999999999")) invalid(Map.of("groupId",value),422);
        invalid(Map.of("pageNum","2"),422); invalid(Map.of("pageSize","101"),422);
        invalid(Map.of("expectedVersion","saq-v1:"+"A".repeat(64)),422);
        assertThat(request(Map.of("pageNum","2","expectedVersion","saq-v1:"+"a".repeat(64))).pageNum()).isEqualTo(2);
        invalid(Map.of("keyword","x".repeat(201)),422);
    }
    @Test void directGroupTamperingCannotBeConvertedUsingAnUnrelatedResolvedScope() {
        var q = request(Map.of("groupId","9"));
        assertThatThrownBy(() -> q.toStatsQuery(new ReadScope(1L,ReadMode.MANAGED,8L,null))).isInstanceOf(IllegalArgumentException.class);
    }
}
