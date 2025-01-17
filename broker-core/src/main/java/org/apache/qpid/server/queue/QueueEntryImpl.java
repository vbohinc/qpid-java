/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.queue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.consumer.ConsumerImpl;
import org.apache.qpid.server.filter.Filterable;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.MessageDeletedException;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.MessageReference;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.store.MessageEnqueueRecord;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.txn.LocalTransaction;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.StateChangeListener;
import org.apache.qpid.server.util.StateChangeListenerEntry;

public abstract class QueueEntryImpl implements QueueEntry
{
    private static final Logger _log = LoggerFactory.getLogger(QueueEntryImpl.class);

    private final QueueEntryList _queueEntryList;

    private final MessageReference _message;

    private Set<Long> _rejectedBy = null;

    private volatile EntryState _state = AVAILABLE_STATE;

    private static final
        AtomicReferenceFieldUpdater<QueueEntryImpl, EntryState>
            _stateUpdater =
        AtomicReferenceFieldUpdater.newUpdater
        (QueueEntryImpl.class, EntryState.class, "_state");


    private volatile StateChangeListenerEntry<? super QueueEntry, State> _stateChangeListeners;

    private static final
        AtomicReferenceFieldUpdater<QueueEntryImpl, StateChangeListenerEntry>
                _listenersUpdater =
        AtomicReferenceFieldUpdater.newUpdater
        (QueueEntryImpl.class, StateChangeListenerEntry.class, "_stateChangeListeners");


    private static final
        AtomicLongFieldUpdater<QueueEntryImpl>
            _entryIdUpdater =
        AtomicLongFieldUpdater.newUpdater
        (QueueEntryImpl.class, "_entryId");


    private volatile long _entryId;

    private static int REDELIVERED_FLAG = 1;
    private static int PERSISTENT_FLAG = 2;
    private static int MANDATORY_FLAG = 4;
    private static int IMMEDIATE_FLAG = 8;
    private int _flags;
    private long _expiration;

    /** Number of times this message has been delivered */
    private volatile int _deliveryCount = -1;
    private static final AtomicIntegerFieldUpdater<QueueEntryImpl> _deliveryCountUpdater = AtomicIntegerFieldUpdater
                    .newUpdater(QueueEntryImpl.class, "_deliveryCount");

    private final MessageEnqueueRecord _enqueueRecord;


    public QueueEntryImpl(QueueEntryList queueEntryList)
    {
        this(queueEntryList, null, Long.MIN_VALUE, null);
        _state = DELETED_STATE;
    }


    public QueueEntryImpl(QueueEntryList queueEntryList,
                          ServerMessage message,
                          final long entryId,
                          final MessageEnqueueRecord enqueueRecord)
    {
        _queueEntryList = queueEntryList;

        _message = message == null ? null : message.newReference(queueEntryList.getQueue());

        _entryIdUpdater.set(this, entryId);
        populateInstanceProperties();
        _enqueueRecord = enqueueRecord;
    }

    public QueueEntryImpl(QueueEntryList queueEntryList,
                          ServerMessage message,
                          final MessageEnqueueRecord enqueueRecord)
    {
        _queueEntryList = queueEntryList;
        _message = message == null ? null :  message.newReference(queueEntryList.getQueue());
        populateInstanceProperties();
        _enqueueRecord = enqueueRecord;
    }

    private void populateInstanceProperties()
    {
        if(_message != null)
        {
            if(_message.getMessage().isPersistent())
            {
                setPersistent();
            }
            _expiration = _message.getMessage().getExpiration();
        }
    }

    public void setExpiration(long expiration)
    {
        _expiration = expiration;
    }

    public InstanceProperties getInstanceProperties()
    {
        return new EntryInstanceProperties();
    }

    protected void setEntryId(long entryId)
    {
        _entryIdUpdater.set(this, entryId);
    }

    protected long getEntryId()
    {
        return _entryId;
    }

    public AMQQueue getQueue()
    {
        return _queueEntryList.getQueue();
    }

    public ServerMessage getMessage()
    {
        return  _message == null ? null : _message.getMessage();
    }

    public long getSize()
    {
        return getMessage() == null ? 0 : getMessage().getSize();
    }

    public boolean getDeliveredToConsumer()
    {
        return _deliveryCountUpdater.get(this) != -1;
    }

    public boolean expired()
    {
        long expiration = _expiration;
        if (expiration != 0L)
        {
            long now = System.currentTimeMillis();

            return (now > expiration);
        }
        return false;

    }

    public boolean isAvailable()
    {
        return _state == AVAILABLE_STATE;
    }

    public boolean isAcquired()
    {
        return _state.getState() == State.ACQUIRED;
    }

    public boolean acquire()
    {
        return acquire(NON_CONSUMER_ACQUIRED_STATE);
    }

    private class DelayedAcquisitionStateListener implements StateChangeListener<MessageInstance, State>
    {
        private final Runnable _task;
        private final AtomicBoolean _run = new AtomicBoolean();

        private DelayedAcquisitionStateListener(final Runnable task)
        {
            _task = task;
        }

        @Override
        public void stateChanged(final MessageInstance object, final State oldState, final State newState)
        {
            if(newState == State.DELETED || newState == State.DEQUEUED)
            {
                QueueEntryImpl.this.removeStateChangeListener(this);
            }
            else if(acquireOrSteal(null))
            {
                runTask();
            }
        }

        void runTask()
        {
            QueueEntryImpl.this.removeStateChangeListener(this);
            if(_run.compareAndSet(false,true))
            {
                _task.run();
            }
        }
    }

    @Override
    public boolean acquireOrSteal(final Runnable delayedAcquisitionTask)
    {
        boolean acquired = acquire();
        if(!acquired)
        {
            QueueConsumer consumer = getDeliveredConsumer();
            acquired = removeAcquisitionFromConsumer(consumer);
            if(acquired)
            {
                consumer.acquisitionRemoved(this);
            }
            else if(delayedAcquisitionTask != null)
            {
                DelayedAcquisitionStateListener listener = new DelayedAcquisitionStateListener(delayedAcquisitionTask);
                addStateChangeListener(listener);
                if(acquireOrSteal(null))
                {
                    listener.runTask();
                }
            }
        }
        return acquired;
    }

    private boolean acquire(final EntryState state)
    {
        boolean acquired = _stateUpdater.compareAndSet(this, AVAILABLE_STATE, state);

        if(acquired && _stateChangeListeners != null)
        {
            notifyStateChange(State.AVAILABLE, State.ACQUIRED);
        }

        return acquired;
    }

    public boolean acquire(ConsumerImpl sub)
    {
        final boolean acquired = acquire(((QueueConsumer<?>) sub).getOwningState().getLockedState());
        if(acquired)
        {
            _deliveryCountUpdater.compareAndSet(this,-1,0);
            getQueue().incrementUnackedMsgCount(this);
        }
        return acquired;
    }

    @Override
    public boolean lockAcquisition()
    {
        EntryState state = _state;
        if(state instanceof ConsumerAcquiredState)
        {
            LockedAcquiredState lockedState = ((ConsumerAcquiredState) state).getLockedState();
            boolean updated = _stateUpdater.compareAndSet(this, state, lockedState);
            if(updated)
            {
                notifyStateChange(state.getState(), lockedState.getState());
            }
            return updated;
        }
        return state instanceof LockedAcquiredState;
    }

    @Override
    public boolean unlockAcquisition()
    {
        EntryState state = _state;
        if(state instanceof LockedAcquiredState)
        {
            ConsumerAcquiredState unlockedState = ((LockedAcquiredState) state).getUnlockedState();
            boolean updated = _stateUpdater.compareAndSet(this, state, unlockedState);
            if(updated)
            {
                notifyStateChange(state.getState(),unlockedState.getState());
            }
            return updated;
        }
        return false;
    }

    public boolean acquiredByConsumer()
    {

        return (_state instanceof ConsumerAcquiredState) || (_state instanceof LockedAcquiredState);
    }

    @Override
    public ConsumerImpl getAcquiringConsumer()
    {
        ConsumerImpl consumer;
        EntryState state = _state;
        if(state instanceof ConsumerAcquiredState)
        {
            consumer = ((ConsumerAcquiredState)state).getConsumer();
        }
        else if(state instanceof LockedAcquiredState)
        {
            consumer = ((LockedAcquiredState)state).getConsumer();
        }
        else
        {
            consumer = null;
        }
        return consumer;
    }

    @Override
    public boolean isAcquiredBy(ConsumerImpl consumer)
    {
        EntryState state = _state;
        return (state instanceof ConsumerAcquiredState
               && ((ConsumerAcquiredState)state).getConsumer() == consumer)
                || (state instanceof LockedAcquiredState
                    && ((LockedAcquiredState)state).getConsumer() == consumer);
    }

    @Override
    public boolean removeAcquisitionFromConsumer(ConsumerImpl consumer)
    {
        EntryState state = _state;
        if(state instanceof ConsumerAcquiredState
               && ((ConsumerAcquiredState)state).getConsumer() == consumer)
        {
            return _stateUpdater.compareAndSet(this,state,NON_CONSUMER_ACQUIRED_STATE);
        }
        else
        {
            return false;
        }
    }

    public void release()
    {
        EntryState state = _state;

        if((state.getState() == State.ACQUIRED) &&_stateUpdater.compareAndSet(this, state, AVAILABLE_STATE))
        {

            if(state instanceof ConsumerAcquiredState || state instanceof LockedAcquiredState)
            {
                getQueue().decrementUnackedMsgCount(this);
            }

            if(!getQueue().isDeleted())
            {
                getQueue().requeue(this);
                if(_stateChangeListeners != null)
                {
                    notifyStateChange(QueueEntry.State.ACQUIRED, QueueEntry.State.AVAILABLE);
                }

            }
            else if(acquire())
            {
                routeToAlternate(null, null);
            }
        }

    }


    @Override
    public QueueConsumer getDeliveredConsumer()
    {
        EntryState state = _state;
        if (state instanceof ConsumerAcquiredState)
        {
            return (QueueConsumer) ((ConsumerAcquiredState) state).getConsumer();
        }
        else if (state instanceof LockedAcquiredState)
        {
            return (QueueConsumer) ((LockedAcquiredState) state).getConsumer();
        }
        else
        {
            return null;
        }
    }

    public void reject()
    {
        QueueConsumer consumer = getDeliveredConsumer();

        if (consumer != null)
        {
            if (_rejectedBy == null)
            {
                _rejectedBy = new HashSet<Long>();
            }

            _rejectedBy.add(consumer.getConsumerNumber());
        }
        else
        {
            _log.warn("Requesting rejection by null subscriber:" + this);
        }
    }

    public boolean isRejectedBy(ConsumerImpl consumer)
    {

        if (_rejectedBy != null) // We have consumers that rejected this message
        {
            return _rejectedBy.contains(consumer.getConsumerNumber());
        }
        else // This message hasn't been rejected yet.
        {
            return false;
        }
    }

    private boolean dequeue()
    {
        EntryState state = _state;

        while(state.getState() == State.ACQUIRED && !_stateUpdater.compareAndSet(this, state, DEQUEUED_STATE))
        {
            state = _state;
        }

        if(state.getState() == State.ACQUIRED)
        {
            if (state instanceof ConsumerAcquiredState || state instanceof LockedAcquiredState)
            {
                getQueue().decrementUnackedMsgCount(this);
            }

            getQueue().dequeue(this);
            if(_stateChangeListeners != null)
            {
                notifyStateChange(state.getState() , QueueEntry.State.DEQUEUED);
            }
            return true;
        }
        else
        {
            return false;
        }

    }

    private void notifyStateChange(final State oldState, final State newState)
    {
        StateChangeListenerEntry<? super QueueEntry, State> entry = _listenersUpdater.get(this);
        while(entry != null)
        {
            StateChangeListener<? super QueueEntry, State> l = entry.getListener();
            if(l != null)
            {
                l.stateChanged(this, oldState, newState);
            }
            entry = entry.next();
        }
    }

    private boolean dispose()
    {
        EntryState state = _state;

        if(state != DELETED_STATE && _stateUpdater.compareAndSet(this,state,DELETED_STATE))
        {
            _queueEntryList.entryDeleted(this);
            onDelete();
            _message.release();

            return true;
        }
        else
        {
            return false;
        }
    }

    public void delete()
    {
        if(dequeue())
        {
            dispose();
        }
    }

    public int routeToAlternate(final Action<? super MessageInstance> action, ServerTransaction txn)
    {
        final AMQQueue currentQueue = getQueue();
        Exchange<?> alternateExchange = currentQueue.getAlternateExchange();
        boolean autocommit =  txn == null;
        int enqueues;

        if(autocommit)
        {
            txn = new LocalTransaction(getQueue().getVirtualHost().getMessageStore());
        }

        if (alternateExchange != null)
        {
            enqueues = alternateExchange.send(getMessage(),
                                              getMessage().getInitialRoutingAddress(),
                                              getInstanceProperties(),
                                              txn,
                                              action);
        }
        else
        {
            enqueues = 0;
        }

        txn.dequeue(getEnqueueRecord(), new ServerTransaction.Action()
        {
            public void postCommit()
            {
                delete();
            }

            public void onRollback()
            {

            }
        });

        if(autocommit)
        {
            txn.commit();
        }

        return enqueues;
    }

    public boolean isQueueDeleted()
    {
        return getQueue().isDeleted();
    }

    public void addStateChangeListener(StateChangeListener<? super MessageInstance,State> listener)
    {
        StateChangeListenerEntry<? super QueueEntry, State> entry = new StateChangeListenerEntry<>(listener);
        if(!_listenersUpdater.compareAndSet(this,null, entry))
        {
            _listenersUpdater.get(this).add(entry);
        }
    }

    public boolean removeStateChangeListener(StateChangeListener<? super MessageInstance, State> listener)
    {
        StateChangeListenerEntry entry = _listenersUpdater.get(this);
        if(entry != null)
        {
            return entry.remove(listener);
        }
        return false;
    }

    @Override
    public int compareTo(final QueueEntry o)
    {
        QueueEntryImpl other = (QueueEntryImpl)o;
        return getEntryId() > other.getEntryId() ? 1 : getEntryId() < other.getEntryId() ? -1 : 0;
    }

    protected void onDelete()
    {
    }

    public QueueEntryList getQueueEntryList()
    {
        return _queueEntryList;
    }

    public boolean isDeleted()
    {
        return _state.isDispensed();
    }

    public int getDeliveryCount()
    {
        return _deliveryCount == -1 ? 0 : _deliveryCount;
    }

    @Override
    public int getMaximumDeliveryCount()
    {
        return getQueue().getMaximumDeliveryAttempts();
    }

    public void incrementDeliveryCount()
    {
        _deliveryCountUpdater.compareAndSet(this,-1,0);
        _deliveryCountUpdater.incrementAndGet(this);
    }

    public void decrementDeliveryCount()
    {
        _deliveryCountUpdater.decrementAndGet(this);
    }

    @Override
    public Filterable asFilterable()
    {
        return Filterable.Factory.newInstance(getMessage(), getInstanceProperties());
    }

    public String toString()
    {
        return "QueueEntryImpl{" +
                "_entryId=" + _entryId +
                ", _state=" + _state +
                '}';
    }

    @Override
    public boolean resend()
    {
        QueueConsumer sub = getDeliveredConsumer();
        if(sub != null)
        {
            return sub.resend(this);
        }
        return false;
    }

    @Override
    public TransactionLogResource getOwningResource()
    {
        return getQueue();
    }

    public void setRedelivered()
    {
        _flags |= REDELIVERED_FLAG;
    }

    private void setPersistent()
    {
        _flags |= PERSISTENT_FLAG;
    }

    public boolean isRedelivered()
    {
        return (_flags & REDELIVERED_FLAG) != 0;
    }

    @Override
    public MessageReference newMessageReference()
    {
        try
        {
            return getMessage().newReference();
        }
        catch (MessageDeletedException mde)
        {
            return null;
        }
    }

    private class EntryInstanceProperties implements InstanceProperties
    {

        @Override
        public Object getProperty(final Property prop)
        {
            switch(prop)
            {

                case REDELIVERED:
                    return (_flags & REDELIVERED_FLAG) != 0;
                case PERSISTENT:
                    return (_flags & PERSISTENT_FLAG) != 0;
                case MANDATORY:
                    return (_flags & MANDATORY_FLAG) != 0;
                case IMMEDIATE:
                    return (_flags & IMMEDIATE_FLAG) != 0;
                case EXPIRATION:
                    return _expiration;
                default:
                    throw new IllegalArgumentException("Unknown property " + prop);
            }
        }

    }

    @Override
    public MessageEnqueueRecord getEnqueueRecord()
    {
        return _enqueueRecord;
    }
}
