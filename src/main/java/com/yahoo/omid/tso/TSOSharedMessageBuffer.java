/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import com.yahoo.omid.tso.messages.TimestampResponse;
import com.yahoo.omid.tso.messages.TimestampRequest;
import com.yahoo.omid.tso.messages.CommitRequest;
import com.yahoo.omid.tso.messages.MultiCommitRequest;
import com.yahoo.omid.client.TSOClient;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.Channels;

public class TSOSharedMessageBuffer {

   private static final Log LOG = LogFactory.getLog(TSOSharedMessageBuffer.class);
   
   private TSOState state;
   
   TSOBuffer pastBuffer = new TSOBuffer();
   TSOBuffer currentBuffer = new TSOBuffer();
   ChannelBuffer writeBuffer = currentBuffer.buffer;
   Deque<TSOBuffer> futureBuffer = new ArrayDeque<TSOBuffer>();
   
   static long _1B = 0;
   static long _2B = 0;
   static long _AB = 0;
   static long _AS = 0;
   static long _LL = 0;
   static long _Coms = 0;
   static long _Writes = 0;
   static double _Avg = 0;
   static double _Avg2 = 0;

   static long _ha = 0;
   static long _fa = 0;
   static long _li = 0;

   static long _overflows = 0;
   static long _emptyFlushes = 0;

   /**
    * The consumer of the reading buffer
    */
   interface Consumer extends Comparable<Consumer> {
       public ChannelFuture consume(ChannelBuffer data);
   }

   class ReadingBuffer implements Comparable<ReadingBuffer> {
       private ChannelBuffer readBuffer;
       private int readerIndex = 0;
       private TSOBuffer readingBuffer;
       //private Channel channel;
       private Consumer consumer;
       
       void init(Consumer consumer) {
           //this.channel = channel;
           this.consumer = consumer;
           this.readingBuffer = currentBuffer.reading(this);
           this.readBuffer = readingBuffer.buffer;
           this.readerIndex = readBuffer.writerIndex();
       }

       public ReadingBuffer(Channel channel) {
           init(new ChannelConsumer(channel));
       }

       public ReadingBuffer(TSOClient tsoClient) {
           init(new TSOConsumer(tsoClient));
       }

       public void flush() {
          flush(true, false);
       }
       
       //if deleteRef is false, the flush is forced due lack of space
       private void flush(boolean deleteRef, final boolean clearPast) {
          int readable = readBuffer.readableBytes() - readerIndex;

          ++_flushes;
          if (!deleteRef) _forcedflushes++;

          _flSize += readable;
          if (readable == 0 && readingBuffer != pastBuffer) {
             _emptyFlushes++;
              if (wrap) {
                 //Channels.write(channel, tBuffer);
                 consumer.consume(tBuffer);
              }
              return;
          }

          ChannelBuffer temp;
          if (wrap && readingBuffer != pastBuffer) {
              temp = ChannelBuffers.wrappedBuffer(readBuffer.slice(readerIndex, readable), tBuffer);  
          } else {
              temp = readBuffer.slice(readerIndex, readable);
          }
          //ChannelFuture future = Channels.write(channel, temp);
          ChannelFuture future = consumer.consume(temp);
          //TODO: future could be null since the corresponding channel is not connected
          readerIndex += readable;
          if (readingBuffer == pastBuffer) {
             readingBuffer = currentBuffer.reading(this);
             readBuffer = readingBuffer.buffer;
             readerIndex = 0;
             readable = readBuffer.readableBytes();
             if (wrap) {
                 temp = ChannelBuffers.wrappedBuffer(readBuffer.slice(readerIndex, readable), tBuffer);
             } else {
                 temp = readBuffer.slice(readerIndex, readable);
             }
             //Channels.write(channel, temp);
             consumer.consume(temp);
             readerIndex += readable;
             _flSize += readable;
             if (deleteRef) {
                 pastBuffer.readingBuffers.remove(this);
             }
             pastBuffer.incrementPending();
             final TSOBuffer pendingBuffer = pastBuffer;
             future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                   if (clearPast) {
                      pendingBuffer.readingBuffers.clear();
                   }
                   if (pendingBuffer.decrementPending() && pendingBuffer.readingBuffers.size() == 0) {
                      pendingBuffer.buffer.clear();
                      synchronized (futureBuffer) {
                          futureBuffer.add(pendingBuffer);
                      }
                   }
                }
             });
          }
          
       }
       
       public class ChannelConsumer implements Consumer {
           Channel channel;

           public ChannelConsumer(Channel channel) {
               this.channel = channel;
           }

           public ChannelFuture consume(ChannelBuffer data) {
               return Channels.write(channel, data);
           }

           @Override
           public int compareTo(Consumer c) {
               if (!(c instanceof ChannelConsumer))
                   return -1;//TODO: it is ugly
               ChannelConsumer cc = (ChannelConsumer)c;
               return this.channel.compareTo(cc.channel);
           }

           @Override
           public boolean equals(Object obj) {
               if (!(obj instanceof ChannelConsumer))
                   return false;
               ChannelConsumer cc = (ChannelConsumer)obj;
               return this.channel.equals(cc.channel);
           }
       }

       public class TSOConsumer implements Consumer {
           TSOClient tsoClient;

           public TSOConsumer(TSOClient tsoClient) {
               this.tsoClient = tsoClient;
           }

           public ChannelFuture consume(ChannelBuffer data) {
               ChannelFuture future = null;
               try {
                   future = tsoClient.forward(data);
               } catch (java.io.IOException e) {
                   //TODO: do something
               }
               return future;
           }

           @Override
           public int compareTo(Consumer c) {
               if (!(c instanceof TSOConsumer))
                   return 1;//TODO: it is ugly
               TSOConsumer tc = (TSOConsumer)c;
               return this.tsoClient.compareTo(tc.tsoClient);
           }

           @Override
           public boolean equals(Object obj) {
               if (!(obj instanceof TSOConsumer))
                   return false;
               TSOConsumer tc = (TSOConsumer)obj;
               return this.tsoClient.equals(tc.tsoClient);
           }
       }

       @Override
       public int compareTo(ReadingBuffer o) {
          //return this.channel.compareTo(o.channel);
          return this.consumer.compareTo(o.consumer);
       }
       
       @Override
       public boolean equals(Object obj) {
          if (!(obj instanceof ReadingBuffer))
             return false;
          ReadingBuffer buf = (ReadingBuffer) obj;
          //return this.channel.equals(buf.channel);
          return this.consumer.equals(buf.consumer);
       }

   }
   
   public TSOSharedMessageBuffer(TSOState state) {
      this.state = state;
   }

   static private final byte m0x80 = (byte) 0x80;
   static private final byte m0x3f = (byte) 0x3f;
   static private final byte m0xff = (byte) 0xff;
   
   private ChannelBuffer tBuffer;
   private boolean wrap = false;
   
   public void writeTimestamp(TimestampResponse tr) {
       wrap = true;
       tBuffer = ChannelBuffers.buffer(1 + TimestampResponse.sizeInBytes());
       tBuffer.writeByte(TSOMessage.TimestampResponse);
       tr.writeObject(tBuffer);
   }
   
   public void rollBackTimestamp() {
       wrap = false;
   }

   public void writeCommit(long startTimestamp, long commitTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Coms;
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      long startDiff = startTimestamp - state.latestStartTimestamp;
      long commitDiff = commitTimestamp - state.latestCommitTimestamp;
      if (commitDiff == 1 && startDiff >= -32 && startDiff <= 31) {
         ++_1B;
        startDiff &= 0x3f;
        writeBuffer.writeByte((byte) startDiff);
    } else if (commitDiff == 1 && startDiff >= -8192 && startDiff <= 8191) {
       ++_2B;
          byte high = m0x80;
          high |= (startDiff >> 8) & m0x3f; 
          byte low = (byte) (startDiff & m0xff);
          writeBuffer.writeByte(high);
          writeBuffer.writeByte(low);
      } else if (commitDiff >= Byte.MIN_VALUE && commitDiff <= Byte.MAX_VALUE) {
          if (startDiff >= Byte.MIN_VALUE && startDiff <= Byte.MAX_VALUE) {
             ++_AB;
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportByteByte);
              writeBuffer.writeByte((byte) startDiff);
          } else if (startDiff >= Short.MIN_VALUE && startDiff <= Short.MAX_VALUE) {
             ++_AS;
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportShortByte);
              writeBuffer.writeShort((short) startDiff);
          } else if (startDiff >= Integer.MIN_VALUE && startDiff <= Integer.MAX_VALUE) {
             ++_LL;
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportIntegerByte);
              writeBuffer.writeInt((int) startDiff);
          } else {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportLongByte);
              writeBuffer.writeLong((byte) startDiff);
          }
          writeBuffer.writeByte((byte) commitDiff);
      }  else if (commitDiff >= Short.MIN_VALUE && commitDiff <= Short.MAX_VALUE) {
          if (startDiff >= Byte.MIN_VALUE && startDiff <= Byte.MAX_VALUE) {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportByteShort);
              writeBuffer.writeByte((byte) startDiff);
          } else if (startDiff >= Short.MIN_VALUE && startDiff <= Short.MAX_VALUE) {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportShortShort);
              writeBuffer.writeShort((short) startDiff);
          } else if (startDiff >= Integer.MIN_VALUE && startDiff <= Integer.MAX_VALUE) {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportIntegerShort);
              writeBuffer.writeInt((int) startDiff);
          } else {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportLongShort);
              writeBuffer.writeLong((byte) startDiff);
          }
          writeBuffer.writeShort((short) commitDiff);
      } else {
          writeBuffer.writeByte(TSOMessage.CommittedTransactionReport);
          writeBuffer.writeLong(startTimestamp);
          writeBuffer.writeLong(commitTimestamp);
       }
      int written = writeBuffer.readableBytes() - readBefore;
      
      _Avg2 += (written - _Avg2) / _Writes;
      _Avg += (written - _Avg) / _Coms;
      state.latestStartTimestamp = startTimestamp;
      state.latestCommitTimestamp = commitTimestamp;
   }

   public void writeHalfAbort(long startTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      long diff = startTimestamp - state.latestHalfAbortTimestamp;
      if (diff >= -16 && diff <= 15) {
         writeBuffer.writeByte((byte)((diff & 0x1f) | (0x40)));
      } else if (diff >= Byte.MIN_VALUE && diff <= Byte.MAX_VALUE) {
          writeBuffer.writeByte(TSOMessage.AbortedTransactionReportByte);
          writeBuffer.writeByte((byte)diff);
      } else {
          writeBuffer.writeByte(TSOMessage.AbortedTransactionReport);
          writeBuffer.writeLong(startTimestamp);
      }
      ++_ha;
      
      state.latestHalfAbortTimestamp = startTimestamp;
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }

   public void writeFailedElder(long startTimestamp, long commitTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();

      writeBuffer.writeByte(TSOMessage.FailedElderReport);
      writeBuffer.writeLong(startTimestamp);
      writeBuffer.writeLong(commitTimestamp);

      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }

   public void writeEldest(long startTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();

      writeBuffer.writeByte(TSOMessage.EldestUpdate);
      writeBuffer.writeLong(startTimestamp);

      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }

   /**
    * The estimate of the message size
    */
   private int ESTIMATED_WRITE_SIZE = 50;
   /**
    * This is used by the sequencer which forwards these two messages
    */
   public void writeMessage(TSOMessage msg) {
       ++_Writes;
       final int MAX_RETRY = 5;
       int readBefore = 0;
       for (int retry = 0; retry < MAX_RETRY; retry++) {//number of retries
           if (writeBuffer.writableBytes() < ESTIMATED_WRITE_SIZE) {
               nextBuffer();
           }
           writeBuffer.markWriterIndex();
           readBefore = writeBuffer.readableBytes();

           if (msg instanceof TimestampRequest)
               writeBuffer.writeByte(TSOMessage.TimestampRequest);
           else if (msg instanceof MultiCommitRequest)
               writeBuffer.writeByte(TSOMessage.MultiCommitRequest);
           //MultiCommitRequest must be before CommitRequest
           else if (msg instanceof CommitRequest)
               writeBuffer.writeByte(TSOMessage.CommitRequest);
           else {
               LOG.error("Unexpected message: " + msg);
               return;
           }
           try {
               msg.writeObject(writeBuffer);
               break;
           } catch (IndexOutOfBoundsException exp) {
               writeBuffer.resetWriterIndex();
               ESTIMATED_WRITE_SIZE *= 2;
               LOG.error("ESTIMATED_WRITE_SIZE: " + ESTIMATED_WRITE_SIZE);
               if (retry == MAX_RETRY) {
                   LOG.error(msg);
                   exp.printStackTrace();
                   throw exp;
               }
           }
       }

       int written = writeBuffer.readableBytes() - readBefore;
       _Avg2 += (written - _Avg2) / _Writes;
   }

   public void writeReincarnatedElder(long startTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();

      writeBuffer.writeByte(TSOMessage.ReincarnationReport);
      writeBuffer.writeLong(startTimestamp);

      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }

   public void writeFullAbort(long startTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      long diff = startTimestamp - state.latestFullAbortTimestamp;
      if (diff >= -16 && diff <= 15) {
         writeBuffer.writeByte((byte)((diff & 0x1f) | (0x60)));
      } else if (diff >= Byte.MIN_VALUE && diff <= Byte.MAX_VALUE) {
          writeBuffer.writeByte(TSOMessage.FullAbortReportByte);
          writeBuffer.writeByte((byte)diff);
      } else {
          writeBuffer.writeByte(TSOMessage.FullAbortReport);
          writeBuffer.writeLong(startTimestamp);
      }
      ++_fa;
      
      state.latestFullAbortTimestamp = startTimestamp;
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }
   
   public void writeLargestIncrease(long largestTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      ++_li;
      int readBefore = writeBuffer.readableBytes();
      writeBuffer.writeByte(TSOMessage.LargestDeletedTimestampReport);
      writeBuffer.writeLong(largestTimestamp);
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }
   
   private void nextBuffer() {      
      _overflows++;
      LOG.debug("Switching buffers");
      Iterator<ReadingBuffer> it = pastBuffer.readingBuffers.iterator();
      boolean moreBuffers = it.hasNext();
      while(moreBuffers) {
         ReadingBuffer buf = it.next();
         moreBuffers = it.hasNext();
         buf.flush(false, !moreBuffers);
      }
      
      pastBuffer = currentBuffer;
      currentBuffer = null;
      synchronized (futureBuffer) {
         if (!futureBuffer.isEmpty()) {
             currentBuffer = futureBuffer.removeLast();
         }
      }
      if (currentBuffer == null) {
          currentBuffer = new TSOBuffer();
      }
      writeBuffer = currentBuffer.buffer;
   }

   static long _forcedflushes = 0;
   static long _flushes = 0;
   static long _flSize = 0;
   
   public void reset() {
      if (pastBuffer != null) {
         pastBuffer.readingBuffers.clear();
         pastBuffer.buffer.clear();
      }
      if (currentBuffer != null) {
         currentBuffer.readingBuffers.clear();
         currentBuffer.buffer.clear();
      }
   }
}

