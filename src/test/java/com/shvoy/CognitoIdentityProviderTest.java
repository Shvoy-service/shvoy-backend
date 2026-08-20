package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * The two failure modes walked live against the dev pool (2026-08-20):
 * a policy-rejected password stranding a half-created user that then
 * blocked every retry with UsernameExists, and the 500 the rejection
 * surfaced as. createConfirmedUser must (1) compensate — a failed
 * set-password deletes the create that preceded it, (2) map the policy
 * rejection to INVALID_PASSWORD (a 400, the caller's mistake), and
 * (3) reclaim a FORCE_CHANGE_PASSWORD orphan left by a pre-fix failure
 * or a crash between the two calls — while never touching a CONFIRMED
 * resident, which is a real identity.
 */
class CognitoIdentityProviderTest {

    private static final String POOL_ID = "eu-west-2_test";
    private static final String EMAIL = "user@acme.example";

    private final CognitoIdentityProviderClient cognito = mock(CognitoIdentityProviderClient.class);
    private final CognitoIdentityProvider provider = new CognitoIdentityProvider(cognito, POOL_ID);

    private static AdminCreateUserResponse createdWithSub(String sub) {
        return AdminCreateUserResponse.builder()
            .user(UserType.builder()
                .attributes(AttributeType.builder().name("sub").value(sub).build())
                .build())
            .build();
    }

    private static InvalidPasswordException policyRejection(String reason) {
        return InvalidPasswordException.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorMessage(reason).build())
            .build();
    }

    @Test
    void createsTheUserAndSetsThePermanentPasswordInOneCall() {
        when(cognito.adminCreateUser(any(Consumer.class))).thenReturn(createdWithSub("sub-1"));
        when(cognito.adminSetUserPassword(any(Consumer.class)))
            .thenReturn(AdminSetUserPasswordResponse.builder().build());

        String sub = provider.createConfirmedUser(EMAIL, "CorrectHorseBattery123!");

        assertThat(sub).isEqualTo("sub-1");
        verify(cognito, never()).adminDeleteUser(any(Consumer.class));
    }

    @Test
    void aPolicyRejectedPasswordDeletesTheHalfCreatedUserAndMapsToInvalidPassword() {
        when(cognito.adminCreateUser(any(Consumer.class))).thenReturn(createdWithSub("sub-1"));
        when(cognito.adminSetUserPassword(any(Consumer.class)))
            .thenThrow(policyRejection("Password does not conform to policy: Password must have symbol characters"));

        assertThatThrownBy(() -> provider.createConfirmedUser(EMAIL, "NoSymbolPassword123"))
            .isInstanceOfSatisfying(ValidationException.class, ex -> {
                assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_PASSWORD);
                assertThat(ex.getMessage())
                    .isEqualTo("Password does not conform to policy: Password must have symbol characters");
            });

        // The compensation targets exactly the user just created.
        ArgumentCaptor<Consumer<AdminDeleteUserRequest.Builder>> delete =
            ArgumentCaptor.forClass(Consumer.class);
        verify(cognito).adminDeleteUser(delete.capture());
        AdminDeleteUserRequest.Builder builder = AdminDeleteUserRequest.builder();
        delete.getValue().accept(builder);
        assertThat(builder.build().username()).isEqualTo(EMAIL);
        assertThat(builder.build().userPoolId()).isEqualTo(POOL_ID);
    }

    @Test
    void aNonPolicySetPasswordFailureAlsoCompensatesButRethrowsUnchanged() {
        when(cognito.adminCreateUser(any(Consumer.class))).thenReturn(createdWithSub("sub-1"));
        TooManyRequestsException throttle = TooManyRequestsException.builder().build();
        when(cognito.adminSetUserPassword(any(Consumer.class))).thenThrow(throttle);

        assertThatThrownBy(() -> provider.createConfirmedUser(EMAIL, "CorrectHorseBattery123!"))
            .isSameAs(throttle);

        verify(cognito).adminDeleteUser(any(Consumer.class));
    }

    @Test
    void aFailedCompensationIsLoggedNotThrownAndTheOriginalFailureStillPropagates() {
        when(cognito.adminCreateUser(any(Consumer.class))).thenReturn(createdWithSub("sub-1"));
        when(cognito.adminSetUserPassword(any(Consumer.class)))
            .thenThrow(policyRejection("Password does not conform to policy: Password not long enough"));
        when(cognito.adminDeleteUser(any(Consumer.class)))
            .thenThrow(SdkClientException.create("network blip"));

        assertThatThrownBy(() -> provider.createConfirmedUser(EMAIL, "short"))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Password does not conform to policy: Password not long enough");
    }

    @Test
    void reclaimsAForceChangePasswordOrphanThenCreatesCleanly() {
        when(cognito.adminCreateUser(any(Consumer.class)))
            .thenThrow(UsernameExistsException.builder().build())
            .thenReturn(createdWithSub("sub-2"));
        when(cognito.adminGetUser(any(Consumer.class)))
            .thenReturn(AdminGetUserResponse.builder().userStatus(UserStatusType.FORCE_CHANGE_PASSWORD).build());
        when(cognito.adminDeleteUser(any(Consumer.class)))
            .thenReturn(AdminDeleteUserResponse.builder().build());
        when(cognito.adminSetUserPassword(any(Consumer.class)))
            .thenReturn(AdminSetUserPasswordResponse.builder().build());

        String sub = provider.createConfirmedUser(EMAIL, "CorrectHorseBattery123!");

        assertThat(sub).isEqualTo("sub-2");
        verify(cognito, times(2)).adminCreateUser(any(Consumer.class));
        verify(cognito).adminDeleteUser(any(Consumer.class));
    }

    @Test
    void neverTouchesAConfirmedResidentIdentity() {
        UsernameExistsException exists = UsernameExistsException.builder().build();
        when(cognito.adminCreateUser(any(Consumer.class))).thenThrow(exists);
        when(cognito.adminGetUser(any(Consumer.class)))
            .thenReturn(AdminGetUserResponse.builder().userStatus(UserStatusType.CONFIRMED).build());

        assertThatThrownBy(() -> provider.createConfirmedUser(EMAIL, "CorrectHorseBattery123!"))
            .isSameAs(exists);

        verify(cognito, never()).adminDeleteUser(any(Consumer.class));
    }

    @Test
    void reclaimsAtMostOnceEvenIfTheOrphanSomehowReappears() {
        // Both creates hit UsernameExists and the resident always looks
        // reclaimable — the second failure must propagate, not loop.
        when(cognito.adminCreateUser(any(Consumer.class))).thenThrow(UsernameExistsException.builder().build());
        when(cognito.adminGetUser(any(Consumer.class)))
            .thenReturn(AdminGetUserResponse.builder().userStatus(UserStatusType.FORCE_CHANGE_PASSWORD).build());
        when(cognito.adminDeleteUser(any(Consumer.class)))
            .thenReturn(AdminDeleteUserResponse.builder().build());

        assertThatThrownBy(() -> provider.createConfirmedUser(EMAIL, "CorrectHorseBattery123!"))
            .isInstanceOf(UsernameExistsException.class);

        verify(cognito, times(2)).adminCreateUser(any(Consumer.class));
        verify(cognito, times(1)).adminDeleteUser(any(Consumer.class));
    }
}
