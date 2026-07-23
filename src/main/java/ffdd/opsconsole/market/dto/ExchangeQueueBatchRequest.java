package ffdd.opsconsole.market.dto;

public record ExchangeQueueBatchRequest(Integer limit, String reason, String operator) {}
