package com.fraud.detection.repository;

import com.fraud.detection.model.UserTransactionHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserTransactionHistoryRepository extends JpaRepository<UserTransactionHistory, Long> {

    long countByUserIdAndOccurredAtAfter(String userId, Instant occurredAtAfter);

    Optional<UserTransactionHistory> findTopByUserIdOrderByOccurredAtDesc(String userId);

    @Modifying
    @Query("DELETE FROM UserTransactionHistory h WHERE h.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
