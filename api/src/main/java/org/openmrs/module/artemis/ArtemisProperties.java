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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("artemis.ArtemisProperties")
public class ArtemisProperties {
	
	@Value("${artemis.user:}")
	private String username;
	
	@Value("${artemis.password:}")
	private String password;
	
	@Value("${artemis.embedded.enabled:true}")
	private Boolean embeddedEnabled;
	
	@Value("${artemis.embedded.port:0}")
	private Integer embeddedPort;
	
	@Value("${artemis.embedded.console.enabled:true}")
	private Boolean consoleEnabled;
	
	@Value("${artemis.embedded.console.port:8161}")
	private Integer consolePort;
	
	public Boolean getEmbeddedEnabled() {
		return embeddedEnabled;
	}
	
	public void setEmbeddedEnabled(Boolean embeddedEnabled) {
		this.embeddedEnabled = embeddedEnabled;
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public String getPassword() {
		return password;
	}
	
	public void setPassword(String password) {
		this.password = password;
	}
	
	public Boolean getConsoleEnabled() {
		return consoleEnabled;
	}
	
	public void setConsoleEnabled(Boolean consoleEnabled) {
		this.consoleEnabled = consoleEnabled;
	}
	
	public Integer getConsolePort() {
		return consolePort;
	}
	
	public void setConsolePort(Integer consolePort) {
		this.consolePort = consolePort;
	}
	
	public Integer getEmbeddedPort() {
		return embeddedPort;
	}
	
	public void setEmbeddedPort(Integer embeddedPort) {
		this.embeddedPort = embeddedPort;
	}
}
