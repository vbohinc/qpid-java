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
package org.apache.qpid.server.model.port;

import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;

import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.ManagedAttribute;
import org.apache.qpid.server.model.ManagedContextDefault;
import org.apache.qpid.server.model.ManagedObject;
import org.apache.qpid.server.model.ManagedStatistic;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.model.StatisticType;
import org.apache.qpid.server.model.StatisticUnit;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.TrustStore;
import org.apache.qpid.server.model.VirtualHostAlias;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;

import javax.net.ssl.SSLContext;

@ManagedObject( category = false, type = "AMQP")
public interface AmqpPort<X extends AmqpPort<X>> extends ClientAuthCapablePort<X>
{
    String DEFAULT_AMQP_TCP_NO_DELAY = "true";

    String DEFAULT_AMQP_NEED_CLIENT_AUTH = "false";
    String DEFAULT_AMQP_WANT_CLIENT_AUTH = "false";

    String MAX_OPEN_CONNECTIONS = "maxOpenConnections";
    String THREAD_POOL_SIZE = "threadPoolSize";
    String NUMBER_OF_SELECTORS = "numberOfSelectors";

    String DEFAULT_AMQP_PROTOCOLS = "qpid.port.default_amqp_protocols";

    String PORT_AMQP_THREAD_POOL_SIZE = "qpid.port.amqp.threadPool.size";
    String PORT_AMQP_THREAD_POOL_KEEP_ALIVE_TIMEOUT = "qpid.port.amqp.threadPool.keep_alive_timeout";

    String PORT_AMQP_NUMBER_OF_SELECTORS = "qpid.port.amqp.threadPool.numberOfSelectors";
    String PORT_AMQP_ACCEPT_BACKLOG = "qpid.port.amqp.acceptBacklog";
    String PORT_AMQP_OUTBOUND_MESSAGE_BUFFER_SIZE = "qpid.port.amqp.outboundMessageBufferSize";

    @ManagedContextDefault(name = DEFAULT_AMQP_PROTOCOLS)
    String INSTALLED_PROTOCOLS = AmqpPortImpl.getInstalledProtocolsAsString();

    String PORT_MAX_OPEN_CONNECTIONS = "qpid.port.max_open_connections";

    @ManagedContextDefault(name = PORT_MAX_OPEN_CONNECTIONS)
    int DEFAULT_MAX_OPEN_CONNECTIONS = -1;

    @SuppressWarnings("unused")
    @ManagedContextDefault( name = PORT_AMQP_THREAD_POOL_SIZE)
    long DEFAULT_PORT_AMQP_THREAD_POOL_SIZE = 8;

    @SuppressWarnings("unused")
    @ManagedContextDefault(name = PORT_AMQP_THREAD_POOL_KEEP_ALIVE_TIMEOUT)
    long DEFAULT_PORT_AMQP_THREAD_POOL_KEEP_ALIVE_TIMEOUT = 60; // Minutes

    @SuppressWarnings("unused")
    @ManagedContextDefault(name = PORT_AMQP_NUMBER_OF_SELECTORS)
    long DEFAULT_PORT_AMQP_NUMBER_OF_SELECTORS = Math.max(DEFAULT_PORT_AMQP_THREAD_POOL_SIZE / 8, 1);

    @SuppressWarnings("unused")
    @ManagedContextDefault(name = PORT_AMQP_ACCEPT_BACKLOG)
    int DEFAULT_PORT_AMQP_ACCEPT_BACKLOG = 1024;

    String PORT_MAX_MESSAGE_SIZE = "qpid.port.max_message_size";

    @ManagedContextDefault(name = PORT_MAX_MESSAGE_SIZE)
    int DEFAULT_MAX_MESSAGE_SIZE = 0x1f40000; // 500Mb

    String OPEN_CONNECTIONS_WARN_PERCENT = "qpid.port.open_connections_warn_percent";

    @ManagedContextDefault(name = OPEN_CONNECTIONS_WARN_PERCENT)
    int DEFAULT_OPEN_CONNECTIONS_WARN_PERCENT = 80;

    @SuppressWarnings("unused")
    @ManagedContextDefault(name = PORT_AMQP_OUTBOUND_MESSAGE_BUFFER_SIZE)
    long DEFAULT_PORT_AMQP_OUTBOUND_MESSAGE_BUFFER_SIZE = 1024 * 1024;

    SSLContext getSSLContext();

    @ManagedAttribute(defaultValue = "*")
    String getBindingAddress();

    @ManagedAttribute( defaultValue = AmqpPort.DEFAULT_AMQP_TCP_NO_DELAY )
    boolean isTcpNoDelay();

    @ManagedAttribute( defaultValue = "${" + PORT_AMQP_THREAD_POOL_SIZE + "}")
    int getThreadPoolSize();

    @ManagedAttribute( defaultValue = "${" + PORT_AMQP_NUMBER_OF_SELECTORS + "}")
    int getNumberOfSelectors();

    @ManagedAttribute( defaultValue = DEFAULT_AMQP_NEED_CLIENT_AUTH )
    boolean getNeedClientAuth();

    @ManagedAttribute( defaultValue = DEFAULT_AMQP_WANT_CLIENT_AUTH )
    boolean getWantClientAuth();

    @ManagedAttribute
    TrustStore<?> getClientCertRecorder();

    @ManagedAttribute( mandatory = true )
    AuthenticationProvider getAuthenticationProvider();


    @ManagedAttribute( defaultValue = "TCP",
                       validValues = {"org.apache.qpid.server.model.port.AmqpPortImpl#getAllAvailableTransportCombinations()"})
    Set<Transport> getTransports();

    @ManagedAttribute( defaultValue = "${" + DEFAULT_AMQP_PROTOCOLS + "}", validValues = {"org.apache.qpid.server.model.port.AmqpPortImpl#getAllAvailableProtocolCombinations()"} )
    Set<Protocol> getProtocols();

    @ManagedAttribute( defaultValue = "${" + PORT_MAX_OPEN_CONNECTIONS + "}" )
    int getMaxOpenConnections();

    @ManagedStatistic(statisticType = StatisticType.POINT_IN_TIME, units = StatisticUnit.COUNT, label = "Connections")
    int getConnectionCount();

    VirtualHostImpl getVirtualHost(String name);

    boolean canAcceptNewConnection(final SocketAddress remoteSocketAddress);

    int incrementConnectionCount();

    int decrementConnectionCount();

    VirtualHostAlias createVirtualHostAlias(Map<String, Object> attributes);

    int getNetworkBufferSize();
}
