package com.shvoy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.Attachment;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/**
 * Real email delivery via Amazon SESv2 (Story 9.4) — the dev/prod {@link
 * EmailSender}, selected by profile ({@code local}/{@code test} keep {@link
 * ConsoleEmailSender}), the third profile-swapped seam after the identity
 * provider and S3.
 *
 * <p><strong>Failure isolation is the story's one real commitment.</strong> The
 * four consumers call {@code send} with no try/catch — so this must
 * <em>never throw</em>: every SES failure (down, sandbox-unverified recipient,
 * throttle) is caught, classified {@code FAILED_PERMANENT}/{@code FAILED_TRANSIENT},
 * recorded, and swallowed, so the business action it accompanies always
 * completes. Every attempt is recorded (subject + metadata, never the body).
 *
 * <p>Synchronous on the calling thread — volumes are trivial and the
 * failure-isolation above makes sync safe; no retry/queue machinery (a failed
 * send's record is the retry mechanism — a human resends). Uses SESv2 simple
 * content, which carries attachments natively (the PO PDF), so no raw MIME.
 */
@Component
@Profile("!local & !test")
public class SesEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private final SesV2Client sesClient;
    private final SendRecordService sendRecordService;
    private final String senderAddress;

    SesEmailSender(SesV2Client sesClient, SendRecordService sendRecordService,
            @Value("${email.sender}") String senderAddress) {
        this.sesClient = sesClient;
        this.sendRecordService = sendRecordService;
        this.senderAddress = senderAddress;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            SendEmailResponse response = sesClient.sendEmail(buildRequest(message));
            sendRecordService.recordSafely(message.source(), message.to(), message.subject(),
                SendOutcome.SENT, response.messageId(), null, message.entityReference());
        } catch (RuntimeException e) {
            SendOutcome outcome = classify(e);
            log.warn("Email send failed ({}) — source={} to={} subject='{}'; business action unaffected",
                outcome, message.source(), message.to(), message.subject(), e);
            sendRecordService.recordSafely(message.source(), message.to(), message.subject(),
                outcome, null, e.getMessage(), message.entityReference());
            // Deliberately swallowed — send is fire-and-forget (4.7's contract, enforced here under real failure).
        }
    }

    private SendEmailRequest buildRequest(EmailMessage message) {
        Message.Builder content = Message.builder()
            .subject(Content.builder().data(message.subject()).build())
            .body(Body.builder().text(Content.builder().data(message.body()).build()).build());
        if (message.attachment() != null) {
            EmailAttachment a = message.attachment();
            content.attachments(Attachment.builder()
                .fileName(a.filename())
                .contentType(a.contentType())
                .rawContent(SdkBytes.fromByteArray(a.content()))
                .build());
        }
        return SendEmailRequest.builder()
            .fromEmailAddress(senderAddress)
            .destination(Destination.builder().toAddresses(message.to()).build())
            .content(EmailContent.builder().simple(content.build()).build())
            .build();
    }

    /**
     * Permanent = won't succeed on retry (rejected/unverified recipient, unverified
     * domain, suspended account, invalid input); transient = might (throttle, limit,
     * paused, or an unmodelled/network error — treated as retryable).
     */
    private static SendOutcome classify(RuntimeException e) {
        if (e instanceof MessageRejectedException
                || e instanceof MailFromDomainNotVerifiedException
                || e instanceof AccountSuspendedException
                || e instanceof BadRequestException
                || e instanceof NotFoundException) {
            return SendOutcome.FAILED_PERMANENT;
        }
        if (e instanceof TooManyRequestsException
                || e instanceof LimitExceededException
                || e instanceof SendingPausedException) {
            return SendOutcome.FAILED_TRANSIENT;
        }
        return SendOutcome.FAILED_TRANSIENT; // unknown/network — safest to treat as retryable
    }
}
