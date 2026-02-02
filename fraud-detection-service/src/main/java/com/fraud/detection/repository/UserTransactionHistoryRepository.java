package com.fraud.detection.repository;

import com.fraud.detection.model.UserTransactionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface UserTransactionHistoryRepository extends JpaRepository<UserTransactionHistory, Long> {

    long countByUserIdAndOccurredAtAfter(String userId, Instant occurredAtAfter);

    Optional<UserTransactionHistory> findTopByUserIdOrderByOccurredAtDesc(String userId);
}
