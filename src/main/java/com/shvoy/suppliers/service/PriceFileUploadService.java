package com.shvoy.suppliers.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.TenantGuard;
import com.shvoy.ValidationException;
import com.shvoy.suppliers.domain.PriceFileUpload;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.dto.CreateSkuRequest;
import com.shvoy.suppliers.dto.PriceFileUploadResponse;
import com.shvoy.suppliers.dto.SkuPriceRequest;
import com.shvoy.suppliers.repository.PriceFileUploadRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Story 3.5 — bulk price-file upload. The raw file is stored in S3 first,
 * unconditionally, before any parsing/validation — it's the audit trail
 * for a rejected file, same as a successful one (see docs/CONTRACT.md).
 * Every row then goes through the same SkuService methods manual entry
 * uses, inside one transaction: any row that fails the supersession rule
 * rolls the whole upload back, matching the all-or-nothing policy.
 */
@Service
public class PriceFileUploadService {

    private final SkuService skuService;
    private final SupplierRepository supplierRepository;
    private final PriceFileUploadRepository priceFileUploadRepository;
    private final S3Client s3Client;
    private final String documentsBucket;

    PriceFileUploadService(SkuService skuService, SupplierRepository supplierRepository,
            PriceFileUploadRepository priceFileUploadRepository, S3Client s3Client,
            @Value("${aws.s3.documents-bucket}") String documentsBucket) {
        this.skuService = skuService;
        this.supplierRepository = supplierRepository;
        this.priceFileUploadRepository = priceFileUploadRepository;
        this.s3Client = s3Client;
        this.documentsBucket = documentsBucket;
    }

    @Transactional
    public PriceFileUploadResponse upload(UUID supplierId, MultipartFile file) {
        findOwnSupplier(supplierId);
        byte[] content = readBytes(file);
        String s3Key = storeInS3(supplierId, file.getOriginalFilename(), content);

        List<PriceFileRow> rows = PriceFileParser.parse(content);
        List<String> rowErrors = new ArrayList<>();
        for (PriceFileRow row : rows) {
            rowErrors.addAll(row.validate());
        }
        if (!rowErrors.isEmpty()) {
            throw new ValidationException(String.join("; ", rowErrors));
        }

        for (PriceFileRow row : rows) {
            applyRow(supplierId, row);
        }

        priceFileUploadRepository.save(new PriceFileUpload(supplierId, s3Key, rows.size()));
        return new PriceFileUploadResponse(rows.size(), s3Key);
    }

    private void applyRow(UUID supplierId, PriceFileRow row) {
        Optional<UUID> existingSkuId = skuService.findExistingSkuId(supplierId, row.skuCode());
        if (existingSkuId.isPresent()) {
            skuService.addPrice(supplierId, existingSkuId.get(),
                new SkuPriceRequest(row.unitPriceAmount(), row.currency(), row.validFrom(), row.validTo()));
        } else {
            skuService.createSku(supplierId, new CreateSkuRequest(
                row.skuCode(), row.description(), row.unitPriceAmount(), row.currency(),
                row.validFrom(), row.validTo()));
        }
    }

    private String storeInS3(UUID supplierId, String originalFilename, byte[] content) {
        String key = "price-files/%s/%s/%s-%s".formatted(
            TenantContext.get(), supplierId, UUID.randomUUID(),
            originalFilename == null ? "price-file.csv" : originalFilename);
        s3Client.putObject(
            PutObjectRequest.builder().bucket(documentsBucket).key(key).build(),
            RequestBody.fromBytes(content));
        return key;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
    }

    private Supplier findOwnSupplier(UUID id) {
        Supplier supplier = supplierRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);
        return supplier;
    }
}
