/*
 * The MIT License
 *
 * Copyright (c) 2014 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.olivergondza.dumpling.factory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.WeakHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import com.github.olivergondza.dumpling.model.ProcessRuntime;
import com.github.olivergondza.dumpling.model.StackTrace;
import com.github.olivergondza.dumpling.model.ThreadLock;
import com.github.olivergondza.dumpling.model.ThreadLock.Monitor;
import com.github.olivergondza.dumpling.model.ThreadStatus;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpRuntime;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpThread;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpThread.Builder;

import static java.util.Arrays.asList;
import static java.util.regex.Pattern.compile;


/**
 * Instantiate {@link ProcessRuntime} from threaddump produced by <tt>jstack</tt> or similar tool.
 *
 * @author ogondza
 */
public class ThreadDumpFactory {

    private static final Logger LOG = Logger.getLogger(ThreadDumpFactory.class.getName());

    private static final StackTraceElement WAIT_TRACE_ELEMENT = StackTrace.nativeElement("java.lang.Object", "wait");

    private static final String NL = "(?:\\r\\n|\\n)";
    private static final String LOCK_SUBPATTERN = "<(?:0x)?(\\w+)> \\(a ([^\\)]+)\\)";

    private static final Pattern EMPTY = Pattern.compile("^\\s*$");
    private static final Pattern THREAD_DELIMITER = Pattern.compile(NL + "(?:" + NL + "(?!\\s)|(?=\"))");
    // TODO the regex is ignoring module name and version at the time: java.lang.Thread.sleep(java.base@9-ea/Native Method)
    private static final Pattern STACK_TRACE_ELEMENT_LINE = Pattern.compile("\\s*at (\\S+)\\.(\\S+)\\((?:.+/)?([^:]+?)(\\:\\d+)?\\)");
    private static final Pattern ACQUIRED_LINE = Pattern.compile("- locked " + LOCK_SUBPATTERN);
    // Oracle/OpenJdk puts unnecessary space after 'parking to wait for'
    private static final Pattern WAITING_ON_LINE = Pattern.compile("- (?:waiting on|parking to wait for ?) " + LOCK_SUBPATTERN);
    private static final Pattern WAITING_TO_LOCK_LINE = Pattern.compile("- waiting to lock " + LOCK_SUBPATTERN);
    private static final Pattern OWNABLE_SYNCHRONIZER_LINE = Pattern.compile("- (?:locked )?" + LOCK_SUBPATTERN);
    private static final Pattern THREAD_HEADER = compile(
            "^\"(.+?)\" ([^\\n\\r]+)(?:" + NL + "\\s+java.lang.Thread.State: ([^\\n\\r]+)(?:" + NL + "(.+))?)?",
            Pattern.DOTALL
    );

    private boolean failOnErrors = false;

    /**
     * Historically, dumpling tolerates some of the errors silently.
     *
     * Turning this on will replace log records for failures to parse the threaddump.
     */
    public ThreadDumpFactory failOnErrors(boolean failOnErrors) {
        this.failOnErrors = failOnErrors;
        return this;
    }

    /**
     * Create runtime from thread dump.
     *
     * @throws IOException File could not be loaded.
     */
    public @Nonnull ThreadDumpRuntime fromFile(@Nonnull File threadDump) throws IOException {
        FileInputStream fis = new FileInputStream(threadDump);
        try {
            return fromStream(fis);
        } finally {
            fis.close();
        }
    }

    public @Nonnull ThreadDumpRuntime fromStream(@Nonnull InputStream stream) {
        Set<ThreadDumpThread.Builder> threads = new LinkedHashSet<ThreadDumpThread.Builder>();
        List<String> header = new ArrayList<String>();

        Scanner scanner = new Scanner(stream);
        scanner.useDelimiter(THREAD_DELIMITER);
        try {
            while (scanner.hasNext()) {
                String singleChunk = scanner.next();
                // Java until 8 vs. Java after 9
                if (singleChunk.startsWith("JNI global references") || singleChunk.startsWith("JNI global refs")) {
                    // Nothing interesting is expected after this point. Also, this is a convenient way to eliminate the
                    // deadlock report that is spread over several chunks
                    break;
                }

                ThreadDumpThread.Builder thread = thread(singleChunk);
                if (thread != null) {
                    threads.add(thread);
                    continue;
                }

                if (header.isEmpty()) { // Still reading header
                    header.addAll(asList(singleChunk.split(NL)));
                    continue;
                }

                // New info in Java 9
                if (singleChunk.startsWith("Threads class SMR info:")) {
                    continue;
                }

                String msg = "Skipping unrecognized chunk: >>>" + singleChunk + "<<<";
                if (failOnErrors) {
                    throw new IllegalRuntimeStateException(msg);
                } else {
                    LOG.warning(msg);
                }
            }
        } finally {
            scanner.close();
        }

        if (threads.isEmpty()) throw new IllegalRuntimeStateException(
                "No threads found in threaddump"
        );

        return new ThreadDumpRuntime(threads, header);
    }

    public @Nonnull ThreadDumpRuntime fromString(@Nonnull String runtime) {
        try {
            InputStream is = new ByteArrayInputStream(runtime.getBytes("UTF-8"));
            try {
                return fromStream(is);
            } finally {
                try {
                    is.close();
                } catch (IOException ex) {} // Ignore
            }
        } catch (UnsupportedEncodingException ex) {
            throw new AssertionError(ex);
        }
    }

    private ThreadDumpThread.Builder thread(String singleThread) {
        Matcher matcher = THREAD_HEADER.matcher(singleThread);
        if (!matcher.find()) return null;

        ThreadDumpThread.Builder builder = new ThreadDumpThread.Builder();
        builder.setName(matcher.group(1));
        initHeader(builder, matcher.group(2));

        final String trace = matcher.group(4);
        if (trace != null) {
            builder = initStacktrace(builder, trace);
        }

        String status = matcher.group(3);
        if (status != null) {
            builder.setThreadStatus(ThreadStatus.fromString(status, builder.getStacktrace().getHead()));
        }


        return fixup(builder, singleThread);
    }

    private Builder initStacktrace(Builder builder, String trace) {
        ArrayList<StackTraceElement> traceElements = new ArrayList<StackTraceElement>();

        List<ThreadLock.Monitor> monitors = new ArrayList<ThreadLock.Monitor>();
        List<ThreadLock> synchronizers = new ArrayList<ThreadLock>();
        ThreadLock waitingToLock = null; // Block waiting on monitor
        ThreadLock waitingOnLock = null; // in Object.wait()
        int depth = -1;

        Iterator<String> lines = asList(trace.split(NL)).iterator();
        while (lines.hasNext()) {
            String line = lines.next();

            StackTraceElement elem = traceElement(line);
            if (elem != null) {
                traceElements.add(elem);
                depth++;
                continue;
            }

            Matcher acquiredMatcher = ACQUIRED_LINE.matcher(line);
            if (acquiredMatcher.find()) {
                monitors.add(new ThreadLock.Monitor(createLock(acquiredMatcher), depth));
                continue;
            }

            Matcher waitingToMatcher = WAITING_TO_LOCK_LINE.matcher(line);
            if (waitingToMatcher.find()) {
                if (waitingToLock != null) throw new IllegalRuntimeStateException(
                        "Waiting to lock reported several times per single thread >>>%n%s%n<<<%n", trace
                );
                waitingToLock = createLock(waitingToMatcher);
                continue;
            }

            Matcher waitingOnMatcher = WAITING_ON_LINE.matcher(line);
            if (waitingOnMatcher.find()) {
                if (waitingOnLock != null) throw new IllegalRuntimeStateException(
                        "Waiting on lock reported several times per single thread >>>%n%s%n<<<%n", trace
                );
                waitingOnLock = createLock(waitingOnMatcher);
                continue;
            }

            if (line.contains("Locked ownable synchronizers:")) {
                while (lines.hasNext()) {
                    line = lines.next();

                    if (EMPTY.matcher(line).matches()) continue;
                    if (line.contains("- None")) break;
                    Matcher matcher = OWNABLE_SYNCHRONIZER_LINE.matcher(line);
                    if (matcher.find()) {
                        synchronizers.add(createLock(matcher));
                    } else {
                        throw new IllegalRuntimeStateException("Unable to parse ownable synchronizer: " + line);
                    }
                }
            }
        }

        builder.setAcquiredMonitors(monitors);
        builder.setAcquiredSynchronizers(synchronizers);
        builder.setStacktrace(new StackTrace(traceElements));
        builder.setWaitingToLock(waitingToLock);
        builder.setWaitingOnLock(waitingOnLock);
        return builder;
    }

    private Builder fixup(Builder builder, String wholeThread) {
        ThreadStatus status = builder.getThreadStatus();
        StackTraceElement innerFrame = builder.getStacktrace().getElement(0);
        ThreadLock waitingToLock = builder.getWaitingToLock(); // Block waiting on monitor
        ThreadLock waitingOnLock = builder.getWaitingOnLock(); // in Object.wait()
        List<ThreadLock.Monitor> monitors = builder.getAcquiredMonitors();


        // Probably a bug in JVM/jstack but let's see what we can do
        if (waitingOnLock == null && !status.isRunnable() && WAIT_TRACE_ELEMENT.equals(innerFrame)) {
            HashSet<ThreadLock> acquiredLocks = new HashSet<ThreadLock>(monitors.size());
            for (Monitor m: monitors) {
                acquiredLocks.add(m.getLock());
            }
            if (acquiredLocks.size() == 1) {
                waitingOnLock = acquiredLocks.iterator().next();
                LOG.fine("FIXUP: Adjust lock state from 'locked' to 'waiting on' when thread entering Object.wait()");
                LOG.fine(wholeThread);
            }
        }

        if (waitingOnLock != null) {
            // Eliminate self lock that is presented in threaddumps when in Object.wait(). It is a matter or convenience - not really a FIXUP
            if (filterMonitors(monitors, waitingOnLock)) {
                builder.setAcquiredMonitors(monitors);
            };

            // 'waiting on' is reported even when blocked re-entering the monitor. Convert it from waitingOn to waitingTo
            if (builder.getThreadStatus().isBlocked()) {
                LOG.fine("FIXUP: Adjust lock state from 'waiting on' to 'waiting to' when thread re-acquiring the monitor after Object.wait()");
                LOG.fine(wholeThread);
                waitingToLock = waitingOnLock;
                waitingOnLock = null;
            }
        }

        // https://github.com/olivergondza/dumpling/issues/43
        if (waitingOnLock != null && status.isRunnable()) {
            // Presumably when entering or leaving the parked state.
            // Remove the lock instead of fixing the thread status as there is
            // no general way to tell PARKED and PARKED_TIMED apart.
            LOG.fine("FIXUP: Remove 'waiting to' lock declared on RUNNABLE thread");
            LOG.fine(wholeThread);
            waitingOnLock = null;
        }

        // https://github.com/olivergondza/dumpling/issues/46
        // The lock state is changed ahead of the thread state while there can be other threads still holding the monitor
        if (waitingToLock == null && status.isBlocked()) {
            Monitor monitor = getMonitorJustAcquired(monitors);
            if (monitor != null) {
                LOG.fine("FIXUP: Adjust lock state from 'locked' to 'waiting to' on BLOCKED thread");
                LOG.fine(wholeThread);
                waitingToLock = monitor.getLock();
                monitors.remove(0);
            } else {
                LOG.fine("FIXUP: Adjust thread state from 'BLOCKED' to 'RUNNABLE' when monitor is missing");
                LOG.fine(wholeThread);
                builder.setThreadStatus(status = ThreadStatus.RUNNABLE);
            }
        }

        builder.setAcquiredMonitors(monitors);
        builder.setWaitingToLock(waitingToLock);
        builder.setWaitingOnLock(waitingOnLock);
        return builder;
    }

    // get monitor acquired on current stackframe, null when it was acquired earlier or not monitor is held
    private Monitor getMonitorJustAcquired(List<ThreadLock.Monitor> monitors) {
        if (monitors.isEmpty()) return null;
        Monitor monitor = monitors.get(0);
        if (monitor.getDepth() != 0) return null;

        for (Monitor duplicateCandidate: monitors) {
            if (monitor.equals(duplicateCandidate)) continue; // skip first - equality includes monitor depth

            if (monitor.getLock().equals(duplicateCandidate.getLock())) return null; // Acquired earlier
        }

        return monitor;
    }

    private static final WeakHashMap<String, StackTraceElement> traceElementCache = new WeakHashMap<String, StackTraceElement>();
    private StackTraceElement traceElement(String line) {
        if (!line.startsWith("\tat ") && !line.matches("(  )+at .*")) return null;

        StackTraceElement cached = traceElementCache.get(line);
        if (cached != null) return cached;

        Matcher match = STACK_TRACE_ELEMENT_LINE.matcher(line);
        if (!match.find()) return null;

        String sourceFile = match.group(3);
        int sourceLine = match.group(4) == null
                ? -1
                : Integer.parseInt(match.group(4).substring(1))
        ;

        if (sourceLine == -1 && "Native Method".equals(match.group(3))) {
            sourceFile = null;
            sourceLine = -2; // Magic value for native methods
        }

        StackTraceElement element = StackTrace.element(
                match.group(1), match.group(2), sourceFile, sourceLine
        );
        traceElementCache.put(line, element);
        return element;
    }

    private boolean filterMonitors(List<ThreadLock.Monitor> monitors, ThreadLock lock) {
        return monitors.removeIf(m -> m.getLock().equals(lock));
    }

    private @Nonnull ThreadLock createLock(Matcher matcher) {
        return new ThreadLock(matcher.group(2), parseLong(matcher.group(1)));
    }

    private void initHeader(ThreadDumpThread.Builder builder, String attrs) {
        StringTokenizer tknzr = new StringTokenizer(attrs, " ");
        while (tknzr.hasMoreTokens()) {
            String token = tknzr.nextToken();
            if ("daemon".equals(token)) builder.setDaemon(true);
            else if (token.startsWith("prio=")) builder.setPriority(Integer.parseInt(token.substring(5)));
            else if (token.startsWith("tid=")) builder.setTid(parseLong(token.substring(4)));
            else if (token.startsWith("nid=")) builder.setNid(parseNid(token.substring(4)));
            else if (token.matches("#\\d+")) builder.setId(Integer.parseInt(token.substring(1)));
            else if (token.matches("t@(\\d+)")) builder.setId(Integer.parseInt(token.substring(2)));
        }
    }

    private long parseNid(String value) {
        return value.startsWith("0x")
                ? parseLong(value.substring(2))
                : Long.parseLong(value) // Dumpling human readable output
        ;
    }

    /*package*/ static long parseLong(String value) {
        if (value.startsWith("0x")) {
            // Oracle JDK on OS X do not use prefix for tid - so we need to be able to read both
            // https://github.com/olivergondza/dumpling/issues/59
            value = value.substring(2);
        }

        // Long.parseLong is faster but unsuitable in some cases: https://github.com/olivergondza/dumpling/issues/71
        return new BigInteger(value, 16).longValue();
    }
}
