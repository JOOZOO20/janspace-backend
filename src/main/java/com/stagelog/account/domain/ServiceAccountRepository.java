package com.stagelog.account.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, Long> {

    Optional<ServiceAccount> findByKeyHash(String keyHash);
}
