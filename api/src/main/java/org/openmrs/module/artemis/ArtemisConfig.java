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

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

@Configuration("artemis.ArtemisConfig")
public class ArtemisConfig {
	
	@Bean("artemis.ConnectionFactory")
	public CachingConnectionFactory connectionFactory(@Qualifier("artemis.Artemis") Artemis artemis) {
		String brokerUri = artemis.getBrokerUri();
		if (brokerUri != null && !brokerUri.contains("reconnectAttempts")) {
			brokerUri += (brokerUri.contains("?") ? "&" : "?") + "reconnectAttempts=3";
		}
		if (brokerUri != null && !brokerUri.contains("initialConnectAttempts")) {
			brokerUri += (brokerUri.contains("?") ? "&" : "?") + "initialConnectAttempts=3";
		}
		if (brokerUri != null && !brokerUri.contains("failoverAttempts")) {
			brokerUri += (brokerUri.contains("?") ? "&" : "?") + "failoverAttempts=3";
		}
		
		ActiveMQConnectionFactory amqFactory = new ActiveMQConnectionFactory(brokerUri);
		
		if (StringUtils.isNotBlank(artemis.getUsername()) && StringUtils.isNotBlank(artemis.getPassword())) {
			amqFactory.setUser(artemis.getUsername());
			amqFactory.setPassword(artemis.getPassword());
		}
		
		return new CachingConnectionFactory(amqFactory);
	}

	@Bean("artemis.JmsTemplate")
	public JmsTemplate jmsTemplate(@Qualifier("artemis.ConnectionFactory") CachingConnectionFactory connectionFactory) {
		JmsTemplate jmsTemplate = new JmsTemplate(connectionFactory);
		jmsTemplate.setExplicitQosEnabled(true);
		jmsTemplate.setDeliveryPersistent(true);
		return jmsTemplate;
	}
}
