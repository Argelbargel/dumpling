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

import static com.github.olivergondza.dumpling.Util.only;
import static com.github.olivergondza.dumpling.Util.pause;
import static com.github.olivergondza.dumpling.Util.streamToString;
import static com.github.olivergondza.dumpling.model.ProcessThread.nameIs;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;

import javax.annotation.Nonnull;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import com.github.olivergondza.dumpling.Util;
import com.github.olivergondza.dumpling.model.StackTrace;
import com.github.olivergondza.dumpling.model.ThreadLock;
import com.github.olivergondza.dumpling.model.ThreadStatus;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpRuntime;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpThread;

/**
 * Make sure threaddump generated by vendor of current JVM can be read by dumpling.
 *
 * Rule will start groovy script from <tt>src/test/resources/com/github/olivergondza/dumpling/factory/ThreadDumpFactoryVendorTest/METHOD_NAME.groovy</tt>
 * in separated process. SUT is picked up by <tt>sut.runtime()</tt> or
 * <tt>sut.thread("name")</tt>, the method will wait for process to be ready.
 *
 * This belongs to core module though it is convenient to use Dumpling CLI as groovy interpreter.
 *
 * @author ogondza
 */
public class ThreadDumpFactoryVendorTest {

    public @Rule Runner sut = new Runner();

    @Test
    public void busyWaiting() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.RUNNABLE));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingToLock(), nullValue());
    }

    @Test
    public void waiting() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.IN_OBJECT_WAIT));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingToLock(), nullValue());
        assertThat(main.getWaitingOnLock().getClassName(), equalTo("java.lang.Object"));
    }

    @Test
    public void waitingTimed() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.IN_OBJECT_WAIT_TIMED));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingToLock(), nullValue());
        assertThat(main.getWaitingOnLock().getClassName(), equalTo("java.lang.Object"));
    }

    @Test
    public void sleeping() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.SLEEPING));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingToLock(), nullValue());
        assertThat(
                main.getStackTrace().getElement(0),
                equalTo(StackTrace.nativeElement("java.lang.Thread", "sleep"))
        );
    }

    @Test
    public void monitorDeadlock() {
        ThreadDumpThread main = sut.thread("main");
        ThreadDumpThread other = sut.thread("other");
        assertThat(main.getStatus(), equalTo(ThreadStatus.BLOCKED));
        assertThat(other.getStatus(), equalTo(ThreadStatus.BLOCKED));

        assertThat(main.getWaitingToLock(), equalTo(only(other.getAcquiredMonitors())));
        assertThat(other.getWaitingToLock(), equalTo(only(main.getAcquiredMonitors())));

        assertThat(other.getAcquiredSynchronizers(), Matchers.<ThreadLock>empty());
        assertThat(main.getAcquiredSynchronizers(), Matchers.<ThreadLock>empty());

        assertThat(main.getBlockingThread(), equalTo(other));
        assertThat(other.getBlockingThread(), equalTo(main));
        assertThat(main.getBlockedThreads().onlyThread(), equalTo(other));
        assertThat(other.getBlockedThreads().onlyThread(), equalTo(main));
    }

    @Test
    public void synchronizerDeadlock() {
        ThreadDumpThread main = sut.thread("main");
        ThreadDumpThread other = sut.thread("other");
        assertThat(main.getStatus(), equalTo(ThreadStatus.PARKED));
        assertThat(other.getStatus(), equalTo(ThreadStatus.PARKED));

        assertThat(main.getWaitingOnLock(), equalTo(only(other.getAcquiredSynchronizers())));
        assertThat(other.getWaitingOnLock(), equalTo(only(main.getAcquiredSynchronizers())));

        assertThat(other.getAcquiredMonitors(), Matchers.<ThreadLock>empty());
        assertThat(main.getAcquiredMonitors(), Matchers.<ThreadLock>empty());
    }

    @Test
    public void reacquireMonitorAfterWait() {
        ThreadDumpThread reacquiring = sut.thread("reacquiring");
        ThreadDumpThread main = sut.thread("main");

        assertThat(main.getStatus(), equalTo(ThreadStatus.SLEEPING));
        assertThat(reacquiring.getStatus(), equalTo(ThreadStatus.BLOCKED));

        assertThat(only(main.getAcquiredLocks()), equalTo(reacquiring.getWaitingToLock()));
        assertThat(reacquiring.getAcquiredLocks().size(), equalTo(2));
        assertThat(
                reacquiring.getStackTrace().getElement(0),
                equalTo(StackTrace.nativeElement("java.lang.Object", "wait"))
        );
    }

    @Test
    public void ignoreWaitLock() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.IN_OBJECT_WAIT));

        assertThat(main.getWaitingToLock(), nullValue());
        assertThat(only(main.getAcquiredLocks()).getClassName(), equalTo("java.lang.Integer"));

        assertTrue(main.getBlockedThreads().isEmpty());
        assertThat(main.getBlockingThread(), nullValue());
        assertThat(main.getWaitingOnLock().getClassName(), equalTo("java.lang.Double"));
    }

    @Test
    public void parked() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.PARKED));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingToLock(), nullValue());
        assertThat(main.getWaitingOnLock(), nullValue());
        assertThat(
                main.getStackTrace().getElement(0),
                equalTo(StackTrace.nativeElement("sun.misc.Unsafe", "park"))
        );
    }

    @Test
    public void parkedTimed() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.PARKED_TIMED));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingToLock(), nullValue());
        assertThat(main.getWaitingOnLock(), nullValue());
        assertThat(
                main.getStackTrace().getElement(0),
                equalTo(StackTrace.nativeElement("sun.misc.Unsafe", "park"))
        );
    }

    @Test
    public void parkedWithBlocker() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.PARKED));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingOnLock().getClassName(), equalTo("java.lang.Object"));
        assertThat(
                main.getStackTrace().getElement(0),
                equalTo(StackTrace.nativeElement("sun.misc.Unsafe", "park"))
        );
    }

    @Test
    public void parkedTimedWithBlocker() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getStatus(), equalTo(ThreadStatus.PARKED_TIMED));
        assertThat(main.getAcquiredLocks(), Matchers.<ThreadLock>empty());
        assertThat(main.getWaitingOnLock().getClassName(), equalTo("java.lang.Object"));
        assertThat(
                main.getStackTrace().getElement(0),
                equalTo(StackTrace.nativeElement("sun.misc.Unsafe", "park"))
        );
    }

    @Test
    public void threadNameWithQuotes() {
        ThreadDumpThread t = sut.thread("a\"thread\"");
        assertThat(t.getName(), equalTo("a\"thread\""));
    }

    @Test
    public void threadNameWithLinebreak() {
        String name = "thread" + System.getProperty("line.separator", "\n") + "name";
        ThreadDumpThread t = sut.thread(name);
        assertThat(t.getName(), equalTo(name));
    }

    @Test
    public void multipleMonitorsOnSingleStackFrame() {
        ThreadDumpThread main = sut.thread("main");
        assertThat(main.getAcquiredLocks(), equalTo(main.getAcquiredMonitors()));

        // Stack depth is not exposed, checking just the order
        ArrayList<ThreadLock> lockList = new ArrayList<ThreadLock>(main.getAcquiredLocks());
        assertThat(lockList.get(0).getClassName(), equalTo("java.lang.Double"));
        assertThat(lockList.get(1).getClassName(), equalTo("java.lang.Integer"));
        assertThat(lockList.get(2).getClassName(), equalTo("java.lang.Object"));
    }

    private static final class Runner implements MethodRule {
        // This expects PidRuntimeFactory delegates to ThreadDumpFactory
        private static final PidRuntimeFactory prf = new PidRuntimeFactory();

        private Process process;
        private ThreadDumpRuntime runtime = null;

        @Override
        public Statement apply(final Statement base, FrameworkMethod method, Object target) {
            try {
                process = run(Util.asFile(Util.resource(
                        ThreadDumpFactoryVendorTest.class, method.getName() + ".groovy"
                )));
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }

            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    try {
                        base.evaluate();
                    } finally {
                        if (process != null) process.destroy();
                    }
                }
            };
        }

        private Process run(File resourceFile) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("surefire.real.class.path"), // Inherit from surefire process
                    "com.github.olivergondza.dumpling.cli.Main",
                    "groovy",
                    "--script", resourceFile.getAbsolutePath()
            );
            return pb.start();
        }

        public ThreadDumpThread thread(@Nonnull String name) {
            return runtime().getThreads().where(nameIs(name)).onlyThread();
        }

        public ThreadDumpRuntime runtime() {
            if (runtime != null) return runtime;

            try {
                int exit = process.exitValue();
                throw reportProblem(exit, null);
            } catch (IllegalThreadStateException ex) {
                // Still running as expected
                try {

                    return runtime = waitForInitialized(process);
                } catch (IOException e) {
                    throw reportProblem(getExitIfDone(process), e);
                } catch (InterruptedException e) {
                    throw reportProblem(getExitIfDone(process), e);
                }
            }
        }

        private int getExitIfDone(Process p) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException _) {
                return -1;
            }
        }

        private Error reportProblem(int exit, Exception cause) {
            AssertionError error = new AssertionError(
                    "Process under test probably terminated prematurelly. Exit code: "
                    + exit + "\nSTDOUT: " + streamToString(process.getInputStream())
                    + "\nSTDERR: " + streamToString(process.getErrorStream())
            );
            error.initCause(cause);
            return error;
        }

        private ThreadDumpRuntime waitForInitialized(Process process) throws IOException, InterruptedException {
            int pid = getPid(process);
            ThreadDumpRuntime runtime = prf.fromProcess(pid);
            for (int i = 0; i < 10; i++) {
                pause(500);

                ThreadDumpThread main = runtime.getThreads().where(nameIs("main")).onlyThread();

                for (StackTraceElement elem: main.getStackTrace().getElements()) {
                    if ("dumpling-script".equals(elem.getClassName()) && "run".equals(elem.getMethodName())) {
                        return prf.fromProcess(pid);
                    }
                }

                runtime = prf.fromProcess(pid);
            }

            throw new AssertionError("Process under test not initialized in time: " + runtime.getThreads());
        }

        private int getPid(Process process) {
            try {
                Field pidField = process.getClass().getDeclaredField("pid");
                pidField.setAccessible(true);
                int pid = (Integer) pidField.get(process);
                if (pid < 1) {
                    throw new Error("Unsupported process implementation" + process.getClass());
                }
                return pid;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    };
}
