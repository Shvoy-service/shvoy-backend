package com.shvoy.containerfill.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.containerfill.domain.ContainerFillOffer;

/**
 * Deliberately no custom derived query methods — the codebase convention (see
 * SupplierRepository's Javadoc for the bootstrap reason). Filtering by shipment
 * or status happens in the service over {@code findAll()}; every query is
 * tenant-constrained automatically.
 */
public interface ContainerFillOfferRepository extends JpaRepository<ContainerFillOffer, UUID> {
}
