package ffdd.team.service;

import ffdd.team.domain.CommissionEvent;
import ffdd.team.dto.BinaryCommissionRequest;
import ffdd.team.dto.CommissionResult;
import ffdd.team.dto.CultivationCommissionRequest;
import ffdd.team.dto.LeadershipCommissionRequest;
import ffdd.team.dto.PeerCommissionRequest;
import ffdd.team.dto.UnilevelCommissionRequest;
import java.util.List;

public interface CommissionService {
    CommissionResult settleUnilevel(UnilevelCommissionRequest request);

    CommissionResult settleBinary(BinaryCommissionRequest request);

    CommissionResult settlePeer(PeerCommissionRequest request);

    CommissionResult settleCultivation(CultivationCommissionRequest request);

    CommissionResult settleLeadership(LeadershipCommissionRequest request);

    List<CommissionEvent> listMine(Long userId);
}

