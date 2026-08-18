package com.shvoy.shipments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.shipments.domain.EtdRevision;

/** No derived queries — filter by consignment over findAll(), the codebase convention. */
public interface EtdRevisionRepository extends JpaRepository<EtdRevision, UUID> {
}
