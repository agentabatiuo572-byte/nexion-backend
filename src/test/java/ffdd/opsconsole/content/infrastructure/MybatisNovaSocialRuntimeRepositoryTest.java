package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.NovaSocialRuntimeMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MybatisNovaSocialRuntimeRepositoryTest {
    private final NovaSocialRuntimeMapper mapper = mock(NovaSocialRuntimeMapper.class);
    private final MybatisNovaSocialRuntimeRepository repository = new MybatisNovaSocialRuntimeRepository(mapper);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 12, 12, 0);

    @Test
    void expiredLeaseCanBeAtomicallyTakenOverWhenInsertLosesUniqueSlotRace() {
        LocalDateTime leaseUntil = now.plusSeconds(15);
        when(mapper.insertSlotClaim("NOVA-SOCIAL-1", "instance-b", leaseUntil, now)).thenReturn(0);
        when(mapper.takeoverExpiredSlot("NOVA-SOCIAL-1", "instance-b", leaseUntil, now)).thenReturn(1);

        assertThat(repository.claimSlot("NOVA-SOCIAL-1", "instance-b", leaseUntil, now)).isTrue();
        verify(mapper).takeoverExpiredSlot("NOVA-SOCIAL-1", "instance-b", leaseUntil, now);
    }

    @Test
    void completedSlotCannotBeClaimedAgain() {
        LocalDateTime leaseUntil = now.plusSeconds(15);
        when(mapper.insertSlotClaim("NOVA-SOCIAL-1", "instance-b", leaseUntil, now)).thenReturn(0);
        when(mapper.takeoverExpiredSlot("NOVA-SOCIAL-1", "instance-b", leaseUntil, now)).thenReturn(0);

        assertThat(repository.claimSlot("NOVA-SOCIAL-1", "instance-b", leaseUntil, now)).isFalse();
    }
}
