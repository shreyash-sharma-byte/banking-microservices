package com.bank.account.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "retail_profiles")
public class RetailProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phone;

    @Column(name = "date_of_birth", nullable = false)
    private java.time.LocalDate dateOfBirth;

    @Column(name = "pan_number", nullable = false)
    private String panNumber;

    @Column(name = "aadhaar_last4")
    private String aadhaarLast4;

    private String address;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public RetailProfile() {}
    public RetailProfile(UUID userId, String fullName, String phone, java.time.LocalDate dateOfBirth, String panNumber) {
        this.userId = userId; this.fullName = fullName; this.phone = phone;
        this.dateOfBirth = dateOfBirth; this.panNumber = panNumber;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public java.time.LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getPanNumber() { return panNumber; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setAadhaarLast4(String aadhaarLast4) { this.aadhaarLast4 = aadhaarLast4; }
    public void setAddress(String address) { this.address = address; }
}
