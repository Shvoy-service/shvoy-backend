package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Same conventions as SkuControllerTest/SupplierControllerTest. S3Client is
 * mocked (@MockitoBean) so `mvn test` never touches real AWS — the real
 * client is only exercised via Testcontainers-backed *IT tests (see
 * S3ConnectivityIT), run separately via `mvn verify`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceFileUploadControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String HEADER = "sku_code,description,unit_price,currency,valid_from,valid_to\n";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    S3Client s3Client;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID supplierBId;

    @BeforeEach
    void seedCompaniesAndSuppliers() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());

        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        supplierAId = UUID.randomUUID();
        supplierBId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierBId, "Supplier B", now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM price_file_uploads WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "prices.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void uploadsValidFileAndAppliesAllRows() throws Exception {
        String csv = HEADER
            + "SKU-1,Widget,1.4275,GBP,2026-01-01,\n"
            + "SKU-2,Gadget,9.99,GBP,2026-01-01,\n";

        mockMvc.perform(multipart("/api/suppliers/{id}/price-file", supplierAId)
                .file(csvFile(csv))
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.rowsProcessed").value(2))
            .andExpect(jsonPath("$.s3Key").isNotEmpty());

        verify(s3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));

        Long skuCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM skus WHERE supplier_id = ?", Long.class, supplierAId);
        assertThat(skuCount).isEqualTo(2L);
        Long uploadCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM price_file_uploads WHERE supplier_id = ?", Long.class, supplierAId);
        assertThat(uploadCount).isEqualTo(1L);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void uploadWithAnInvalidRowRejectsTheWholeFile() throws Exception {
        String csv = HEADER
            + "SKU-1,Widget,1.4275,GBP,2026-01-01,\n"
            + "SKU-2,Gadget,not-a-number,GBP,2026-01-01,\n";

        mockMvc.perform(multipart("/api/suppliers/{id}/price-file", supplierAId)
                .file(csvFile(csv))
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Row 2")));

        // The raw file is still stored for audit even though the rows were rejected.
        verify(s3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));

        Long skuCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM skus WHERE supplier_id = ?", Long.class, supplierAId);
        assertThat(skuCount).isZero();
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void uploadWithWrongHeaderReturnsValidationError() throws Exception {
        String csv = "code,price\nSKU-1,1.00\n";

        mockMvc.perform(multipart("/api/suppliers/{id}/price-file", supplierAId)
                .file(csvFile(csv))
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void uploadForAnotherCompanysSupplierReturnsNotFoundWithoutTouchingS3() throws Exception {
        mockMvc.perform(multipart("/api/suppliers/{id}/price-file", supplierBId)
                .file(csvFile(HEADER + "SKU-1,Widget,1.00,GBP,2026-01-01,\n"))
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void uploadIsForbiddenForReadOnlyRole() throws Exception {
        mockMvc.perform(multipart("/api/suppliers/{id}/price-file", supplierAId)
                .file(csvFile(HEADER + "SKU-1,Widget,1.00,GBP,2026-01-01,\n"))
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }
}
