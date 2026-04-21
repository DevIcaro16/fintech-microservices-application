package com.fintech.transfer.domain;

public class TransferHistorySummary {
    private final String transferId;
    private final String status;
    private final String amount;
    private final String createdAt;
    private final String destinationAccountId;

    public TransferHistorySummary(String transferId, String status, String amount,
                                  String createdAt, String destinationAccountId) {
        this.transferId = transferId;
        this.status = status;
        this.amount = amount;
        this.createdAt = createdAt;
        this.destinationAccountId = destinationAccountId;
    }

    public String getTransferId()            { return transferId; }
    public String getStatus()                { return status; }
    public String getAmount()                { return amount; }
    public String getCreatedAt()             { return createdAt; }
    public String getDestinationAccountId()  { return destinationAccountId; }
}
