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
package org.apache.qpid.systest.rest;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.plugin.AuthenticationManagerFactory;
import org.apache.qpid.server.security.auth.manager.AnonymousAuthenticationManagerFactory;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class PortRestTest extends QpidRestTestCase
{
    public void testGet() throws Exception
    {
        List<Map<String, Object>> ports = getRestTestHelper().getJsonAsList("/rest/port/");
        assertNotNull("Port data cannot be null", ports);
        assertEquals("Unexpected number of ports", 2, ports.size());

        String httpPortName = TestBrokerConfiguration.ENTRY_NAME_HTTP_PORT;
        Map<String, Object> portData = getRestTestHelper().find(Port.NAME, httpPortName, ports);
        assertNotNull("Http port " + httpPortName + " is not found", portData);
        Asserts.assertPortAttributes(portData);

        String amqpPortName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        Map<String, Object> amqpPortData = getRestTestHelper().find(Port.NAME, amqpPortName, ports);
        assertNotNull("Amqp port " + amqpPortName + " is not found", amqpPortData);
        Asserts.assertPortAttributes(amqpPortData);
    }

    public void testGetPort() throws Exception
    {
        List<Map<String, Object>> ports = getRestTestHelper().getJsonAsList("/rest/port/");
        assertNotNull("Ports data cannot be null", ports);
        assertEquals("Unexpected number of ports", 2, ports.size());
        for (Map<String, Object> portMap : ports)
        {
            String portName = (String) portMap.get(Port.NAME);
            assertNotNull("Port name attribute is not found", portName);
            Map<String, Object> portData = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + URLDecoder.decode(portName, "UTF-8"));
            assertNotNull("Port " + portName + " is not found", portData);
            Asserts.assertPortAttributes(portData);
        }
    }

    public void testPutAmqpPortWithMinimumAttributes() throws Exception
    {
        String portName = "test-port";
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.PORT, findFreePort());

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response code", 201, responseCode);

        List<Map<String, Object>> portDetails = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertNotNull("Port details cannot be null", portDetails);
        assertEquals("Unexpected number of ports with name " + portName, 1, portDetails.size());
        Map<String, Object> port = portDetails.get(0);
        Asserts.assertPortAttributes(port);

        // make sure that port is there after broker restart
        restartBroker();

        portDetails = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertNotNull("Port details cannot be null", portDetails);
        assertEquals("Unexpected number of ports with name " + portName, 1, portDetails.size());
    }

    public void testPutRmiPortWithMinimumAttributes() throws Exception
    {
        String portName = "test-port";
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.PORT, findFreePort());
        attributes.put(Port.PROTOCOLS, Collections.singleton(Protocol.RMI));

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response code", 201, responseCode);

        List<Map<String, Object>> portDetails = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertNotNull("Port details cannot be null", portDetails);
        assertEquals("Unexpected number of ports with name " + portName, 1, portDetails.size());
        Map<String, Object> port = portDetails.get(0);
        Asserts.assertPortAttributes(port, State.QUIESCED);

        // make sure that port is there after broker restart
        restartBroker();

        portDetails = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertNotNull("Port details cannot be null", portDetails);
        assertEquals("Unexpected number of ports with name " + portName, 1, portDetails.size());
        port = portDetails.get(0);
        Asserts.assertPortAttributes(port, State.ACTIVE);

        // try to add a second RMI port
        attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName + 2);
        attributes.put(Port.PORT, findFreePort());
        attributes.put(Port.PROTOCOLS, Collections.singleton(Protocol.RMI));

        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Adding of a second RMI port should fail", 409, responseCode);
    }

    public void testPutCreateAndUpdateAmqpPort() throws Exception
    {
        String portName = "test-port";
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.PORT, findFreePort());

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response code for port creation", 201, responseCode);

        List<Map<String, Object>> portDetails = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertNotNull("Port details cannot be null", portDetails);
        assertEquals("Unexpected number of ports with name " + portName, 1, portDetails.size());
        Map<String, Object> port = portDetails.get(0);
        Asserts.assertPortAttributes(port);

        Map<String, Object> authProviderAttributes = new HashMap<String, Object>();
        authProviderAttributes.put(AuthenticationManagerFactory.ATTRIBUTE_TYPE, AnonymousAuthenticationManagerFactory.PROVIDER_TYPE);
        authProviderAttributes.put(AuthenticationProvider.NAME, TestBrokerConfiguration.ENTRY_NAME_ANONYMOUS_PROVIDER);

        responseCode = getRestTestHelper().submitRequest("/rest/authenticationprovider/" + TestBrokerConfiguration.ENTRY_NAME_ANONYMOUS_PROVIDER, "PUT", authProviderAttributes);
        assertEquals("Unexpected response code for authentication provider creation", 201, responseCode);

        attributes = new HashMap<String, Object>(port);
        attributes.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_ANONYMOUS_PROVIDER);
        attributes.put(Port.PROTOCOLS, Collections.singleton(Protocol.AMQP_0_9_1));

        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Port cannot be updated in non management mode", 409, responseCode);

        restartBrokerInManagementMode();

        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Port should be allwed to update in a management mode", 200, responseCode);

        portDetails = getRestTestHelper().getJsonAsList("/rest/port/" + portName);
        assertNotNull("Port details cannot be null", portDetails);
        assertEquals("Unexpected number of ports with name " + portName, 1, portDetails.size());
        port = portDetails.get(0);

        assertEquals("Unexpected authentication provider", TestBrokerConfiguration.ENTRY_NAME_ANONYMOUS_PROVIDER, port.get(Port.AUTHENTICATION_PROVIDER));
        Object protocols = port.get(Port.PROTOCOLS);
        assertNotNull("Protocols attribute is not found", protocols);
        assertTrue("Protocol attribute value is not collection:" + protocols, protocols instanceof Collection);
        @SuppressWarnings("unchecked")
        Collection<String> protocolsCollection = ((Collection<String>)protocols);
        assertEquals("Unexpected protocols size", 1, protocolsCollection.size());
        assertEquals("Unexpected protocols", Protocol.AMQP_0_9_1.name(), protocolsCollection.iterator().next());
    }

    public void testPutUpdateOpenedAmqpPortFails() throws Exception
    {
        Map<String, Object> port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT);
        Integer portValue = (Integer)port.get(Port.PORT);

        port.put(Port.PORT, findFreePort());

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT, "PUT", port);
        assertEquals("Unexpected response code for port update", 409, responseCode);

        port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT);
        assertEquals("Port has been changed", portValue, port.get(Port.PORT));
    }

    public void testUpdatePortTransportFromTCPToSSLWhenKeystoreIsConfigured() throws Exception
    {
        restartBrokerInManagementMode();

        String portName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Transport has not been changed to SSL " , 200, responseCode);

        restartBroker();

        Map<String, Object> port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + portName);

        @SuppressWarnings("unchecked")
        Collection<String> transports = (Collection<String>) port.get(Port.TRANSPORTS);
        assertEquals("Unexpected auth provider", new HashSet<String>(Arrays.asList(Transport.SSL.name())),
                new HashSet<String>(transports));
    }

    public void testUpdateTransportFromTCPToSSLWithoutKeystoreConfiguredFails() throws Exception
    {
        getBrokerConfiguration().setBrokerAttribute(Broker.KEY_STORE_PATH, null);
        getBrokerConfiguration().setSaved(false);
        restartBrokerInManagementMode();

        String portName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Creation of SSL port without keystore should fail", 409, responseCode);
    }

    public void testUpdateWantNeedClientAuth() throws Exception
    {
        String portName = TestBrokerConfiguration.ENTRY_NAME_SSL_PORT;
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.PORT, DEFAULT_SSL_PORT);
        attributes.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));

        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("SSL port was not added", 201, responseCode);

        restartBrokerInManagementMode();

        attributes.put(Port.NEED_CLIENT_AUTH, true);
        attributes.put(Port.WANT_CLIENT_AUTH, true);

        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Attributes for need/want client auth are not set", 200, responseCode);

        restartBroker();
        Map<String, Object> port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + portName);
        assertEquals("Unexpected " + Port.NEED_CLIENT_AUTH, true, port.get(Port.NEED_CLIENT_AUTH));
        assertEquals("Unexpected " + Port.WANT_CLIENT_AUTH, true, port.get(Port.WANT_CLIENT_AUTH));

        restartBrokerInManagementMode();

        attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.TRANSPORTS, Collections.singleton(Transport.TCP));

        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Should not be able to change transport to SSL without reseting of attributes for need/want client auth", 409, responseCode);

        attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.TRANSPORTS, Collections.singleton(Transport.TCP));
        attributes.put(Port.NEED_CLIENT_AUTH, false);
        attributes.put(Port.WANT_CLIENT_AUTH, false);

        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Should be able to change transport to TCP ", 200, responseCode);

        restartBroker();
        port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + portName);
        assertEquals("Unexpected " + Port.NEED_CLIENT_AUTH, false, port.get(Port.NEED_CLIENT_AUTH));
        assertEquals("Unexpected " + Port.WANT_CLIENT_AUTH, false, port.get(Port.WANT_CLIENT_AUTH));

        @SuppressWarnings("unchecked")
        Collection<String> transports = (Collection<String>) port.get(Port.TRANSPORTS);
        assertEquals("Unexpected auth provider", new HashSet<String>(Arrays.asList(Transport.TCP.name())),
                new HashSet<String>(transports));
    }

    public void testUpdateSettingWantNeedCertificateFailsForNonSSLPort() throws Exception
    {
        restartBrokerInManagementMode();

        String portName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.NEED_CLIENT_AUTH, true);
        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response when trying to set 'needClientAuth' on non-SSL port", 409, responseCode);

        attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.WANT_CLIENT_AUTH, true);
        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response when trying to set 'wantClientAuth' on non-SSL port", 409, responseCode);
    }

    public void testUpdatePortAuthenticationProvider() throws Exception
    {
        restartBrokerInManagementMode();

        String portName = TestBrokerConfiguration.ENTRY_NAME_AMQP_PORT;
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.AUTHENTICATION_PROVIDER, "non-existing");
        int responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response when trying to change auth provider to non-existing one", 409, responseCode);

        attributes = new HashMap<String, Object>();
        attributes.put(Port.NAME, portName);
        attributes.put(Port.AUTHENTICATION_PROVIDER, ANONYMOUS_AUTHENTICATION_PROVIDER);
        responseCode = getRestTestHelper().submitRequest("/rest/port/" + portName, "PUT", attributes);
        assertEquals("Unexpected response when trying to change auth provider to existing one", 200, responseCode);

        Map<String, Object> port = getRestTestHelper().getJsonAsSingletonList("/rest/port/" + portName);
        assertEquals("Unexpected auth provider", ANONYMOUS_AUTHENTICATION_PROVIDER, port.get(Port.AUTHENTICATION_PROVIDER));
    }
}
