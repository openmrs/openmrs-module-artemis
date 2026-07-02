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
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.apache.commons.lang.StringUtils;
import org.openmrs.event.EventPayload;
import org.openmrs.event.EventPublisher;
import org.openmrs.event.broker.BrokerEventListenerFactory;
import org.openmrs.event.broker.BrokerIncomingEvent;
import org.openmrs.event.broker.BrokerOutgoingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.openmrs.module.artemis.Artemis.BROKER_ID;

@Component("artemis.ArtemisEventListener")
public class ArtemisEventListener {
	
	private static final Logger log = LoggerFactory.getLogger(ArtemisEventListener.class);

	/**
	 * Important: All {@link org.openmrs.event.broker.BrokerEventListener} instances listening to the same source
	 * are invoked within a single transacted message delivery. If any listener throws an exception, the entire
	 * transaction is rolled back, causing the message to be redelivered to all listeners. This means:
	 * 
	 * <ul>
	 *   <li>Listeners must be idempotent—they may be called multiple times for the same message.</li>
	 *   <li>A failure in one listener will re-run all listeners on that source.</li>
	 *   <li>Use the JMS delivery count header (JMSXDeliveryCount) or a database-backed idempotency key if you need to detect retries.</li>
	 * </ul>
	 */
	private final ObjectMapper objectMapper;
	private final EventPublisher eventPublisher;
	private final String defaultEventBroker;
	private final BrokerEventListenerFactory listenerFactory;

	private final CachingConnectionFactory connectionFactory;
	private final JmsTemplate jmsTemplate;

	private final List<DefaultMessageListenerContainer> listenerContainers = new ArrayList<>();
	
	private boolean initialized = false;

	public ArtemisEventListener(ObjectMapper objectMapper, EventPublisher eventPublisher,
	                            @Value("${event.broker.default:artemis}") String defaultEventBroker,
	                            BrokerEventListenerFactory listenerFactory,
	                            @Qualifier("artemis.ConnectionFactory") CachingConnectionFactory connectionFactory,
	                            @Qualifier("artemis.JmsTemplate") JmsTemplate jmsTemplate) {
		this.objectMapper = objectMapper;
		this.eventPublisher = eventPublisher;
		this.defaultEventBroker = defaultEventBroker;
		this.listenerFactory = listenerFactory;
		this.connectionFactory = connectionFactory;
		this.jmsTemplate = jmsTemplate;
	}

	@EventListener
	public void setupListeners(ContextRefreshedEvent event) {
		if (initialized) {
			return;
		}
		initialized = true;
		
		Map<String, List<BrokerEventListenerFactory.Listener>> listenersBySource = new HashMap<>();
		for (BrokerEventListenerFactory.Listener listener : listenerFactory.getListeners()) {
			if ((StringUtils.isBlank(listener.getBroker()) && BROKER_ID.equals(defaultEventBroker)) || BROKER_ID.equals(listener.getBroker())) {
				listenersBySource.computeIfAbsent(listener.getSource(), k -> new ArrayList<>()).add(listener);
			}
		}

		for (Map.Entry<String, List<BrokerEventListenerFactory.Listener>> entry : listenersBySource.entrySet()) {
			String source = entry.getKey();
			List<BrokerEventListenerFactory.Listener> listeners = entry.getValue();

			DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
			container.setConnectionFactory(this.connectionFactory);
			container.setDestinationName(source);
			container.setSessionTransacted(true); // Ensures message is redelivered if an exception is thrown
			
			container.setMessageListener((MessageListener) message -> {
				try {
					Object inputStreamPayload = null;
					String stringPayload = null;
					
					Map<String, Object> headers = new HashMap<>();
					Enumeration<?> propertyNames = message.getPropertyNames();
					if (propertyNames != null) {
						while (propertyNames.hasMoreElements()) {
							String propertyName = propertyNames.nextElement().toString();
							headers.put(propertyName, message.getObjectProperty(propertyName));
						}
					}

					for (BrokerEventListenerFactory.Listener listener : listeners) {
						Object payload;
						if (InputStream.class.isAssignableFrom(listener.getPayloadType())) {
							if (inputStreamPayload == null && message instanceof ActiveMQMessage) {
								inputStreamPayload = ((ActiveMQMessage) message).getCoreMessage().getBodyInputStream();
							}

							payload = inputStreamPayload;
						} else {
							if (stringPayload == null) {
								if (message instanceof TextMessage) {
									stringPayload = ((TextMessage) message).getText();
								} else if (message instanceof ActiveMQMessage) {
									// Fallback if the message was sent purely via Artemis Core API
									stringPayload = ((ActiveMQMessage) message).getCoreMessage().getBodyBuffer().readString();
								} else {
									log.warn("Received unsupported message type: {}", message.getClass());
									continue;
								}
							}
							
							if (String.class.isAssignableFrom(listener.getPayloadType())) {
								payload = stringPayload;
							} else {
								payload = objectMapper.readValue(stringPayload, listener.getPayloadType());
							}
						}

						BrokerIncomingEvent<?> incomingEvent = new BrokerIncomingEvent<>(payload, listener.getSource(), BROKER_ID);
						incomingEvent.setHeaders(headers);
						eventPublisher.publishEvent(incomingEvent);

						log.debug("Received and published incoming event from Artemis: {}", payload);
					}
				} catch (Exception e) {
					log.error("Failed to process incoming Artemis message", e);
					throw new RuntimeException(e); // Trigger JMS transaction rollback
				}
			});
			container.initialize();
			container.start();
			listenerContainers.add(container);
		}
		log.info("ArtemisEventListener connected to Artemis broker successfully.");
	}

	@EventListener
	public void handleEvent(BrokerOutgoingEvent<?> event) throws ArtemisException {
		// Determine if this event is intended for the Artemis broker
		boolean isForThisBroker = (StringUtils.isBlank(event.getBroker()) && BROKER_ID.equals(defaultEventBroker))
		        || BROKER_ID.equals(event.getBroker());
		if (!isForThisBroker) {
			return;
		}

		try {
			jmsTemplate.send(event.getTarget(), session -> {
				try {
					Message message;
					if (event.getPayload() instanceof EventPayload) {
						message = session.createTextMessage(((EventPayload) event.getPayload()).toPayload());
					} else if (event.getPayload() instanceof String) {
						message = session.createTextMessage((String) event.getPayload());
					} else if (event.getPayload() instanceof InputStream) {
						// Unpack to underlying ActiveMQMessage to use highly efficient native streaming
						message = session.createMessage();
						((ActiveMQMessage) message).getCoreMessage().setBodyInputStream((InputStream) event.getPayload());
					} else {
						message = session.createTextMessage(objectMapper.writeValueAsString(event.getPayload()));
					}
					
					if (event.getHeaders() != null) {
						for (Map.Entry<String, Object> entry : event.getHeaders().entrySet()) {
							message.setObjectProperty(entry.getKey(), entry.getValue());
						}
					}
					
					return message;
				} catch (Exception e) {
					throw new RuntimeException("Failed to serialize event payload to Artemis message", e);
				}
			});

			log.debug("Published event to Artemis: {}", event);
		} catch (Exception e) {
			throw new ArtemisException("Failed to publish event to Artemis target: " + event.getTarget(), e);
		}
	}
	
	@PreDestroy
	public void cleanup() {
		try {
			for (DefaultMessageListenerContainer container : listenerContainers) {
				container.shutdown();
			}
			log.info("ArtemisEventListener disconnected.");
		} catch (Exception e) {
			log.error("Error shutting down Artemis Event Listener components", e);
		}
	}
}