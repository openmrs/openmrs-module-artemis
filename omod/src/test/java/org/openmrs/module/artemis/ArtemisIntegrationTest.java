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
import org.apache.commons.lang.exception.ExceptionUtils;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openmrs.event.EventPublisher;
import org.openmrs.event.broker.BrokerEventListenerFactory;
import org.openmrs.event.broker.BrokerOutgoingEvent;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.openmrs.module.artemis.Artemis.BROKER_ID;

public class ArtemisIntegrationTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private EventPublisher eventPublisher;
	
	@Autowired
	private BrokerEventTestListener testListener;
	
	@Autowired
	private Artemis artemis;
	
	@Autowired
	private ApplicationContext applicationContext;
	
	@Autowired
	private BrokerEventListenerFactory listenerFactory;
	
	@Override
	public Properties getRuntimeProperties() {
		Properties props = super.getRuntimeProperties();
		props.setProperty("artemis.broker.maxDiskUsage", "100");
		props.setProperty("artemis.broker.diskScanPeriod", "3600000");
		return props;
	}
	
	@Test
	public void testPublishAndReceiveEvent() throws Exception {
		String testPayload = "Integration Test Payload";
		
		BrokerOutgoingEvent<String> outgoingEvent = new BrokerOutgoingEvent<String>(testPayload,
		        BrokerEventTestListener.TEST_QUEUE, BROKER_ID);
		
		testListener.resetEventsAndLatch(2);
		
		// Publish the event using EventPublisher
		eventPublisher.publishEvent(outgoingEvent);
		
		// Wait for the message to be received by the JMS listener and published to our EventPublisher
		testListener.await(30, TimeUnit.SECONDS);
		
		MatcherAssert.assertThat(
		    testListener.getReceivedEvents(),
		    hasItems(allOf(hasProperty("payload", equalTo(testPayload))),
		        allOf(hasProperty("payload", equalTo(testPayload)))));
	}
	
	@Test
	public void testPublishAndReceiveInputStreamEvent() throws Exception {
		byte[] testBytes = "InputStream test payload".getBytes(StandardCharsets.UTF_8);

		testListener.resetEventsAndLatch(1);

		BrokerOutgoingEvent<InputStream> outgoingEvent = new BrokerOutgoingEvent<>(
		    new ByteArrayInputStream(testBytes), BrokerEventTestListener.INPUTSTREAM_QUEUE, BROKER_ID);
		eventPublisher.publishEvent(outgoingEvent);

		testListener.await(30, TimeUnit.SECONDS);

		Assertions.assertArrayEquals(testBytes, testListener.getReceivedInputStreamBytes());
	}
	
	@Test
	public void testRuntimeModuleListenerReceivesMessages() throws Exception {
		// Simulate BrokerEventListenerFactory discovering a @BrokerEventListener from a module that
		// started after the initial ContextRefreshedEvent (the normal OpenMRS runtime-module path).
		Field listenersField = BrokerEventListenerFactory.class.getDeclaredField("listeners");
		listenersField.setAccessible(true);
		@SuppressWarnings("unchecked")
		CopyOnWriteArrayList<BrokerEventListenerFactory.Listener> listeners =
		    (CopyOnWriteArrayList<BrokerEventListenerFactory.Listener>) listenersField.get(listenerFactory);
		listeners.addIfAbsent(new BrokerEventListenerFactory.Listener(
		    BrokerEventTestListener.RUNTIME_QUEUE, Artemis.BROKER_ID, String.class));

		// Simulate the ContextRefreshedEvent that OpenMRS fires when a module's context is refreshed.
		// setupListeners should create a new JMS container for RUNTIME_QUEUE.
		applicationContext.publishEvent(new ContextRefreshedEvent(applicationContext));

		String testPayload = "Runtime module payload";
		testListener.resetEventsAndLatch(1);
		eventPublisher.publishEvent(new BrokerOutgoingEvent<>(testPayload,
		    BrokerEventTestListener.RUNTIME_QUEUE, BROKER_ID));

		testListener.await(30, TimeUnit.SECONDS);
		Assertions.assertEquals(testPayload, testListener.getReceivedRuntimePayload());
	}
	
	@Test
	public void testPublishAndReceiveEventPayload() throws Exception {
		SimpleEventPayload outgoing = new SimpleEventPayload("hello", "world");

		testListener.resetEventsAndLatch(1);
		eventPublisher.publishEvent(new BrokerOutgoingEvent<>(outgoing,
		    BrokerEventTestListener.EVENTPAYLOAD_QUEUE, BROKER_ID));
		testListener.await(30, TimeUnit.SECONDS);

		SimpleEventPayload received = testListener.getReceivedEventPayload();
		Assertions.assertNotNull(received);
		Assertions.assertEquals("hello", received.getName());
		Assertions.assertEquals("world", received.getValue());
	}
	
	@Test
	public void testPublishAndReceiveEventWithPrimitiveHeaders() throws Exception {
		Map<String, Object> outgoingHeaders = new HashMap<>();
		outgoingHeaders.put("stringHeader", "plain-value");
		outgoingHeaders.put("intHeader", 42);

		testListener.resetEventsAndLatch(2);
		eventPublisher.publishEvent(new BrokerOutgoingEvent<>(
		    "header test", BrokerEventTestListener.TEST_QUEUE, BROKER_ID, outgoingHeaders));
		testListener.await(30, TimeUnit.SECONDS);

		Map<String, Object> receivedHeaders = testListener.getReceivedEvents().get(0).getHeaders();
		assertThat(receivedHeaders.get("stringHeader"), equalTo("plain-value"));
		assertThat(receivedHeaders.get("intHeader"), equalTo(42));
	}
	
	@Test
	public void testPublishAndReceiveEventWithRetry() throws Exception {
		String testPayload = "Integration Test Retry Payload";
		
		BrokerOutgoingEvent<String> outgoingEvent = new BrokerOutgoingEvent<String>(testPayload,
		        BrokerEventTestListener.RETRY_QUEUE, BROKER_ID);
		
		testListener.resetEventsAndLatch(1);
		
		// Publish the event using EventPublisher
		eventPublisher.publishEvent(outgoingEvent);
		
		// Wait for the message to be received by the JMS listener and published to our EventPublisher
		testListener.await(30, TimeUnit.SECONDS);
		
		MatcherAssert.assertThat(testListener.getReceivedEvents(),
		    hasItems(allOf(hasProperty("payload", equalTo(testPayload)))));
		MatcherAssert.assertThat(testListener.getAttempts(), equalTo(3));
	}
	
	@Test
	public void testPublishAndReceiveEventMovesToDLQ() throws Exception {
		String testPayload = "Integration Test DLQ Payload";
		
		BrokerOutgoingEvent<String> outgoingEvent = new BrokerOutgoingEvent<String>(testPayload,
		        BrokerEventTestListener.DLQ_TEST_QUEUE, BROKER_ID);
		
		testListener.resetEventsAndLatch(1);
		
		// Publish the event using EventPublisher
		eventPublisher.publishEvent(outgoingEvent);
		
		// Wait for the message to fail max attempts and be routed to DLQ listener
		testListener.await(120, TimeUnit.SECONDS);
		
		MatcherAssert.assertThat(testListener.getReceivedEvents(),
		    hasItems(allOf(hasProperty("payload", equalTo(testPayload)))));
		MatcherAssert.assertThat(testListener.getDlqAttempts(), equalTo(10)); // Default Artemis max-delivery-attempts is 10
	}
	
	@Test
	public void testPublishEventFailsFastWhenDiskFull() throws Exception {
		Field f = Artemis.class.getDeclaredField("embeddedActiveMQ");
		f.setAccessible(true);
		EmbeddedActiveMQ embedded = (EmbeddedActiveMQ) f.get(artemis);

		Object pagingManager = embedded.getActiveMQServer().getPagingManager();
		Method setDiskFull = pagingManager.getClass().getDeclaredMethod("setDiskFull", boolean.class);
		setDiskFull.setAccessible(true);
		setDiskFull.invoke(pagingManager, true);

		try {
			long start = System.currentTimeMillis();
			Assertions.assertThrows(Exception.class,
			    () -> eventPublisher.publishEvent(new BrokerOutgoingEvent<>("msg", "integration.test.disk.full.queue", BROKER_ID)));
			long duration = System.currentTimeMillis() - start;

			Assertions.assertTrue(duration < 1000,
			    "Expected fast failure due to DiskFullMessagePolicy.FAIL but send blocked for " + duration + "ms");
		}
		finally {
			setDiskFull.invoke(pagingManager, false);
		}
	}
	
	@Test
	public void testPublishEventThrowsExceptionOnConnectionFailure() throws Exception {
		// Stop the embedded broker to simulate a connection failure
		artemis.stop();
		
		String testPayload = "Integration Test Connection Failure Payload";
		BrokerOutgoingEvent<String> outgoingEvent =
				new BrokerOutgoingEvent<String>(testPayload, "integration.test.failure.topic", BROKER_ID);
		
		long startTime = System.currentTimeMillis();
		
		Exception exception = Assertions.assertThrows(Exception.class, () -> {
			eventPublisher.publishEvent(outgoingEvent);
		});
		
		long duration = System.currentTimeMillis() - startTime;
		
		// Verify the underlying cause logs the ArtemisException message
		String trace = ExceptionUtils.getFullStackTrace(exception);
		assertThat(trace, containsString("Failed to publish event to Artemis target: integration.test.failure.topic"));
		
		// Verify the client attempted to reconnect. 
		// With initialConnectAttempts=3 and default retryInterval=2000ms, it will pause twice.
		// 2 pauses * 2000ms = ~4000ms. We use 3500ms to allow for minor OS timer inaccuracies.
		Assertions.assertTrue(duration >= 3500, "Publishing failed too quickly (" + duration + "ms), indicating retries did not happen.");

		artemis.start();
	}
}
