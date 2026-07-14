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

import org.openmrs.event.EventPayload;

public class SimpleEventPayload implements EventPayload {
	
	private String name;
	
	private String value;
	
	public SimpleEventPayload() {
	}
	
	public SimpleEventPayload(String name, String value) {
		this.name = name;
		this.value = value;
	}
	
	@Override
	public String toPayload() {
		return name + "|" + value;
	}
	
	@Override
	public void fromPayload(String payload) {
		String[] parts = payload.split("\\|", 2);
		this.name = parts[0];
		this.value = parts.length > 1 ? parts[1] : "";
	}
	
	public String getName() {
		return name;
	}
	
	public String getValue() {
		return value;
	}
}
