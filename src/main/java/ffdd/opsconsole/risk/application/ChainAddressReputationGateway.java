package ffdd.opsconsole.risk.application;

import java.math.BigDecimal;

/** Production port for the configured chain-address reputation provider. */
public interface ChainAddressReputationGateway {
    BigDecimal score(String chain, String address);
}
