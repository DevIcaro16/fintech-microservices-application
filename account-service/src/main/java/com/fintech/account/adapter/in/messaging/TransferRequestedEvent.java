package com.fintech.account.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public class TransferRequestedEvent {

    @JsonProperty("transfer_id")
    private String transferId;

    @JsonProperty("source_account_id")
    private String sourceAccountId;

    @JsonProperty("destination_account_id")
    private String destinationAccountId;

    @JsonProperty("amount")
    private BigDecimal amount;

    public String getTransferId() { return transferId; }
    public String getSourceAccountId() { return sourceAccountId; }
    public String getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
}
