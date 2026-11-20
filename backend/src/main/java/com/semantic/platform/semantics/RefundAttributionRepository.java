package com.semantic.platform.semantics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RefundAttributionRepository extends JpaRepository<RefundAttribution, Long> {

    List<RefundAttribution> findBySemanticsIdAndRevision(Long semanticsId, Integer revision);

    List<RefundAttribution> findBySemanticsIdAndRevisionAndPurpose(Long semanticsId, Integer revision, String purpose);

    /** 取出期间内参与某用途的归属行（只取 attributed，避免把待归属计入期间）。 */
    List<RefundAttribution> findBySemanticsIdAndRevisionAndPurposeAndAttributionStatusAndAttributionDateBetween(
            Long semanticsId, Integer revision, String purpose, String attributionStatus,
            LocalDate from, LocalDate to);

    List<RefundAttribution> findBySemanticsIdAndRevisionAndPurposeAndAttributionStatus(
            Long semanticsId, Integer revision, String purpose, String attributionStatus);

    List<RefundAttribution> findBySemanticsIdAndPurposeAndOrderLineId(
            Long semanticsId, String purpose, String orderLineId);

    List<RefundAttribution> findBySemanticsIdAndOrderLineId(Long semanticsId, String orderLineId);

    void deleteBySemanticsIdAndRevision(Long semanticsId, Integer revision);
}
