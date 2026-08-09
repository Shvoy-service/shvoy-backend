package com.shvoy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for Cognito, used under the local and test profiles so
 * neither requires real AWS. Every created/deleted email is recorded so
 * tests can assert on the exact compensation behavior of
 * RegistrationService.activate — e.g. that a lost race deletes precisely the
 * identity it just created, not some other one.
 */
@Component
@Profile("local | test")
public class LocalIdentityProvider implements IdentityProvider {

    private final List<String> createdEmails = new CopyOnWriteArrayList<>();
    private final List<String> deletedEmails = new CopyOnWriteArrayList<>();

    @Override
    public String createConfirmedUser(String email, String password) {
        createdEmails.add(email);
        return UUID.randomUUID().toString();
    }

    @Override
    public void deleteUser(String email) {
        deletedEmails.add(email);
    }

    public List<String> createdEmails() {
        return List.copyOf(createdEmails);
    }

    public List<String> deletedEmails() {
        return List.copyOf(deletedEmails);
    }
}
