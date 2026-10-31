package ffdd.opsconsole.finance.cregis;

/** The provider chain identifier is deliberately separate from the EVM RPC chain identifier. */
public enum CregisNetwork {
    BEP20("2510", "0x55d398326f99059ff775485246999027b3197955", 18, "0x38", "BNB"),
    ERC20("60", "0xdac17f958d2ee523a2206206994597c13d831ec7", 6, "0x1", "ETH");

    private final String chainId, tokenId, rpcChainId, gasSymbol;
    private final int decimals;

    CregisNetwork(String chainId, String tokenId, int decimals, String rpcChainId, String gasSymbol) {
        this.chainId=chainId; this.tokenId=tokenId; this.decimals=decimals;
        this.rpcChainId=rpcChainId; this.gasSymbol=gasSymbol;
    }
    public String network() { return name(); }
    public String chainId() { return chainId; }
    public String tokenId() { return tokenId; }
    public String currency() { return chainId+"@"+tokenId; }
    public int decimals() { return decimals; }
    public String rpcChainId() { return rpcChainId; }
    public String gasSymbol() { return gasSymbol; }
    public String policyChainId() { return this==BEP20 ? "BSC" : "ETH"; }
    public String policyTokenId() { return "USDT-"+network(); }
    public String depositChainName() { return "CREGIS_USDT_"+network(); }
    public int cursorId() { return this==BEP20 ? 1 : 2; }

    public String getChainId() { return chainId(); }
    public String getTokenId() { return tokenId(); }
    public int getCursorId() { return cursorId(); }
    public String getPolicyChainId() { return policyChainId(); }
    public String getPolicyTokenId() { return policyTokenId(); }
    public String getDepositChainName() { return depositChainName(); }
    public String getNetwork() { return network(); }
    public static CregisNetwork fromNetwork(String value) {
        for (var network:values()) if(network.network().equalsIgnoreCase(value)) return network;
        throw new IllegalArgumentException("CREGIS_NETWORK_UNSUPPORTED");
    }
    public static CregisNetwork fromChainId(String value) {
        for (var network:values()) if(network.chainId.equals(value)) return network;
        throw new IllegalArgumentException("CREGIS_NETWORK_UNSUPPORTED");
    }
    public static CregisNetwork fromAsset(String chain, String token) {
        var network=fromChainId(chain);
        if(!network.tokenId.equalsIgnoreCase(token)) throw new IllegalArgumentException("CREGIS_ASSET_UNSUPPORTED");
        return network;
    }
}
