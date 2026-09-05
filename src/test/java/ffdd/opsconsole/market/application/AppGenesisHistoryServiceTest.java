package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.market.mapper.AppGenesisHistoryMapper;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppGenesisHistoryServiceTest {
    private final AppGenesisHistoryMapper mapper = mock(AppGenesisHistoryMapper.class);
    private final AppGenesisMapper users = mock(AppGenesisMapper.class);
    private final MockEnvironment env = new MockEnvironment();
    private final AppGenesisHistoryService service = new AppGenesisHistoryService(mapper, users, env);

    AppGenesisHistoryServiceTest() { env.setActiveProfiles("dev"); }

    @Test void keysetReturns100RowsAndCursorWithoutLeakingDatabaseIds() {
        List<Map<String,Object>> rows = IntStream.range(0,101).mapToObj(i ->
            Map.<String,Object>of("cursorId", 200L-i,"holdingNo","HOLD-"+i)).toList();
        when(mapper.listings(Long.MAX_VALUE)).thenReturn(rows);
        var data = service.page("listings",null,null).getData();
        assertThat(data).containsEntry("nextCursor","101").containsEntry("sourceEnvironment","PRODUCTION");
        var items = (List<?>)data.get("items");
        assertThat(items).hasSize(100);
        assertThat(((Map<?,?>)items.get(0)).containsKey("cursorId")).isFalse();
        when(mapper.listings(101L)).thenReturn(List.of(rows.get(100)));
        var next = service.page("listings",null,"101").getData();
        assertThat(next.get("nextCursor")).isNull();
        assertThat((List<?>)next.get("items")).hasSize(1);
    }

    @Test void rejectsInvalidCursorsBeforeQuerying() {
        for (String value : List.of("", "0", "-1", "1 OR 1=1", "9223372036854775808"))
            assertThatThrownBy(() -> service.page("listings",null,value)).isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }

    @Test void deniesMissingSandboxAndDeletedPersonalSubjects() {
        assertThatThrownBy(() -> service.page("orders",null,null)).isInstanceOf(BizException.class);
        when(users.userSandbox(42L)).thenReturn(1);
        assertThatThrownBy(() -> service.page("orders",42L,null)).isInstanceOf(BizException.class);
        when(users.userSandbox(42L)).thenReturn(0);
        assertThatThrownBy(() -> service.page("orders",42L,null)).isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }

    @Test void personalPagesUseAuthenticatedOwnerAndNormalizeMissingPaidDate() {
        when(users.userSandbox(42L)).thenReturn(0);
        when(users.userPolicy(42L)).thenReturn(new AppGenesisMapper.UserPolicyRow(42L,"VN","P1",1,0,"2026-W36"));
        when(mapper.emissions(42L,51L)).thenReturn(List.of(Map.of("cursorId",50L,"batchNo","batch")));
        var items = (List<?>)service.page("emissions",42L,"51").getData().get("items");
        assertThat(((Map<?,?>)items.get(0)).containsKey("paidAt")).isTrue();
        verify(mapper).emissions(42L,51L);
    }

    @Test void normalizesDatabaseDateAndClosesUnknownEnvironments() {
        when(mapper.transactions(Long.MAX_VALUE)).thenReturn(List.of(Map.of("cursorId",1L,"completedAt",LocalDateTime.parse("2026-09-05T12:00:00"))));
        var items = (List<?>)service.page("transactions",null,null).getData().get("items");
        assertThat(((Map<?,?>)items.get(0)).get("completedAt").toString()).endsWith("Z");
        for (String profile : List.of("test","unknown")) {
            env.setActiveProfiles(profile);
            assertThatThrownBy(() -> service.page("transactions",null,null)).isInstanceOf(BizException.class);
        }
    }
}
