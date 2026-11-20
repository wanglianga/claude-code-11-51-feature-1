package com.semantic.platform.semantics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PurposeProfileRepository extends JpaRepository<PurposeProfile, Long> {
    Optional<PurposeProfile> findByPurpose(String purpose);
}
