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

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseContextMockTest;

import java.util.Properties;

import static org.junit.Assert.assertEquals;

@RunWith(MockitoJUnitRunner.class)
public class ArtemisConfigTest extends BaseContextMockTest {
	
	private Properties savedRuntimeProperties;
	
	@Before
	public void setUp() {
		savedRuntimeProperties = new Properties();
		savedRuntimeProperties.putAll(Context.getRuntimeProperties());
	}
	
	@After
	public void tearDown() {
		Context.setRuntimeProperties(savedRuntimeProperties);
	}
	
	private Artemis createArtemisWithUri(String uri, long callTimeout) {
		Properties runtimeProperties = Context.getRuntimeProperties();
		runtimeProperties.setProperty(Artemis.ARTEMIS_URI, uri);
		Context.setRuntimeProperties(runtimeProperties);
		
		ArtemisProperties props = new ArtemisProperties();
		props.setEmbeddedEnabled(false);
		props.setUsername("");
		props.setPassword("");
		props.setSendCallTimeout(callTimeout);
		return new Artemis(props);
	}
	
	@Test
	public void listenerConnectionFactory_shouldAppendCallTimeoutToUri_whenConfigured() {
		Artemis artemis = createArtemisWithUri("vm://0", 5000L);
		ActiveMQConnectionFactory factory = new ArtemisConfig().listenerConnectionFactory(artemis);
		assertEquals(5000L, factory.getCallTimeout());
	}
	
	@Test
	public void listenerConnectionFactory_shouldUseArtemisDefault_whenCallTimeoutNotConfigured() {
		Artemis artemis = createArtemisWithUri("vm://0", -1L);
		ActiveMQConnectionFactory factory = new ArtemisConfig().listenerConnectionFactory(artemis);
		assertEquals(ActiveMQClient.DEFAULT_CALL_TIMEOUT, factory.getCallTimeout());
	}
	
	@Test
	public void listenerConnectionFactory_shouldUseArtemisDefault_whenCallTimeoutIsZero() {
		Artemis artemis = createArtemisWithUri("vm://0", 0L);
		ActiveMQConnectionFactory factory = new ArtemisConfig().listenerConnectionFactory(artemis);
		assertEquals(ActiveMQClient.DEFAULT_CALL_TIMEOUT, factory.getCallTimeout());
	}
	
	@Test
	public void listenerConnectionFactory_shouldNotOverrideCallTimeout_whenAlreadyPresentInUri() {
		Artemis artemis = createArtemisWithUri("vm://0?callTimeout=10000", 5000L);
		ActiveMQConnectionFactory factory = new ArtemisConfig().listenerConnectionFactory(artemis);
		assertEquals(10000L, factory.getCallTimeout());
	}
}
