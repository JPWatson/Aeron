/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.cluster.client.ClusterClock;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.NanoClock;

import java.util.concurrent.TimeUnit;

class TestClusterClock implements ClusterClock, EpochClock, NanoClock
{
    private volatile long tick;
    private final TimeUnit timeUnit;

    TestClusterClock(final TimeUnit timeUnit)
    {
        this.timeUnit = timeUnit;
    }

    public TimeUnit timeUnit()
    {
        return timeUnit;
    }

    public long time()
    {
        return timeUnit.toMillis(tick);
    }

    public long nanoTime()
    {
        return timeUnit.toNanos(tick);
    }

    void update(final long tick, final TimeUnit tickTimeUnit)
    {
        this.tick = tickTimeUnit.convert(tick, tickTimeUnit);
    }
}
