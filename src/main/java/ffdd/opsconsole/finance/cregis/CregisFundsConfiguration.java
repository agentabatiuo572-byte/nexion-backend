package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisAutoForwardMapper;
import ffdd.opsconsole.finance.mapper.CregisFundsPreparationMapper;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CregisFundsConfiguration implements ApplicationRunner {
    private final CregisProperties properties;
    private final CregisFundsPreparationMapper preparation;
    private final CregisAutoForwardMapper autoForward;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long project = properties.getProjectId();
        if (project <= 0) return;

        for (var network : CregisNetwork.values()) {
            if (!properties.autoForwardAddressesConfigured(network)) continue;
            if (network == CregisNetwork.BEP20) {
                preparation.installBep20Config(project,
                        network.normalizeAddress(properties.autoForwardPlatform(network)),
                        network.normalizeAddress(properties.autoForwardCounterparty(network)));
                if (!matchesBep20(properties, preparation.config(project))) continue;
            }
            autoForward.installPolicy(project, network.chainId());
        }
    }

    static boolean matchesBep20(CregisProperties properties, Map<String,Object> row) {
        var network = CregisNetwork.BEP20;
        return row != null && properties.autoForwardAddressesConfigured(network)
                && network.policyChainId().equals(row.get("network")) && "USDT".equals(row.get("asset"))
                && network.addressEquals(properties.autoForwardPlatform(network), Objects.toString(row.get("platformAddress"), ""))
                && network.addressEquals(properties.autoForwardCounterparty(network), Objects.toString(row.get("counterpartyAddress"), ""));
    }
}
