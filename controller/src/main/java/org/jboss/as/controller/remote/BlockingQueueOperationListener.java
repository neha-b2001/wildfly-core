/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.remote;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.OperationResponse;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.threads.AsyncFuture;

/**
 * Basic operation listener backed by a blocking queue. If the limit of the queue is reached prepared operations
 * are going to be rolled back automatically.
 *
 * @param <T> the operation type
 */
public class BlockingQueueOperationListener<T extends TransactionalProtocolClient.Operation> implements TransactionalProtocolClient.TransactionalOperationListener<T> {

    private final BlockingQueue<TransactionalProtocolClient.PreparedOperation<T>> queue;

    public BlockingQueueOperationListener() {
        this(new LinkedBlockingQueue<TransactionalProtocolClient.PreparedOperation<T>>());
    }

    public BlockingQueueOperationListener(final int capacity) {
        this(new ArrayBlockingQueue<TransactionalProtocolClient.PreparedOperation<T>>(capacity));
    }

    public BlockingQueueOperationListener(final BlockingQueue<TransactionalProtocolClient.PreparedOperation<T>> queue) {
        this.queue = queue;
    }

    @Override
    public void operationPrepared(final TransactionalProtocolClient.PreparedOperation<T> prepared) {
        ControllerLogger.MGMT_OP_LOGGER.tracef("received prepared operation  %s", prepared);
        if(! queue.offer(prepared)) {
            prepared.rollback();
        }
    }

    @Override
    public void operationFailed(T operation, ModelNode result) {
        ControllerLogger.MGMT_OP_LOGGER.tracef("received failed operation  %s", operation);
        queue.offer(new FailedOperation<T>(operation, result));
    }

    @Override
    public void operationComplete(T operation, OperationResponse result) {
        //
    }

    /**
     * Retrieves and removes the head of the underlying queue, waiting if necessary until an element becomes available.
     *
     * @return the prepared operation
     * @throws InterruptedException
     */
    public TransactionalProtocolClient.PreparedOperation<T> retrievePreparedOperation() throws InterruptedException {
        return queue.take();
    }

    protected void drainTo(final Collection<TransactionalProtocolClient.PreparedOperation<T>> collection) {
        if(!queue.isEmpty()) {
            queue.drainTo(collection);
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time if necessary for an element to become available.
     *
     * @param timeout the timeout
     * @param timeUnit the time unit
     * @return the prepared operation
     * @throws InterruptedException
     */
    public TransactionalProtocolClient.PreparedOperation<T> retrievePreparedOperation(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
        return queue.poll(timeout, timeUnit);
    }

    public static class FailedOperation<T extends TransactionalProtocolClient.Operation> implements TransactionalProtocolClient.PreparedOperation<T> {

        private final T operation;
        private final ModelNode finalResult;
        private final boolean timedOut;

        /**
         * Create a failed operation.
         *
         * @param operation the operation
         * @param t the throwable
         * @param <T> the operation type
         * @return the failed operation
         */
        public static <T extends TransactionalProtocolClient.Operation> TransactionalProtocolClient.PreparedOperation<T> create(final T operation, final Throwable t) {
            final String failureDescription = t.getLocalizedMessage() == null ? t.getClass().getName() : t.getLocalizedMessage();
            return create(operation, failureDescription);
        }

        /**
         * Create a failed operation.
         *
         * @param operation the operation
         * @param failureDescription the failure description
         * @param <T> the operation type
         * @return the failed operation
         */
        public static <T extends TransactionalProtocolClient.Operation> TransactionalProtocolClient.PreparedOperation<T> create(final T operation, final String failureDescription) {
            final ModelNode failedResult = new ModelNode();
            failedResult.get(ModelDescriptionConstants.OUTCOME).set(ModelDescriptionConstants.FAILED);
            failedResult.get(FAILURE_DESCRIPTION).set(failureDescription);
            return new FailedOperation<T>(operation, failedResult, false);
        }

        private static boolean isTimeoutFailureDescription(final ModelNode response) {
            boolean result = response.hasDefined(FAILURE_DESCRIPTION);
            if (result) {
                result = response.get(FAILURE_DESCRIPTION).asString().startsWith("WFLYCTL0409");
            }
            return result;
        }

        public FailedOperation(final T operation, final ModelNode finalResult) {
            this(operation, finalResult, isTimeoutFailureDescription(finalResult));
        }

        public FailedOperation(final T operation, final ModelNode finalResult, final boolean timedOut) {
            this.operation = operation;
            this.finalResult = finalResult;
            this.timedOut = timedOut;
        }

        @Override
        public T getOperation() {
            return operation;
        }

        @Override
        public ModelNode getPreparedResult() {
            return finalResult;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public boolean isFailed() {
            return true;
        }

        @Override
        public boolean isTimedOut() {
            return timedOut;
        }

        @Override
        public AsyncFuture<OperationResponse> getFinalResult() {
            return new CompletedFuture<>(OperationResponse.Factory.createSimple(finalResult));
        }

        @Override
        public void commit() {
            throw new IllegalStateException();
        }

        @Override
        public void rollback() {
            throw new IllegalStateException();
        }

    }

}
