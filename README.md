Artemis Module
==========================

Description
-----------
The Artemis module provides an embedded Apache ActiveMQ Artemis broker for OpenMRS. 
It seamlessly integrates with the OpenMRS Event Module, allowing you to publish and subscribe to events using JMS, with support for features like redelivery, dead letter queues (DLQ), and a built-in web management console.

Features
--------
* **Embedded Broker**: Runs a lightweight, high-performance ActiveMQ Artemis broker directly within OpenMRS (zero-config by default).
* **Web Console**: Includes an embedded Hawtio-based web console for monitoring queues, topics, and connections.
* **OpenMRS Event Integration**: Fully compatible with the OpenMRS Event framework (`BrokerOutgoingEvent`, `BrokerIncomingEvent`, and `@BrokerEventListener`).
* **DLQ & Retry Mechanisms**: Auto-configured Dead Letter Queues (`.DLQ`) and exponential backoff for failed message deliveries.
* **Health Watchdog**: Monitors the embedded broker in the background and automatically restarts it if it crashes unexpectedly.
* **Header Propagation**: Preserves message headers end-to-end between OpenMRS events and JMS messages.
* **External Broker Support**: Can be configured to connect to a standalone/external ActiveMQ Artemis instance instead of running the embedded one.

Configuration
-------------
The module can be configured via your `openmrs-runtime.properties` file. If no properties are provided, the module defaults to running an embedded broker on a random free TCP port with the web console enabled on port `8161`.

| Property | Default    | Description                                                                                                                                            |
| :--- |:-----------|:-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `artemis.embedded.enabled` | `true`     | Set to `false` to disable the embedded broker (e.g., if you are connecting to an external broker).                                                     |
| `artemis.embedded.port` | `0`        | The TCP port for the embedded broker. `0` automatically assigns a random free port.                                                                    |
| `artemis.console.enabled` | `true`     | Set to `false` to disable the embedded web management console.                                                                                         |
| `artemis.console.port` | `8161`     | The HTTP port for the web management console.                                                                                                          |
| `artemis.user` | 'admin'    | Username for broker authentication and/or embedded console (disabled authentication if empty).                                                         |
| `artemis.password` | 'Admin123' | Password for broker authentication and/or embedded consolre (disabled authentication if empty).                                                        |
| `artemis.uri` | *(auto)*   | The URI used to connect to the broker. If using an external broker, set this to your broker's URI (e.g., `tcp://external-host:61616`).                 |

Usage Examples
--------------

### Publishing an Event
You can publish messages to an Artemis queue or topic by creating a `BrokerOutgoingEvent` and sending it via the `EventPublisher`. Make sure to specify the `Artemis.BROKER_ID` as the target broker.

```java
import org.openmrs.event.EventPublisher;
import org.openmrs.event.broker.BrokerOutgoingEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MyService {
    
    @Autowired
    private EventPublisher eventPublisher;

    public void notifySomethingHappened() {
        String payload = "Hello from OpenMRS!";
        BrokerOutgoingEvent<String> event = new BrokerOutgoingEvent<>(payload, "my.custom.topic", Artemis.BROKER_ID);
        eventPublisher.publishEvent(event);
    }
}
```

### Listening to an Event
To consume messages from an Artemis topic or queue, annotate a method in a Spring-managed bean with `@BrokerEventListener`.

```java
import org.openmrs.event.broker.BrokerEventListener;
import org.openmrs.event.broker.BrokerIncomingEvent;
import org.springframework.stereotype.Component;

@Component
public class MyEventListener {

    @BrokerEventListener(value = "my.custom.topic", broker = Artemis.BROKER_ID)
    public void handleEvent(BrokerIncomingEvent<String> event) {
        System.out.println("Received message: " + event.getPayload());
    }
}
```

Building from Source
--------------------
You will need to have Java 1.8+ and Maven 2.x+ installed.  Use the command 'mvn package' to 
compile and package the module.  The .omod file will be in the omod/target folder.

Installation
------------
1. Build the module to produce the .omod file.
2. Use the OpenMRS Administration > Manage Modules screen to upload and install the .omod file.

If uploads are not allowed from the web (changeable via a runtime property), you can drop the omod
into the ~/.OpenMRS/modules folder.  (Where ~/.OpenMRS is assumed to be the Application 
Data Directory that the running openmrs is currently using.)  After putting the file in there 
simply restart OpenMRS/tomcat and the module will be loaded and started.
