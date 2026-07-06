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
import org.openmrs.module.artemis.jaas.ProgrammaticJaasConfiguration;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.security.auth.login.Configuration;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component("artemis.Artemis")
public class Artemis {
	
	private static final Logger log = LoggerFactory.getLogger(Artemis.class);
	
	static final String HAWTIO_AUTHENTICATION_ENABLED = "hawtio.authenticationEnabled";
	
	static final String HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES = "hawtio.authenticationContainerDiscoveryClasses";
	
	static final String HAWTIO_OFFLINE = "hawtio.offline";
	
	static final String HAWTIO_REALM = "hawtio.realm";
	
	static final String HAWTIO_ROLE = "hawtio.role";
	
	static final String HAWTIO_ROLES = "hawtio.roles";
	
	static final String HAWTIO_ROLE_PRINCIPAL_CLASSES = "hawtio.rolePrincipalClasses";
	
	static final String HAWTIO_USER_PRINCIPAL_CLASSES = "hawtio.userPrincipalClasses";
	
	static final String HAWTIO_REALM_NAME = "activemq";
	
	static final String HAWTIO_EMBEDDED_ROLE = "amq";
	
	public static final String BROKER_ID = "artemis";
	
	public static final String ARTEMIS_URI = "artemis.uri";
	
	private EmbeddedActiveMQ embeddedActiveMQ;
	
	private WebServerComponent webServer;
	
	private ScheduledExecutorService monitorExecutor;
	
	private volatile boolean shuttingDown = false;
	
	private volatile int consecutiveRestartFailures = 0;
	
	private ProgrammaticJaasConfiguration jaasConfiguration;
	
	private final ArtemisProperties artemisProperties;
	
	public Artemis(ArtemisProperties artemisProperties) {
		this.artemisProperties = artemisProperties;
	}
	
	public String getUsername() {
		return artemisProperties.getUsername();
	}
	
	public String getPassword() {
		return artemisProperties.getPassword();
	}
	
	public String getBrokerUri() {
		if (embeddedActiveMQ != null) {
			return "vm://0"; //Use in-vm
		} else {
			String brokerUri = Context.getRuntimeProperties().getProperty(ARTEMIS_URI);
			if (brokerUri == null) {
				throw new IllegalStateException(
				        "Artemis: embedded broker is disabled but no external broker URI configured. "
				                + "Set artemis.embedded.enabled=true or configure artemis.uri with your external broker URI (e.g., tcp://host:61616)");
			}
			return brokerUri;
		}
	}
	
	@PostConstruct
	public void start() throws Exception {
		if (artemisProperties.getEmbeddedEnabled()) {
			// Clear flags in case this instance is restarted after stop()
			shuttingDown = false;
			consecutiveRestartFailures = 0;
			try {
				String username = artemisProperties.getUsername();
				String password = artemisProperties.getPassword();
				boolean hasCredentials = StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password);
				
				ConfigurationImpl config = new ConfigurationImpl().addAcceptorConfiguration("in-vm", "vm://0");
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

				// Parse broker-level config from runtime properties with artemis.broker. prefix.
				config.parsePrefixedProperties(Context.getRuntimeProperties(), "artemis.broker.");
				
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
						
						configureHawtioAuthentication(username, password, hasCredentials);
						
						WebServerDTO webServerDTO = new WebServerDTO();
						// Bind the embedded console to the configured host (defaults to loopback) to avoid exposing it on all interfaces
						webServerDTO.bind = "http://" + artemisProperties.getConsoleHost() + ":" + artemisProperties.getConsolePort();
						webServerDTO.path = "console";
						
						AppDTO app = new AppDTO();
						app.url = "console";
						app.war = "console.war"; // The console WAR file name
						
						webServerDTO.apps = new ArrayList<>();
						webServerDTO.apps.add(app);
						
						webServer = new WebServerComponent();
						// We use your dataDir as the home/instance dir where Artemis will look for the WAR file
						webServer.configure(webServerDTO, dataDir, dataDir);
						// Use the Artemis module classloader as context classloader so Jetty's webapp
						// classloader inherits only jakarta.servlet classes, not any javax.servlet-based
						// Hawtio version that another module (e.g. openmrs-module-camel) may have loaded.
						ClassLoader savedClassLoader = Thread.currentThread().getContextClassLoader();
						try {
							Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
							webServer.start();
						} finally {
							Thread.currentThread().setContextClassLoader(savedClassLoader);
						}
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
	
	void configureHawtioAuthentication(String username, String password, boolean hasCredentials) {
		clearHawtioAuthenticationProperties();
		
		if (hasCredentials) {
			// Hawtio 4.7 auto-discovers Tomcat auth providers unless discovery is disabled explicitly.
			// Our embedded console runs on Jetty with a programmatic JAAS realm instead.
			try {
				ProgrammaticJaasConfiguration cfg = new ProgrammaticJaasConfiguration(HAWTIO_REALM_NAME, username, password,
				        HAWTIO_EMBEDDED_ROLE);
				jaasConfiguration = cfg;
				Configuration.setConfiguration(cfg);
				System.setProperty(HAWTIO_AUTHENTICATION_ENABLED, "true");
				System.setProperty(HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES, "");
				System.setProperty(HAWTIO_REALM, HAWTIO_REALM_NAME);
				System.setProperty(HAWTIO_ROLE, HAWTIO_EMBEDDED_ROLE);
				System.setProperty(HAWTIO_ROLES, HAWTIO_EMBEDDED_ROLE);
				System.setProperty(HAWTIO_ROLE_PRINCIPAL_CLASSES, "org.openmrs.module.artemis.jaas.RolePrincipal");
				System.setProperty(HAWTIO_USER_PRINCIPAL_CLASSES, "org.openmrs.module.artemis.jaas.UserPrincipal");
			}
			catch (Exception e) {
				log.warn("Failed to configure programmatic JAAS. Artemis Web Console login might fail.", e);
			}
		} else {
			// Bypass Hawtio's JAAS authentication requirement for embedded setups
			System.setProperty(HAWTIO_AUTHENTICATION_ENABLED, "false");
		}
		
		System.setProperty(HAWTIO_OFFLINE, "true");
	}
	
	private void clearHawtioAuthenticationProperties() {
		System.clearProperty(HAWTIO_AUTHENTICATION_ENABLED);
		System.clearProperty(HAWTIO_AUTHENTICATION_CONTAINER_DISCOVERY_CLASSES);
		System.clearProperty(HAWTIO_REALM);
		System.clearProperty(HAWTIO_ROLE);
		System.clearProperty(HAWTIO_ROLES);
		System.clearProperty(HAWTIO_ROLE_PRINCIPAL_CLASSES);
		System.clearProperty(HAWTIO_USER_PRINCIPAL_CLASSES);
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
                    consecutiveRestartFailures = 0; // Reset failure counter on successful restart
                    log.info("Successfully restarted Embedded Artemis server.");
                } catch (Exception restartException) {
                    consecutiveRestartFailures++;
                    log.error("Failed to restart Embedded Artemis server (attempt {}). Manual intervention required.", 
                              consecutiveRestartFailures, restartException);
                    
                    // After 3 consecutive failures, stop attempting automatic restarts
                    if (consecutiveRestartFailures >= 3) {
                        log.error("CRITICAL: Artemis broker has failed {} times and automatic restarts have been disabled. " +
                                 "Manual intervention required to resolve the broker issue.", consecutiveRestartFailures);
                        monitorExecutor.shutdownNow();
                    }
                }
            } else {
                // Reset failure counter when broker is running
                if (consecutiveRestartFailures > 0) {
                    consecutiveRestartFailures = 0;
                    log.info("Artemis broker health restored.");
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
		
		if (jaasConfiguration != null) {
			Configuration.setConfiguration(jaasConfiguration.getPreviousConfiguration());
			jaasConfiguration = null;
		}
	}
}
