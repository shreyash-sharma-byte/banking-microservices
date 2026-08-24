package com.bank.account.repository;

import com.bank.account.model.BusinessEmployee;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessEmployeeRepository extends JpaRepository<BusinessEmployee, UUID> {
    List<BusinessEmployee> findByBusinessId(UUID businessId);
    Optional<BusinessEmployee> findByBusinessIdAndEmployeeId(UUID businessId, UUID employeeId);
    boolean existsByBusinessIdAndEmployeeIdAndStatus(UUID businessId, UUID employeeId, String status);
}
