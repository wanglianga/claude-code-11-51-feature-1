package com.semantic.platform.metric;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetricReleaseRepository extends JpaRepository<MetricRelease, Long> {
    List<MetricRelease> findByMetricKeyOrderByReleasedAtDesc(String metricKey);
    List<MetricRelease> findByPurpose(String purpose);
    Optional<MetricRelease> findFirstByMetricKeyAndPurposeOrderByReleasedAtDesc(String metricKey, String purpose);
    Optional<MetricRelease> findFirstByMetricKeyOrderByReleasedAtDesc(String metricKey);
    void deleteByMetricKeyAndVersionAndPurpose(String metricKey, Integer version, String purpose);
}
