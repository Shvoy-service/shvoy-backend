package com.shvoy.containerfill.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Story 8.3 — confirm/decline/link and the full fill path. The central invariant:
 * confirming and linking a fill PO mutates <strong>no</strong> existing PO — the fill
 * is a new order that rides the container via the existing 7.3 co-load attach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class ContainerFillDecisionControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID userBId;
    UUID supplierA;
    UUID supplierB;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        userBId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "a-" + userAId + "@x.com", now, companyA);
        jdbcTemplate.update("INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userBId, "b-" + userBId + "@x.com", now, companyB);
        supplierA = insertSupplier(companyA);
        supplierB = insertSupplier(companyB);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM container_fill_offer_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM container_fill_offers WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- confirm / decline ---

    @Test
    void confirmingAnAwaitingOfferRecordsConfirmedAndAudits() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));
        setDeadline(offerId, Instant.now().plusSeconds(86400)).andExpect(status().isOk());

        confirm(offerId, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.fillPurchaseOrderId").doesNotExist());
        assertThat(auditCount(offerId, "CONFIRMED")).isEqualTo(1);
    }

    @Test
    void anOpenOfferCanBeConfirmedWithoutEverSettingADeadline() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));

        confirm(offerId, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmingWithAFillPoLinksIt() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));
        UUID fillPo = insertPo(companyA, supplierA);

        confirm(offerId, fillPo)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.fillPurchaseOrderId").value(fillPo.toString()));
    }

    @Test
    void confirmingWithAnUnknownFillPoIs404() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));
        confirm(offerId, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void decliningRecordsDeclined() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));

        decline(offerId, "no space needed")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(auditCount(offerId, "DECLINED")).isEqualTo(1);
    }

    @Test
    void aDecidedOfferCannotBeDecidedAgain() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));
        confirm(offerId, null).andExpect(status().isOk());

        decline(offerId, "changed my mind")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_OFFER_NOT_DECIDABLE"));
    }

    // --- linking the fill PO after confirm ---

    @Test
    void aFillPoCanBeLinkedAfterConfirmButNotBeforeIt() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));
        UUID fillPo = insertPo(companyA, supplierA);

        // Before confirm: not confirmed.
        linkFillPo(offerId, fillPo)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_OFFER_NOT_CONFIRMED"));

        confirm(offerId, null).andExpect(status().isOk());
        linkFillPo(offerId, fillPo)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fillPurchaseOrderId").value(fillPo.toString()));
        assertThat(auditCount(offerId, "FILL_PO_LINKED")).isEqualTo(1);
    }

    // --- the full fill path ---

    @Test
    void confirmAndFillRidesTheContainerWithoutTouchingTheOriginalPo() throws Exception {
        UUID originalPo = insertPo(companyA, supplierA);
        String originalStatusBefore = poStatusOf(originalPo);
        UUID offerId = flag(originalPo);
        UUID shipmentId = shipmentIdOf(offerId);
        assertThat(activeConsignmentCount(shipmentId)).isEqualTo(1); // the original PO's consignment

        UUID fillPo = insertPo(companyA, supplierA);
        confirm(offerId, fillPo).andExpect(status().isOk());

        // The fill is a new order that joins the same container via the existing 7.3 co-load path.
        mockMvc.perform(post("/api/shipments/{s}/consignments", shipmentId)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"purchaseOrderId\":\"" + fillPo + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.length()").value(2));
        assertThat(activeConsignmentCount(shipmentId)).isEqualTo(2); // the fill got its own independent slice

        // No existing PO was mutated: the original PO's status is exactly as before.
        assertThat(poStatusOf(originalPo)).isEqualTo(originalStatusBefore);
    }

    // --- tenancy ---

    @Test
    void anotherCompanyCannotConfirmTheOffer() throws Exception {
        UUID offerId = flag(insertPo(companyA, supplierA));
        mockMvc.perform(post("/api/container-fill-offers/{id}/confirm", offerId)
                .header(TENANT, companyB.toString()).header(USER, userBId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    // --- driving ---

    private UUID flag(UUID po) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{po}/container-fill-offers", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spareCbm\":2.50,\"supplierId\":\"" + supplierA + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.offerId"));
    }

    private ResultActions setDeadline(UUID offerId, Instant deadline) throws Exception {
        return mockMvc.perform(put("/api/container-fill-offers/{id}/deadline", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"deadline\":\"" + deadline + "\"}"));
    }

    private ResultActions confirm(UUID offerId, UUID fillPo) throws Exception {
        String body = fillPo == null ? "{}" : "{\"fillPurchaseOrderId\":\"" + fillPo + "\"}";
        return mockMvc.perform(post("/api/container-fill-offers/{id}/confirm", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decline(UUID offerId, String reason) throws Exception {
        return mockMvc.perform(post("/api/container-fill-offers/{id}/decline", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions linkFillPo(UUID offerId, UUID fillPo) throws Exception {
        return mockMvc.perform(put("/api/container-fill-offers/{id}/fill-po", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"fillPurchaseOrderId\":\"" + fillPo + "\"}"));
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

    // --- assertions ---

    private int auditCount(UUID offerId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM container_fill_offer_audit_events WHERE offer_id = ? AND event_type = ?",
            Integer.class, offerId, eventType);
    }

    private UUID shipmentIdOf(UUID offerId) {
        return jdbcTemplate.queryForObject("SELECT shipment_id FROM container_fill_offers WHERE id = ?", UUID.class, offerId);
    }

    private int activeConsignmentCount(UUID shipmentId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_consignments WHERE shipment_id = ? AND detached = FALSE", Integer.class, shipmentId);
    }

    private String poStatusOf(UUID poId) {
        return jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, poId);
    }
}
