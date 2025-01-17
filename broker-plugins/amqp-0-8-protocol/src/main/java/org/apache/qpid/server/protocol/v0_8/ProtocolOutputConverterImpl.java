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

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.QpidException;
import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.framing.AMQBody;
import org.apache.qpid.framing.AMQDataBlock;
import org.apache.qpid.framing.AMQFrame;
import org.apache.qpid.framing.AMQMethodBody;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.BasicCancelOkBody;
import org.apache.qpid.framing.BasicContentHeaderProperties;
import org.apache.qpid.framing.BasicGetOkBody;
import org.apache.qpid.framing.BasicReturnBody;
import org.apache.qpid.framing.ContentHeaderBody;
import org.apache.qpid.framing.MessagePublishInfo;
import org.apache.qpid.protocol.AMQVersionAwareProtocolSession;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.MessageContentSource;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.plugin.MessageConverter;
import org.apache.qpid.server.protocol.MessageConverterRegistry;
import org.apache.qpid.transport.ByteBufferSender;
import org.apache.qpid.util.ByteBufferUtils;
import org.apache.qpid.util.GZIPUtils;

public class ProtocolOutputConverterImpl implements ProtocolOutputConverter
{
    private static final int BASIC_CLASS_ID = 60;
    private final AMQPConnection_0_8 _connection;
    private static final AMQShortString GZIP_ENCODING = AMQShortString.valueOf(GZIPUtils.GZIP_CONTENT_ENCODING);

    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolOutputConverterImpl.class);

    public ProtocolOutputConverterImpl(AMQPConnection_0_8 connection)
    {
        _connection = connection;
    }


    public long writeDeliver(final ServerMessage m,
                             final InstanceProperties props, int channelId,
                             long deliveryTag,
                             AMQShortString consumerTag)
    {
        final AMQMessage msg = convertToAMQMessage(m);
        final boolean isRedelivered = Boolean.TRUE.equals(props.getProperty(InstanceProperties.Property.REDELIVERED));
        AMQBody deliverBody = createEncodedDeliverBody(msg, isRedelivered, deliveryTag, consumerTag);
        return writeMessageDelivery(msg, channelId, deliverBody);
    }

    private AMQMessage convertToAMQMessage(ServerMessage serverMessage)
    {
        if(serverMessage instanceof AMQMessage)
        {
            return (AMQMessage) serverMessage;
        }
        else
        {
            return getMessageConverter(serverMessage).convert(serverMessage, _connection.getVirtualHost());
        }
    }

    private <M extends ServerMessage> MessageConverter<M, AMQMessage> getMessageConverter(M message)
    {
        Class<M> clazz = (Class<M>) message.getClass();
        return MessageConverterRegistry.getConverter(clazz, AMQMessage.class);
    }

    private long writeMessageDelivery(AMQMessage message, int channelId, AMQBody deliverBody)
    {
        return writeMessageDelivery(message, message.getContentHeaderBody(), channelId, deliverBody);
    }

    private long writeMessageDelivery(MessageContentSource message, ContentHeaderBody contentHeaderBody, int channelId, AMQBody deliverBody)
    {

        int bodySize = (int) message.getSize();
        boolean msgCompressed = isCompressed(contentHeaderBody);
        Collection<QpidByteBuffer> modifiedContentBuffers = null;

        boolean compressionSupported = _connection.isCompressionSupported();

        Collection<QpidByteBuffer> contentBuffers = message.getContent();

        long length;
        if(msgCompressed
           && !compressionSupported
           && (contentBuffers != null)
           && (modifiedContentBuffers = inflateIfPossible(contentBuffers)) != null)
        {
            BasicContentHeaderProperties modifiedProps =
                    new BasicContentHeaderProperties(contentHeaderBody.getProperties());
            modifiedProps.setEncoding((String)null);

            length = writeMessageDeliveryModified(modifiedContentBuffers, channelId, deliverBody, modifiedProps);
       }
        else if(!msgCompressed
                && compressionSupported
                && contentHeaderBody.getProperties().getEncoding()==null
                && bodySize > _connection.getMessageCompressionThreshold()
                && (contentBuffers != null)
                && (modifiedContentBuffers = deflateIfPossible(contentBuffers)) != null)
        {
            BasicContentHeaderProperties modifiedProps =
                    new BasicContentHeaderProperties(contentHeaderBody.getProperties());
            modifiedProps.setEncoding(GZIP_ENCODING);

            length = writeMessageDeliveryModified(modifiedContentBuffers, channelId, deliverBody, modifiedProps);
        }
        else
        {
            writeMessageDeliveryUnchanged(contentBuffers, channelId, deliverBody, contentHeaderBody, bodySize);

            length = bodySize;
        }

        if (contentBuffers != null)
        {
            for (QpidByteBuffer buf : contentBuffers)
            {
                buf.dispose();
            }
        }

        if (modifiedContentBuffers != null)
        {
            for(QpidByteBuffer buf : modifiedContentBuffers)
            {
                buf.dispose();
            }
        }

        return length;
    }

    private Collection<QpidByteBuffer> deflateIfPossible(final Collection<QpidByteBuffer> buffers)
    {
        try
        {
            return QpidByteBuffer.deflate(buffers);
        }
        catch (IOException e)
        {
            LOGGER.warn("Unable to compress message payload for consumer with gzip, message will be sent as is", e);
            return null;
        }
    }

    private Collection<QpidByteBuffer> inflateIfPossible(final Collection<QpidByteBuffer> buffers)
    {
        try
        {
            return QpidByteBuffer.inflate(buffers);
        }
        catch (IOException e)
        {
            LOGGER.warn("Unable to decompress message payload for consumer with gzip, message will be sent as is", e);
            return null;
        }
    }

    private int writeMessageDeliveryModified(final Collection<QpidByteBuffer> contentBuffers, final int channelId,
                                             final AMQBody deliverBody,
                                             final BasicContentHeaderProperties modifiedProps)
    {
        final int bodySize = ByteBufferUtils.remaining(contentBuffers);
        ContentHeaderBody modifiedHeaderBody = new ContentHeaderBody(modifiedProps, bodySize);
        writeMessageDeliveryUnchanged(contentBuffers, channelId, deliverBody, modifiedHeaderBody, bodySize);
        return bodySize;
    }


    private void writeMessageDeliveryUnchanged(Collection<QpidByteBuffer> contentBuffers,
                                               int channelId, AMQBody deliverBody, ContentHeaderBody contentHeaderBody,
                                               int bodySize)
    {
        if (bodySize == 0)
        {
            SmallCompositeAMQBodyBlock compositeBlock = new SmallCompositeAMQBodyBlock(channelId, deliverBody,
                                                                                       contentHeaderBody);

            writeFrame(compositeBlock);
        }
        else
        {
            int maxBodySize = (int) _connection.getMaxFrameSize() - AMQFrame.getFrameOverhead();


            int capacity = bodySize > maxBodySize ? maxBodySize : bodySize;

            int writtenSize = capacity;

            AMQBody firstContentBody = new MessageContentSourceBody(contentBuffers, 0, capacity);

            CompositeAMQBodyBlock
                    compositeBlock =
                    new CompositeAMQBodyBlock(channelId, deliverBody, contentHeaderBody, firstContentBody);
            writeFrame(compositeBlock);

            while (writtenSize < bodySize)
            {
                capacity = bodySize - writtenSize > maxBodySize ? maxBodySize : bodySize - writtenSize;
                AMQBody body = new MessageContentSourceBody(contentBuffers, writtenSize, capacity);
                writtenSize += capacity;

                writeFrame(new AMQFrame(channelId, body));
            }
        }
    }

    private boolean isCompressed(final ContentHeaderBody contentHeaderBody)
    {
        return GZIP_ENCODING.equals(contentHeaderBody.getProperties().getEncoding());
    }

    private class MessageContentSourceBody implements AMQBody
    {
        public static final byte TYPE = 3;
        private final int _length;
        private final Collection<QpidByteBuffer> _contentBuffers;
        private final int _offset;

        public MessageContentSourceBody(Collection<QpidByteBuffer> bufs, int offset, int length)
        {
            int pos = 0;
            int added = 0;

            List<QpidByteBuffer> content = new ArrayList<>(bufs.size());
            for(QpidByteBuffer buf : bufs)
            {
                if(pos < offset)
                {
                    final int remaining = buf.remaining();
                    if(pos + remaining > offset)
                    {
                        buf = buf.view(offset-pos,length);

                        content.add(buf);
                        added += buf.remaining();
                    }
                    pos += remaining;

                }
                else
                {
                    buf = buf.slice();
                    if(buf.remaining() > (length-added))
                    {
                        buf.limit(length-added);
                    }
                    content.add(buf);
                    added += buf.remaining();
                }
                if(added >= length)
                {
                    break;
                }
            }

            _contentBuffers = content;
            _offset = offset;
            _length = length;
        }

        public byte getFrameType()
        {
            return TYPE;
        }

        public int getSize()
        {
            return _length;
        }

        public void writePayload(DataOutput buffer) throws IOException
        {
            for(QpidByteBuffer buf : _contentBuffers)
            {
                if (buf.hasArray())
                {
                    buffer.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
                }
                else
                {

                    byte[] data = new byte[_length];

                    buf.get(data);

                    buffer.write(data);
                }
                buf.dispose();
            }
        }

        @Override
        public long writePayload(final ByteBufferSender sender) throws IOException
        {
            long size = 0l;
            for(QpidByteBuffer buf : _contentBuffers)
            {
                size += buf.remaining();

                sender.send(buf);
                buf.dispose();
            }
            return size;
        }

        public void handle(int channelId, AMQVersionAwareProtocolSession amqProtocolSession) throws QpidException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString()
        {
            return "[" + getClass().getSimpleName() + " offset: " + _offset + ", length: " + _length + "]";
        }

    }

    public long writeGetOk(final ServerMessage msg,
                           final InstanceProperties props,
                           int channelId,
                           long deliveryTag,
                           int queueSize)
    {
        AMQBody deliver = createEncodedGetOkBody(msg, props, deliveryTag, queueSize);
        return writeMessageDelivery(convertToAMQMessage(msg), channelId, deliver);
    }


    private AMQBody createEncodedDeliverBody(AMQMessage message,
                                             boolean isRedelivered,
                                             final long deliveryTag,
                                             final AMQShortString consumerTag)
    {

        final AMQShortString exchangeName;
        final AMQShortString routingKey;

        final MessagePublishInfo pb = message.getMessagePublishInfo();
        exchangeName = pb.getExchange();
        routingKey = pb.getRoutingKey();

        final AMQBody returnBlock = new EncodedDeliveryBody(deliveryTag, routingKey, exchangeName, consumerTag, isRedelivered);
        return returnBlock;
    }

    private class EncodedDeliveryBody implements AMQBody
    {
        private final long _deliveryTag;
        private final AMQShortString _routingKey;
        private final AMQShortString _exchangeName;
        private final AMQShortString _consumerTag;
        private final boolean _isRedelivered;
        private AMQBody _underlyingBody;

        private EncodedDeliveryBody(long deliveryTag, AMQShortString routingKey, AMQShortString exchangeName, AMQShortString consumerTag, boolean isRedelivered)
        {
            _deliveryTag = deliveryTag;
            _routingKey = routingKey;
            _exchangeName = exchangeName;
            _consumerTag = consumerTag;
            _isRedelivered = isRedelivered;
        }

        public AMQBody createAMQBody()
        {
            return _connection.getMethodRegistry().createBasicDeliverBody(_consumerTag,
                                                                               _deliveryTag,
                                                                               _isRedelivered,
                                                                               _exchangeName,
                                                                               _routingKey);
        }

        public byte getFrameType()
        {
            return AMQMethodBody.TYPE;
        }

        public int getSize()
        {
            if(_underlyingBody == null)
            {
                _underlyingBody = createAMQBody();
            }
            return _underlyingBody.getSize();
        }

        public void writePayload(DataOutput buffer) throws IOException
        {
            if(_underlyingBody == null)
            {
                _underlyingBody = createAMQBody();
            }
            _underlyingBody.writePayload(buffer);
        }

        public long writePayload(ByteBufferSender sender) throws IOException
        {
            if(_underlyingBody == null)
            {
                _underlyingBody = createAMQBody();
            }
            return _underlyingBody.writePayload(sender);
        }

        public void handle(final int channelId, final AMQVersionAwareProtocolSession amqProtocolSession)
            throws QpidException
        {
            throw new QpidException("This block should never be dispatched!");
        }

        @Override
        public String toString()
        {
            return "[" + getClass().getSimpleName() + " underlyingBody: " + String.valueOf(_underlyingBody) + "]";
        }
    }

    private AMQBody createEncodedGetOkBody(ServerMessage msg, InstanceProperties props, long deliveryTag, int queueSize)
    {
        final AMQShortString exchangeName;
        final AMQShortString routingKey;

        final AMQMessage message = convertToAMQMessage(msg);
        final MessagePublishInfo pb = message.getMessagePublishInfo();
        exchangeName = pb.getExchange();
        routingKey = pb.getRoutingKey();

        final boolean isRedelivered = Boolean.TRUE.equals(props.getProperty(InstanceProperties.Property.REDELIVERED));

        BasicGetOkBody getOkBody =
                _connection.getMethodRegistry().createBasicGetOkBody(deliveryTag,
                                                                          isRedelivered,
                                                                          exchangeName,
                                                                          routingKey,
                                                                          queueSize);

        return getOkBody;
    }

    private AMQBody createEncodedReturnFrame(MessagePublishInfo messagePublishInfo,
                                             int replyCode,
                                             AMQShortString replyText)
    {

        BasicReturnBody basicReturnBody =
                _connection.getMethodRegistry().createBasicReturnBody(replyCode,
                                                                           replyText,
                                                                           messagePublishInfo.getExchange(),
                                                                           messagePublishInfo.getRoutingKey());


        return basicReturnBody;
    }

    public void writeReturn(MessagePublishInfo messagePublishInfo, ContentHeaderBody header, MessageContentSource message, int channelId, int replyCode, AMQShortString replyText)
    {

        AMQBody returnFrame = createEncodedReturnFrame(messagePublishInfo, replyCode, replyText);

        writeMessageDelivery(message, header, channelId, returnFrame);
    }


    public void writeFrame(AMQDataBlock block)
    {
        _connection.writeFrame(block);
    }


    public void confirmConsumerAutoClose(int channelId, AMQShortString consumerTag)
    {

        BasicCancelOkBody basicCancelOkBody = _connection.getMethodRegistry().createBasicCancelOkBody(consumerTag);
        writeFrame(basicCancelOkBody.generateFrame(channelId));

    }


    public static final class CompositeAMQBodyBlock extends AMQDataBlock
    {
        public static final int OVERHEAD = 3 * AMQFrame.getFrameOverhead();

        private final AMQBody _methodBody;
        private final AMQBody _headerBody;
        private final AMQBody _contentBody;
        private final int _channel;


        public CompositeAMQBodyBlock(int channel, AMQBody methodBody, AMQBody headerBody, AMQBody contentBody)
        {
            _channel = channel;
            _methodBody = methodBody;
            _headerBody = headerBody;
            _contentBody = contentBody;
        }

        public long getSize()
        {
            return OVERHEAD + _methodBody.getSize() + _headerBody.getSize() + _contentBody.getSize();
        }

        public void writePayload(DataOutput buffer) throws IOException
        {
            AMQFrame.writeFrames(buffer, _channel, _methodBody, _headerBody, _contentBody);
        }

        @Override
        public long writePayload(final ByteBufferSender sender) throws IOException
        {
            long size = (new AMQFrame(_channel, _methodBody)).writePayload(sender);

            size += (new AMQFrame(_channel, _headerBody)).writePayload(sender);

            size += (new AMQFrame(_channel, _contentBody)).writePayload(sender);

            return size;
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("[").append(getClass().getSimpleName())
                .append(" methodBody=").append(_methodBody)
                .append(", headerBody=").append(_headerBody)
                .append(", contentBody=").append(_contentBody)
                .append(", channel=").append(_channel).append("]");
            return builder.toString();
        }

    }

    public static final class SmallCompositeAMQBodyBlock extends AMQDataBlock
    {
        public static final int OVERHEAD = 2 * AMQFrame.getFrameOverhead();

        private final AMQBody _methodBody;
        private final AMQBody _headerBody;
        private final int _channel;


        public SmallCompositeAMQBodyBlock(int channel, AMQBody methodBody, AMQBody headerBody)
        {
            _channel = channel;
            _methodBody = methodBody;
            _headerBody = headerBody;

        }

        public long getSize()
        {
            return OVERHEAD + _methodBody.getSize() + _headerBody.getSize() ;
        }

        public void writePayload(DataOutput buffer) throws IOException
        {
            AMQFrame.writeFrames(buffer, _channel, _methodBody, _headerBody);
        }

        @Override
        public long writePayload(final ByteBufferSender sender) throws IOException
        {
            long size = (new AMQFrame(_channel, _methodBody)).writePayload(sender);
            size += (new AMQFrame(_channel, _headerBody)).writePayload(sender);
            return size;
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(getClass().getSimpleName())
                .append("methodBody=").append(_methodBody)
                .append(", headerBody=").append(_headerBody)
                .append(", channel=").append(_channel).append("]");
            return builder.toString();
        }
    }

}
