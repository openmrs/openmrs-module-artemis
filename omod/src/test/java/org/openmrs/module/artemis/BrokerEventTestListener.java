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

import org.openmrs.event.broker.BrokerEventListener;
import org.openmrs.event.broker.BrokerIncomingEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BrokerEventTestListener {

    public static final String TEST_QUEUE = "integration.test.topic";
    public static final String RETRY_QUEUE = "integration.test.retry.topic";
    public static final String DLQ_TEST_QUEUE = "integration.test.dlq.topic";

    private CountDownLatch latch = new CountDownLatch(1);

    private List<BrokerIncomingEvent<?>> receivedEvents = new CopyOnWriteArrayList<>();
    private AtomicInteger attempts = new AtomicInteger(0);
    private AtomicInteger dlqAttempts = new AtomicInteger(0);

    @BrokerEventListener(value = TEST_QUEUE, broker = Artemis.BROKER_ID)
    public void brokerEvent(BrokerIncomingEvent<String> event) {
        this.receivedEvents.add(event);
        latch.countDown();
    }

    @BrokerEventListener(value = TEST_QUEUE, broker = Artemis.BROKER_ID)
    public void brokerSameEvent(BrokerIncomingEvent<String> event) {
        this.receivedEvents.add(event);
        latch.countDown();
    }

    @BrokerEventListener(value = RETRY_QUEUE, broker = Artemis.BROKER_ID)
    public void brokerRetryEvent(BrokerIncomingEvent<String> event) {
        attempts.incrementAndGet();
        if (attempts.get() < 3) {
            throw new RuntimeException("Simulated failure");
        }
        this.receivedEvents.add(event);
        latch.countDown();
    }

    @BrokerEventListener(value = DLQ_TEST_QUEUE, broker = Artemis.BROKER_ID)
    public void brokerDlqTestEvent(BrokerIncomingEvent<String> event) {
        dlqAttempts.incrementAndGet();
        throw new RuntimeException("Simulated permanent failure");
    }

    @BrokerEventListener(value = "DLQ::" + DLQ_TEST_QUEUE + ".DLQ", broker = Artemis.BROKER_ID)
    public void brokerDlqEvent(BrokerIncomingEvent<String> event) {
        this.receivedEvents.add(event);
        latch.countDown();
    }

    public List<BrokerIncomingEvent<?>> getReceivedEvents() {
        return receivedEvents;
    }

    public void resetEventsAndLatch(int count) {
        this.receivedEvents.clear();
        this.latch = new CountDownLatch(count);
        this.attempts.set(0);
        this.dlqAttempts.set(0);
    }

    public boolean await(int timeout, TimeUnit unit) throws InterruptedException {
        return latch.await(timeout, unit);
    }

    public int getAttempts() {
        return attempts.get();
    }

    public int getDlqAttempts() {
        return dlqAttempts.get();
    }
}
