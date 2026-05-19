package ffdd.team.service.impl;

import ffdd.team.dto.TeamSummaryResponse;
import ffdd.team.service.TeamService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class TeamServiceImpl implements TeamService {
    @Override
    public TeamSummaryResponse summary() {
        return new TeamSummaryResponse("V1", new BigDecimal("18240.00"), 7, 128, new BigDecimal("342.18"));
    }
}

