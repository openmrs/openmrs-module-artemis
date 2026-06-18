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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.GlobalProperty;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.ContextDAO;
import org.openmrs.test.jupiter.BaseContextMockTest;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ArtemisTest extends BaseContextMockTest {
	
	@Mock
	private ConfigurableApplicationContext applicationContext;
	
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
	public void start_shouldStartEmbeddedActiveMQIfEnabled() throws Exception {
		Path tempDir = Files.createTempDirectory("artemis-test");
		System.setProperty("OPENMRS_APPLICATION_DATA_DIRECTORY", tempDir.toAbsolutePath().toString());
		
		Artemis artemis = new Artemis(createProperties(true));
		artemis.setApplicationContext(applicationContext);
		try {
			artemis.start();
			assertEquals("vm://0", artemis.getBrokerUri());
		}
		finally {
			artemis.stop();
			System.clearProperty("OPENMRS_APPLICATION_DATA_DIRECTORY");
		}
	}
}
