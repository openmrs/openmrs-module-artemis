Artemis Module
==========================

Description
-----------
The Artemis module provides an embedded Apache ActiveMQ Artemis broker for OpenMRS. 
It seamlessly integrates with the OpenMRS Event Module, allowing you to publish and subscribe to events using JMS, with support for features like redelivery, dead letter queues (DLQ), and a built-in web management console.

Features
--------
* **Embedded Broker**: Runs a lightweight, high-performance ActiveMQ Artemis broker directly within OpenMRS (zero-config by default).
* **Web Console**: Includes an embedded Hawtio-based web console for monitoring queues and connections.
* **OpenMRS Event Integration**: Fully compatible with the OpenMRS Event framework (`BrokerOutgoingEvent`, `BrokerIncomingEvent`, and `@BrokerEventListener`).
* **DLQ & Retry Mechanisms**: Auto-configured Dead Letter Queues (`.DLQ`) and exponential backoff for failed message deliveries.
* **Health Watchdog**: Monitors the embedded broker in the background and automatically restarts it if it crashes unexpectedly.
* **Header Propagation**: Preserves message headers end-to-end between OpenMRS events and JMS messages.
* **External Broker Support**: Can be configured to connect to a standalone/external ActiveMQ Artemis instance instead of running the embedded one.

Requirements
------------
The module requires OpenMRS Core 2.9+ and Java 17.

Configuration
-------------
The module can be configured via your `openmrs-runtime.properties` file. If no properties are provided, the module defaults to running an embedded broker on a random free TCP port with the web console enabled on port `8161`.

| Property | Default     | Description                                                                                                                                                                                                                                                              |
| :--- |:------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `artemis.embedded.enabled` | `true`      | Set to `false` to disable the embedded broker (e.g., if you are connecting to an external broker).                                                                                                                                                                       |
| `artemis.embedded.port` | `0`         | The TCP port for the embedded broker. `0` automatically assigns a random free port.                                                                                                                                                                                      |
| `artemis.embedded.console.enabled` | `false`     | Set to `true` to enable the embedded web management console.                                                                                                                                                                                                             |
| `artemis.embedded.console.port` | `8161`      | The HTTP port for the web management console.                                                                                                                                                                                                                            |
| `artemis.embedded.console.host` | `127.0.0.1` | The host/address the console binds to. Defaults to loopback; set to 0.0.0.0 or a specific interface to expose remotely (not recommended).                                                                                                                                |
| `artemis.user` | *(none)*    | Username for broker authentication and/or embedded console. If left empty, no network acceptor is opened and the embedded broker remains in-vm only.                                                                                                                     |
| `artemis.password` | *(none)*    | Password for broker authentication and/or embedded console. If left empty, no network acceptor is opened and the embedded broker remains in-vm only.                                                                                                                     |
| `artemis.uri` | *(auto)*    | The URI used to connect to the broker. If using an external broker, set this to your broker's URI (e.g., `tcp://external-host:61616`).                                                                                                                                   |
| `artemis.send.callTimeout` | `10000`     | Milliseconds any blocking call on the connection (session creation, transacted commits, sends) will wait before throwing an exception. Set to a positive value (e.g., `5000`) to fail fast; use `0` or a negative value to keep the Artemis client default of 30 000 ms. |

### Advanced broker configuration

Low-level broker settings (e.g., `maxDiskUsage`, `diskScanPeriod`) can be passed as runtime properties prefixed with `artemis.broker.`. For example:

```properties
artemis.broker.maxDiskUsage=85
```

### Back-pressure and disk-full behavior

The embedded broker sets `DiskFullMessagePolicy.FAIL` to prevent producer threads from blocking indefinitely when the disk fills up. When the data directory crosses `artemis.broker.maxDiskUsage` (default 90 %), the broker rejects new producer credit requests immediately rather than parking the producer thread. Without this policy, a full disk blocks the calling thread until space is freed — `artemis.send.callTimeout` does not protect against this because producer credits are acquired before the packet the timeout bounds.

Troubleshooting
---------------

### `AMQ219058: Address "QUEUE_NAME" is full`

This error (`javax.jms.JMSException: AMQ219058: Address "QUEUE_NAME" is full`) means the broker's disk has crossed the `maxDiskUsage` threshold (default 90 %) and is refusing new messages to prevent the disk from filling completely.

**Option 1 — Free up disk space.** Remove unneeded files from the volume that holds the OpenMRS application data directory until disk usage drops below the threshold. The broker will resume accepting messages automatically once the next disk scan detects the freed space (scan interval is controlled by `artemis.broker.diskScanPeriod`, default 5 000 ms).

**Option 2 — Raise the threshold.** Add the following to `openmrs-runtime.properties` to allow the broker to use more of the disk before blocking:

```properties
artemis.broker.maxDiskUsage=95
```

Use this only as a short-term measure while you address the underlying disk-space issue.

Usage Examples
--------------

### Publishing an Event
You can publish messages to an Artemis queue by creating a `BrokerOutgoingEvent` and sending it via the `EventPublisher`. Make sure to specify the `Artemis.BROKER_ID` as the target broker.

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
        BrokerOutgoingEvent<String> event = new BrokerOutgoingEvent<>(payload, "my.custom.queue", Artemis.BROKER_ID);
        eventPublisher.publishEvent(event);
    }
}
```

### Listening to an Event
To consume messages from an Artemis queue, annotate a method in a Spring-managed bean with `@BrokerEventListener`.

```java
import org.openmrs.event.broker.BrokerEventListener;
import org.openmrs.event.broker.BrokerIncomingEvent;
import org.springframework.stereotype.Component;

@Component
public class MyEventListener {

    @BrokerEventListener(value = "my.custom.queue", broker = Artemis.BROKER_ID)
    public void handleEvent(BrokerIncomingEvent<String> event) {
        System.out.println("Received message: " + event.getPayload());
    }
}
```

Building from Source
--------------------
You will need to have Java 17+ and Maven 2.x+ installed.  Use the command 'mvn package' to 
compile and package the module.  The .omod file will be in the omod/target folder.

Installation
------------
1. Build the module to produce the .omod file.
2. Use the OpenMRS Administration > Manage Modules screen to upload and install the .omod file.

If uploads are not allowed from the web (changeable via a runtime property), you can drop the omod
into the ~/.OpenMRS/modules folder.  (Where ~/.OpenMRS is assumed to be the Application 
Data Directory that the running openmrs is currently using.)  After putting the file in there 
simply restart OpenMRS/tomcat and the module will be loaded and started.
