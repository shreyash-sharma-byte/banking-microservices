package com.bank.auth.repository;

import com.bank.auth.model.TokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklist, UUID> {
    boolean existsByJti(String jti);
}
