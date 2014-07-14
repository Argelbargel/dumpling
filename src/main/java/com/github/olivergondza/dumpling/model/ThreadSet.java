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
package com.github.olivergondza.dumpling.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.annotation.Nonnull;

public class ThreadSet implements Collection<ProcessThread> {

    private @Nonnull ProcessRuntime runtime;
    private @Nonnull Set<ProcessThread> threads;

    public ThreadSet(@Nonnull ProcessRuntime runtime, @Nonnull Set<ProcessThread> threads) {
        this.runtime = runtime;
        this.threads = threads;
    }

    /**
     * Extract the only thread from set.
     *
     * @throws IllegalStateException if not exactly one thread present.
     */
    public @Nonnull ProcessThread onlyThread() throws IllegalStateException {
        if (size() != 1) throw new IllegalStateException(
                "Exactly one thread expected in the set. Found " + size()
        );

        return threads.iterator().next();
    }

    public @Nonnull ThreadSet onlyNamed(final String name) {
        return filter(new Predicate() {
            public boolean isValid(ProcessThread thread) {
                return thread.getName().equals(name);
            }
        });
    }

    private @Nonnull ThreadSet filter(Predicate pred) {
        HashSet<ProcessThread> subset = new HashSet<ProcessThread>(size() / 2);
        for (@Nonnull ProcessThread thread: threads) {
            if (pred.isValid(thread)) subset.add(thread);
        }

        return new ThreadSet(runtime, subset);
    }

    private static interface Predicate {
        boolean isValid(@Nonnull ProcessThread thread);
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        for (ProcessThread thread: threads) {
            stringBuilder.append(thread).append("\n\n");
        }
        return stringBuilder.toString();
    }

    @Override
    public boolean equals(Object rhs) {
        if (rhs == null) return false;

        if (this == rhs) return true;

        if (!this.getClass().equals(rhs.getClass())) return false;

        ThreadSet other = (ThreadSet) rhs;

        return runtime.equals(other.runtime) && threads.equals(other.threads);
    }

    @Override
    public int hashCode() {
        return runtime.hashCode() + threads.hashCode() * 31;
    }

    public int size() {
        return threads.size();
    }

    public boolean isEmpty() {
        return threads.isEmpty();
    }

    public boolean contains(Object o) {
        return threads.contains(o);
    }

    public boolean containsAll(Collection<?> c) {
        return threads.containsAll(c);
    }

    public Iterator<ProcessThread> iterator() {
        return threads.iterator();
    }

    public Object[] toArray() {
        return threads.toArray();
    }

    public <T> T[] toArray(T[] a) {
        return threads.toArray(a);
    }

    public boolean add(ProcessThread e) {
        throw new UnsupportedOperationException();
    }

    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    public boolean addAll(Collection<? extends ProcessThread> c) {
        throw new UnsupportedOperationException();
    }

    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    public void clear() {
        throw new UnsupportedOperationException();
    }
}
