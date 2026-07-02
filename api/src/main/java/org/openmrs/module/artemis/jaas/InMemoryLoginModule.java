package org.openmrs.module.artemis.jaas;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.security.Principal;
import java.util.*;

public class InMemoryLoginModule implements LoginModule {

    private Subject subject;
    private CallbackHandler callbackHandler;
    private Map<String, ?> options;
    private boolean succeeded = false;
    private boolean commitSucceeded = false;
    private UserPrincipal userPrincipal;
    private final List<RolePrincipal> rolePrincipals = new ArrayList<>();

    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
        this.subject = subject;
        this.callbackHandler = callbackHandler;
        this.options = options != null ? options : Collections.emptyMap();
    }

    @Override
    public boolean login() throws FailedLoginException {
        if (callbackHandler == null) {
            throw new FailedLoginException("No CallbackHandler available");
        }

        NameCallback nameCb = new NameCallback("Username: ");
        PasswordCallback passCb = new PasswordCallback("Password: ", false);
        Callback[] callbacks = new Callback[]{nameCb, passCb};
        try {
            callbackHandler.handle(callbacks);
        } catch (IOException | javax.security.auth.callback.UnsupportedCallbackException e) {
            throw new FailedLoginException("Failed to obtain credentials");
        }

        String providedUser = nameCb.getName();
        char[] providedPass = passCb.getPassword();
        passCb.clearPassword();

        Object eu = options.get("username");
        Object ep = options.get("password");
        String expectedUser = eu != null ? eu.toString() : "";
        String expectedPass = ep != null ? ep.toString() : "";

        if (expectedUser.isEmpty() || expectedPass == null) {
            throw new FailedLoginException("No configured credentials");
        }

        if (expectedUser.equals(providedUser) && expectedPass.equals(new String(providedPass))) {
            userPrincipal = new UserPrincipal(providedUser);
            Object rolesObj = options.get("roles");
            String roles = rolesObj != null ? rolesObj.toString() : "amq";
            for (String r : roles.split(",")) {
                if (!r.trim().isEmpty()) {
                    rolePrincipals.add(new RolePrincipal(r.trim()));
                }
            }
            succeeded = true;
            return true;
        }

        throw new FailedLoginException("Invalid username or password");
    }

    @Override
    public boolean commit() {
        if (!succeeded) return false;
        if (!subject.getPrincipals().contains(userPrincipal)) {
            subject.getPrincipals().add(userPrincipal);
        }
        for (RolePrincipal rp : rolePrincipals) {
            if (!subject.getPrincipals().contains(rp)) {
                subject.getPrincipals().add(rp);
            }
        }
        commitSucceeded = true;
        return true;
    }

    @Override
    public boolean abort() {
        if (!succeeded) return false;
        succeeded = false;
        userPrincipal = null;
        rolePrincipals.clear();
        return true;
    }

    @Override
    public boolean logout() {
        if (userPrincipal != null) {
            subject.getPrincipals().remove(userPrincipal);
        }
        for (RolePrincipal rp : rolePrincipals) {
            subject.getPrincipals().remove(rp);
        }
        succeeded = false;
        commitSucceeded = false;
        userPrincipal = null;
        rolePrincipals.clear();
        return true;
    }
}
