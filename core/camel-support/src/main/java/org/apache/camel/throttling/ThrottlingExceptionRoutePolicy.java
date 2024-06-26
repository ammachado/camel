/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.throttling;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.spi.Configurer;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.support.RoutePolicySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modeled after the circuit breaker {@link ThrottlingInflightRoutePolicy} this {@link RoutePolicy} will stop consuming
 * from an endpoint based on the type of exceptions that are thrown and the threshold setting.
 *
 * The scenario: if a route cannot process data from an endpoint due to problems with resources used by the route (ie
 * database down) then it will stop consuming new messages from the endpoint by stopping the consumer. The
 * implementation is comparable to the Circuit Breaker pattern. After a set amount of time, it will move to a half open
 * state and attempt to determine if the consumer can be started. There are two ways to determine if a route can be
 * closed after being opened (1) start the consumer and check the failure threshold (2) call the
 * {@link ThrottlingExceptionHalfOpenHandler} The second option allows a custom check to be performed without having to
 * take on the possibility of multiple messages from the endpoint. The idea is that a handler could run a simple test
 * (ie select 1 from dual) to determine if the processes that cause the route to be open are now available
 */
@Metadata(label = "bean",
          description = "A throttle based RoutePolicy which is modelled after the circuit breaker and will stop consuming"
                        + " from an endpoint based on the type of exceptions that are thrown and the threshold settings.",
          annotations = { "interfaceName=org.apache.camel.spi.RoutePolicy" })
@Configurer(metadataOnly = true)
public class ThrottlingExceptionRoutePolicy extends RoutePolicySupport implements CamelContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(ThrottlingExceptionRoutePolicy.class);

    private static final int STATE_CLOSED = 0;
    private static final int STATE_HALF_OPEN = 1;
    private static final int STATE_OPEN = 2;

    private CamelContext camelContext;
    private final Lock lock = new ReentrantLock();

    // configuration
    @Metadata(description = "How many failed messages within the window would trigger the circuit breaker to open")
    private int failureThreshold;
    @Metadata(description = "Sliding window for how long time to go back (in millis) when counting number of failures")
    private long failureWindow;
    @Metadata(description = "Interval (in millis) for how often to check whether a currently open circuit breaker may work again")
    private long halfOpenAfter;
    @Metadata(description = "Allows to only throttle based on certain types of exceptions. Multiple exceptions (use FQN class name) can be separated by comma.")
    private String exceptions;
    private List<Class<?>> throttledExceptions;
    // handler for half open circuit can be used instead of resuming route to check on resources
    @Metadata(label = "advanced",
              description = "Custom check to perform whether the circuit breaker can move to half-open state."
                            + " If set then this is used instead of resuming the route.")
    private ThrottlingExceptionHalfOpenHandler halfOpenHandler;

    // stateful information
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger state = new AtomicInteger(STATE_CLOSED);
    private final AtomicBoolean keepOpen = new AtomicBoolean();
    private volatile Timer halfOpenTimer;
    private volatile long lastFailure;
    private volatile long openedAt;

    public ThrottlingExceptionRoutePolicy() {
    }

    public ThrottlingExceptionRoutePolicy(int threshold, long failureWindow, long halfOpenAfter,
                                          List<Class<?>> handledExceptions) {
        this(threshold, failureWindow, halfOpenAfter, handledExceptions, false);
    }

    public ThrottlingExceptionRoutePolicy(int threshold, long failureWindow, long halfOpenAfter,
                                          List<Class<?>> handledExceptions, boolean keepOpen) {
        this.throttledExceptions = handledExceptions;
        this.failureWindow = failureWindow;
        this.halfOpenAfter = halfOpenAfter;
        this.failureThreshold = threshold;
        this.keepOpen.set(keepOpen);
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    public List<Class<?>> getThrottledExceptions() {
        return throttledExceptions;
    }

    public String getExceptions() {
        return exceptions;
    }

    public void setExceptions(String exceptions) {
        this.exceptions = exceptions;
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();

        var list = new ArrayList<Class<?>>();
        if (exceptions != null && throttledExceptions == null) {
            for (String fqn : exceptions.split(",")) {
                Class<?> clazz = camelContext.getClassResolver().resolveMandatoryClass(fqn);
                list.add(clazz);
            }
        }
        this.throttledExceptions = list;
    }

    @Override
    public void onInit(Route route) {
        LOG.debug("Initializing ThrottlingExceptionRoutePolicy route policy");
        logState();
    }

    @Override
    public void onStart(Route route) {
        // if keepOpen then start w/ the circuit open
        if (keepOpen.get()) {
            openCircuit(route);
        }
    }

    @Override
    protected void doStop() throws Exception {
        Timer timer = halfOpenTimer;
        if (timer != null) {
            timer.cancel();
            halfOpenTimer = null;
        }
    }

    @Override
    public void onExchangeDone(Route route, Exchange exchange) {
        if (keepOpen.get()) {
            if (state.get() != STATE_OPEN) {
                LOG.debug("Opening circuit (keepOpen is true)");
                openCircuit(route);
            }
        } else {
            if (hasFailed(exchange)) {
                // record the failure
                failures.incrementAndGet();
                lastFailure = System.currentTimeMillis();
            } else {
                success.incrementAndGet();
            }

            // check for state change
            calculateState(route);
        }
    }

    /**
     * Uses similar approach as circuit breaker if the exchange has an exception that we are watching then we count that
     * as a failure otherwise we ignore it
     */
    private boolean hasFailed(Exchange exchange) {
        if (exchange == null) {
            return false;
        }

        boolean answer = false;

        if (exchange.getException() != null) {
            if (throttledExceptions == null || throttledExceptions.isEmpty()) {
                // if no exceptions defined then always fail
                // (ie) assume we throttle on all exceptions
                answer = true;
            } else {
                for (Class<?> exception : throttledExceptions) {
                    // will look in exception hierarchy
                    if (exchange.getException(exception) != null) {
                        answer = true;
                        break;
                    }
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            String exceptionName
                    = exchange.getException() == null ? "none" : exchange.getException().getClass().getSimpleName();
            LOG.debug("hasFailed ({}) with Throttled Exception: {} for exchangeId: {}", answer, exceptionName,
                    exchange.getExchangeId());
        }
        return answer;
    }

    private void calculateState(Route route) {

        // have we reached the failure limit?
        boolean failureLimitReached = isThresholdExceeded();

        if (state.get() == STATE_CLOSED) {
            if (failureLimitReached) {
                LOG.debug("Opening circuit...");
                openCircuit(route);
            }
        } else if (state.get() == STATE_HALF_OPEN) {
            if (failureLimitReached) {
                LOG.debug("Opening circuit...");
                openCircuit(route);
            } else {
                LOG.debug("Closing circuit...");
                closeCircuit(route);
            }
        } else if (state.get() == STATE_OPEN) {
            if (!keepOpen.get()) {
                long elapsedTimeSinceOpened = System.currentTimeMillis() - openedAt;
                if (halfOpenAfter <= elapsedTimeSinceOpened) {
                    LOG.debug("Checking an open circuit...");
                    if (halfOpenHandler != null) {
                        if (halfOpenHandler.isReadyToBeClosed()) {
                            LOG.debug("Closing circuit...");
                            closeCircuit(route);
                        } else {
                            LOG.debug("Opening circuit...");
                            openCircuit(route);
                        }
                    } else {
                        LOG.debug("Half opening circuit...");
                        halfOpenCircuit(route);
                    }
                } else {
                    LOG.debug("Keeping circuit open (time not elapsed)...");
                }
            } else {
                LOG.debug("Keeping circuit open (keepOpen is true)...");
                this.addHalfOpenTimer(route);
            }
        }

    }

    protected boolean isThresholdExceeded() {
        boolean output = false;
        logState();
        // failures exceed the threshold
        // AND the last of those failures occurred within window
        if (failures.get() >= failureThreshold && lastFailure >= System.currentTimeMillis() - failureWindow) {
            output = true;
        }

        return output;
    }

    protected void openCircuit(Route route) {
        try {
            lock.lock();
            suspendOrStopConsumer(route.getConsumer());
            state.set(STATE_OPEN);
            openedAt = System.currentTimeMillis();
            this.addHalfOpenTimer(route);
            logState();
        } catch (Exception e) {
            handleException(e);
        } finally {
            lock.unlock();
        }
    }

    protected void addHalfOpenTimer(Route route) {
        halfOpenTimer = new Timer();
        halfOpenTimer.schedule(new HalfOpenTask(route), halfOpenAfter);
    }

    protected void halfOpenCircuit(Route route) {
        try {
            lock.lock();
            resumeOrStartConsumer(route.getConsumer());
            state.set(STATE_HALF_OPEN);
            logState();
        } catch (Exception e) {
            handleException(e);
        } finally {
            lock.unlock();
        }
    }

    protected void closeCircuit(Route route) {
        try {
            lock.lock();
            resumeOrStartConsumer(route.getConsumer());
            failures.set(0);
            success.set(0);
            lastFailure = 0;
            openedAt = 0;
            state.set(STATE_CLOSED);
            logState();
        } catch (Exception e) {
            handleException(e);
        } finally {
            lock.unlock();
        }
    }

    private void logState() {
        if (LOG.isDebugEnabled()) {
            LOG.debug(dumpState());
        }
    }

    public String getStateAsString() {
        return stateAsString(state.get());
    }

    public String dumpState() {
        String routeState = getStateAsString();
        if (failures.get() > 0) {
            return String.format("State %s, failures %d, last failure %d ms ago", routeState, failures.get(),
                    System.currentTimeMillis() - lastFailure);
        } else {
            return String.format("State %s, failures %d", routeState, failures.get());
        }
    }

    private static String stateAsString(int num) {
        if (num == STATE_CLOSED) {
            return "closed";
        } else if (num == STATE_HALF_OPEN) {
            return "half opened";
        } else {
            return "opened";
        }
    }

    class HalfOpenTask extends TimerTask {
        private final Route route;

        HalfOpenTask(Route route) {
            this.route = route;
        }

        @Override
        public void run() {
            halfOpenTimer.cancel();
            calculateState(route);
        }
    }

    public ThrottlingExceptionHalfOpenHandler getHalfOpenHandler() {
        return halfOpenHandler;
    }

    public void setHalfOpenHandler(ThrottlingExceptionHalfOpenHandler halfOpenHandler) {
        this.halfOpenHandler = halfOpenHandler;
    }

    public boolean getKeepOpen() {
        return this.keepOpen.get();
    }

    public void setKeepOpen(boolean keepOpen) {
        this.keepOpen.set(keepOpen);
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getFailureWindow() {
        return failureWindow;
    }

    public void setFailureWindow(long failureWindow) {
        this.failureWindow = failureWindow;
    }

    public long getHalfOpenAfter() {
        return halfOpenAfter;
    }

    public void setHalfOpenAfter(long halfOpenAfter) {
        this.halfOpenAfter = halfOpenAfter;
    }

    public int getFailures() {
        return failures.get();
    }

    public int getSuccess() {
        return success.get();
    }

    public long getLastFailure() {
        return lastFailure;
    }

    public long getOpenedAt() {
        return openedAt;
    }

}
