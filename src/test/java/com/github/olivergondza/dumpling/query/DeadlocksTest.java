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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.github.olivergondza.dumpling.Util;
import com.github.olivergondza.dumpling.cli.AbstractCliTest;
import com.github.olivergondza.dumpling.factory.JvmRuntimeFactory;
import com.github.olivergondza.dumpling.factory.ThreadDumpFactory;
import com.github.olivergondza.dumpling.model.ProcessRuntime;
import com.github.olivergondza.dumpling.model.ProcessThread;
import com.github.olivergondza.dumpling.model.ThreadSet;
import com.github.olivergondza.dumpling.model.jvm.JvmRuntime;

public class DeadlocksTest extends AbstractCliTest {

    volatile boolean running = false;
    @Test
    public void noDeadlockShouldBePresentNormally() {

        Thread thread = new Thread("Running thread") {
            @Override
            public void run() {
                running = true;
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        // Ignore
                    }
                }
                running = false;
            }
        };
        thread.start();

        Util.pause(100);

        assertTrue("No deadlock should be present", deadlocks(runtime()).isEmpty());

        assertTrue(running);
    }

    @Test
    public void discoverActualDeadlock() {
        final Object lockA = new Object();
        final Object lockB = new Object();

        new Thread("Deadlock thread A") {
            @Override
            public void run() {
                while (true) {
                    synchronized (lockA) {
                        Util.pause(100);
                        synchronized (lockB) {
                            hashCode();
                        }
                    }
                }
            }
        }.start();

        new Thread("Deadlock thread B") {
            @Override
            public void run() {
                while (true) {
                    synchronized (lockB) {
                        Util.pause(100);
                        synchronized (lockA) {
                            hashCode();
                        }
                    }
                }
            }
        }.start();

        Util.pause(1000);

        ProcessRuntime runtime = runtime();
        final Set<ThreadSet> deadlocks = deadlocks(runtime);

        assertEquals("One deadlock should be present\n\n" + runtime.getThreads().toString(), 1, deadlocks.size());
        for (ThreadSet deadlock: deadlocks) {
            assertEquals("Deadlock should contain of 2 threads", 2, deadlock.size());
            for (ProcessThread thread: deadlock) {
                assertTrue(thread.getName().matches("Deadlock thread [AB]"));
            }
        }
    }

    @Test
    public void cliQuery() throws Exception {
        run("deadlocks", "--in", "threaddump", Util.resourceFile("deadlock.log").getAbsolutePath());
        assertThat(err.toString(), equalTo(""));
        assertListing(out.toString());

        assertThat(exitValue, equalTo(1));
    }

    @Test
    public void toStringNoTraces() throws Exception {
        ProcessRuntime runtime = new ThreadDumpFactory().fromFile(Util.resourceFile("deadlock.log"));
        assertListing(runtime.query(new Deadlocks()).toString());
    }

    private void assertListing(String out) {
        assertThat(out, containsString("- Handling POST /hudson/job/some_other_job/doRename : ajp-127.0.0.1-8009-24 - Handling POST /hudson/view/some_view/configSubmit : ajp-127.0.0.1-8009-107"));
        assertThat(out, not(containsString(
                "%n\"Handling POST /hudson/job/some_other_job/doRename : ajp-127.0.0.1-8009-24\" daemon prio=10 tid=1481750528 nid=27336%n"
        )));
        assertThat(out, not(containsString(
                "%n\"Handling POST /hudson/view/some_view/configSubmit : ajp-127.0.0.1-8009-107\" daemon prio=10 tid=47091108077568 nid=17982%n"
        )));
        assertThat(out, containsString("%n1 deadlocks detected%n"));
    }

    @Test
    public void cliQueryTraces() throws Exception {
        run("deadlocks", "--show-stack-traces", "--in", "threaddump", Util.resourceFile("deadlock.log").getAbsolutePath());
        assertThat(err.toString(), equalTo(""));
        assertLongListing(out.toString());

        assertThat(exitValue, equalTo(1));
    }

    @Test
    public void toStringWithTraces() throws Exception {
        ProcessRuntime runtime = new ThreadDumpFactory().fromFile(Util.resourceFile("deadlock.log"));
        assertLongListing(runtime.query(new Deadlocks().showStackTraces()).toString());
    }

    @Test
    public void typeSafeQuerying() throws Exception {
        JvmRuntime runtime = new JvmRuntimeFactory().currentRuntime();

        runtime.query(new Deadlocks()).getDeadlocks().itterator().next().forCurrentThread();
    }

    private void assertLongListing(String out) {
        assertThat(out, containsString("- Handling POST /hudson/job/some_other_job/doRename : ajp-127.0.0.1-8009-24 - Handling POST /hudson/view/some_view/configSubmit : ajp-127.0.0.1-8009-107"));

        assertThat(out, containsString(
                "%n\"Handling POST /hudson/job/some_other_job/doRename : ajp-127.0.0.1-8009-24\" daemon prio=10 tid=1481750528 nid=27336%n"
        ));
        assertThat(out, containsString(
                "%n\"Handling POST /hudson/view/some_view/configSubmit : ajp-127.0.0.1-8009-107\" daemon prio=10 tid=47091108077568 nid=17982%n"
        ));
        assertThat(out, containsString("%n1 deadlocks detected%n"));
    }

    private Set<ThreadSet> deadlocks(ProcessRuntime runtime) {
        return runtime.getThreads().query(new Deadlocks()).getDeadlocks();
    }

    private ProcessRuntime runtime() {
        return new JvmRuntimeFactory().currentRuntime();
    }
}
