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
package org.apache.qpid.server.virtualhostalias;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.ConfiguredObjectFactory;
import org.apache.qpid.server.model.PatternMatchingAlias;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.model.VirtualHostAlias;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.model.port.AmqpPort;
import org.apache.qpid.server.util.BrokerTestHelper;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;
import org.apache.qpid.test.utils.QpidTestCase;

public class VirtualHostAliasTest extends QpidTestCase
{
    private final Map<String, VirtualHost<?,?,?>> _vhosts = new HashMap<>();
    private Broker<?> _broker;
    private AmqpPort _port;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _broker = BrokerTestHelper.createBrokerMock();

        AuthenticationProvider dummyAuthProvider = mock(AuthenticationProvider.class);
        when(dummyAuthProvider.getName()).thenReturn("dummy");
        when(dummyAuthProvider.getId()).thenReturn(UUID.randomUUID());
        when(dummyAuthProvider.getMechanisms()).thenReturn(Arrays.asList("PLAIN"));
        when(_broker.getChildren(eq(AuthenticationProvider.class))).thenReturn(Collections.singleton(dummyAuthProvider));
        for(String name : new String[] { "red", "blue", "purple", "black" })
        {
            boolean defaultVHN = "black".equals(name);
            VirtualHostImpl<?, ?, ?> virtualHost = BrokerTestHelper.createVirtualHost(name, _broker, defaultVHN);
            VirtualHostNode vhn = virtualHost.getParent(VirtualHostNode.class);
            assertNotSame(vhn.getName(), virtualHost.getName());
            _vhosts.put(name, virtualHost);

            if (defaultVHN)
            {
                when(_broker.findDefautVirtualHostNode()).thenReturn(vhn);
            }
        }
        ConfiguredObjectFactory objectFactory = _broker.getObjectFactory();

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(Port.NAME, getTestName());
        attributes.put(Port.PORT, findFreePort());
        attributes.put(Port.AUTHENTICATION_PROVIDER, "dummy");
        attributes.put(Port.TYPE, "AMQP");
        _port = (AmqpPort) objectFactory.create(Port.class, attributes, _broker );

    }

    @Override
    protected void tearDown() throws Exception
    {
        _port.close();
        for (VirtualHost vhost : _vhosts.values())
        {
            vhost.close();
        }
        super.tearDown();
    }

    public void testDefaultAliases_VirtualHostNameAlias()
    {
        VirtualHostImpl vhost = _port.getVirtualHost("red");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("red"), vhost);

        vhost = _port.getVirtualHost("blue");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("blue"), vhost);

        vhost = _port.getVirtualHost("orange!");

        assertNull(vhost);
    }

    public void testDefaultAliases_DefaultVirtualHostAlias()
    {

        // test the default vhost resolution
        VirtualHostImpl vhost = _port.getVirtualHost("");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("black"), vhost);
    }

    public void testDefaultAliases_HostNameAlias()
    {
        // 127.0.0.1 should always resolve and thus return the default vhost
        VirtualHostImpl vhost = _port.getVirtualHost("127.0.0.1");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("black"), vhost);
    }

    public void testPatternMatching()
    {
        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(VirtualHostAlias.NAME, "matcher");
        attributes.put(VirtualHostAlias.TYPE, PatternMatchingAlias.TYPE_NAME);
        attributes.put(PatternMatchingAlias.PATTERN, "orange|pink.*");
        attributes.put(PatternMatchingAlias.VIRTUAL_HOST_NODE, _vhosts.get("purple").getParent(VirtualHostNode.class));
        _port.createVirtualHostAlias(attributes);

        VirtualHostImpl vhost = _port.getVirtualHost("orange");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("purple"), vhost);

        vhost = _port.getVirtualHost("pink");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("purple"), vhost);


        vhost = _port.getVirtualHost("pinker");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("purple"), vhost);



        vhost = _port.getVirtualHost("o.*");

        assertNull(vhost);

    }

    public void testPriority()
    {

        VirtualHostImpl vhost = _port.getVirtualHost("blue");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("blue"), vhost);

        vhost = _port.getVirtualHost("black");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("black"), vhost);



        Map<String, Object> attributes = new HashMap<>();
        attributes.put(VirtualHostAlias.NAME, "matcher10");
        attributes.put(VirtualHostAlias.TYPE, PatternMatchingAlias.TYPE_NAME);
        attributes.put(VirtualHostAlias.PRIORITY, 10);
        attributes.put(PatternMatchingAlias.PATTERN, "bl.*");
        attributes.put(PatternMatchingAlias.VIRTUAL_HOST_NODE, _vhosts.get("purple").getParent(VirtualHostNode.class));
        _port.createVirtualHostAlias(attributes);

        vhost = _port.getVirtualHost("blue");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("purple"), vhost);

        vhost = _port.getVirtualHost("black");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("purple"), vhost);


        attributes = new HashMap<>();
        attributes.put(VirtualHostAlias.NAME, "matcher5");
        attributes.put(VirtualHostAlias.TYPE, PatternMatchingAlias.TYPE_NAME);
        attributes.put(VirtualHostAlias.PRIORITY, 5);
        attributes.put(PatternMatchingAlias.PATTERN, ".*u.*");
        attributes.put(PatternMatchingAlias.VIRTUAL_HOST_NODE, _vhosts.get("red").getParent(VirtualHostNode.class));
        _port.createVirtualHostAlias(attributes);



        vhost = _port.getVirtualHost("blue");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("red"), vhost);

        vhost = _port.getVirtualHost("black");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("purple"), vhost);



        vhost = _port.getVirtualHost("purple");

        assertNotNull(vhost);
        assertEquals(_vhosts.get("red"), vhost);



    }
}
