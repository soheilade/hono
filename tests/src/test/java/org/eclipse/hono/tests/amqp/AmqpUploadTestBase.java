/*******************************************************************************
 * Copyright (c) 2016, 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.tests.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.service.management.tenant.Adapter;
import org.eclipse.hono.service.management.tenant.Tenant;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.tests.Tenants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.EventConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

/**
 * Base class for the AMQP adapter integration tests.
 */
public abstract class AmqpUploadTestBase extends AmqpAdapterTestBase {

    private static final long DEFAULT_TEST_TIMEOUT = 15000; // ms
    private static final String DEVICE_PASSWORD = "device-password";

    private ProtonSender sender;
    private MessageConsumer consumer;

    /**
     * Perform additional checks on a received message.
     * <p>
     * This default implementation does nothing. Subclasses should override this method to implement
     * reasonable checks.
     * 
     * @param ctx The test context.
     * @param msg The message to perform checks on.
     */
    protected void assertAdditionalMessageProperties(final VertxTestContext ctx, final Message msg) {
        // empty
    }

    /**
     * Creates a test specific message consumer.
     *
     * @param tenantId        The tenant to create the consumer for.
     * @param messageConsumer The handler to invoke for every message received.
     * @return A future succeeding with the created consumer.
     */
    protected abstract Future<MessageConsumer> createConsumer(String tenantId, Consumer<Message> messageConsumer);

    /**
     * Gets the endpoint name.
     * 
     * @return The name of the endpoint.
     */
    protected abstract String getEndpointName();

    /**
     * Logs a message before running a test case.
     * 
     * @param testInfo Meta information about the test being run.
     */
    @BeforeEach
    public void before(final TestInfo testInfo) {
        log.info("running {}", testInfo.getDisplayName());
    }

    /**
     * Disconnect the AMQP 1.0 client connected to the AMQP Adapter and close senders and consumers.
     * Also delete all random tenants and devices generated during the execution of a test case.
     * 
     * @param context The Vert.x test context.
     */
    @AfterEach
    public void after(final VertxTestContext context) {
        helper.deleteObjects(context);
        close(context);
    }

    /**
     * Verifies that a message containing a payload which has the <em>emtpy notification</em>
     * content type is rejected by the adapter.
     * 
     * @param context The Vert.x context for running asynchronous tests.
     * @throws InterruptedException if test is interrupted while running.
     */
    @Test
    @Timeout(timeUnit = TimeUnit.SECONDS, value = 10)
    public void testAdapterRejectsBadInboundMessage(final VertxTestContext context) throws InterruptedException {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);

        final VertxTestContext setup = new VertxTestContext();

        setupProtocolAdapter(tenantId, deviceId, false)
        .map(s -> {
            sender = s;
            return s;
        })
        .setHandler(setup.completing());

        assertThat(setup.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            context.failNow(setup.causeOfFailure());
        }

        final Message msg = ProtonHelper.message("some payload");
        msg.setContentType(EventConstants.CONTENT_TYPE_EMPTY_NOTIFICATION);
        msg.setAddress(getEndpointName());
        sender.send(msg, delivery -> {

            context.verify(() -> {
                assertThat(delivery.getRemoteState()).isInstanceOf(Rejected.class);
                final Rejected rejected = (Rejected) delivery.getRemoteState();
                final ErrorCondition error = rejected.getError();
                assertThat((Object) error.getCondition()).isEqualTo(Constants.AMQP_BAD_REQUEST);
            });
            context.completeNow();
        });

    }

    /**
     * Verifies that the AMQP Adapter rejects (closes) AMQP links that contain a target address.
     * 
     * @param context The Vert.x test context.
     */
    @Test
    public void testAnonymousRelayRequired(final VertxTestContext context) {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String username = IntegrationTestSupport.getUsername(deviceId, tenantId);
        final String targetAddress = String.format("%s/%s/%s", getEndpointName(), tenantId, deviceId);

        final Tenant tenant = new Tenant();
        helper.registry
        .addDeviceForTenant(tenantId, tenant, deviceId, DEVICE_PASSWORD)
        // connect and create sender (with a valid target address)
        .compose(ok -> connectToAdapter(username, DEVICE_PASSWORD))
        .compose(con -> {
            this.connection = con;
            return createProducer(targetAddress);
        })
        .setHandler(context.failing(t -> {
            log.info("failed to open sender", t);
            context.completeNow();
        }));
    }

    /**
     * Verifies that a number of messages published through the AMQP adapter can be successfully consumed by
     * applications connected to the AMQP messaging network.
     *
     * @param ctx The Vert.x test context.
     * @throws InterruptedException if test is interrupted while running.
     */
    @Test
    public void testUploadMessagesUsingSaslPlain(final VertxTestContext ctx) throws InterruptedException {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);

        final VertxTestContext setup = new VertxTestContext();
        setupProtocolAdapter(tenantId, deviceId, false)
        .map(s -> {
            sender = s;
            return s;
        })
        .setHandler(setup.completing());

        assertThat(setup.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
        }

        testUploadMessages(tenantId, ctx);
    }

    /**
     * Verifies that a number of messages uploaded to the AMQP adapter using client certificate
     * based authentication can be successfully consumed via the AMQP Messaging Network.
     *
     * @param ctx The test context.
     * @throws InterruptedException if test execution is interrupted.
     */
    @Test
    public void testUploadMessagesUsingSaslExternal(final VertxTestContext ctx) throws InterruptedException {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);

        final SelfSignedCertificate deviceCert = SelfSignedCertificate.create(UUID.randomUUID().toString());

        final VertxTestContext setup = new VertxTestContext();

        helper.getCertificate(deviceCert.certificatePath())
        .compose(cert -> {
            final var tenant = Tenants.createTenantForTrustAnchor(cert);
            return helper.registry.addDeviceForTenant(tenantId, tenant, deviceId, cert);
        })
        .compose(ok -> connectToAdapter(deviceCert))
        .compose(con -> createProducer(null))
        .map(s -> {
            sender = s;
            return s;
        })
        .setHandler(setup.completing());

        assertThat(setup.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
        }

        testUploadMessages(tenantId, ctx);
    }

    //------------------------------------------< private methods >---

    private void testUploadMessages(final String tenantId, final VertxTestContext ctx) throws InterruptedException {

        final Function<Handler<Void>, Future<Void>> receiver = callback -> {
            return createConsumer(tenantId, msg -> {
                assertAdditionalMessageProperties(ctx, msg);
                callback.handle(null);
            }).map(c -> {
                consumer = c;
                return null;
            });
        };

        doUploadMessages(ctx, receiver, payload -> {

            final Message msg = ProtonHelper.message(payload);
            msg.setAddress(getEndpointName());
            final Future<?> sendingComplete = Future.future();
            final Handler<ProtonSender> sendMsgHandler = replenishedSender -> {
                replenishedSender.send(msg, delivery -> {
                    if (Accepted.class.isInstance(delivery.getRemoteState())) {
                        sendingComplete.complete();
                    } else {
                        sendingComplete.fail(new IllegalStateException("peer did not accept message"));
                    }
                });
            };
            context.runOnContext(go -> {
                if (sender.getCredit() <= 0) {
                    sender.sendQueueDrainHandler(sendMsgHandler);
                } else {
                    sendMsgHandler.handle(sender);
                }
            });
            return sendingComplete;
        });
    }

    /**
     * Upload a number of messages to Hono's Telemetry/Event APIs.
     * 
     * @param context The Vert.x test context.
     * @param receiverFactory The factory to use for creating the receiver for consuming
     *                        messages from the messaging network.
     * @param sender The sender for sending messaging to the Hono server.
     * @throws InterruptedException if test execution is interrupted.
     */
    protected void doUploadMessages(
            final VertxTestContext context,
            final Function<Handler<Void>, Future<Void>> receiverFactory,
            final Function<String, Future<?>> sender) throws InterruptedException {

        final CountDownLatch remainingMessages = new CountDownLatch(IntegrationTestSupport.MSG_COUNT);
        final AtomicInteger messagesSent = new AtomicInteger(0);

        final VertxTestContext receiverCreation = new VertxTestContext();

        receiverFactory.apply(msgReceived -> {
            remainingMessages.countDown();
            if (remainingMessages.getCount() % 200 == 0) {
                log.info("messages received: {}", IntegrationTestSupport.MSG_COUNT - remainingMessages.getCount());
            }
        })
        .setHandler(receiverCreation.completing());

        assertThat(receiverCreation.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        if (receiverCreation.failed()) {
            context.failNow(receiverCreation.causeOfFailure());
        }

        while (messagesSent.get() < IntegrationTestSupport.MSG_COUNT) {

            final int msgNo = messagesSent.getAndIncrement();
            final String payload = "temp: " + msgNo;

            final CountDownLatch msgSent = new CountDownLatch(1);

            sender.apply(payload).setHandler(sendAttempt -> {
                if (sendAttempt.failed()) {
                    if (sendAttempt.cause() instanceof ServerErrorException &&
                            ((ServerErrorException) sendAttempt.cause()).getErrorCode() == HttpURLConnection.HTTP_UNAVAILABLE) {
                        // no credit available
                        // do not expect this message to be received
                        log.info("skipping message no {}, no credit", msgNo);
                        remainingMessages.countDown();
                    } else {
                        log.info("error sending message no {}", msgNo, sendAttempt.cause());
                    }
                }
                msgSent.countDown();
            });
            msgSent.await();
            if (messagesSent.get() % 200 == 0) {
                log.info("messages sent: {}", messagesSent.get());
            }
        }

        final long timeToWait = Math.max(DEFAULT_TEST_TIMEOUT, Math.round(IntegrationTestSupport.MSG_COUNT * 1.2));
        if (remainingMessages.await(timeToWait, TimeUnit.MILLISECONDS)) {
            context.completeNow();
        } else {
            context.failNow(new IllegalStateException("did not receive all uploaded messages"));
        }
    }


    /**
     * Gets the QoS to use for sending messages.
     * 
     * @return The QoS
     */
    protected abstract ProtonQoS getProducerQoS();

    /**
     * Sets up the protocol adapter by doing the following:
     * <ul>
     * <li>Add a device (with credentials) for the tenant identified by the given tenantId.</li>
     * <li>Create an AMQP 1.0 client and authenticate it to the protocol adapter with username: {@code [device-id@tenantId]}.</li>
     * <li>After a successful connection, create a producer/sender for sending messages to the protocol adapter.</li>
     * </ul>
     *
     * @param tenantId The tenant to register with the device registry.
     * @param deviceId The device to add to the tenant identified by tenantId.
     * @param disableTenant If true, disable the protocol adapter for the tenant.
     * 
     * @return A future succeeding with the created sender.
     */
    private Future<ProtonSender> setupProtocolAdapter(final String tenantId, final String deviceId, final boolean disableTenant) {

        final String username = IntegrationTestSupport.getUsername(deviceId, tenantId);

        final Tenant tenant = new Tenant();
        if (disableTenant) {
            tenant.addAdapterConfig(new Adapter(Constants.PROTOCOL_ADAPTER_TYPE_AMQP).setEnabled(false));
        }

        return helper.registry
                .addDeviceForTenant(tenantId, tenant, deviceId, DEVICE_PASSWORD)
                .compose(ok -> connectToAdapter(username, DEVICE_PASSWORD))
                .compose(con -> createProducer(null)).recover(t -> {
                    log.error("error setting up AMQP protocol adapter", t);
                    return Future.failedFuture(t);
                });
    }

    private void close(final VertxTestContext ctx) {

        final Future<ProtonConnection> connectionTracker = Future.future();
        final Future<ProtonSender> senderTracker = Future.future();
        final Future<Void> receiverTracker = Future.future();

        if (sender == null) {
            senderTracker.complete();
        } else {
            context.runOnContext(go -> {
                sender.closeHandler(senderTracker);
                sender.close();
            });
        }

        if (consumer == null) {
            receiverTracker.complete();
        } else {
            consumer.close(receiverTracker);
        }

        if (connection == null || connection.isDisconnected()) {
            connectionTracker.complete();
        } else {
            context.runOnContext(go -> {
                connection.closeHandler(connectionTracker);
                connection.close();
            });
        }

        CompositeFuture.join(connectionTracker, senderTracker, receiverTracker).setHandler(c -> {
           context = null;
           ctx.completeNow();
        });
    }
}
