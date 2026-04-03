package com.invoice.extractor.model;

public class LineItem {
    private String description;
    private String hsn;
    private String quantity;
    private String unitPrice;
    private String amount;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getHsn() { return hsn; }
    public void setHsn(String hsn) { this.hsn = hsn; }

    public String getQuantity() { return quantity; }
    public void setQuantity(String quantity) { this.quantity = quantity; }

    public String getUnitPrice() { return unitPrice; }
    public void setUnitPrice(String unitPrice) { this.unitPrice = unitPrice; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
}
