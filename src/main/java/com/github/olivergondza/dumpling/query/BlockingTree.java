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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nonnull;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.Option;

import com.github.olivergondza.dumpling.cli.CliCommand;
import com.github.olivergondza.dumpling.cli.ProcessStream;
import com.github.olivergondza.dumpling.model.ProcessRuntime;
import com.github.olivergondza.dumpling.model.ProcessThread;
import com.github.olivergondza.dumpling.model.ThreadSet;

/**
 * Get a forest of all blocking trees.
 *
 * Non-blocked threads are the roots of tree hierarchies where parent-child
 * relationship represents blocking-blocked situation. Leaves of such trees
 * represents blocked but not blocking threads.
 *
 * Only such branches are included that contain threads from initial set.
 * Provide all threads in runtime to analyze all threads.
 *
 * @author ogondza
 */
public final class BlockingTree implements SingleThreadSetQuery<BlockingTree.Result<?, ?, ?>> {

    private boolean showStackTraces = false;

    public BlockingTree showStackTraces() {
        this.showStackTraces = true;
        return this;
    }

    @Override
    public @Nonnull <
            SetType extends ThreadSet<SetType, RuntimeType, ThreadType>,
            RuntimeType extends ProcessRuntime<RuntimeType, SetType, ThreadType>,
            ThreadType extends ProcessThread<ThreadType, SetType, RuntimeType>
    > Result<SetType, RuntimeType, ThreadType> query(SetType threads) {
        return new Result<SetType, RuntimeType, ThreadType>(threads, showStackTraces);
    }

    public final static class Command implements CliCommand {

        @Option(name = "-i", aliases = {"--in"}, required = true, usage = "Input for process runtime")
        private ProcessRuntime<?, ?, ?> runtime;

        @Option(name = "--show-stack-traces", usage = "List stack traces of all threads involved")
        private boolean showStackTraces = false;

        @Override
        public String getName() {
            return "blocking-tree";
        }

        @Override
        public String getDescription() {
            return "Print contention trees";
        }

        @Override
        public int run(@Nonnull ProcessStream process) throws CmdLineException {
            Result<?, ?, ?> result = new Result(runtime.getThreads(), showStackTraces);
            result.printInto(process.out());
            return result.exitCode();
        }
    }

    public static final class Result<
            SetType extends ThreadSet<SetType, RuntimeType, ThreadType>,
            RuntimeType extends ProcessRuntime<RuntimeType, SetType, ThreadType>,
            ThreadType extends ProcessThread<ThreadType, SetType, RuntimeType>
    > extends SingleThreadSetQuery.Result<SetType> {

        private final @Nonnull Set<Tree<ThreadType>> trees;
        private final @Nonnull SetType involved;
        private final boolean showStackTraces;

        private Result(SetType threads, boolean showStackTraces) {
            @Nonnull Set<Tree<ThreadType>> roots = new HashSet<Tree<ThreadType>>();
            for (ThreadType thread: threads.getProcessRuntime().getThreads()) {
                if (thread.getWaitingOnLock() == null && !thread.getAcquiredLocks().isEmpty()) {
                    if (!thread.getBlockedThreads().isEmpty()) {
                        roots.add(new Tree<ThreadType>(thread, buildDown(thread)));
                    }
                }
            }

            this.trees = Collections.unmodifiableSet(filter(roots, threads));
            this.showStackTraces = showStackTraces;

            LinkedHashSet<ThreadType> involved = new LinkedHashSet<ThreadType>();
            for (Tree<ThreadType> root: trees) {
                flatten(root, involved);
            }

            this.involved = threads.derive(involved);
        }

        private @Nonnull Set<Tree<ThreadType>> buildDown(ThreadType thread) {
            @Nonnull Set<Tree<ThreadType>> newTrees = new HashSet<Tree<ThreadType>>();
            for(ThreadType t: thread.getBlockedThreads()) {
                newTrees.add(new Tree<ThreadType>(t, buildDown(t)));
            }

            return newTrees;
        }

        private @Nonnull Set<Tree<ThreadType>> filter(Set<Tree<ThreadType>> roots, SetType threads) {
            Set<Tree<ThreadType>> filtered = new HashSet<Tree<ThreadType>>();
            for (Tree<ThreadType> r: roots) {
                // Add whitelisted items including their subtrees
                if (threads.contains(r.getRoot())) {
                    filtered.add(r);
                }

                // Remove nodes with all children filtered out
                final Set<Tree<ThreadType>> filteredLeaves = filter(r.getLeaves(), threads);
                if (filteredLeaves.isEmpty()) continue;

                filtered.add(new Tree<ThreadType>(r.getRoot(), filteredLeaves));
            }

            return filtered;
        }

        private void flatten(Tree<ThreadType> tree, Set<ThreadType> accumulator) {
            accumulator.add(tree.getRoot());
            for (Tree<ThreadType> leaf: tree.getLeaves()) {
                flatten(leaf, accumulator);
            }
        }

        public @Nonnull Set<Tree<ThreadType>> getTrees() {
            return trees;
        }

        /**
         * Get tree roots, blocking threads that are not blocked.
         *
         * @since 0.2
         */
        public @Nonnull SetType getRoots() {
            Set<ThreadType> roots = new LinkedHashSet<ThreadType>(trees.size());
            for (Tree<ThreadType> tree: trees) {
                roots.add(tree.getRoot());
            }

            return involved.derive(roots);
        }

        @Override
        protected void printResult(PrintStream out) {
            for (Tree<ThreadType> tree: trees) {
                out.println(tree);
            }
        }

        @Override
        protected SetType involvedThreads() {
            return showStackTraces ? involved : null;
        }
    }

    /**
     * Blocking tree node.
     *
     * A <tt>root</tt> with directly blocked subtrees (<tt>leaves</tt>). If
     * leave set is empty root thread does not block any other threads.
     *
     * @author ogondza
     */
    public final static class Tree<ThreadType extends ProcessThread<ThreadType, ?, ?>> {

        private static final @Nonnull String NL = System.getProperty("line.separator", "\n");

        private final @Nonnull ThreadType root;
        private final @Nonnull Set<Tree<ThreadType>> leaves;

        private Tree(@Nonnull ThreadType root, @Nonnull Set<Tree<ThreadType>> leaves) {
            this.root = root;
            this.leaves = Collections.unmodifiableSet(leaves);
        }

        /*package*/ Tree(@Nonnull ThreadType root, @Nonnull Tree<ThreadType>... leaves) {
            this.root = root;
            this.leaves = Collections.unmodifiableSet(new HashSet<Tree<ThreadType>>(Arrays.asList(leaves)));
        }

        public @Nonnull ThreadType getRoot() {
            return root;
        }

        public @Nonnull Set<Tree<ThreadType>> getLeaves() {
            return leaves;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            writeInto("", sb);
            return sb.toString();
        }

        private void writeInto(String prefix, StringBuilder sb) {
            sb.append(prefix).append(root.getHeader()).append(NL);
            for (Tree<ThreadType> l: leaves) {
                l.writeInto(prefix + "\t", sb);
            }
        }

        @Override
        public int hashCode() {
            int hashCode = 31 * root.hashCode();
            for (Tree<ThreadType> l: leaves) {
                hashCode += l.hashCode() * 7;
            }

            return hashCode;
        }

        @Override
        public boolean equals(Object rhs) {
            if (rhs == null) return false;
            if (rhs == this) return true;

            if (!rhs.getClass().equals(this.getClass())) return false;

            Tree<ThreadType> other = (Tree<ThreadType>) rhs;
            return this.root.equals(other.root) && this.leaves.equals(other.leaves);
        }
    }
}
