package com.semantic.platform.semantics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundSemanticsRepository extends JpaRepository<RefundSemantics, Long> {
    Optional<RefundSemantics> findByCodeAndVersion(String code, Integer version);
    List<RefundSemantics> findByCodeOrderByVersionDesc(String code);
    Optional<RefundSemantics> findFirstByCodeAndStatusOrderByVersionDesc(String code, String status);
    List<RefundSemantics> findByCodeAndStatusOrderByVersionDesc(String code, String status);
}
