package com.bank.account.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_employees")
public class BusinessEmployee {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "business_id", nullable = false) private UUID businessId;
    @Column(name = "employee_id", nullable = false) private UUID employeeId;
    @Column(name = "employee_code") private String employeeCode;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public BusinessEmployee() {}
    public BusinessEmployee(UUID businessId, UUID employeeId, String employeeCode) {
        this.businessId = businessId; this.employeeId = employeeId; this.employeeCode = employeeCode;
    }
    public UUID getId() { return id; }
    public UUID getBusinessId() { return businessId; }
    public UUID getEmployeeId() { return employeeId; }
    public String getEmployeeCode() { return employeeCode; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
}
