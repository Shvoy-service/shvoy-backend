package com.shvoy.containerfill.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 8.1 — flagging spare container capacity. PO-keyed create (so an early
 * offer creates the shipment on first touch, exactly as 7.5's ETD does), the
 * post-arrival rejection, the multiple-offers advisory, cancel-and-relog, the
 * undecided-default list, and cross-tenant isolation. JDBC seeding, debug
 * headers; no class-level @Transactional (see the other shipment tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
class ContainerFillOfferControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID supplierA;
    UUID supplierB;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "cf-" + userAId + "@x.com", now, companyA);
        supplierA = insertSupplier(companyA);
        supplierB = insertSupplier(companyB);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM container_fill_offer_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM container_fill_offers WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- flagging ---

    @Test
    void flaggingAnOfferCreatesItOpenAndAuditsIt() throws Exception {
        UUID po = insertPo(companyA, supplierA);

        MvcResult result = flag(po, supplierA, "2.50", "can fit 2 more pallets if confirmed by Friday")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.spareCbm").value(2.50))
            .andExpect(jsonPath("$.supplierId").value(supplierA.toString()))
            .andExpect(jsonPath("$.flaggedBy").value(userAId.toString()))
            .andExpect(jsonPath("$.otherUndecidedOffersOnShipment").value(0))
            .andReturn();

        UUID offerId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.offerId"));
        assertThat(auditCount(offerId, "FLAGGED")).isEqualTo(1);
    }

    @Test
    void anEarlyOfferWithNoShipmentRecordYetCreatesItViaFirstTouch() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        assertThat(consignmentCount(po)).isZero(); // no shipment data at all yet

        flag(po, supplierA, "3.00", null).andExpect(status().isCreated());

        assertThat(consignmentCount(po)).isEqualTo(1); // the shipment/consignment now exists
    }

    @Test
    void flaggingSpareCapacityOnAFullyArrivedContainerIsRejected() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        UUID shipment = insertShipment(companyA);
        UUID consignmentId = consignment(shipment, po, companyA);
        jdbcTemplate.update("UPDATE shipment_consignments SET receipt_status = 'ARRIVED_CONFIRMED' WHERE id = ?", consignmentId);

        flag(po, supplierA, "2.50", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_OFFER_AFTER_ARRIVAL"));
    }

    @Test
    void aSecondOfferOnTheSameContainerIsAllowedAndSurfacesTheExistingUndecidedOne() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        flag(po, supplierA, "2.50", null).andExpect(status().isCreated());

        flag(po, supplierA, "1.00", "loading plan changed")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.otherUndecidedOffersOnShipment").value(1));
    }

    @Test
    void invalidCbmIsRejected() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        flag(po, supplierA, "0", null).andExpect(status().isBadRequest());
        flag(po, supplierA, "-2.5", null).andExpect(status().isBadRequest());
    }

    // --- cancel-and-relog ---

    @Test
    void cancellingAnOfferAuditsItAndBlocksASecondCancel() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        UUID offerId = flagAndGetId(po, supplierA, "2.50");

        cancel(offerId, "wrong CBM, will relog")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(auditCount(offerId, "CANCELLED")).isEqualTo(1);

        // A cancelled offer is fixed — no silent re-editing.
        cancel(offerId, "again")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_OFFER_NOT_CANCELLABLE"));

        // Relog = a fresh offer (never an edit of the cancelled one).
        flag(po, supplierA, "2.75", null).andExpect(status().isCreated());
    }

    // --- list ---

    @Test
    void theListDefaultsToUndecidedAndCanIncludeDecided() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        UUID toCancel = flagAndGetId(po, supplierA, "2.50");
        cancel(toCancel, "superseded").andExpect(status().isOk());
        flagAndGetId(po, supplierA, "1.50"); // a live undecided one

        mockMvc.perform(get("/api/container-fill-offers").header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("OPEN"));

        mockMvc.perform(get("/api/container-fill-offers?includeDecided=true").header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    // --- tenancy ---

    @Test
    void anotherCompanyCannotReadTheOffer() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        UUID offerId = flagAndGetId(po, supplierA, "2.50");

        mockMvc.perform(get("/api/container-fill-offers/{id}", offerId).header(TENANT, companyB.toString()))
            .andExpect(status().isNotFound());
    }

    // --- driving ---

    private ResultActions flag(UUID po, UUID supplierId, String spareCbm, String notes) throws Exception {
        String body = notes == null
            ? "{\"spareCbm\":" + spareCbm + ",\"supplierId\":\"" + supplierId + "\"}"
            : "{\"spareCbm\":" + spareCbm + ",\"supplierId\":\"" + supplierId + "\",\"notes\":\"" + notes + "\"}";
        return mockMvc.perform(post("/api/purchase-orders/{po}/container-fill-offers", po)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID flagAndGetId(UUID po, UUID supplierId, String spareCbm) throws Exception {
        MvcResult result = flag(po, supplierId, spareCbm, null).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.offerId"));
    }

    private ResultActions cancel(UUID offerId, String reason) throws Exception {
        return mockMvc.perform(post("/api/container-fill-offers/{id}/cancel", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    // --- seeding ---

    private UUID insertSupplier(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertPo(UUID company, UUID supplier) {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?)",
            po, supplier, "PO-" + po, userAId, now, now, company);
        return po;
    }

    private UUID insertShipment(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shipments (id, bl_reference, created_at, company_id) VALUES (?, ?, ?, ?)",
            id, "BL-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID consignment(UUID shipment, UUID po, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments (id, shipment_id, purchase_order_id, receipt_status, detached, inspection_due, created_at, company_id) "
                + "VALUES (?, ?, ?, 'DOCUMENTS_PENDING', FALSE, FALSE, ?, ?)",
            id, shipment, po, Timestamp.from(Instant.now()), company);
        return id;
    }

    // --- assertions ---

    private int consignmentCount(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_consignments WHERE purchase_order_id = ?", Integer.class, po);
    }

    private int auditCount(UUID offerId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM container_fill_offer_audit_events WHERE offer_id = ? AND event_type = ?",
            Integer.class, offerId, eventType);
    }
}
