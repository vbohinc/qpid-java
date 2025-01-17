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
package org.apache.qpid.codec;

import java.io.IOException;

import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.framing.*;

public class ServerDecoder extends AMQDecoder<ServerMethodProcessor<? extends ServerChannelMethodProcessor>>
{

    /**
     * Creates a new AMQP decoder.
     *
     * @param methodProcessor          method processor
     */
    public ServerDecoder(final ServerMethodProcessor<? extends ServerChannelMethodProcessor> methodProcessor)
    {
        super(true, methodProcessor);
    }

    public void decodeBuffer(QpidByteBuffer buf) throws AMQFrameDecodingException, AMQProtocolVersionException, IOException
    {
        decode(buf.asDataInput());
    }


    void processMethod(int channelId,
                       MarkableDataInput in)
            throws AMQFrameDecodingException, IOException
    {
        ServerMethodProcessor<? extends ServerChannelMethodProcessor> methodProcessor = getMethodProcessor();
        final int classAndMethod = in.readInt();
        int classId = classAndMethod >> 16;
        int methodId = classAndMethod & 0xFFFF;
        methodProcessor.setCurrentMethod(classId, methodId);
        try
        {
            switch (classAndMethod)
            {
                //CONNECTION_CLASS:
                case 0x000a000b:
                    ConnectionStartOkBody.process(in, methodProcessor);
                    break;
                case 0x000a0015:
                    ConnectionSecureOkBody.process(in, methodProcessor);
                    break;
                case 0x000a001f:
                    ConnectionTuneOkBody.process(in, methodProcessor);
                    break;
                case 0x000a0028:
                    ConnectionOpenBody.process(in, methodProcessor);
                    break;
                case 0x000a0032:
                    if (methodProcessor.getProtocolVersion().equals(ProtocolVersion.v0_8))
                    {
                        throw newUnknownMethodException(classId, methodId,
                                                        methodProcessor.getProtocolVersion());
                    }
                    else
                    {
                        ConnectionCloseBody.process(in, methodProcessor);
                    }
                    break;
                case 0x000a0033:
                    if (methodProcessor.getProtocolVersion().equals(ProtocolVersion.v0_8))
                    {
                        throw newUnknownMethodException(classId, methodId,
                                                        methodProcessor.getProtocolVersion());
                    }
                    else
                    {
                        methodProcessor.receiveConnectionCloseOk();
                    }
                    break;
                case 0x000a003c:
                    if (methodProcessor.getProtocolVersion().equals(ProtocolVersion.v0_8))
                    {
                        ConnectionCloseBody.process(in, methodProcessor);
                    }
                    else
                    {
                        throw newUnknownMethodException(classId, methodId,
                                                        methodProcessor.getProtocolVersion());
                    }
                    break;
                case 0x000a003d:
                    if (methodProcessor.getProtocolVersion().equals(ProtocolVersion.v0_8))
                    {
                        methodProcessor.receiveConnectionCloseOk();
                    }
                    else
                    {
                        throw newUnknownMethodException(classId, methodId,
                                                        methodProcessor.getProtocolVersion());
                    }
                    break;

                // CHANNEL_CLASS:

                case 0x0014000a:
                    ChannelOpenBody.process(channelId, in, methodProcessor);
                    break;
                case 0x00140014:
                    ChannelFlowBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00140015:
                    ChannelFlowOkBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00140028:
                    ChannelCloseBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00140029:
                    methodProcessor.getChannelMethodProcessor(channelId).receiveChannelCloseOk();
                    break;

                // ACCESS_CLASS:

                case 0x001e000a:
                    AccessRequestBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;

                // EXCHANGE_CLASS:

                case 0x0028000a:
                    ExchangeDeclareBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00280014:
                    ExchangeDeleteBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00280016:
                    ExchangeBoundBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;


                // QUEUE_CLASS:

                case 0x0032000a:
                    QueueDeclareBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00320014:
                    QueueBindBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x0032001e:
                    QueuePurgeBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00320028:
                    QueueDeleteBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x00320032:
                    QueueUnbindBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;


                // BASIC_CLASS:

                case 0x003c000a:
                    BasicQosBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0014:
                    BasicConsumeBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c001e:
                    BasicCancelBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0028:
                    BasicPublishBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0046:
                    BasicGetBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0050:
                    BasicAckBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c005a:
                    BasicRejectBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0064:
                    BasicRecoverBody.process(in, methodProcessor.getProtocolVersion(),
                                             methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0066:
                    BasicRecoverSyncBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c006e:
                    BasicRecoverSyncBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;
                case 0x003c0078:
                    BasicNackBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;

                // CONFIRM CLASS:

                case 0x0055000a:
                    ConfirmSelectBody.process(in, methodProcessor.getChannelMethodProcessor(channelId));
                    break;

                // TX_CLASS:

                case 0x005a000a:
                    if(!methodProcessor.getChannelMethodProcessor(channelId).ignoreAllButCloseOk())
                    {
                        methodProcessor.getChannelMethodProcessor(channelId).receiveTxSelect();
                    }
                    break;
                case 0x005a0014:
                    if(!methodProcessor.getChannelMethodProcessor(channelId).ignoreAllButCloseOk())
                    {
                        methodProcessor.getChannelMethodProcessor(channelId).receiveTxCommit();
                    }
                    break;
                case 0x005a001e:
                    if(!methodProcessor.getChannelMethodProcessor(channelId).ignoreAllButCloseOk())
                    {
                        methodProcessor.getChannelMethodProcessor(channelId).receiveTxRollback();
                    }
                    break;


                default:
                    throw newUnknownMethodException(classId, methodId,
                                                    methodProcessor.getProtocolVersion());

            }
        }
        finally
        {
            methodProcessor.setCurrentMethod(0, 0);
        }
    }

}
