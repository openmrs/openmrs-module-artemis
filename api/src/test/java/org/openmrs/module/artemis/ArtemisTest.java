/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.artemis;

import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseContextMockTest;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(MockitoJUnitRunner.class)
public class ArtemisTest extends BaseContextMockTest {
	
	private ArtemisProperties createProperties(boolean embeddedEnabled) {
		ArtemisProperties props = new ArtemisProperties();
		props.setEmbeddedEnabled(embeddedEnabled);
		props.setUsername("testUser");
		props.setPassword("testPass");
		props.setConsoleEnabled(false);
		props.setConsolePort(8161);
		props.setEmbeddedPort(0);
		return props;
	}
	
	@Test
	public void getUsername_shouldReturnUsername() {
		Artemis artemis = new Artemis(createProperties(false));
		assertEquals("testUser", artemis.getUsername());
	}
	
	@Test
	public void getPassword_shouldReturnPassword() {
		Artemis artemis = new Artemis(createProperties(false));
		assertEquals("testPass", artemis.getPassword());
	}
	
	@Test
	public void start_shouldNotStartEmbeddedActiveMQIfDisabled() throws Exception {
		Artemis artemis = new Artemis(createProperties(false));
		artemis.start();
	}
	
	@Test
	public void getBrokerUri_shouldReturnUriFromPropertiesWhenNotEmbedded() {
		Artemis artemis = new Artemis(createProperties(false));
		
		Properties runtimeProperties = Context.getRuntimeProperties();
		runtimeProperties.setProperty("artemis.uri", "tcp://external:61616");
		Context.setRuntimeProperties(runtimeProperties);
		
		assertEquals("tcp://external:61616", artemis.getBrokerUri());
		
		runtimeProperties.remove("artemis.uri");
	}
	
	@Test
	public void getBrokerUri_shouldThrowWhenEmbeddedDisabledAndNoUriConfigured() {
		Artemis artemis = new Artemis(createProperties(false));
		
		Properties runtimeProperties = Context.getRuntimeProperties();
		runtimeProperties.remove("artemis.uri");
		Context.setRuntimeProperties(runtimeProperties);
		
		try {
			artemis.getBrokerUri();
			fail("Expected IllegalStateException");
		}
		catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("artemis.uri"));
		}
	}
	
	@Test
	public void start_shouldNotAddTcpAcceptorWhenCredentialsAreBlank() throws Exception {
		Path tempDir = Files.createTempDirectory("artemis-test-nocreds");
		System.setProperty("OPENMRS_APPLICATION_DATA_DIRECTORY", tempDir.toAbsolutePath().toString());

		ArtemisProperties props = new ArtemisProperties();
		props.setEmbeddedEnabled(true);
		props.setUsername("");
		props.setPassword("");
		props.setConsoleEnabled(false);
		props.setConsolePort(8161);
		props.setEmbeddedPort(0);

		Artemis artemis = new Artemis(props);

		try {
			artemis.start();
			Field f = Artemis.class.getDeclaredField("embeddedActiveMQ");
			f.setAccessible(true);
			EmbeddedActiveMQ embedded = (EmbeddedActiveMQ) f.get(artemis);
			long acceptorCount = embedded.getActiveMQServer()
			        .getConfiguration().getAcceptorConfigurations().stream()
			        .filter(a -> a.getName().equals("tcp")).count();
			assertEquals("No TCP acceptor should be created without credentials", 0, acceptorCount);
		}
		finally {
			artemis.stop();
			System.clearProperty("OPENMRS_APPLICATION_DATA_DIRECTORY");
		}
	}
	
	@Test
	public void start_shouldStartEmbeddedActiveMQIfEnabled() throws Exception {
		Path tempDir = Files.createTempDirectory("artemis-test");
		System.setProperty("OPENMRS_APPLICATION_DATA_DIRECTORY", tempDir.toAbsolutePath().toString());
		
		Artemis artemis = new Artemis(createProperties(true));
		
		try {
			artemis.start();
			assertEquals("vm://0", artemis.getBrokerUri());
		}
		finally {
			artemis.stop();
			System.clearProperty("OPENMRS_APPLICATION_DATA_DIRECTORY");
		}
	}
	
	@Test
	public void configureHawtioAuthentication_shouldDisableContainerDiscoveryWhenCredentialsConfigured() {
		Artemis artemis = new Artemis(createProperties(false));
		Configuration originalConfiguration = Configuration.getConfiguration();
		Properties originalProperties = snapshotHawtioProperties();
		
		try {
			artemis.configureHawtioAuthentication("testUser", "testPass", true);
			
			assertEquals("true", System.getProperty(Artemis.HAWTIO_AUTHENTICATION_ENABLED));
			assertEquals("", System.getProperty(Artemis.HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES));
			assertEquals("true", System.getProperty(Artemis.HAWTIO_OFFLINE));
			assertEquals(Artemis.HAWTIO_REALM_NAME, System.getProperty(Artemis.HAWTIO_REALM));
			assertEquals(Artemis.HAWTIO_EMBEDDED_ROLE, System.getProperty(Artemis.HAWTIO_ROLES));
			AppConfigurationEntry[] entries = Configuration.getConfiguration().getAppConfigurationEntry(
			    Artemis.HAWTIO_REALM_NAME);
			assertNotNull(entries);
			assertEquals(1, entries.length);
		}
		finally {
			Configuration.setConfiguration(originalConfiguration);
			restoreHawtioProperties(originalProperties);
		}
	}
	
	@Test
	public void configureHawtioAuthentication_shouldDisableAuthAndClearJaasPropertiesWithoutCredentials() {
		Artemis artemis = new Artemis(createProperties(false));
		Configuration originalConfiguration = Configuration.getConfiguration();
		Properties originalProperties = snapshotHawtioProperties();
		
		try {
			System.setProperty(Artemis.HAWTIO_REALM, "staleRealm");
			System.setProperty(Artemis.HAWTIO_ROLES, "staleRole");
			System.setProperty(Artemis.HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES, "staleDiscovery");
			
			artemis.configureHawtioAuthentication("", "", false);
			
			assertEquals("false", System.getProperty(Artemis.HAWTIO_AUTHENTICATION_ENABLED));
			assertEquals("true", System.getProperty(Artemis.HAWTIO_OFFLINE));
			assertNull(System.getProperty(Artemis.HAWTIO_REALM));
			assertNull(System.getProperty(Artemis.HAWTIO_ROLES));
			assertNull(System.getProperty(Artemis.HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES));
		}
		finally {
			Configuration.setConfiguration(originalConfiguration);
			restoreHawtioProperties(originalProperties);
		}
	}
	
	private Properties snapshotHawtioProperties() {
		Properties properties = new Properties();
		copyProperty(properties, Artemis.HAWTIO_AUTHENTICATION_ENABLED);
		copyProperty(properties, Artemis.HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES);
		copyProperty(properties, Artemis.HAWTIO_OFFLINE);
		copyProperty(properties, Artemis.HAWTIO_REALM);
		copyProperty(properties, Artemis.HAWTIO_ROLE);
		copyProperty(properties, Artemis.HAWTIO_ROLES);
		copyProperty(properties, Artemis.HAWTIO_ROLE_PRINCIPAL_CLASSES);
		copyProperty(properties, Artemis.HAWTIO_USER_PRINCIPAL_CLASSES);
		return properties;
	}
	
	private void restoreHawtioProperties(Properties properties) {
		restoreProperty(properties, Artemis.HAWTIO_AUTHENTICATION_ENABLED);
		restoreProperty(properties, Artemis.HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES);
		restoreProperty(properties, Artemis.HAWTIO_OFFLINE);
		restoreProperty(properties, Artemis.HAWTIO_REALM);
		restoreProperty(properties, Artemis.HAWTIO_ROLE);
		restoreProperty(properties, Artemis.HAWTIO_ROLES);
		restoreProperty(properties, Artemis.HAWTIO_ROLE_PRINCIPAL_CLASSES);
		restoreProperty(properties, Artemis.HAWTIO_USER_PRINCIPAL_CLASSES);
	}
	
	private void copyProperty(Properties target, String name) {
		String value = System.getProperty(name);
		if (value != null) {
			target.setProperty(name, value);
		}
	}
	
	private void restoreProperty(Properties source, String name) {
		if (source.containsKey(name)) {
			System.setProperty(name, source.getProperty(name));
		} else {
			System.clearProperty(name);
		}
	}
}
