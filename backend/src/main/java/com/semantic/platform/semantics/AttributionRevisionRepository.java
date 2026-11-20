package com.semantic.platform.semantics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttributionRevisionRepository extends JpaRepository<AttributionRevision, Long> {
    Optional<AttributionRevision> findFirstBySemanticsIdOrderByRevisionDesc(Long semanticsId);
    Optional<AttributionRevision> findBySemanticsIdAndRevision(Long semanticsId, Integer revision);
    List<AttributionRevision> findBySemanticsIdOrderByRevisionDesc(Long semanticsId);
}
