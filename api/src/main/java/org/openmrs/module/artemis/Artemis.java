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

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.component.WebServerComponent;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.security.CheckType;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.dto.AppDTO;
import org.apache.activemq.artemis.dto.WebServerDTO;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager;
import org.apache.commons.lang.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component("artemis.Artemis")
public class Artemis implements ApplicationContextAware {
	
	private static final Logger log = LoggerFactory.getLogger(Artemis.class);
	
	public static final String BROKER_ID = "artemis";
	
	public static final String ARTEMIS_URI = "artemis.uri";
	
	private EmbeddedActiveMQ embeddedActiveMQ;
	
	private WebServerComponent webServer;
	
	private ApplicationContext applicationContext;
	
	private ScheduledExecutorService monitorExecutor;
	
	private volatile boolean shuttingDown = false;
	
	private ArtemisProperties artemisProperties;
	
	public Artemis(ArtemisProperties artemisProperties) {
		this.artemisProperties = artemisProperties;
	}
	
	public String getUsername() {
		return artemisProperties.getUsername();
	}
	
	public String getPassword() {
		return artemisProperties.getPassword();
	}
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
	
	public String getBrokerUri() {
		if (embeddedActiveMQ != null) {
			return "vm://0"; //Use in-vm
		} else {
			return Context.getRuntimeProperties().getProperty(ARTEMIS_URI);
		}
	}
	
	@PostConstruct
	public void start() throws Exception {
		if (artemisProperties.getEmbeddedEnabled()) {
			// Clear shuttingDown flag in case this instance is restarted after stop()
			shuttingDown = false;
			try {
				String username = artemisProperties.getUsername();
				String password = artemisProperties.getPassword();
				boolean hasCredentials = StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password);
				
				ConfigurationImpl config = new ConfigurationImpl()				        .addAcceptorConfiguration("in-vm", "vm://0");
				        // Only add a TCP acceptor (exposed to the network) when credentials are configured
				        if (hasCredentials) {
				        config.addAcceptorConfiguration("tcp", "tcp://0.0.0.0:" + artemisProperties.getEmbeddedPort()); // assign the configured port (0 means random free port)
				        }
				        config.setSecurityEnabled(hasCredentials).setJMXManagementEnabled(true);
				
				String dataDir = OpenmrsUtil.getApplicationDataDirectory() + File.separator + "artemis";
				config.setBindingsDirectory(dataDir + File.separator + "bindings");
				config.setJournalDirectory(dataDir + File.separator + "journal");
				config.setLargeMessagesDirectory(dataDir + File.separator + "large-messages");
				config.setPagingDirectory(dataDir + File.separator + "paging");

				// Configure default DLQ
				AddressSettings addressSettings = new AddressSettings();
				addressSettings.setAutoCreateDeadLetterResources(true);
				addressSettings.setDeadLetterAddress(SimpleString.of("DLQ"));
				addressSettings.setDeadLetterQueueSuffix(SimpleString.of(".DLQ"));
				addressSettings.setMaxDeliveryAttempts(10);
				addressSettings.setRedeliveryDelay(250); // Initial delay of 250 ms
				addressSettings.setRedeliveryMultiplier(2.0); // Double the delay on each subsequent retry
				addressSettings.setMaxRedeliveryDelay(30000); // Cap the maximum delay at 30 seconds
				config.addAddressSetting("#", addressSettings);

				// Parse any properties from runtime properties with artemis. prefix.
				config.parsePrefixedProperties(Context.getRuntimeProperties(), "artemis.");
				
				embeddedActiveMQ = new EmbeddedActiveMQ();
				embeddedActiveMQ.setConfiguration(config);
				
				if (hasCredentials) {
					embeddedActiveMQ.setSecurityManager(new ActiveMQSecurityManager() {
						@Override
						public boolean validateUser(String user, String pass) {
							return username.equals(user) && password.equals(pass);
						}
						
						@Override
						public boolean validateUserAndRole(String user, String pass, Set<Role> roles, CheckType checkType) {
							return validateUser(user, pass);
						}
					});
				}
				
				embeddedActiveMQ.start();
				
				if (artemisProperties.getConsoleEnabled()) {
					try {
						File dataDirFile = new File(dataDir);
						if (!dataDirFile.exists()) {
							dataDirFile.mkdirs();
						}
						
						// Artemis WebServerComponent strictly looks for WAR files inside a "web" subfolder
						File webDirFile = new File(dataDirFile, "console");
						if (!webDirFile.exists()) {
							webDirFile.mkdirs();
						}
						
						File consoleWar = new File(webDirFile, "console.war");
						if (!consoleWar.exists()) {
							try (InputStream is = getClass().getResourceAsStream("/console.war")) {
								if (is != null) {
									Files.copy(is, consoleWar.toPath(), StandardCopyOption.REPLACE_EXISTING);
								} else {
									log.warn("console.war not found in classpath. Artemis Web Console may fail to start or return a 404 error.");
								}
							}
						}
						
						if (hasCredentials) {
							File etcDir = new File(dataDirFile, "etc");
							if (!etcDir.exists()) {
								etcDir.mkdirs();
							}
							
							// Create JAAS Properties files dynamically based on configured credentials
							File usersFile = new File(etcDir, "artemis-users.properties");
							Files.write(usersFile.toPath(), (username + "=" + password + "\n").getBytes(StandardCharsets.UTF_8));
							
							File rolesFile = new File(etcDir, "artemis-roles.properties");
							Files.write(rolesFile.toPath(), ("amq=" + username + "\n").getBytes(StandardCharsets.UTF_8));
							
							File loginConfig = new File(etcDir, "login.config");
							
							String jaasConfig = "activemq {\n" +
							        "    org.apache.activemq.artemis.spi.core.security.jaas.PropertiesLoginModule required\n" +
							        "        debug=false\n" +
							        "        reload=true\n" +
							        "        org.apache.activemq.jaas.properties.user=\"artemis-users.properties\"\n" +
							        "        org.apache.activemq.jaas.properties.role=\"artemis-roles.properties\";\n" +
							        "};\n";
							Files.write(loginConfig.toPath(), jaasConfig.getBytes(StandardCharsets.UTF_8));
							
							System.setProperty("java.security.auth.login.config", loginConfig.getAbsolutePath());
							
							try {
								// Force Java to reload JAAS configs now that we've set the property
								javax.security.auth.login.Configuration.getConfiguration().refresh();
							} catch (Exception e) {
								log.warn("Failed to refresh JAAS configuration. Artemis Web Console login might fail.", e);
							}
							
							System.setProperty("hawtio.authenticationEnabled", "true");
							System.setProperty("hawtio.realm", "activemq");
							System.setProperty("hawtio.role", "amq");
							System.setProperty("hawtio.roles", "amq");
							System.setProperty("hawtio.rolePrincipalClasses", "org.apache.activemq.artemis.spi.core.security.jaas.RolePrincipal");
							System.setProperty("hawtio.userPrincipalClasses", "org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal");
						} else {
							// Bypass Hawtio's JAAS authentication requirement for embedded setups
							System.setProperty("hawtio.authenticationEnabled", "false");
						}
						System.setProperty("hawtio.offline", "true");
						
						WebServerDTO webServerDTO = new WebServerDTO();
						// Bind the embedded console to loopback by default to avoid exposing it on all interfaces
						webServerDTO.bind = "http://127.0.0.1:" + artemisProperties.getConsolePort();
						webServerDTO.path = "console";
						
						AppDTO app = new AppDTO();
						app.url = "console";
						app.war = "console.war"; // The console WAR file name
						
						webServerDTO.apps = new ArrayList<>();
						webServerDTO.apps.add(app);
						
						webServer = new WebServerComponent();
						// We use your dataDir as the home/instance dir where Artemis will look for the WAR file
						webServer.configure(webServerDTO, dataDir, dataDir); 
						webServer.start();
						log.info("Embedded Artemis Web Console started on http://localhost:{}/console", artemisProperties.getConsolePort());
					} catch (Exception e) {
						log.warn("Failed to start Artemis Web Console. Ensure artemis-web dependency and console.war are available.", e);
					}
				}

				log.info("Embedded Artemis broker started successfully. Starting to monitor it...");
				
				startHealthMonitor();
			}
			catch (Exception e) {
				// Throwing an exception here halts Spring context initialization,
				// causing the entire application startup to fail.
				throw new RuntimeException("CRITICAL: Failed to start Embedded Artemis server", e);
			}
		}
	}
	
	private void startHealthMonitor() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Artemis-Health-Watchdog");
            thread.setDaemon(true); // Daemon so it doesn't block JVM shutdown
            return thread;
        });

        // Check the health of the server every 5 seconds
        monitorExecutor.scheduleAtFixedRate(() -> {
            if (shuttingDown) {
                return; // Ignore if we are intentionally stopping the application
            }

            boolean isRunning;
            try {
                isRunning = embeddedActiveMQ.getActiveMQServer() != null && 
                            embeddedActiveMQ.getActiveMQServer().isStarted();
            } catch (Exception e) {
                isRunning = false; // Treat any evaluation exception as a failure
            }

            if (!isRunning) {
                log.error("Embedded Artemis server stopped unexpectedly in the background. Attempting to restart...");
                try {
                    // Attempt to cleanly stop the instance first to clear any lingering resources
                    try {
                        embeddedActiveMQ.stop();
                    } catch (Exception ignored) {
                    }
                    embeddedActiveMQ.start();
                    log.info("Successfully restarted Embedded Artemis server.");
                } catch (Exception restartException) {
                    log.error("CRITICAL: Failed to restart Embedded Artemis server. Terminating application...", restartException);
                    if (applicationContext instanceof ConfigurableApplicationContext) {
                        ((ConfigurableApplicationContext) applicationContext).close();
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
	
	@PreDestroy
	public void stop() throws Exception {
		shuttingDown = true;
		
		if (monitorExecutor != null) {
			monitorExecutor.shutdownNow();
		}
		
		if (embeddedActiveMQ != null) {
			embeddedActiveMQ.stop();
		}
		
		if (webServer != null) {
			try {
				webServer.stop();
			}
			catch (Exception e) {
				log.error("Failed to stop Artemis Web Console", e);
			}
		}
	}
}
