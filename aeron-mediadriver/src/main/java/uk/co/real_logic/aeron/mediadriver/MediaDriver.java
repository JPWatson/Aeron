/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.mediadriver;

import uk.co.real_logic.aeron.mediadriver.buffer.BufferManagement;
import uk.co.real_logic.aeron.util.*;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.OneToOneConcurrentArrayQueue;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBuffer;
import uk.co.real_logic.aeron.util.status.StatusBufferCreator;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static java.lang.Integer.getInteger;
import static uk.co.real_logic.aeron.mediadriver.buffer.BufferManagement.newMappedBufferManager;
import static uk.co.real_logic.aeron.util.CommonConfiguration.*;
import static uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBufferDescriptor.TRAILER_LENGTH;

/**
 * Main class for JVM-based mediadriver
 * <p>
 * <p>
 * Usage:
 * <code>
 * $ java -jar aeron-mediadriver.jar
 * $ java -Doption=value -jar aeron-mediadriver.jar
 * </code>
 * Properties
 * <ul>
 * <li><code>aeron.conductor.dir</code>: Use value as directory name for conductor buffers.</li>
 * <li><code>aeron.data.dir</code>: Use value as directory name for data buffers.</li>
 * <li><code>aeron.rcv.buffer.size</code>: Use int value as size of buffer for receiving from network.</li>
 * <li><code>aeron.command.buffer.size</code>: Use int value as size of the command buffers between threads.</li>
 * <li><code>aeron.conductor.buffer.size</code>: Use int value as size of the conductor buffers between the media
 * driver and the client.</li>
 * </ul>
 */
public class MediaDriver implements AutoCloseable
{
    /**
     * Byte buffer size (in bytes) for reads
     */
    public static final String READ_BUFFER_SZ_PROP_NAME = "aeron.rcv.buffer.size";

    /**
     * Size (in bytes) of the command buffers between threads
     */
    public static final String COMMAND_BUFFER_SZ_PROP_NAME = "aeron.command.buffer.size";

    /**
     * Size (in bytes) of the conductor buffers between the media driver and the client
     */
    public static final String CONDUCTOR_BUFFER_SZ_PROP_NAME = "aeron.conductor.buffer.size";

    /**
     * Size (in bytes) of the counter storage buffer
     */
    public static final String COUNTERS_BUFFER_SZ_PROP_NAME = "aeron.counters.buffer.size";

    /**
     * Size (in bytes) of the counter storage buffer
     */
    public static final String DESCRIPTOR_BUFFER_SZ_PROP_NAME = "aeron.counters.descriptor.size";

    /**
     * Default byte buffer size for reads
     */
    public static final int READ_BYTE_BUFFER_SZ_DEFAULT = 4096;

    /**
     * Default buffer size for command buffers between threads
     */
    public static final int COMMAND_BUFFER_SZ_DEFAULT = 65536;

    /**
     * Default buffer size for conductor buffers between the media driver and the client
     */
    public static final int CONDUCTOR_BUFFER_SZ_DEFAULT = 65536 + TRAILER_LENGTH;

    /**
     * Size (in bytes) of the counter storage buffer
     */
    public static final int COUNTERS_BUFFER_SZ_DEFAULT = 65536;

    /**
     * Size (in bytes) of the counter storage buffer
     */
    public static final int DESCRIPTOR_BUFFER_SZ_DEFAULT = 65536;

    /**
     * Default group size estimate for NAK delay randomization
     */
    public static final int NAK_GROUPSIZE_DEFAULT = 10;
    /**
     * Default group RTT estimate for NAK delay randomization in msec
     */
    public static final int NAK_GRTT_DEFAULT = 10;
    /**
     * Default max backoff for NAK delay randomization in msec
     */
    public static final int NAK_MAX_BACKOFF_DEFAULT = 60;

    /**
     * Default Unicast NAK delay in nanoseconds
     */
    public static final long NAK_UNICAST_DELAY_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Default group size estimate for retransmit delay randomization
     */
    public static final int RETRANS_GROUPSIZE_DEFAULT = 10;
    /**
     * Default group RTT estimate for retransmit delay randomization in msec
     */
    public static final int RETRANS_GRTT_DEFAULT = 10;
    /**
     * Default max backoff for retransmit delay randomization in msec
     */
    public static final int RETRANS_MAX_BACKOFF_DEFAULT = 60;
    /**
     * Default delay for retransmission of data for unicast
     */
    public static final long RETRANS_UNICAST_DELAY_DEFAULT_NS = TimeUnit.NANOSECONDS.toNanos(0);
    /**
     * Default delay for linger for unicast
     */
    public static final long RETRANS_UNICAST_LINGER_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Default max number of active retransmissions per Term
     */
    public static final int MAX_RETRANSMITS_DEFAULT = 16;

    public static final long DESCRIPTOR_BUFFER_SIZE = 1024L;
    public static final long COUNTERS_BUFFER_SIZE = 1024L;

    public static final int READ_BYTE_BUFFER_SZ = getInteger(READ_BUFFER_SZ_PROP_NAME, READ_BYTE_BUFFER_SZ_DEFAULT);
    public static final int COMMAND_BUFFER_SZ = getInteger(COMMAND_BUFFER_SZ_PROP_NAME, COMMAND_BUFFER_SZ_DEFAULT);
    public static final int CONDUCTOR_BUFFER_SZ = getInteger(CONDUCTOR_BUFFER_SZ_PROP_NAME, CONDUCTOR_BUFFER_SZ_DEFAULT);
    public static final int COUNTERS_BUFFER_SZ = getInteger(COUNTERS_BUFFER_SZ_PROP_NAME, COUNTERS_BUFFER_SZ_DEFAULT);
    public static final int DESCRIPTOR_BUFFER_SZ = getInteger(DESCRIPTOR_BUFFER_SZ_PROP_NAME,
                                                              DESCRIPTOR_BUFFER_SZ_DEFAULT);

    /**
     * ticksPerWheel for TimerWheel in conductor thread
     */
    public static final int MEDIA_CONDUCTOR_TICKS_PER_WHEEL = 1024;

    /**
     * tickDuration (in MICROSECONDS) for TimerWheel in conductor thread
     */
    public static final int MEDIA_CONDUCTOR_TICK_DURATION_US = 10 * 1000;

    public static final long AGENT_IDLE_MAX_SPINS = 5000;
    public static final long AGENT_IDLE_MAX_YIELDS = 100;
    public static final long AGENT_IDLE_MIN_PARK_NS = TimeUnit.NANOSECONDS.toNanos(10);
    public static final long AGENT_IDLE_MAX_PARK_NS = TimeUnit.MICROSECONDS.toNanos(100);

    private final File adminDirFile;
    private final File dataDirFile;
    private final File countersDirFile;

    private final ConductorShmBuffers conductorShmBuffers;
    private final BufferManagement bufferManagement;
    private final StatusBufferCreator countersCreator;

    private final Receiver receiver;
    private final Sender sender;
    private final MediaConductor conductor;

    private ExecutorService executor;

    private Thread conductorThread;
    private Thread senderThread;
    private Thread receiverThread;

    private Future conductorFuture;
    private Future senderFuture;
    private Future receiverFuture;

    /**
     * Start Media Driver as a stand-alone process.
     *
     * @param args command line arguments
     * @throws Exception
     */
    public static void main(final String[] args) throws Exception
    {
        try (final MediaDriver mediaDriver = new MediaDriver())
        {
            mediaDriver.invokeDaemonized();

            while (true)
            {
                Thread.sleep(1000);
            }
        }
    }

    public MediaDriver() throws Exception
    {
        this.adminDirFile = new File(ADMIN_DIR_NAME);
        this.dataDirFile = new File(DATA_DIR_NAME);
        this.countersDirFile = new File(COUNTERS_DIR_NAME);

        ensureDirectoriesExist();

        this.conductorShmBuffers = new ConductorShmBuffers(ADMIN_DIR_NAME, CONDUCTOR_BUFFER_SZ);
        this.bufferManagement = newMappedBufferManager(DATA_DIR_NAME);
        this.countersCreator = new StatusBufferCreator(DESCRIPTOR_BUFFER_SIZE, COUNTERS_BUFFER_SIZE);

        final Context ctx = new Context()
            .conductorCommandBuffer(COMMAND_BUFFER_SZ)
            .receiverCommandBuffer(COMMAND_BUFFER_SZ)
            .receiverNioSelector(new NioSelector())
            .conductorNioSelector(new NioSelector())
            .senderFlowControl(UnicastSenderControlStrategy::new)
            .conductorShmBuffers(conductorShmBuffers)
            .bufferManagement(bufferManagement)
            .subscribedSessions(new AtomicArray<>())
            .conductorTimerWheel(new TimerWheel(MEDIA_CONDUCTOR_TICK_DURATION_US,
                                                TimeUnit.MICROSECONDS,
                                                MEDIA_CONDUCTOR_TICKS_PER_WHEEL))
            .newReceiveBufferEventQueue(new OneToOneConcurrentArrayQueue<>(1024))
            .mtuLength(CommonConfiguration.MTU_LENGTH)
            .conductorIdleStrategy(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                                                         AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS))
            .senderIdleStrategy(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                                                      AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS))
            .receiverIdleStrategy(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                                                        AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS));

        ctx.receiverProxy(new ReceiverProxy(ctx.receiverCommandBuffer(), ctx.newReceiveBufferEventQueue()));
        ctx.mediaConductorProxy(new MediaConductorProxy(ctx.mediaCommandBuffer()));

        this.receiver = new Receiver(ctx);
        this.sender = new Sender(ctx);
        this.conductor = new MediaConductor(ctx, sender);
    }

    public Receiver receiver()
    {
        return receiver;
    }

    public Sender sender()
    {
        return sender;
    }

    public MediaConductor conductor()
    {
        return conductor;
    }

    /**
     * Spin up all {@link Agent}s as Daemon threads.
     */
    public void invokeDaemonized()
    {
        conductorThread = new Thread(conductor);
        invokeDaemonized(conductorThread, "media-driver-conductor");

        senderThread = new Thread(sender);
        invokeDaemonized(senderThread, "media-driver-sender");

        receiverThread = new Thread(receiver);
        invokeDaemonized(receiverThread, "media-driver-receiver");
    }

    /**
     * Spin up specific thread as a Daemon thread.
     *
     * @param agentThread thread to Daemonize
     * @param name        to associate with thread
     */
    public void invokeDaemonized(final Thread agentThread, final String name)
    {
        agentThread.setName(name);
        agentThread.setDaemon(true);
        agentThread.start();
    }

    /**
     * Invoke and start all {@link uk.co.real_logic.aeron.util.Agent}s internal to the media driver using
     * a fixed size thread pool internal to the media driver.
     */
    public void invokeEmbedded()
    {
        executor = Executors.newFixedThreadPool(3);

        conductorFuture = executor.submit(conductor);
        senderFuture = executor.submit(sender);
        receiverFuture = executor.submit(receiver);
    }

    /**
     * Stop running {@link uk.co.real_logic.aeron.util.Agent}s. Waiting for each to finish.
     *
     * @throws Exception
     */
    public void shutdown() throws Exception
    {
        if (null != senderThread)
        {
            shutdown(senderThread, sender);
        }

        if (null != receiverThread)
        {
            shutdown(receiverThread, receiver);
        }

        if (null != conductorThread)
        {
            shutdown(conductorThread, conductor);
        }

        if (null != executor)
        {
            shutdownExecutorThread(senderFuture, sender);
            shutdownExecutorThread(receiverFuture, receiver);
            shutdownExecutorThread(conductorFuture, conductor);

            executor.shutdown();
        }
    }

    /**
     * Close and cleanup all resources for media driver
     *
     * @throws Exception
     */
    public void close() throws Exception
    {
        receiver.close();
        receiver.nioSelector().selectNowWithoutProcessing();
        sender.close();
        conductor.close();
        conductor.nioSelector().selectNowWithoutProcessing();
        conductorShmBuffers.close();
        bufferManagement.close();
        countersCreator.close();
        deleteDirectories();
    }

    public void ensureDirectoriesExist() throws Exception
    {
        final BiConsumer<String, String> callback = (path, name) ->
        {
            // TODO: replace with logging?
            System.err.println("WARNING: " + name + " directory already exists: " + path);
        };

        IoUtil.ensureDirectoryExists(adminDirFile, "conductor", callback);
        IoUtil.ensureDirectoryExists(dataDirFile, "data", callback);
        IoUtil.ensureDirectoryExists(countersDirFile, "counter", callback);
    }

    public void deleteDirectories() throws Exception
    {
        if (DIRS_DELETE_ON_EXIT)
        {
            IoUtil.delete(adminDirFile, false);
            IoUtil.delete(dataDirFile, false);
            IoUtil.delete(countersDirFile, false);
        }
    }

    private void shutdown(final Thread thread, final Agent agent)
    {
        agent.stop();
        thread.interrupt();

        do
        {
            try
            {
                thread.join(100);

                if (!thread.isAlive())
                {
                    break;
                }
            }
            catch (final InterruptedException ex)
            {
                System.err.println("Daemon Thread <" + thread.getName() + "> interrupted stop. Retrying...");
                thread.interrupt();
            }
        }
        while (true);
    }

    private void shutdownExecutorThread(final Future future, final Agent agent)
    {
        agent.stop();

        try
        {
            future.get(100, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException ex)
        {
            future.cancel(true);
        }
        catch (final Exception ex)
        {
            ex.printStackTrace();
        }
    }

    public static class Context
    {
        private RingBuffer mediaCommandBuffer;
        private RingBuffer receiverCommandBuffer;
        private BufferManagement bufferManagement;
        private ConductorShmBuffers conductorShmBuffers;
        private NioSelector receiverNioSelector;
        private NioSelector conductorNioSelector;
        private Supplier<SenderControlStrategy> senderFlowControl;
        private TimerWheel conductorTimerWheel;
        private int mtuLength;
        private Queue<NewReceiveBufferEvent> newReceiveBufferEventQueue;
        private ReceiverProxy receiverProxy;
        private MediaConductorProxy mediaConductorProxy;
        private AgentIdleStrategy conductorIdleStrategy;
        private AgentIdleStrategy senderIdleStrategy;
        private AgentIdleStrategy receiverIdleStrategy;
        private AtomicArray<SubscribedSession> subscribedSessions;

        private void initializeWithSystemProperties()
        {
            // TODO: grab system properties and fill with defaults
        }

        private RingBuffer createNewCommandBuffer(final int size)
        {
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(size + TRAILER_LENGTH);
            final AtomicBuffer atomicBuffer = new AtomicBuffer(byteBuffer);

            return new ManyToOneRingBuffer(atomicBuffer);
        }

        public Context conductorCommandBuffer(final int size)
        {
            this.mediaCommandBuffer = createNewCommandBuffer(size);
            return this;
        }

        public Context receiverCommandBuffer(final int size)
        {
            this.receiverCommandBuffer = createNewCommandBuffer(size);
            return this;
        }

        public Context bufferManagement(final BufferManagement bufferManagement)
        {
            this.bufferManagement = bufferManagement;
            return this;
        }

        public Context conductorShmBuffers(final ConductorShmBuffers conductorShmBuffers)
        {
            this.conductorShmBuffers = conductorShmBuffers;
            return this;
        }

        public Context receiverNioSelector(final NioSelector nioSelector)
        {
            this.receiverNioSelector = nioSelector;
            return this;
        }

        public Context conductorNioSelector(final NioSelector nioSelector)
        {
            this.conductorNioSelector = nioSelector;
            return this;
        }

        public Context senderFlowControl(final Supplier<SenderControlStrategy> senderFlowControl)
        {
            this.senderFlowControl = senderFlowControl;
            return this;
        }

        public Context mtuLength(final int mtuLength)
        {
            this.mtuLength = mtuLength;
            return this;
        }

        public Context conductorTimerWheel(final TimerWheel wheel)
        {
            this.conductorTimerWheel = wheel;
            return this;
        }

        public Context newReceiveBufferEventQueue(final Queue<NewReceiveBufferEvent> newReceiveBufferEventQueue)
        {
            this.newReceiveBufferEventQueue = newReceiveBufferEventQueue;
            return this;
        }

        public Context receiverProxy(final ReceiverProxy receiverProxy)
        {
            this.receiverProxy = receiverProxy;
            return this;
        }

        public Context mediaConductorProxy(final MediaConductorProxy mediaConductorProxy)
        {
            this.mediaConductorProxy = mediaConductorProxy;
            return this;
        }

        public Context conductorIdleStrategy(final AgentIdleStrategy strategy)
        {
            this.conductorIdleStrategy = strategy;
            return this;
        }

        public Context senderIdleStrategy(final AgentIdleStrategy strategy)
        {
            this.senderIdleStrategy = strategy;
            return this;
        }

        public Context receiverIdleStrategy(final AgentIdleStrategy strategy)
        {
            this.receiverIdleStrategy = strategy;
            return this;
        }

        public Context subscribedSessions(final AtomicArray<SubscribedSession> subscribedSessions)
        {
            this.subscribedSessions = subscribedSessions;
            return this;
        }

        public RingBuffer mediaCommandBuffer()
        {
            return mediaCommandBuffer;
        }

        public RingBuffer receiverCommandBuffer()
        {
            return receiverCommandBuffer;
        }

        public BufferManagement bufferManagement()
        {
            return bufferManagement;
        }

        public ConductorShmBuffers conductorShmBuffers()
        {
            return conductorShmBuffers;
        }

        public NioSelector receiverNioSelector()
        {
            return receiverNioSelector;
        }

        public NioSelector conductorNioSelector()
        {
            return conductorNioSelector;
        }

        public Supplier<SenderControlStrategy> senderFlowControl()
        {
            return senderFlowControl;
        }

        public int mtuLength()
        {
            return mtuLength;
        }

        public TimerWheel conductorTimerWheel()
        {
            return conductorTimerWheel;
        }

        public Queue<NewReceiveBufferEvent> newReceiveBufferEventQueue()
        {
            return newReceiveBufferEventQueue;
        }

        public ReceiverProxy receiverProxy()
        {
            return receiverProxy;
        }

        public MediaConductorProxy mediaConductorProxy()
        {
            return mediaConductorProxy;
        }

        public AgentIdleStrategy conductorIdleStrategy()
        {
            return conductorIdleStrategy;
        }

        public AgentIdleStrategy senderIdleStrategy()
        {
            return senderIdleStrategy;
        }

        public AgentIdleStrategy receiverIdleStrategy()
        {
            return receiverIdleStrategy;
        }

        public AtomicArray<SubscribedSession> subscribedSessions()
        {
            return subscribedSessions;
        }
    }
}
