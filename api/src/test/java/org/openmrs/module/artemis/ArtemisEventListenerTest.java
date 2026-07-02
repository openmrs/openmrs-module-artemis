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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.event.EventPublisher;
import org.openmrs.event.broker.BrokerEventListenerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ArtemisEventListenerTest {
	
	@Mock
	private ObjectMapper objectMapper;
	
	@Mock
	private EventPublisher eventPublisher;
	
	@Mock
	private BrokerEventListenerFactory listenerFactory;
	
	private BrokerEventListenerFactory.Listener createMockListener(String broker, String source) {
		BrokerEventListenerFactory.Listener listener = mock(BrokerEventListenerFactory.Listener.class);
		when(listener.getBroker()).thenReturn(broker);
		when(listener.getSource()).thenReturn(source);
		return listener;
	}
	
	private void injectMockConnectionFactory(ArtemisEventListener listener) throws Exception {
		Field field = ArtemisEventListener.class.getDeclaredField("connectionFactory");
		field.setAccessible(true);
		field.set(listener, mock(CachingConnectionFactory.class));
	}
	
	@SuppressWarnings("unchecked")
	private List<DefaultMessageListenerContainer> getListenerContainers(ArtemisEventListener listener) throws Exception {
		Field field = ArtemisEventListener.class.getDeclaredField("listenerContainers");
		field.setAccessible(true);
		return (List<DefaultMessageListenerContainer>) field.get(listener);
	}
	
	@Test
	public void setupListeners_shouldRegisterListenersForArtemisOrMatchingDefaultBroker() throws Exception {
		BrokerEventListenerFactory.Listener explicitArtemisListener = createMockListener("artemis", "source1");
		BrokerEventListenerFactory.Listener implicitDefaultListener = createMockListener("", "source2");
		BrokerEventListenerFactory.Listener otherBrokerListener = createMockListener("activemq", "source3");
		BrokerEventListenerFactory.Listener duplicateSourceListener = createMockListener("artemis", "source1");
		
		when(listenerFactory.getListeners()).thenReturn(
		    Arrays.asList(explicitArtemisListener, implicitDefaultListener, otherBrokerListener, duplicateSourceListener));
		
		ArtemisEventListener eventListener = new ArtemisEventListener(objectMapper, eventPublisher, "artemis",
		        listenerFactory, mock(CachingConnectionFactory.class), mock(ActiveMQConnectionFactory.class),
		        mock(JmsTemplate.class));
		
		eventListener.setupListeners(mock(ContextRefreshedEvent.class));
		
		List<DefaultMessageListenerContainer> containers = getListenerContainers(eventListener);
		
		// Should group "source1" into one container, create one for "source2", and ignore "source3"
		assertEquals(2, containers.size());
		
		boolean foundSource1 = false;
		boolean foundSource2 = false;
		
		for (DefaultMessageListenerContainer container : containers) {
			if ("source1".equals(container.getDestinationName())) {
				foundSource1 = true;
			} else if ("source2".equals(container.getDestinationName())) {
				foundSource2 = true;
			}
		}
		
		assertTrue("Container for source1 should be created", foundSource1);
		assertTrue("Container for source2 should be created", foundSource2);
	}
	
	@Test
	public void setupListeners_shouldIgnoreEmptyBrokerIfDefaultIsNotArtemis() throws Exception {
		BrokerEventListenerFactory.Listener implicitDefaultListener = createMockListener("", "source1");
		when(listenerFactory.getListeners()).thenReturn(Collections.singletonList(implicitDefaultListener));
		ArtemisEventListener eventListener = new ArtemisEventListener(objectMapper, eventPublisher, "otherBroker",
		        listenerFactory, mock(CachingConnectionFactory.class), mock(ActiveMQConnectionFactory.class),
		        mock(JmsTemplate.class));
		injectMockConnectionFactory(eventListener);
		eventListener.setupListeners(mock(ContextRefreshedEvent.class));
		List<DefaultMessageListenerContainer> containers = getListenerContainers(eventListener);
		assertEquals("Should not create container since default broker is not artemis", 0, containers.size());
	}
}
