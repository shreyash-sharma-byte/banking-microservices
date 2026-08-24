package com.bank.account.repository;

import com.bank.account.model.RetailProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RetailProfileRepository extends JpaRepository<RetailProfile, UUID> {
    Optional<RetailProfile> findByUserId(UUID userId);
}
