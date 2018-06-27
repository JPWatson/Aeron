package io.aeron.cluster.service;

import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

import java.util.Date;

public class DebugClusteredService implements ClusteredService
{
    private static void print(final String str)
    {
        System.out.print(new Date().toString() + " - ");
        System.out.println(str);
    }

    @Override
    public void onStart(final Cluster cluster)
    {
        print("onStart");
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestampMs)
    {
        print("onSessionOpen sessionId: " + session.id());
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestampMs, final CloseReason closeReason)
    {
        print("onSessionClose sessionId: " + session.id());
    }

    @Override
    public void onSessionMessage(
        final ClientSession session, final long correlationId, final long timestampMs,
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        print("onSessionMessage sessionId: " + session + ", correlationId: " + correlationId);

        final long result = session.offer(correlationId, buffer, offset, length);

        if (result <= 0)
        {
            print("failed to echo message result: " + result);
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestampMs)
    {
        print("onTimerEvent");
    }

    @Override
    public void onTakeSnapshot(final Publication snapshotPublication)
    {
        print("onTakeSnapshot");
    }

    @Override
    public void onLoadSnapshot(final Image snapshotImage)
    {
        print("onLoadSnapshot");
    }

    @Override
    public void onReplayBegin()
    {
        print("onReplayBegin");
    }

    @Override
    public void onReplayEnd()
    {
        print("onReplayEnd");
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole)
    {
        print("onRoleChange newRole: " + newRole);
    }

    @Override
    public void onReady()
    {
        print("OnReady");
    }
}
