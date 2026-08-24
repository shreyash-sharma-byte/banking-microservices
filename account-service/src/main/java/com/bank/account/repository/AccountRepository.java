package com.bank.account.repository;

import com.bank.account.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByOwnerIdAndStatusIn(UUID ownerId, List<String> statuses);
    Optional<Account> findByAccountNumber(String accountNumber);
    boolean existsByOwnerIdAndLabel(UUID ownerId, String label);
    long countByOwnerIdAndStatus(UUID ownerId, String status);

    @Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
    Long nextAccountNumber();

    @Query(value = """
        SELECT a.* FROM accounts a
        LEFT JOIN retail_profiles rp ON rp.user_id = a.owner_id
        LEFT JOIN business_profiles bp ON bp.user_id = a.owner_id
        WHERE a.status = 'ACTIVE'
        AND (a.account_number LIKE CONCAT('%', :q, '%')
          OR LOWER(a.label) LIKE LOWER(CONCAT('%', :q, '%'))
          OR LOWER(COALESCE(rp.full_name, bp.company_name, '')) LIKE LOWER(CONCAT('%', :q, '%')))
        LIMIT 10
        """, nativeQuery = true)
    List<Account> searchByNumberOrLabel(String q);
}
