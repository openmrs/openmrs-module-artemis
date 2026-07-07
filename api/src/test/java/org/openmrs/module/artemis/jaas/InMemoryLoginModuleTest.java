/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.artemis.jaas;

import org.junit.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.FailedLoginException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class InMemoryLoginModuleTest {
	
	private InMemoryLoginModule createModule(String username, String password, String roles) {
		InMemoryLoginModule module = new InMemoryLoginModule();
		Map<String, String> options = new HashMap<>();
		options.put("username", username);
		options.put("password", password);
		if (roles != null) {
			options.put("roles", roles);
		}
		module.initialize(new Subject(), callbacks -> {
			for (Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName(username);
				} else if (callback instanceof PasswordCallback) {
					((PasswordCallback) callback).setPassword(password.toCharArray());
				}
			}
		}, new HashMap<>(), options);
		return module;
	}
	
	@Test
	public void login_shouldSucceedWithCorrectCredentials() throws Exception {
		InMemoryLoginModule module = createModule("user", "pass", "amq");
		assertTrue(module.login());
	}
	
	@Test
	public void login_shouldFailWithWrongPassword() {
		InMemoryLoginModule module = new InMemoryLoginModule();
		Map<String, String> options = new HashMap<>();
		options.put("username", "user");
		options.put("password", "correct");
		module.initialize(new Subject(), callbacks -> {
			for (Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName("user");
				} else if (callback instanceof PasswordCallback) {
					((PasswordCallback) callback).setPassword("wrong".toCharArray());
				}
			}
		}, new HashMap<>(), options);

		try {
			module.login();
			fail("Expected FailedLoginException");
		} catch (FailedLoginException e) {
			assertTrue(e.getMessage().contains("Invalid"));
		}
	}
	
	@Test
	public void login_shouldFailWhenPasswordCallbackReturnsNull() {
		InMemoryLoginModule module = new InMemoryLoginModule();
		Map<String, String> options = new HashMap<>();
		options.put("username", "user");
		options.put("password", "pass");
		module.initialize(new Subject(), callbacks -> {
			for (Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName("user");
				}
				// PasswordCallback left unset — getPassword() returns null
			}
		}, new HashMap<>(), options);

		try {
			module.login();
			fail("Expected FailedLoginException");
		} catch (FailedLoginException e) {
			assertTrue(e.getMessage().contains("Invalid"));
		}
	}
	
	@Test
	public void login_shouldFailWhenConfiguredPasswordIsEmpty() {
		InMemoryLoginModule module = new InMemoryLoginModule();
		Map<String, String> options = new HashMap<>();
		options.put("username", "user");
		options.put("password", "");
		module.initialize(new Subject(), callbacks -> {
			for (Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName("user");
				} else if (callback instanceof PasswordCallback) {
					((PasswordCallback) callback).setPassword("".toCharArray());
				}
			}
		}, new HashMap<>(), options);

		try {
			module.login();
			fail("Expected FailedLoginException");
		} catch (FailedLoginException e) {
			assertTrue(e.getMessage().contains("No configured credentials"));
		}
	}
	
	@Test
	public void commit_shouldAddPrincipalsToSubject() throws Exception {
		Subject subject = new Subject();
		InMemoryLoginModule module = new InMemoryLoginModule();
		Map<String, String> options = new HashMap<>();
		options.put("username", "user");
		options.put("password", "pass");
		options.put("roles", "amq,admin");
		module.initialize(subject, callbacks -> {
			for (Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName("user");
				} else if (callback instanceof PasswordCallback) {
					((PasswordCallback) callback).setPassword("pass".toCharArray());
				}
			}
		}, new HashMap<>(), options);

		module.login();
		module.commit();

		long userPrincipals = subject.getPrincipals().stream()
		        .filter(p -> p instanceof UserPrincipal).count();
		long rolePrincipals = subject.getPrincipals().stream()
		        .filter(p -> p instanceof RolePrincipal).count();
		assertEquals(1, userPrincipals);
		assertEquals(2, rolePrincipals);
	}
	
	@Test
	public void abort_shouldClearState() throws Exception {
		InMemoryLoginModule module = createModule("user", "pass", "amq");
		module.login();
		assertTrue(module.abort());
	}
	
	@Test
	public void logout_shouldRemovePrincipalsFromSubject() throws Exception {
		Subject subject = new Subject();
		InMemoryLoginModule module = new InMemoryLoginModule();
		Map<String, String> options = new HashMap<>();
		options.put("username", "user");
		options.put("password", "pass");
		options.put("roles", "amq");
		module.initialize(subject, callbacks -> {
			for (Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName("user");
				} else if (callback instanceof PasswordCallback) {
					((PasswordCallback) callback).setPassword("pass".toCharArray());
				}
			}
		}, new HashMap<>(), options);

		module.login();
		module.commit();
		assertTrue("Subject should have principals after commit", !subject.getPrincipals().isEmpty());

		module.logout();
		assertTrue("Subject should have no principals after logout", subject.getPrincipals().isEmpty());
	}
}
