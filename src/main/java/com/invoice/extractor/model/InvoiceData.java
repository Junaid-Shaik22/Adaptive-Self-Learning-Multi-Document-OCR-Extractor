package com.invoice.extractor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

public class InvoiceData {
    public static final String NOT_MENTIONED = "NOT_MENTIONED";

    private String invoiceNumber;
    private String invoiceDate;
    private String vendorName;
    private String vendorGstin;
    private String buyerName;
    private String buyerGstin;
    private String poNumber;
    private String poDate;
    private String orderReference;
    private String deliveryNote;
    private String dispatchThrough;
    private String transporterName;
    private String transportDetails;
    private String vehicleNumber;
    private String destination;
    private String placeOfSupply;
    private String paymentTerms;
    @JsonIgnore
    private String bankDetails;
    private String bankName;
    private String accountNumber;
    private String ifscCode;
    private String branch;
    private String irn;
    private String ackNumber;
    private String ewayBill;
    private String vendorPhone;
    private String vendorEmail;
    private String vendorWebsite;
    private String vendorAddress;
    private String buyerAddress;
    private String vendorPAN;
    private String vendorCIN;
    private String msmeNumber;
    private String state;
    private String stateCode;
    private String pincode;
    private String subTotal;
    private String taxableValue;
    private String cgst;
    private String sgst;
    private String igst;
    private String taxAmount;
    private String roundOff;
    private String totalAmount;
    private String currency = "INR";
    private List<LineItem> lineItems;
    @JsonIgnore
    private Map<String, String> knownFields;
    @JsonIgnore
    private Map<String, String> dynamicFields;
    @JsonIgnore
    private String rawText;
    @JsonIgnore
    private int pagesProcessed;
    private double confidenceScore;
    @JsonIgnore
    private String templateId;
    @JsonIgnore
    private String status;

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(String invoiceDate) { this.invoiceDate = invoiceDate; }

    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }

    public String getVendorGstin() { return vendorGstin; }
    public void setVendorGstin(String vendorGstin) { this.vendorGstin = vendorGstin; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getBuyerGstin() { return buyerGstin; }
    public void setBuyerGstin(String buyerGstin) { this.buyerGstin = buyerGstin; }

    public String getPoNumber() { return poNumber; }
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }

    public String getPoDate() { return poDate; }
    public void setPoDate(String poDate) { this.poDate = poDate; }

    public String getOrderReference() { return orderReference; }
    public void setOrderReference(String orderReference) { this.orderReference = orderReference; }

    public String getDeliveryNote() { return deliveryNote; }
    public void setDeliveryNote(String deliveryNote) { this.deliveryNote = deliveryNote; }

    public String getDispatchThrough() { return dispatchThrough; }
    public void setDispatchThrough(String dispatchThrough) { this.dispatchThrough = dispatchThrough; }

    public String getTransporterName() { return transporterName; }
    public void setTransporterName(String transporterName) { this.transporterName = transporterName; }

    public String getTransportDetails() { return transportDetails; }
    public void setTransportDetails(String transportDetails) { this.transportDetails = transportDetails; }

    public String getVehicleNumber() { return vehicleNumber; }
    public void setVehicleNumber(String vehicleNumber) { this.vehicleNumber = vehicleNumber; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getPlaceOfSupply() { return placeOfSupply; }
    public void setPlaceOfSupply(String placeOfSupply) { this.placeOfSupply = placeOfSupply; }

    public String getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(String paymentTerms) { this.paymentTerms = paymentTerms; }

    public String getBankDetails() { return bankDetails; }
    public void setBankDetails(String bankDetails) { this.bankDetails = bankDetails; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getIrn() { return irn; }
    public void setIrn(String irn) { this.irn = irn; }

    public String getAckNumber() { return ackNumber; }
    public void setAckNumber(String ackNumber) { this.ackNumber = ackNumber; }

    public String getEwayBill() { return ewayBill; }
    public void setEwayBill(String ewayBill) { this.ewayBill = ewayBill; }

    public String getVendorPhone() { return vendorPhone; }
    public void setVendorPhone(String vendorPhone) { this.vendorPhone = vendorPhone; }

    public String getVendorEmail() { return vendorEmail; }
    public void setVendorEmail(String vendorEmail) { this.vendorEmail = vendorEmail; }

    public String getVendorWebsite() { return vendorWebsite; }
    public void setVendorWebsite(String vendorWebsite) { this.vendorWebsite = vendorWebsite; }

    public String getVendorAddress() { return vendorAddress; }
    public void setVendorAddress(String vendorAddress) { this.vendorAddress = vendorAddress; }

    public String getBuyerAddress() { return buyerAddress; }
    public void setBuyerAddress(String buyerAddress) { this.buyerAddress = buyerAddress; }

    public String getVendorPAN() { return vendorPAN; }
    public void setVendorPAN(String vendorPAN) { this.vendorPAN = vendorPAN; }

    public String getVendorCIN() { return vendorCIN; }
    public void setVendorCIN(String vendorCIN) { this.vendorCIN = vendorCIN; }

    public String getMsmeNumber() { return msmeNumber; }
    public void setMsmeNumber(String msmeNumber) { this.msmeNumber = msmeNumber; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public String getSubTotal() { return subTotal; }
    public void setSubTotal(String subTotal) { this.subTotal = subTotal; }

    public String getTaxableValue() { return taxableValue; }
    public void setTaxableValue(String taxableValue) { this.taxableValue = taxableValue; }

    public String getCgst() { return cgst; }
    public void setCgst(String cgst) { this.cgst = cgst; }

    public String getSgst() { return sgst; }
    public void setSgst(String sgst) { this.sgst = sgst; }

    public String getIgst() { return igst; }
    public void setIgst(String igst) { this.igst = igst; }

    public String getTaxAmount() { return taxAmount; }
    public void setTaxAmount(String taxAmount) { this.taxAmount = taxAmount; }

    public String getRoundOff() { return roundOff; }
    public void setRoundOff(String roundOff) { this.roundOff = roundOff; }

    public String getTotalAmount() { return totalAmount; }
    public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<LineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItem> lineItems) { this.lineItems = lineItems; }

    public Map<String, String> getKnownFields() { return knownFields; }
    public void setKnownFields(Map<String, String> knownFields) { this.knownFields = knownFields; }

    public Map<String, String> getDynamicFields() { return dynamicFields; }
    public void setDynamicFields(Map<String, String> dynamicFields) { this.dynamicFields = dynamicFields; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public int getPagesProcessed() { return pagesProcessed; }
    public void setPagesProcessed(int pagesProcessed) { this.pagesProcessed = pagesProcessed; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
