package ffdd.opsconsole.finance.cregis;

/** The provider chain identifier is deliberately separate from the EVM RPC chain identifier. */
public enum CregisNetwork {
    BEP20("2510", "0x55d398326f99059ff775485246999027b3197955", 18, "0x38", "BNB"),
    ERC20("60", "0xdac17f958d2ee523a2206206994597c13d831ec7", 6, "0x1", "ETH"),
    TRC20("195", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6, null, "TRX");

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
    public String policyChainId() { return switch(this) { case BEP20 -> "BSC"; case ERC20 -> "ETH"; case TRC20 -> "TRON"; }; }
    public String policyTokenId() { return "USDT-"+network(); }
    public String depositChainName() { return "CREGIS_USDT_"+network(); }
    public int cursorId() { return switch(this) { case BEP20 -> 1; case ERC20 -> 2; case TRC20 -> 3; }; }

    public String normalizeAddress(String address) {
        if (this==TRC20) return TronAddress.normalize(address);
        if (address==null || !address.matches("(?i)0x[0-9a-f]{40}")) throw new IllegalArgumentException("CREGIS_ADDRESS_INVALID");
        return address.toLowerCase(java.util.Locale.ROOT);
    }
    public boolean validAddress(String address) {
        try { if(this==TRC20 && (address==null || !address.startsWith("T")))return false; normalizeAddress(address); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    public boolean addressEquals(String left,String right) {
        try { return normalizeAddress(left).equals(normalizeAddress(right)); }
        catch(IllegalArgumentException invalid) { return false; }
    }
    public boolean tokenMatches(String token) {
        return this==TRC20 ? tokenId.equals(token) : tokenId.equalsIgnoreCase(token);
    }
    public boolean currencyMatches(String currency) {
        return policyTokenId().equalsIgnoreCase(currency) || (this==TRC20 ? currency().equals(currency) : currency().equalsIgnoreCase(currency));
    }
    public boolean validTxid(String txid) {
        return txid!=null && txid.matches(this==TRC20 ? "(?i)[0-9a-f]{64}" : "(?i)(0x)?[0-9a-f]{64}");
    }
    public String normalizeTxid(String txid) {
        if(!validTxid(txid)) throw new IllegalArgumentException("CREGIS_TXID_INVALID");
        String value=txid.toLowerCase(java.util.Locale.ROOT);
        return this==TRC20 || value.startsWith("0x") ? value : "0x"+value;
    }

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
        if(!network.tokenMatches(token)) throw new IllegalArgumentException("CREGIS_ASSET_UNSUPPORTED");
        return network;
    }
}
