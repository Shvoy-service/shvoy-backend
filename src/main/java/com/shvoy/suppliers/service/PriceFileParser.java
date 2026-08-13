package com.shvoy.suppliers.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.shvoy.ValidationException;

/**
 * Parses the canonical price-file CSV template (Story 3.5) — a fixed
 * column set, chosen as the MVP default pending Product Owner confirmation
 * of SHVOY's real supplier price-file format (see docs/CONTRACT.md's price
 * file upload section). A different real-world format would mean a
 * column-mapping step ahead of this parser, not a change to it.
 */
final class PriceFileParser {

    static final List<String> EXPECTED_HEADERS =
        List.of("sku_code", "description", "unit_price", "currency", "valid_from", "valid_to");

    private PriceFileParser() {
    }

    static List<PriceFileRow> parse(byte[] content) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8), format)) {
            if (!EXPECTED_HEADERS.equals(parser.getHeaderNames())) {
                throw new ValidationException(
                    "Price file header must be exactly: " + String.join(",", EXPECTED_HEADERS));
            }
            List<PriceFileRow> rows = new ArrayList<>();
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                rows.add(new PriceFileRow(rowNumber, record.get("sku_code"), record.get("description"),
                    record.get("unit_price"), record.get("currency"), record.get("valid_from"),
                    record.get("valid_to")));
            }
            return rows;
        } catch (IOException | IllegalArgumentException e) {
            throw new ValidationException("Could not parse price file: " + e.getMessage());
        }
    }
}
