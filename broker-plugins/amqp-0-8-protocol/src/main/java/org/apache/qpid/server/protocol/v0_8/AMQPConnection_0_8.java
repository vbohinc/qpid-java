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
package org.apache.qpid.server.protocol.v0_8;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.security.auth.Subject;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.AMQConnectionException;
import org.apache.qpid.QpidException;
import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.codec.ServerDecoder;
import org.apache.qpid.common.ServerPropertyNames;
import org.apache.qpid.configuration.CommonProperties;
import org.apache.qpid.framing.*;
import org.apache.qpid.properties.ConnectionStartProperties;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.protocol.ConnectionClosingTicker;
import org.apache.qpid.server.security.*;
import org.apache.qpid.server.transport.AbstractAMQPConnection;
import org.apache.qpid.server.transport.MultiVersionProtocolEngine;
import org.apache.qpid.server.transport.ProtocolEngine;
import org.apache.qpid.server.configuration.BrokerProperties;
import org.apache.qpid.server.consumer.ConsumerImpl;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.messages.ConnectionMessages;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.port.AmqpPort;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.security.auth.SubjectAuthenticationResult;
import org.apache.qpid.server.store.StoreException;
import org.apache.qpid.server.transport.ServerNetworkConnection;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;
import org.apache.qpid.server.util.ServerScopedRuntimeException;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;
import org.apache.qpid.transport.ByteBufferSender;
import org.apache.qpid.transport.TransportException;
import org.apache.qpid.transport.network.AggregateTicker;

public class AMQPConnection_0_8
        extends AbstractAMQPConnection<AMQPConnection_0_8>
        implements ServerMethodProcessor<ServerChannelMethodProcessor>
{

    enum ConnectionState
    {
        INIT,
        AWAIT_START_OK,
        AWAIT_SECURE_OK,
        AWAIT_TUNE_OK,
        AWAIT_OPEN,
        OPEN
    }

    private static final Logger _logger = LoggerFactory.getLogger(AMQPConnection_0_8.class);

    private static final String BROKER_DEBUG_BINARY_DATA_LENGTH = "broker.debug.binaryDataLength";
    private static final int DEFAULT_DEBUG_BINARY_DATA_LENGTH = 80;

    private static final long CLOSE_OK_TIMEOUT = 10000l;

    private final AtomicBoolean _stateChanged = new AtomicBoolean();
    private final AtomicReference<Action<ProtocolEngine>> _workListener = new AtomicReference<>();

    private volatile VirtualHostImpl<?,?,?> _virtualHost;

    private final Object _channelAddRemoveLock = new Object();
    private final Map<Integer, AMQChannel> _channelMap = new ConcurrentHashMap<>();

    private ConnectionState _state = ConnectionState.INIT;

    /**
     * The channels that the latest call to {@link ProtocolEngine#received(QpidByteBuffer)} applied to.
     * Used so we know which channels we need to call {@link AMQChannel#receivedComplete()}
     * on after handling the frames.
     */
    private final Set<AMQChannel> _channelsForCurrentMessage = Collections.newSetFromMap(new ConcurrentHashMap<AMQChannel, Boolean>());

    private final ServerDecoder _decoder;

    private volatile SaslServer _saslServer;

    private volatile long _maxNoOfChannels;

    private volatile ProtocolVersion _protocolVersion;
    private volatile MethodRegistry _methodRegistry;

    private final Queue<Action<? super AMQPConnection_0_8>> _asyncTaskList =
            new ConcurrentLinkedQueue<>();

    private final Map<Integer, Long> _closingChannelsList = new ConcurrentHashMap<>();
    private volatile ProtocolOutputConverter _protocolOutputConverter;

    private final Object _reference = new Object();

    private volatile int _maxFrameSize;
    private final AtomicBoolean _orderlyClose = new AtomicBoolean(false);

    private final ServerNetworkConnection _network;
    private final ByteBufferSender _sender;

    private volatile boolean _deferFlush;
    /** Guarded by _channelAddRemoveLock */
    private boolean _blocking;

    private volatile boolean _closeWhenNoRoute;
    private volatile boolean _compressionSupported;
    private volatile int _messageCompressionThreshold;

    /**
     * QPID-6744 - Older queue clients (<=0.32) set the nowait flag false on the queue.delete method and then
     * incorrectly await regardless.  If we detect an old Qpid client, we send the queue.delete-ok response regardless
     * of the queue.delete flag request made by the client.
     */
    private volatile boolean _sendQueueDeleteOkRegardless;
    private final Pattern _sendQueueDeleteOkRegardlessClientVerRegexp;

    private volatile int _currentClassId;
    private volatile int _currentMethodId;
    private final int _binaryDataLimit;
    private final long _maxMessageSize;
    private volatile boolean _transportBlockedForWriting;

    public AMQPConnection_0_8(Broker<?> broker,
                              ServerNetworkConnection network,
                              AmqpPort<?> port,
                              Transport transport,
                              Protocol protocol,
                              long connectionId,
                              AggregateTicker aggregateTicker)
    {
        super(broker, network, port, transport, protocol, connectionId, aggregateTicker);


        _maxNoOfChannels = broker.getConnection_sessionCountLimit();
        _decoder = new BrokerDecoder(this);
        _binaryDataLimit = getBroker().getContextKeys(false).contains(BROKER_DEBUG_BINARY_DATA_LENGTH)
                ? getBroker().getContextValue(Integer.class, BROKER_DEBUG_BINARY_DATA_LENGTH)
                : DEFAULT_DEBUG_BINARY_DATA_LENGTH;
        String sendQueueDeleteOkRegardlessRegexp = getBroker().getContextKeys(false).contains(Broker.SEND_QUEUE_DELETE_OK_REGARDLESS_CLIENT_VER_REGEXP)
                ? getBroker().getContextValue(String.class, Broker.SEND_QUEUE_DELETE_OK_REGARDLESS_CLIENT_VER_REGEXP): "";
        _sendQueueDeleteOkRegardlessClientVerRegexp = Pattern.compile(sendQueueDeleteOkRegardlessRegexp);

        int maxMessageSize = port.getContextValue(Integer.class, AmqpPort.PORT_MAX_MESSAGE_SIZE);
        _maxMessageSize = (maxMessageSize > 0) ? (long) maxMessageSize : Long.MAX_VALUE;

        _network = network;
        _sender = network.getSender();
        _closeWhenNoRoute = getBroker().getConnection_closeWhenNoRoute();

        logConnectionOpen();
    }

    @Override
    public boolean isTransportBlockedForWriting()
    {
        return _transportBlockedForWriting;
    }

    @Override
    public void setTransportBlockedForWriting(final boolean blocked)
    {
        if(_transportBlockedForWriting != blocked)
        {
            _transportBlockedForWriting = blocked;
            for (AMQChannel channel : _channelMap.values())
            {
                channel.transportStateChanged();
            }
        }
    }

    public void setMaxFrameSize(int frameMax)
    {
        _maxFrameSize = frameMax;
        _decoder.setMaxFrameSize(frameMax);
    }

    public long getMaxFrameSize()
    {
        return _maxFrameSize;
    }

    private int getDefaultMaxFrameSize()
    {
        Broker<?> broker = getBroker();

        // QPID-6784 : Some old clients send payload with size equals to max frame size
        // we want to fit those frames into the network buffer
        return broker.getNetworkBufferSize() - AMQFrame.getFrameOverhead();
    }

    public boolean isClosing()
    {
        return _orderlyClose.get();
    }

    public ClientDeliveryMethod createDeliveryMethod(int channelId)
    {
        return new WriteDeliverMethod(channelId);
    }

    public void received(final QpidByteBuffer msg)
    {
        AccessController.doPrivileged(new PrivilegedAction<Void>()
        {
            @Override
            public Void run()
            {
                updateLastReadTime();

                try
                {
                    _decoder.decodeBuffer(msg);
                    receivedCompleteAllChannels();
                }
                catch (AMQFrameDecodingException | IOException e)
                {
                    _logger.error("Unexpected exception", e);
                    throw new ConnectionScopedRuntimeException(e);
                }
                catch (StoreException e)
                {
                    if (_virtualHost.getState() == State.ACTIVE)
                    {
                        throw new ServerScopedRuntimeException(e);
                    }
                    else
                    {
                        throw new ConnectionScopedRuntimeException(e);
                    }
                }
                return null;
            }
        }, getAccessControllerContext());

    }

    private void receivedCompleteAllChannels()
    {
        RuntimeException exception = null;

        for (AMQChannel channel : _channelsForCurrentMessage)
        {
            try
            {
                channel.receivedComplete();
            }
            catch(RuntimeException exceptionForThisChannel)
            {
                if(exception == null)
                {
                    exception = exceptionForThisChannel;
                }
                _logger.error("Error informing channel that receiving is complete. Channel: " + channel,
                              exceptionForThisChannel);
            }
        }

        _channelsForCurrentMessage.clear();

        if(exception != null)
        {
            throw exception;
        }
    }


    void channelRequiresSync(final AMQChannel amqChannel)
    {
        _channelsForCurrentMessage.add(amqChannel);
    }

    private synchronized void protocolInitiationReceived(ProtocolInitiation pi)
    {
        // this ensures the codec never checks for a PI message again
        _decoder.setExpectProtocolInitiation(false);
        try
        {
            ProtocolVersion pv = pi.checkVersion(); // Fails if not correct
            setProtocolVersion(pv);

            StringBuilder mechanismBuilder = new StringBuilder();
            SubjectCreator subjectCreator = getPort().getAuthenticationProvider().getSubjectCreator(getTransport().isSecure());
            for(String mechanismName : subjectCreator.getMechanisms())
            {
                if(mechanismBuilder.length() != 0)
                {
                    mechanismBuilder.append(' ');
                }
                mechanismBuilder.append(mechanismName);
            }
            String mechanisms = mechanismBuilder.toString();

            String locales = "en_US";


            FieldTable serverProperties = FieldTableFactory.newFieldTable();

            serverProperties.setString(ServerPropertyNames.PRODUCT,
                    CommonProperties.getProductName());
            serverProperties.setString(ServerPropertyNames.VERSION,
                    CommonProperties.getReleaseVersion());
            serverProperties.setString(ServerPropertyNames.QPID_BUILD,
                    CommonProperties.getBuildVersion());
            serverProperties.setString(ServerPropertyNames.QPID_INSTANCE_NAME,
                    getBroker().getName());
            serverProperties.setString(ConnectionStartProperties.QPID_CLOSE_WHEN_NO_ROUTE,
                    String.valueOf(_closeWhenNoRoute));
            serverProperties.setString(ConnectionStartProperties.QPID_MESSAGE_COMPRESSION_SUPPORTED,
                                       String.valueOf(getBroker().isMessageCompressionEnabled()));
            serverProperties.setString(ConnectionStartProperties.QPID_CONFIRMED_PUBLISH_SUPPORTED, Boolean.TRUE.toString());
            serverProperties.setString(ConnectionStartProperties.QPID_VIRTUALHOST_PROPERTIES_SUPPORTED, String.valueOf(getBroker().isVirtualHostPropertiesNodeEnabled()));


            AMQMethodBody responseBody = getMethodRegistry().createConnectionStartBody((short) getProtocolMajorVersion(),
                                                                                       (short) pv.getActualMinorVersion(),
                                                                                       serverProperties,
                                                                                       mechanisms.getBytes(),
                                                                                       locales.getBytes());
            writeFrame(responseBody.generateFrame(0));
            _state = ConnectionState.AWAIT_START_OK;

            _sender.flush();

        }
        catch (QpidException e)
        {
            _logger.debug("Received unsupported protocol initiation for protocol version: {} ", getProtocolVersion());

            writeFrame(new ProtocolInitiation(ProtocolVersion.getLatestSupportedVersion()));
            _sender.flush();
        }
    }

    public synchronized void writeFrame(AMQDataBlock frame)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("SEND: " + frame);
        }

        try
        {
            frame.writePayload(_sender);
        }
        catch (IOException e)
        {
            throw new ServerScopedRuntimeException(e);
        }


        updateLastWriteTime();

        if(!_deferFlush)
        {
            _sender.flush();
        }
    }

    public AMQChannel getChannel(int channelId)
    {
        final AMQChannel channel = _channelMap.get(channelId);
        if ((channel == null) || channel.isClosing())
        {
            return null;
        }
        else
        {
            return channel;
        }
    }

    public boolean channelAwaitingClosure(int channelId)
    {
        return !_closingChannelsList.isEmpty() && _closingChannelsList.containsKey(channelId);
    }

    private void addChannel(AMQChannel channel)
    {
        synchronized (_channelAddRemoveLock)
        {
            _channelMap.put(channel.getChannelId(), channel);
            sessionAdded(channel);
            if(_blocking)
            {
                channel.block();
            }
        }
    }

    private void removeChannel(int channelId)
    {
        AMQChannel session;
        synchronized (_channelAddRemoveLock)
        {
            session = _channelMap.remove(channelId);
        }
        sessionRemoved(session);
    }

    public long getMaximumNumberOfChannels()
    {
        return _maxNoOfChannels;
    }

    private void setMaximumNumberOfChannels(Long value)
    {
        _maxNoOfChannels = value;
    }


    void closeChannel(AMQChannel channel)
    {
        closeChannel(channel, null, null, false);
    }

    public void closeChannelAndWriteFrame(AMQChannel channel, AMQConstant cause, String message)
    {
        writeFrame(new AMQFrame(channel.getChannelId(),
                                getMethodRegistry().createChannelCloseBody(cause.getCode(),
                                                                           AMQShortString.validValueOf(message),
                                                                           _currentClassId,
                                                                           _currentMethodId)));
        closeChannel(channel, cause, message, true);
    }

    public void closeChannel(int channelId, AMQConstant cause, String message)
    {
        final AMQChannel channel = getChannel(channelId);
        if (channel == null)
        {
            throw new IllegalArgumentException("Unknown channel id");
        }
        closeChannel(channel, cause, message, true);
    }

    void closeChannel(AMQChannel channel, AMQConstant cause, String message, boolean mark)
    {
        int channelId = channel.getChannelId();
        try
        {
            channel.close(cause, message);
            if(mark)
            {
                markChannelAwaitingCloseOk(channelId);
            }
        }
        finally
        {
            removeChannel(channelId);
        }
    }


    public void closeChannelOk(int channelId)
    {
        _closingChannelsList.remove(channelId);
    }

    private void markChannelAwaitingCloseOk(int channelId)
    {
        _closingChannelsList.put(channelId, System.currentTimeMillis());
    }

    private void initHeartbeats(int delay)
    {
        if (delay > 0)
        {
            _network.setMaxWriteIdleMillis(1000L * delay);
            _network.setMaxReadIdleMillis(1000L * BrokerProperties.HEARTBEAT_TIMEOUT_FACTOR * delay);
        }
        else
        {
            _network.setMaxWriteIdleMillis(0);
            _network.setMaxReadIdleMillis(0);
        }
    }

    private void closeAllChannels()
    {
        try
        {
            RuntimeException firstException = null;
            for (AMQChannel channel : getSessionModels())
            {
                try
                {
                    channel.close();
                }
                catch (RuntimeException re)
                {
                    if (!(re instanceof ConnectionScopedRuntimeException))
                    {
                        _logger.error("Unexpected exception closing channel", re);
                    }
                    firstException = re;
                }
            }

            if (firstException != null)
            {
                throw firstException;
            }
        }
        finally
        {
            synchronized (_channelAddRemoveLock)
            {
                _channelMap.clear();
            }
        }
    }

    private void completeAndCloseAllChannels()
    {
        try
        {
            receivedCompleteAllChannels();
        }
        finally
        {
            closeAllChannels();
        }
    }

    void sendConnectionClose(AMQConstant errorCode,
                             String message, int channelId)
    {
        sendConnectionClose(channelId, new AMQFrame(0, new ConnectionCloseBody(getProtocolVersion(), errorCode.getCode(), AMQShortString.validValueOf(message), _currentClassId, _currentMethodId)));
    }

    private void sendConnectionClose(int channelId, AMQFrame frame)
    {
        if (_orderlyClose.compareAndSet(false, true))
        {
            try
            {
                markChannelAwaitingCloseOk(channelId);
                completeAndCloseAllChannels();
            }
            finally
            {
                try
                {
                    writeFrame(frame);
                }
                finally
                {
                    final long timeoutTime = System.currentTimeMillis() + CLOSE_OK_TIMEOUT;
                    getAggregateTicker().addTicker(new ConnectionClosingTicker(timeoutTime, _network));
                }
            }
        }
    }

    public void closeNetworkConnection()
    {
        _network.close();
    }

    @Override
    public String toString()
    {
        return _network.getRemoteAddress() + "(" + ((getAuthorizedPrincipal() == null ? "?" : getAuthorizedPrincipal().getName()) + ")");
    }

    private String getLocalFQDN()
    {
        SocketAddress address = _network.getLocalAddress();
        if (address instanceof InetSocketAddress)
        {
            return ((InetSocketAddress) address).getHostName();
        }
        else
        {
            throw new IllegalArgumentException("Unsupported socket address class: " + address);
        }
    }

    private SaslServer getSaslServer()
    {
        return _saslServer;
    }

    private void setSaslServer(SaslServer saslServer)
    {
        _saslServer = saslServer;
    }

    public boolean isSendQueueDeleteOkRegardless()
    {
        return _sendQueueDeleteOkRegardless;
    }

    void setSendQueueDeleteOkRegardless(boolean sendQueueDeleteOkRegardless)
    {
        _sendQueueDeleteOkRegardless = sendQueueDeleteOkRegardless;
    }

    private void setClientProperties(FieldTable clientProperties)
    {
        if (clientProperties != null)
        {
            String closeWhenNoRoute = clientProperties.getString(ConnectionStartProperties.QPID_CLOSE_WHEN_NO_ROUTE);
            if (closeWhenNoRoute != null)
            {
                _closeWhenNoRoute = Boolean.parseBoolean(closeWhenNoRoute);
                _logger.debug("Client set closeWhenNoRoute={} for connection {}", _closeWhenNoRoute, this);
            }
            String compressionSupported = clientProperties.getString(ConnectionStartProperties.QPID_MESSAGE_COMPRESSION_SUPPORTED);
            if (compressionSupported != null)
            {
                _compressionSupported = Boolean.parseBoolean(compressionSupported);
                _logger.debug("Client set compressionSupported={} for connection {}", _compressionSupported, this);
            }

            String clientId = clientProperties.getString(ConnectionStartProperties.CLIENT_ID_0_8);
            String clientVersion = clientProperties.getString(ConnectionStartProperties.VERSION_0_8);
            String clientProduct = clientProperties.getString(ConnectionStartProperties.PRODUCT);
            String remoteProcessPid = clientProperties.getString(ConnectionStartProperties.PID);

            boolean mightBeQpidClient = clientProduct != null &&
                                        (clientProduct.toLowerCase().contains("qpid") || clientProduct.toLowerCase() .equals("unknown"));
            boolean sendQueueDeleteOkRegardless = mightBeQpidClient &&(clientVersion == null || _sendQueueDeleteOkRegardlessClientVerRegexp
                    .matcher(clientVersion).matches());

            setSendQueueDeleteOkRegardless(sendQueueDeleteOkRegardless);
            if (sendQueueDeleteOkRegardless)
            {
                _logger.debug("Peer is an older Qpid client, queue delete-ok response will be sent"
                              + " regardless for connection {}", this);
            }

            setClientVersion(clientVersion);
            setClientProduct(clientProduct);
            setRemoteProcessPid(remoteProcessPid);
            setClientId(clientId == null ? UUID.randomUUID().toString() : clientId);
        }
    }

    private void setProtocolVersion(ProtocolVersion pv)
    {
        // TODO MultiVersionProtocolEngine takes responsibility for making the ProtocolVersion determination.
        // These steps could be moved to construction.
        _protocolVersion = pv;
        _methodRegistry = new MethodRegistry(_protocolVersion);
        _protocolOutputConverter = new ProtocolOutputConverterImpl(this);
    }

    public byte getProtocolMajorVersion()
    {
        return _protocolVersion.getMajorVersion();
    }

    public ProtocolVersion getProtocolVersion()
    {
        return _protocolVersion;
    }

    public byte getProtocolMinorVersion()
    {
        return _protocolVersion.getMinorVersion();
    }

    public MethodRegistry getRegistry()
    {
        return getMethodRegistry();
    }

    public VirtualHostImpl<?,?,?> getVirtualHost()
    {
        return _virtualHost;
    }

    public void setVirtualHost(VirtualHostImpl<?,?,?> virtualHost)
    {
        _virtualHost = virtualHost;
        virtualHostAssociated();

        _messageCompressionThreshold = virtualHost.getContextValue(Integer.class,
                                                                   Broker.MESSAGE_COMPRESSION_THRESHOLD_SIZE);
        if(_messageCompressionThreshold <= 0)
        {
            _messageCompressionThreshold = Integer.MAX_VALUE;
        }
        getSubject().getPrincipals().add(virtualHost.getPrincipal());

        updateAccessControllerContext();
        logConnectionOpen();
    }

    public ProtocolOutputConverter getProtocolOutputConverter()
    {
        return _protocolOutputConverter;
    }

    public void setAuthorizedSubject(final Subject authorizedSubject)
    {
        if (authorizedSubject == null)
        {
            throw new IllegalArgumentException("authorizedSubject cannot be null");
        }

        getSubject().getPrincipals().addAll(authorizedSubject.getPrincipals());
        getSubject().getPrivateCredentials().addAll(authorizedSubject.getPrivateCredentials());
        getSubject().getPublicCredentials().addAll(authorizedSubject.getPublicCredentials());

        updateAccessControllerContext();

    }

    public Subject getAuthorizedSubject()
    {
        return getSubject();
    }

    public Principal getAuthorizedPrincipal()
    {

        return getSubject().getPrincipals(AuthenticatedPrincipal.class).size() == 0 ? null : AuthenticatedPrincipal.getAuthenticatedPrincipalFromSubject(getSubject());
    }

    public Principal getPeerPrincipal()
    {
        return _network.getPeerPrincipal();
    }

    public MethodRegistry getMethodRegistry()
    {
        return _methodRegistry;
    }

    public void closed()
    {
        try
        {
            try
            {
                if (!_orderlyClose.get())
                {
                    completeAndCloseAllChannels();
                }
            }
            finally
            {
                if (_virtualHost != null)
                {
                    _virtualHost.deregisterConnection(this);
                }

                performDeleteTasks();
            }
        }
        catch (ConnectionScopedRuntimeException | TransportException e)
        {
            _logger.error("Could not close protocol engine", e);
        }
        finally
        {
            markTransportClosed();

            runAsSubject(new PrivilegedAction<Void>()
            {
                @Override
                public Void run()
                {
                    getEventLogger().message(_orderlyClose.get()
                                                     ? ConnectionMessages.CLOSE()
                                                     : ConnectionMessages.DROPPED_CONNECTION());
                    return null;
                }
            });
        }
    }


    @Override
    public void encryptedTransport()
    {
    }

    public void readerIdle()
    {
        AccessController.doPrivileged(new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                getEventLogger().message(ConnectionMessages.IDLE_CLOSE());
                _network.close();
                return null;
            }
        }, getAccessControllerContext());
    }

    public synchronized void writerIdle()
    {
        writeFrame(HeartbeatBody.FRAME);
    }

    public long getSessionCountLimit()
    {
        return getMaximumNumberOfChannels();
    }

    public String getAddress()
    {
        return String.valueOf(_network.getRemoteAddress());
    }

    public void closeSessionAsync(final AMQSessionModel<?> session, final AMQConstant cause, final String message)
    {
        addAsyncTask(new Action<AMQPConnection_0_8>()
        {

            @Override
            public void performAction(final AMQPConnection_0_8 object)
            {
                int channelId = session.getChannelId();
                closeChannel(channelId, cause, message);

                MethodRegistry methodRegistry = getMethodRegistry();
                ChannelCloseBody responseBody =
                        methodRegistry.createChannelCloseBody(
                                cause.getCode(),
                                AMQShortString.validValueOf(message),
                                0, 0);

                writeFrame(responseBody.generateFrame(channelId));
            }
        });

    }

    @Override
    public void sendConnectionCloseAsync(final AMQConstant cause, final String message)
    {
        Action<AMQPConnection_0_8> action = new Action<AMQPConnection_0_8>()
        {
            @Override
            public void performAction(final AMQPConnection_0_8 object)
            {
                AMQConnectionException e = new AMQConnectionException(cause, message, 0, 0,
                        getMethodRegistry(),
                        null);
                sendConnectionClose(0, e.getCloseFrame());
            }
        };
        addAsyncTask(action);
    }

    private void addAsyncTask(final Action<AMQPConnection_0_8> action)
    {
        _asyncTaskList.add(action);
        notifyWork();
    }

    public void block()
    {
        synchronized (_channelAddRemoveLock)
        {
            if(!_blocking)
            {
                _blocking = true;
                for(AMQChannel channel : _channelMap.values())
                {
                    channel.block();
                }
            }
        }
    }

    public void unblock()
    {
        synchronized (_channelAddRemoveLock)
        {
            if(_blocking)
            {
                _blocking = false;
                for(AMQChannel channel : _channelMap.values())
                {
                    channel.unblock();
                }
            }
        }
    }

    @Override
    public List<AMQChannel> getSessionModels()
    {
        return new ArrayList<>(_channelMap.values());
    }

    @Override
    public String getRemoteContainerName()
    {
        return getClientId();
    }


    public void setDeferFlush(boolean deferFlush)
    {
        _deferFlush = deferFlush;
    }

    @Override
    public boolean hasSessionWithName(final byte[] name)
    {
        return false;
    }

    @Override
    public void receiveChannelOpen(final int channelId)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + channelId + "] ChannelOpen");
        }
        assertState(ConnectionState.OPEN);

        // Protect the broker against out of order frame request.
        if (_virtualHost == null)
        {
            sendConnectionClose(AMQConstant.COMMAND_INVALID,
                    "Virtualhost has not yet been set. ConnectionOpen has not been called.", channelId);
        }
        else if(getChannel(channelId) != null || channelAwaitingClosure(channelId))
        {
            sendConnectionClose(AMQConstant.CHANNEL_ERROR, "Channel " + channelId + " already exists", channelId);
        }
        else if(channelId > getMaximumNumberOfChannels())
        {
            sendConnectionClose(AMQConstant.CHANNEL_ERROR,
                    "Channel " + channelId + " cannot be created as the max allowed channel id is "
                            + getMaximumNumberOfChannels(),
                    channelId);
        }
        else
        {
            _logger.debug("Connecting to: {}", _virtualHost.getName());

            final AMQChannel channel = new AMQChannel(this, channelId, _virtualHost.getMessageStore());

            addChannel(channel);

            ChannelOpenOkBody response;


            response = getMethodRegistry().createChannelOpenOkBody();


            writeFrame(response.generateFrame(channelId));
        }
    }

    void assertState(final ConnectionState requiredState)
    {
        if(_state != requiredState)
        {
            sendConnectionClose(AMQConstant.COMMAND_INVALID, "Command Invalid", 0);

        }
    }

    @Override
    public void receiveConnectionOpen(AMQShortString virtualHostName,
                                      AMQShortString capabilities,
                                      boolean insist)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV ConnectionOpen[" +" virtualHost: " + virtualHostName + " capabilities: " + capabilities + " insist: " + insist + " ]");
        }

        String virtualHostStr = AMQShortString.toString(virtualHostName);
        if ((virtualHostStr != null) && virtualHostStr.charAt(0) == '/')
        {
            virtualHostStr = virtualHostStr.substring(1);
        }

        VirtualHostImpl<?,?,?> virtualHost = ((AmqpPort)getPort()).getVirtualHost(virtualHostStr);

        if (virtualHost == null)
        {
            sendConnectionClose(AMQConstant.NOT_FOUND,
                    "Unknown virtual host: '" + virtualHostName + "'", 0);

        }
        else
        {
            // Check virtualhost access
            if (virtualHost.getState() != State.ACTIVE)
            {
                String redirectHost = virtualHost.getRedirectHost(getPort());
                if(redirectHost != null)
                {
                    sendConnectionClose(0, new AMQFrame(0, new ConnectionRedirectBody(getProtocolVersion(), AMQShortString.valueOf(redirectHost), null)));
                }
                else
                {
                    sendConnectionClose(AMQConstant.CONNECTION_FORCED,
                            "Virtual host '" + virtualHost.getName() + "' is not active", 0);
                }

            }
            else
            {
                setVirtualHost(virtualHost);
                try
                {

                    if(virtualHost.authoriseCreateConnection(this))
                    {
                        MethodRegistry methodRegistry = getMethodRegistry();
                        AMQMethodBody responseBody = methodRegistry.createConnectionOpenOkBody(virtualHostName);

                        writeFrame(responseBody.generateFrame(0));
                        _state = ConnectionState.OPEN;
                    }
                    else
                    {
                        sendConnectionClose(AMQConstant.ACCESS_REFUSED, "Connection refused", 0);
                    }
                }
                catch (AccessControlException e)
                {
                    sendConnectionClose(AMQConstant.ACCESS_REFUSED, e.getMessage(), 0);
                }
            }
        }
    }

    @Override
    public void receiveConnectionClose(final int replyCode,
                                       final AMQShortString replyText,
                                       final int classId,
                                       final int methodId)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV ConnectionClose[" +" replyCode: " + replyCode + " replyText: " + replyText + " classId: " + classId + " methodId: " + methodId + " ]");
        }

        try
        {
            if (_orderlyClose.compareAndSet(false, true))
            {
                completeAndCloseAllChannels();
            }

            MethodRegistry methodRegistry = getMethodRegistry();
            ConnectionCloseOkBody responseBody = methodRegistry.createConnectionCloseOkBody();
            writeFrame(responseBody.generateFrame(0));
        }
        catch (Exception e)
        {
            _logger.error("Error closing connection for " + getRemoteAddressString(), e);
        }
        finally
        {
            closeNetworkConnection();
        }
    }

    @Override
    public void receiveConnectionCloseOk()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV ConnectionCloseOk");
        }

        closeNetworkConnection();
    }

    @Override
    public void receiveConnectionSecureOk(final byte[] response)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV ConnectionSecureOk[ response: ******** ] ");
        }

        assertState(ConnectionState.AWAIT_SECURE_OK);

        Broker<?> broker = getBroker();

        SubjectCreator subjectCreator = getSubjectCreator();

        SaslServer ss = getSaslServer();
        if (ss == null)
        {
            sendConnectionClose(AMQConstant.INTERNAL_ERROR, "No SASL context set up in connection", 0);
        }
        MethodRegistry methodRegistry = getMethodRegistry();
        SubjectAuthenticationResult authResult = subjectCreator.authenticate(ss, response);
        switch (authResult.getStatus())
        {
            case ERROR:
                Exception cause = authResult.getCause();

                _logger.debug("Authentication failed: {}", (cause == null ? "" : cause.getMessage()));

                sendConnectionClose(AMQConstant.NOT_ALLOWED, "Authentication failed", 0);

                disposeSaslServer();
                break;
            case SUCCESS:
                _logger.debug("Connected as: {} ", authResult.getSubject());

                int frameMax = getDefaultMaxFrameSize();

                if (frameMax <= 0)
                {
                    frameMax = Integer.MAX_VALUE;
                }

                ConnectionTuneBody tuneBody =
                        methodRegistry.createConnectionTuneBody(broker.getConnection_sessionCountLimit(),
                                                                frameMax,
                                                                broker.getConnection_heartBeatDelay());
                writeFrame(tuneBody.generateFrame(0));
                _state = ConnectionState.AWAIT_TUNE_OK;
                setAuthorizedSubject(authResult.getSubject());
                disposeSaslServer();
                break;
            case CONTINUE:

                ConnectionSecureBody
                        secureBody = methodRegistry.createConnectionSecureBody(authResult.getChallenge());
                writeFrame(secureBody.generateFrame(0));
        }
    }


    private void disposeSaslServer()
    {
        SaslServer ss = getSaslServer();
        if (ss != null)
        {
            setSaslServer(null);
            try
            {
                ss.dispose();
            }
            catch (SaslException e)
            {
                _logger.error("Error disposing of Sasl server: " + e);
            }
        }
    }

    @Override
    public void receiveConnectionStartOk(final FieldTable clientProperties,
                                         final AMQShortString mechanism,
                                         final byte[] response,
                                         final AMQShortString locale)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug("RECV ConnectionStartOk["
                          + " clientProperties: "
                          + clientProperties
                          + " mechanism: "
                          + mechanism
                          + " response: ********"
                          + " locale: "
                          + locale
                          + " ]");
        }

        assertState(ConnectionState.AWAIT_START_OK);

        Broker<?> broker = getBroker();

        _logger.debug("SASL Mechanism selected: {} Locale : {}", mechanism, locale);

        SubjectCreator subjectCreator = getSubjectCreator();
        SaslServer ss;
        try
        {
            ss = subjectCreator.createSaslServer(String.valueOf(mechanism),
                                                 getLocalFQDN(),
                                                 getPeerPrincipal());

            if (ss == null)
            {
                sendConnectionClose(AMQConstant.RESOURCE_ERROR, "Unable to create SASL Server:" + mechanism, 0);

            }
            else
            {
                //save clientProperties
                setClientProperties(clientProperties);

                setSaslServer(ss);

                final SubjectAuthenticationResult authResult = subjectCreator.authenticate(ss, response);

                MethodRegistry methodRegistry = getMethodRegistry();

                switch (authResult.getStatus())
                {
                    case ERROR:
                        Exception cause = authResult.getCause();

                        _logger.debug("Authentication failed: {}", (cause == null ? "" : cause.getMessage()));

                        sendConnectionClose(AMQConstant.NOT_ALLOWED, "Authentication failed", 0);

                        disposeSaslServer();
                        break;

                    case SUCCESS:
                        _logger.debug("Connected as: {}", authResult.getSubject());
                        setAuthorizedSubject(authResult.getSubject());

                        int frameMax = getDefaultMaxFrameSize();

                        if (frameMax <= 0)
                        {
                            frameMax = Integer.MAX_VALUE;
                        }

                        ConnectionTuneBody
                                tuneBody =
                                methodRegistry.createConnectionTuneBody(broker.getConnection_sessionCountLimit(),
                                                                        frameMax,
                                                                        broker.getConnection_heartBeatDelay());
                        writeFrame(tuneBody.generateFrame(0));
                        _state = ConnectionState.AWAIT_TUNE_OK;
                        break;
                    case CONTINUE:
                        ConnectionSecureBody
                                secureBody = methodRegistry.createConnectionSecureBody(authResult.getChallenge());
                        writeFrame(secureBody.generateFrame(0));

                        _state = ConnectionState.AWAIT_SECURE_OK;
                }
            }
        }
        catch (SaslException e)
        {
            disposeSaslServer();
            sendConnectionClose(AMQConstant.INTERNAL_ERROR, "SASL error: " + e, 0);
        }
    }

    @Override
    public void receiveConnectionTuneOk(final int channelMax, final long frameMax, final int heartbeat)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV ConnectionTuneOk[" +" channelMax: " + channelMax + " frameMax: " + frameMax + " heartbeat: " + heartbeat + " ]");
        }

        assertState(ConnectionState.AWAIT_TUNE_OK);

        initHeartbeats(heartbeat);

        int brokerFrameMax = getDefaultMaxFrameSize();
        if (brokerFrameMax <= 0)
        {
            brokerFrameMax = Integer.MAX_VALUE;
        }

        if (frameMax > (long) brokerFrameMax)
        {
            sendConnectionClose(AMQConstant.SYNTAX_ERROR,
                    "Attempt to set max frame size to " + frameMax
                            + " greater than the broker will allow: "
                            + brokerFrameMax, 0);
        }
        else if (frameMax > 0 && frameMax < AMQConstant.FRAME_MIN_SIZE.getCode())
        {
            sendConnectionClose(AMQConstant.SYNTAX_ERROR,
                    "Attempt to set max frame size to " + frameMax
                            + " which is smaller than the specification defined minimum: "
                            + AMQConstant.FRAME_MIN_SIZE.getCode(), 0);
        }
        else
        {
            int calculatedFrameMax = frameMax == 0 ? brokerFrameMax : (int) frameMax;
            setMaxFrameSize(calculatedFrameMax);

            //0 means no implied limit, except that forced by protocol limitations (0xFFFF)
            setMaximumNumberOfChannels( ((channelMax == 0l) || (channelMax > 0xFFFFL))
                                               ? 0xFFFFL
                                               : channelMax);

        }
        _state = ConnectionState.AWAIT_OPEN;

    }

    public int getBinaryDataLimit()
    {
        return _binaryDataLimit;
    }

    public long getMaxMessageSize()
    {
        return _maxMessageSize;
    }

    public final class WriteDeliverMethod
            implements ClientDeliveryMethod
    {
        private final int _channelId;

        public WriteDeliverMethod(int channelId)
        {
            _channelId = channelId;
        }

        @Override
        public long deliverToClient(final ConsumerImpl sub, final ServerMessage message,
                                    final InstanceProperties props, final long deliveryTag)
        {
            long size = _protocolOutputConverter.writeDeliver(message,
                                                  props,
                                                  _channelId,
                                                  deliveryTag,
                                                  new AMQShortString(sub.getName()));
            registerMessageDelivered(size);
            return size;
        }

    }

    public Object getReference()
    {
        return _reference;
    }

    public boolean isCloseWhenNoRoute()
    {
        return _closeWhenNoRoute;
    }

    public boolean isCompressionSupported()
    {
        return _compressionSupported && getBroker().isMessageCompressionEnabled();
    }

    public int getMessageCompressionThreshold()
    {
        return _messageCompressionThreshold;
    }

    private SubjectCreator getSubjectCreator()
    {
        return getPort().getAuthenticationProvider().getSubjectCreator(getTransport().isSecure());
    }

    @Override
    public EventLogger getEventLogger()
    {
        if(_virtualHost != null)
        {
            return _virtualHost.getEventLogger();
        }
        else
        {
            return getBroker().getEventLogger();
        }
    }

    @Override
    public ServerChannelMethodProcessor getChannelMethodProcessor(final int channelId)
    {
        assertState(ConnectionState.OPEN);

        ServerChannelMethodProcessor channelMethodProcessor = getChannel(channelId);
        if(channelMethodProcessor == null)
        {
            channelMethodProcessor = (ServerChannelMethodProcessor) Proxy.newProxyInstance(ServerMethodDispatcher.class.getClassLoader(),
                                                            new Class[] { ServerChannelMethodProcessor.class }, new InvocationHandler()
                    {
                        @Override
                        public Object invoke(final Object proxy, final Method method, final Object[] args)
                                throws Throwable
                        {
                            if(method.getName().startsWith("receive"))
                            {
                                sendConnectionClose(AMQConstant.CHANNEL_ERROR,
                                        "Unknown channel id: " + channelId,
                                        channelId);
                                return null;
                            }
                            else if(method.getName().equals("ignoreAllButCloseOk"))
                            {
                                return false;
                            }
                            return null;
                        }
                    });
        }
        return channelMethodProcessor;
    }

    @Override
    public void receiveHeartbeat()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV Heartbeat");
        }

        // No op
    }

    @Override
    public void receiveProtocolHeader(final ProtocolInitiation protocolInitiation)
    {

        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV ProtocolHeader [" + protocolInitiation + " ]");
        }

        protocolInitiationReceived(protocolInitiation);
    }

    @Override
    public void setCurrentMethod(final int classId, final int methodId)
    {
        _currentClassId = classId;
        _currentMethodId = methodId;
    }

    @Override
    public boolean ignoreAllButCloseOk()
    {
        return isClosing();
    }

    @Override
    public boolean hasWork()
    {
        return _stateChanged.get();
    }

    @Override
    public void notifyWork()
    {
        _stateChanged.set(true);

        final Action<ProtocolEngine> listener = _workListener.get();
        if(listener != null)
        {

            listener.performAction(this);
        }
    }

    @Override
    public void clearWork()
    {
        _stateChanged.set(false);
    }

    @Override
    public void setWorkListener(final Action<ProtocolEngine> listener)
    {
        _workListener.set(listener);
    }

    @Override
    public Iterator<Runnable> processPendingIterator()
    {
        if (!isIOThread())
        {
            return Collections.emptyIterator();
        }
        return new ProcessPendingIterator();
    }

    private class ProcessPendingIterator implements Iterator<Runnable>
    {
        private final List<? extends AMQSessionModel<?>> _sessionsWithPending;
        private Iterator<? extends AMQSessionModel<?>> _sessionIterator;
        private ProcessPendingIterator()
        {
            _sessionsWithPending = new ArrayList<>(getSessionModels());
            _sessionIterator = _sessionsWithPending.iterator();
        }

        @Override
        public boolean hasNext()
        {
            return !(_sessionsWithPending.isEmpty() && _asyncTaskList.isEmpty());
        }

        @Override
        public Runnable next()
        {
            if(!_sessionsWithPending.isEmpty())
            {
                if(!_sessionIterator.hasNext())
                {
                    _sessionIterator = _sessionsWithPending.iterator();
                }
                final AMQSessionModel<?> session = _sessionIterator.next();
                return new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if(!session.processPending())
                        {
                            _sessionIterator.remove();
                        }
                    }
                };
            }
            else if(!_asyncTaskList.isEmpty())
            {
                final Action<? super AMQPConnection_0_8> asyncAction = _asyncTaskList.poll();
                return new Runnable()
                {
                    @Override
                    public void run()
                    {
                        asyncAction.performAction(AMQPConnection_0_8.this);
                    }
                };
            }
            else
            {
                throw new NoSuchElementException();
            }
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }
}
