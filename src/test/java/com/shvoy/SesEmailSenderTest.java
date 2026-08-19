package com.shvoy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/**
 * Story 9.4 — the story's one real design commitment, unit-tested in isolation:
 * <strong>a send failure never propagates</strong> (so the business action always
 * completes), is classified permanent/transient, and is recorded. The four
 * consumers call {@code send} with no try/catch, so "never throws" is the
 * contract this proves.
 */
class SesEmailSenderTest {

    private final SesV2Client sesClient = mock(SesV2Client.class);
    private final SendRecordService recordService = mock(SendRecordService.class);
    private final SesEmailSender sender = new SesEmailSender(sesClient, recordService, "noreply@shvoy.dev");

    private static EmailMessage invite() {
        return new EmailMessage("user@acme.example", "You've been invited", "link", EmailSource.INVITATION, "ref-1");
    }

    @Test
    void aSuccessfulSendRecordsSentWithTheMessageId() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(SendEmailResponse.builder().messageId("ses-msg-42").build());

        assertThatCode(() -> sender.send(invite())).doesNotThrowAnyException();

        verify(recordService).recordSafely(eq(EmailSource.INVITATION), eq("user@acme.example"),
            eq("You've been invited"), eq(SendOutcome.SENT), eq("ses-msg-42"), isNull(), eq("ref-1"));
    }

    @Test
    void anUnverifiedRecipientIsAPermanentFailureThatDoesNotPropagate() {
        // The sandbox case: SES rejects an unverified recipient. The invite must still create.
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(MessageRejectedException.builder().message("Email address is not verified").build());

        assertThatCode(() -> sender.send(invite())).doesNotThrowAnyException(); // swallowed

        verify(recordService).recordSafely(eq(EmailSource.INVITATION), eq("user@acme.example"),
            eq("You've been invited"), eq(SendOutcome.FAILED_PERMANENT), isNull(), any(), eq("ref-1"));
    }

    @Test
    void aThrottleIsATransientFailureThatDoesNotPropagate() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(TooManyRequestsException.builder().message("Maximum sending rate exceeded").build());

        assertThatCode(() -> sender.send(invite())).doesNotThrowAnyException();

        verify(recordService).recordSafely(eq(EmailSource.INVITATION), eq("user@acme.example"),
            eq("You've been invited"), eq(SendOutcome.FAILED_TRANSIENT), isNull(), any(), eq("ref-1"));
    }

    @Test
    void anUnexpectedErrorIsTreatedAsTransientAndDoesNotPropagate() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(new RuntimeException("connection reset")); // network blip — retryable

        assertThatCode(() -> sender.send(invite())).doesNotThrowAnyException();

        verify(recordService).recordSafely(eq(EmailSource.INVITATION), eq("user@acme.example"),
            eq("You've been invited"), eq(SendOutcome.FAILED_TRANSIENT), isNull(), any(), eq("ref-1"));
    }
}
