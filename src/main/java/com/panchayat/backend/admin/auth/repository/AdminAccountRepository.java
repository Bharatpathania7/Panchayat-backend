
        package com.panchayat.backend.admin.auth.repository;

import com.panchayat.backend.admin.auth.entity.AdminAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminAccountRepository
        extends JpaRepository<AdminAccount, Long> {

    Optional<AdminAccount> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);
}
