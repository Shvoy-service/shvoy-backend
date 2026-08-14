package com.shvoy.purchaseorders.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/**
 * Story 4.6: renders a {@link PurchaseOrderDocumentData} to PDF bytes —
 * this class is the only place data and layout meet. Two deliberately
 * separate steps, not one: Thymeleaf turns the data into XHTML from a
 * template file ({@code templates/purchase-order-document.html}, plain
 * content-focused HTML/CSS, no Thymeleaf logic beyond {@code th:text}/
 * {@code th:each}), then openhtmltopdf-pdfbox turns that XHTML into a PDF —
 * so the layout can change (or a new template for a different document —
 * dispute letters, compliance packs — can be added) without touching this
 * class or any pricing/business logic.
 *
 * {@code ITemplateEngine} here is never wired into Spring MVC's view
 * resolution — this app is a {@code @RestController}-only JSON API, no
 * server-rendered views exist. It's used purely as a string templating
 * engine: {@link #render} calls {@code process(...)} directly and returns
 * PDF bytes, the same way it'd return a JSON body from any other service.
 *
 * openhtmltopdf-pdfbox chosen over iText (AGPL/commercial dual-licensed —
 * see docs/CONTRACT.md's Story 4.6 section for the full rationale) and
 * over raw Apache PDFBox (would mix layout with data by construction,
 * exactly what this two-step split avoids). {@code useFastMode()} skips
 * openhtmltopdf's two-pass layout (a minor performance option, not a
 * correctness concern) — appropriate here since this document has no
 * running headers/footers or page-number-dependent content that would
 * need the second pass.
 */
@Service
public class PurchaseOrderDocumentRenderer {

    private static final String TEMPLATE_NAME = "purchase-order-document";

    private final ITemplateEngine templateEngine;

    PurchaseOrderDocumentRenderer(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public byte[] render(PurchaseOrderDocumentData data) {
        Context context = new Context();
        context.setVariable("po", data);
        String xhtml = templateEngine.process(TEMPLATE_NAME, context);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(xhtml, null);
        builder.toStream(output);
        try {
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render purchase order PDF for " + data.poNumber(), e);
        }
        return output.toByteArray();
    }
}
