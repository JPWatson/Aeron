package io.aeron.samples;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.Strings;
import org.agrona.concurrent.SigInt;

import java.io.PrintStream;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.samples.SampleConfiguration.FRAGMENT_COUNT_LIMIT;
import static java.lang.System.exit;
import static org.agrona.SystemUtil.loadPropertiesFiles;

public class Prototyper
{
    public static void main(final String[] args)
    {
        if (args.length < 3)
        {
            usage(System.out);
            exit(-1);
        }

        loadPropertiesFiles(args);

        try (Aeron aeron = Aeron.connect())
        {
            final int streamId = Integer.parseInt(args[2]);
            final String channel = args[1];
            switch (args[0])
            {
                case "pub":
                    final ExclusivePublication publication =
                        aeron.addExclusivePublication(channel, streamId);

                    System.out.println("Publication. channel=" + channel + ", streamId=" + streamId);

                    for (int i = 3; i < args.length; i++)
                    {
                        System.out.println("Adding destination: " + i);
                        publication.addDestination(args[i]);
                    }

                    System.out.println("Start typing to send message...");

                    final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

                    final Scanner in = new Scanner(System.in);

                    String line;
                    while (!Strings.isEmpty(line = in.nextLine()))
                    {
                        final int length = buffer.putStringWithoutLengthAscii(0, line);
                        final long result = publication.offer(buffer, 0, length);

                        if (result == Publication.NOT_CONNECTED)
                        {
                            System.out.println("Failed: NOT_CONNECTED");

                        }
                        else if (result == Publication.BACK_PRESSURED)
                        {
                            System.out.println("Failed: BACK_PRESSURED");

                        }
                        else if (result == Publication.ADMIN_ACTION)
                        {
                            System.out.println("Failed: ADMIN_ACTION");

                        }
                        else if (result == Publication.CLOSED)
                        {
                            System.out.println("Failed: CLOSED");

                        }
                        else if (result == Publication.MAX_POSITION_EXCEEDED)
                        {
                            System.out.println("Failed: MAX_POSITION_EXCEEDED");
                        }
                        else
                        {
                            System.out.println("Success: " + result);
                        }
                    }
                    break;

                case "sub":
                    final Subscription subscription = aeron.addSubscription(channel, streamId,
                        SamplesUtil::printAvailableImage, SamplesUtil::printUnavailableImage);

                    System.out.println("Subscription. channel=" + channel + ", streamId=" + streamId);

                    for (int i = 3; i < args.length; i++)
                    {
                        System.out.println("Adding destination: " + i);
                        subscription.addDestination(args[i]);
                    }

                    System.out.println("Listening...");

                    final AtomicBoolean running = new AtomicBoolean(true);

                    // Register a SIGINT handler for graceful shutdown.
                    SigInt.register(() -> running.set(false));

                    final FragmentHandler fragmentHandler = SamplesUtil.printStringMessage(streamId);
                    SamplesUtil.subscriberLoop(fragmentHandler, FRAGMENT_COUNT_LIMIT, running)
                        .accept(subscription);

                    break;
            }
        }
    }

    private static void onFragment(
        final DirectBuffer directBuffer, final int offset, final int length, final Header header)
    {
        System.out.println("Received: " + directBuffer.getStringWithoutLengthAscii(offset, length));
    }

    private static void usage(final PrintStream out)
    {
        out.println("Usage: <sub|pub> <channelUri> <stream>");
    }
}
