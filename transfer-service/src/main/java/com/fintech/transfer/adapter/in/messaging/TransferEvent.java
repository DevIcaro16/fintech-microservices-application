package com.fintech.transfer.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TransferEvent {

    @JsonProperty("transfer_id")
    private String transferId;

    public String getTransferId() { return transferId; }
}
