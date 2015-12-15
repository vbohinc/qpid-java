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

/*
 * This file is auto-generated by Qpid Gentools v.0.1 - do not modify.
 * Supported AMQP version:
 *   8-0
 */

package org.apache.qpid.framing;

import java.io.IOException;

import org.apache.qpid.QpidException;
import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.codec.MarkableDataInput;

public class ExchangeDeleteBody extends AMQMethodBodyImpl implements EncodableAMQDataBlock, AMQMethodBody
{

    public static final int CLASS_ID =  40;
    public static final int METHOD_ID = 20;

    // Fields declared in specification
    private final int _ticket; // [ticket]
    private final AMQShortString _exchange; // [exchange]
    private final byte _bitfield0; // [ifUnused, nowait]

    // Constructor
    public ExchangeDeleteBody(MarkableDataInput buffer) throws AMQFrameDecodingException, IOException
    {
        _ticket = buffer.readUnsignedShort();
        _exchange = buffer.readAMQShortString();
        _bitfield0 = buffer.readByte();
    }

    public ExchangeDeleteBody(
            int ticket,
            AMQShortString exchange,
            boolean ifUnused,
            boolean nowait
                             )
    {
        _ticket = ticket;
        _exchange = exchange;
        byte bitfield0 = (byte)0;
        if( ifUnused )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 0));
        }

        if( nowait )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 1));
        }
        _bitfield0 = bitfield0;
    }

    public int getClazz()
    {
        return CLASS_ID;
    }

    public int getMethod()
    {
        return METHOD_ID;
    }

    public final int getTicket()
    {
        return _ticket;
    }
    public final AMQShortString getExchange()
    {
        return _exchange;
    }
    public final boolean getIfUnused()
    {
        return (((int)(_bitfield0)) & ( 1 << 0)) != 0;
    }
    public final boolean getNowait()
    {
        return (((int)(_bitfield0)) & ( 1 << 1)) != 0;
    }

    protected int getBodySize()
    {
        int size = 3;
        size += getSizeOf( _exchange );
        return size;
    }

    public void writeMethodPayload(QpidByteBuffer buffer)
    {
        writeUnsignedShort( buffer, _ticket );
        writeAMQShortString( buffer, _exchange );
        writeBitfield( buffer, _bitfield0 );
    }

    public boolean execute(MethodDispatcher dispatcher, int channelId) throws QpidException
	{
        return dispatcher.dispatchExchangeDelete(this, channelId);
	}

    public String toString()
    {
        StringBuilder buf = new StringBuilder("[ExchangeDeleteBodyImpl: ");
        buf.append( "ticket=" );
        buf.append(  getTicket() );
        buf.append( ", " );
        buf.append( "exchange=" );
        buf.append(  getExchange() );
        buf.append( ", " );
        buf.append( "ifUnused=" );
        buf.append(  getIfUnused() );
        buf.append( ", " );
        buf.append( "nowait=" );
        buf.append(  getNowait() );
        buf.append("]");
        return buf.toString();
    }

    public static void process(final MarkableDataInput buffer,
                               final ServerChannelMethodProcessor dispatcher)
            throws IOException
    {

        int ticket = buffer.readUnsignedShort();
        AMQShortString exchange = buffer.readAMQShortString();
        byte bitfield = buffer.readByte();
        boolean ifUnused = (bitfield & 0x01) == 0x01;
        boolean nowait = (bitfield & 0x02) == 0x02;
        if(!dispatcher.ignoreAllButCloseOk())
        {
            dispatcher.receiveExchangeDelete(exchange, ifUnused, nowait);
        }
    }
}
