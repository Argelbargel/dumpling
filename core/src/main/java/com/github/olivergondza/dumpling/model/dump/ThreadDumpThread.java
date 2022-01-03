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
package com.github.olivergondza.dumpling.model.dump;


import com.github.olivergondza.dumpling.factory.IllegalRuntimeStateException;
import com.github.olivergondza.dumpling.model.ProcessThread;
import com.github.olivergondza.dumpling.model.ThreadStatus;

import javax.annotation.Nonnull;


public final class ThreadDumpThread extends ProcessThread<ThreadDumpThread, ThreadDumpThreadSet, ThreadDumpRuntime> {
    ThreadDumpThread(@Nonnull ThreadDumpRuntime runtime, @Nonnull ThreadDumpThread.Builder builder) {
        super(runtime, builder);
    }

    @Override
    protected void checkSanity(ProcessThread.Builder<?> state) {
        super.checkSanity(state);
        ThreadStatus status = state.getThreadStatus();
        if (state.getWaitingToLock() != null && !status.isBlocked() && !status.isParked()) {
            throw new IllegalRuntimeStateException(
                    "%s thread declares waitingTo lock %s: >>>%n%s%n<<<%n", status, state.getWaitingToLock(), state
            );
        }
        if (state.getWaitingOnLock() != null && !status.isWaiting() && !status.isParked()) {
            throw new IllegalRuntimeStateException(
                    "%s thread declares waitingOn lock %s: >>>%n%s%n<<<%n", status, state.getWaitingToLock(), state
            );
        }
    }

    public final static class Builder extends ProcessThread.Builder<Builder> {
    }
}
