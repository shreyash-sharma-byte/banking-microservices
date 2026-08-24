package com.bank.account.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_profiles")
public class BusinessProfile {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false, unique = true) private UUID userId;
    @Column(name = "company_name", nullable = false) private String companyName;
    @Column(name = "contact_name", nullable = false) private String contactName;
    @Column(name = "contact_phone", nullable = false) private String contactPhone;
    @Column(name = "gst_number", nullable = false) private String gstNumber;
    @Column(name = "pan_number", nullable = false) private String panNumber;
    @Column(name = "business_type", nullable = false) private String businessType;
    @Column(name = "registered_addr", nullable = false) private String registeredAddress;
    @Column(name = "annual_turnover") private BigDecimal annualTurnover;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public BusinessProfile() {}
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID id) { this.userId = id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String n) { this.companyName = n; }
    public String getContactName() { return contactName; }
    public void setContactName(String n) { this.contactName = n; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String p) { this.contactPhone = p; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String g) { this.gstNumber = g; }
    public String getPanNumber() { return panNumber; }
    public void setPanNumber(String p) { this.panNumber = p; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String t) { this.businessType = t; }
    public String getRegisteredAddress() { return registeredAddress; }
    public void setRegisteredAddress(String a) { this.registeredAddress = a; }
    public BigDecimal getAnnualTurnover() { return annualTurnover; }
    public void setAnnualTurnover(BigDecimal t) { this.annualTurnover = t; }
}
