package com.shvoy.shipments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.shipments.domain.ArrivalDiscrepancy;

/** No derived queries — filter over findAll(), the codebase convention. */
public interface ArrivalDiscrepancyRepository extends JpaRepository<ArrivalDiscrepancy, UUID> {
}
