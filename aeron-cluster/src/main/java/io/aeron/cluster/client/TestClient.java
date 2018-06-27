package io.aeron.cluster.client;

import org.agrona.BitUtil;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import static org.agrona.SystemUtil.loadPropertiesFiles;

public class TestClient
{
    private static IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1000);

    public static void main(final String[] args)
    {
        loadPropertiesFiles(args);

        final AeronCluster cluster = AeronCluster.connect(new AeronCluster.Context().sessionMessageListener(
            (correlationId, clusterSessionId, timestamp, buffer, offset, length, header) ->
            {
                final long rtt = System.currentTimeMillis() - buffer.getLong(offset);

                System.out.println("RTT: " + rtt + "ms");
            }
        ));

        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[BitUtil.SIZE_OF_LONG]);

        for (int i = 0; i < 10000; i++)
        {
            buffer.putLong(0, System.currentTimeMillis());

            cluster.offer(cluster.nextCorrelationId(), buffer, 0, 8);

            while (cluster.pollEgress() <= 0)
            {
                Thread.yield();
            }

            idleStrategy.idle();
        }
    }
}
