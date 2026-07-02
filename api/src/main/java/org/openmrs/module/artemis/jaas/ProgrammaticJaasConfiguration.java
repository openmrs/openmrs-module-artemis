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

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import java.util.HashMap;
import java.util.Map;

public class ProgrammaticJaasConfiguration extends Configuration {
	
	private final String username;
	
	private final String password;
	
	private final String roles;
	
	private final String realmName;
	
	public ProgrammaticJaasConfiguration(String realmName, String username, String password, String roles) {
		this.realmName = realmName;
		this.username = username;
		this.password = password;
		this.roles = roles != null ? roles : "amq";
	}
	
	@Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if (!realmName.equals(name)) {
            return null;
        }

        Map<String, String> options = new HashMap<>();
        options.put("username", username != null ? username : "");
        options.put("password", password != null ? password : "");
        options.put("roles", roles);

        AppConfigurationEntry entry = new AppConfigurationEntry(
                InMemoryLoginModule.class.getName(),
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                options
        );

        return new AppConfigurationEntry[]{entry};
    }
}
