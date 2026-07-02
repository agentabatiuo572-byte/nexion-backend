package ffdd.opsconsole.team.infrastructure;

import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import ffdd.opsconsole.team.mapper.TeamFulfillmentQueueMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisTeamFulfillmentQueueRepository implements TeamFulfillmentQueueRepository {
    private final TeamFulfillmentQueueMapper mapper;

    @Override
    public List<TeamFulfillmentQueueRow> fulfillmentQueues() {
        return mapper.fulfillmentQueues();
    }
}
