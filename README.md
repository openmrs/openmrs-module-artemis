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
The module can be configured via your `openmrs-runtime.properties` file. If no properties are provided, the module defaults to running an embedded in-process broker with no TCP port exposed and the web console disabled.

| Property | Default     | Description                                                                                                                                                                                                                                                              |
| :--- |:------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `artemis.embedded.enabled` | `true`      | Set to `false` to disable the embedded broker (e.g., if you are connecting to an external broker).                                                                                                                                                                       |
| `artemis.embedded.port` | `0`         | The TCP port for the embedded broker. `0` automatically assigns a random free port.                                                                                                                                                                                      |
| `artemis.embedded.console.enabled` | `false`     | Set to `true` to enable the embedded web management console.                                                                                                                                                                                                             |
| `artemis.embedded.console.port` | `8161`      | The HTTP port for the web management console.                                                                                                                                                                                                                            |
| `artemis.embedded.console.host` | `127.0.0.1` | The host/address the console binds to. Defaults to loopback; set to 0.0.0.0 or a specific interface to expose remotely (not recommended).                                                                                                                                |
| `artemis.user` | *(none)*    | Username for broker TCP authentication. If left empty, no network acceptor is opened and the embedded broker remains in-vm only.                                                                                                                                         |
| `artemis.password` | *(none)*    | Password for broker TCP authentication. If left empty, no network acceptor is opened and the embedded broker remains in-vm only.                                                                                                                                         |
| `artemis.embedded.console.user` | *(none)*    | Username for the embedded web console. If left empty, falls back to `artemis.user`. Set this to authenticate the console independently of the broker TCP port.                                                                                                          |
| `artemis.embedded.console.password` | *(none)*    | Password for the embedded web console. If left empty, falls back to `artemis.password`.                                                                                                                                                                                 |
| `artemis.uri` | *(auto)*    | The URI used to connect to the broker. If using an external broker, set this to your broker's URI (e.g., `tcp://external-host:61616`).                                                                                                                                   |
| `artemis.send.callTimeout` | `10000`     | Milliseconds any blocking call on the connection (session creation, transacted commits, sends) will wait before throwing an exception. Set to a positive value (e.g., `5000`) to fail fast; use `0` or a negative value to keep the Artemis client default of 30 000 ms. |

### Event delivery semantics

By default, the module publishes events **synchronously and outside any database transaction coordination**. This means:

- An event sent inside a DB transaction reaches consumers **even if the transaction later rolls back** — there is no JMS/DB two-phase commit.
- If the broker is unavailable at send time, an `ArtemisException` propagates directly into the calling thread. Nothing retries automatically.
- In tests annotated with `@Transactional` (including `BaseModuleContextSensitiveTest`), the test transaction rolls back after each test, but any events published during the test are already delivered to listeners — listeners fire regardless of the transaction outcome.

If your workflow requires transactionally-consistent event delivery (event persisted and retried alongside the DB commit), consider OpenMRS Core's outbox mechanism (`OutboxEventInterceptor` + `@OutboxEventListener`) instead of publishing directly to this broker.

### Advanced broker configuration

Low-level broker settings (e.g., `maxDiskUsage`, `diskScanPeriod`) can be passed as runtime properties prefixed with `artemis.broker.`. For example:

```properties
artemis.broker.maxDiskUsage=85
```

### Back-pressure and disk-full behavior

The embedded broker sets `DiskFullMessagePolicy.FAIL` to prevent producer threads from blocking indefinitely when the disk fills up. When the data directory crosses `artemis.broker.maxDiskUsage` (default 90 %), the broker rejects new producer credit requests immediately rather than parking the producer thread. Without this policy, a full disk blocks the calling thread until space is freed — `artemis.send.callTimeout` does not protect against this because producer credits are acquired before the packet the timeout bounds.

Multi-node deployments
----------------------

For multi-node (HA) deployments, you must use an **external broker** shared by all nodes. Do not point multiple OpenMRS instances at the same embedded broker data directory — the second node will block indefinitely waiting to acquire the journal lock.

Set `artemis.embedded.enabled=false` on all nodes and configure `artemis.uri` to point at your shared external Artemis instance. With an external broker, queues are anycast by default, meaning each event is delivered to exactly one node; this is usually the desired behaviour for HA.

Note that the DLQ and redelivery settings the module applies automatically (max delivery attempts, exponential backoff, dead-letter routing) apply only to the embedded broker. When using an external broker, configure equivalent address settings server-side.

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

> **Note:** Listener methods run on JMS consumer threads with no OpenMRS session — the same situation as a plain Spring `@EventListener`. Calling `Context.*` APIs directly will throw `APIException: A user context must first be passed to setUserContext()`. Wrap any OpenMRS API calls in `Context.openSession()` / `Context.closeSession()` (or use `Daemon.runInDaemonThread`) as you would in any background thread.

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

Operations
----------

### Dead Letter Queues

After 10 consecutive failed deliveries (with exponential backoff up to 30 s), a message is moved to `<source>.DLQ` and no longer redelivered. From OpenMRS's point of view the message disappears unless something actively watches the DLQ.

**Monitoring:** DLQ depth can be observed via the embedded web console (enable with `artemis.embedded.console.enabled=true`) or via JMX, which is always enabled. Watch for the broker log warning `AMQ222149: Sending message to Dead Letter Address` as a signal that a message has been parked.

**Reprocessing in code:** Subscribe to a dead-letter queue using the Fully Qualified Queue Name (FQQN) syntax:

```java
@BrokerEventListener(value = "DLQ::my.custom.queue.DLQ", broker = Artemis.BROKER_ID)
public void handleDeadLetter(BrokerIncomingEvent<String> event) {
    // inspect, log, or reprocess the dead-lettered payload
}
```

**Reprocessing via console:** Use the embedded web console or an external Artemis management tool to move messages from the DLQ back to the original queue for redelivery.

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
