package com.fraud.alert.repository;

import com.fraud.alert.model.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, String> {

    List<Alert> findAllByOrderByCreatedAtDesc();

    List<Alert> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Alert> findByTransactionId(String transactionId);
}
