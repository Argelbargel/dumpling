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
package com.github.olivergondza.dumpling.query;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.Option;

import com.github.olivergondza.dumpling.cli.CliCommand;
import com.github.olivergondza.dumpling.cli.ProcessStream;
import com.github.olivergondza.dumpling.model.ProcessRuntime;
import com.github.olivergondza.dumpling.model.ProcessThread;
import com.github.olivergondza.dumpling.model.ThreadLock;
import com.github.olivergondza.dumpling.model.ThreadSet;

/**
 * Detect deadlocks in thread set.
 *
 * @author ogondza
 */
public final class Deadlocks implements SingleThreadSetQuery<Deadlocks.Result> {

    private boolean showStackTraces = false;

    public Deadlocks showStackTraces() {
        this.showStackTraces = true;
        return this;
    }

    /**
     * @param threads Include only cycles that contain at least one of input threads.
     */
    @Override
    public @Nonnull Result query(@Nonnull ThreadSet threads) {
        return new Result(threads, showStackTraces);
    }

    public final static class Command implements CliCommand {

        @Option(name = "-i", aliases = {"--in"}, required = true, usage = "Input for process runtime")
        private ProcessRuntime runtime;

        @Option(name = "--show-stack-traces", usage = "List stack traces of all threads involved")
        private boolean showStackTraces = false;

        @Override
        public String getName() {
            return "deadlocks";
        }

        @Override
        public String getDescription() {
            return "Detect cycles of blocked threads";
        }

        @Override
        public int run(@Nonnull ProcessStream process) throws CmdLineException {

            Result result = new Result(runtime.getThreads(), showStackTraces);
            result.printInto(process.out());
            return result.exitCode();
        }
    }

    /**
     * Deadlock detection result.
     *
     * A set of all deadlocks found. Involved threads are all threads that are part of any deadlock.
     *
     * @author ogondza
     */
    public final static class Result extends SingleThreadSetQuery.Result {
        private final @Nonnull Set<ThreadSet> deadlocks;
        private final @Nonnull ThreadSet involved;

        private Result(@Nonnull ThreadSet input, boolean showStackTraces) {
            super(showStackTraces);

            final LinkedHashSet<ThreadSet> deadlocks = new LinkedHashSet<ThreadSet>(1);
            final LinkedHashSet<ProcessThread> involved = new LinkedHashSet<ProcessThread>(2);
            // No need to visit threads more than once
            final Set<ProcessThread> analyzed = new HashSet<ProcessThread>(input.size());

            for (ProcessThread thread: input) {

                ArrayList<ProcessThread> cycleCandidate = new ArrayList<ProcessThread>(2);
                for (ProcessThread blocking = thread.getBlockingThread(); blocking != null; blocking = blocking.getBlockingThread()) {
                    if (analyzed.contains(thread)) break;

                    int beginning = cycleCandidate.indexOf(blocking);
                    if (beginning != -1) {
                        List<ProcessThread> cycle = cycleCandidate.subList(beginning, cycleCandidate.size());
                        deadlocks.add(input.derive(cycle));
                        involved.addAll(cycle);
                        analyzed.addAll(cycleCandidate);
                        break;
                    }

                    cycleCandidate.add(blocking);
                }

                analyzed.add(thread);
            }

            this.deadlocks = Collections.unmodifiableSet(deadlocks);
            this.involved = input.derive(involved);
        }

        /**
         * Get found deadlocks.
         *
         * @return {@link Set} of {@link ThreadSet}s representing found deadlocks.
         */
        public @Nonnull Set<ThreadSet> getDeadlocks() {
            return deadlocks;
        }

        @Override
        protected void printResult(PrintStream out) {
            int i = 1;
            for(ThreadSet deadlock: deadlocks) {
                HashSet<ThreadLock> involvedLocks = new HashSet<ThreadLock>(deadlock.size());
                for(ProcessThread thread: deadlock) {
                    involvedLocks.add(thread.getWaitingToLock());
                }

                out.printf("%nDeadlock #%d:%n", i++);
                for(ProcessThread thread: deadlock) {
                    out.println(thread.getHeader());
                    out.printf("\tWaiting to %s%n", thread.getWaitingToLock());

                    for (ThreadLock lock: thread.getAcquiredLocks()) {

                        char mark = involvedLocks.contains(lock) ? '*' : ' ';
                        out.printf("\tAcquired %c %s%n", mark, lock);
                    }
                }
            }
        }

        @Override
        protected ThreadSet involvedThreads() {
            return involved;
        }

        @Override
        protected void printSummary(PrintStream out) {
            out.printf("Deadlocks: %d%n", deadlocks.size());
        }

        @Override
        public int exitCode() {
            return deadlocks.size();
        }
    }
}
