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

import java.io.InputStream;
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
public final class BlockingTree implements SingleThreadSetQuery<BlockingTree.Result> {

    private boolean showStackTraces = false;

    public BlockingTree showStackTraces() {
        this.showStackTraces = true;
        return this;
    }

    @Override
    public @Nonnull Result query(ThreadSet threads) {
        return new Result(threads, showStackTraces);
    }

    public final static class Command implements CliCommand {

        @Option(name = "-i", aliases = {"--in"}, required = true, usage = "Input for process runtime")
        private ProcessRuntime runtime;

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
        public int run(InputStream in, PrintStream out, PrintStream err) throws CmdLineException {
            Result result = new Result(runtime.getThreads(), showStackTraces);
            result.printInto(out);
            return result.exitCode();
        }
    }

    public static final class Result extends SingleThreadSetQuery.Result {

        private final @Nonnull Set<Tree> trees;
        private final @Nonnull ThreadSet involved;
        private final boolean showStackTraces;

        private Result(ThreadSet threads, boolean showStackTraces) {
            @Nonnull Set<Tree> roots = new HashSet<Tree>();
            for (ProcessThread thread: threads.getProcessRuntime().getThreads()) {
                if (thread.getWaitingOnLock() == null && !thread.getAcquiredLocks().isEmpty()) {
                    if (!thread.getBlockedThreads().isEmpty()) {
                        roots.add(new Tree(thread, buildDown(thread)));
                    }
                }
            }

            this.trees = Collections.unmodifiableSet(filter(roots, threads));
            this.showStackTraces = showStackTraces;

            LinkedHashSet<ProcessThread> involved = new LinkedHashSet<ProcessThread>();
            for (Tree root: trees) {
                flatten(root, involved);
            }

            this.involved = threads.derive(involved);
        }

        private @Nonnull Set<Tree> buildDown(ProcessThread thread) {
            @Nonnull Set<Tree> newTrees = new HashSet<Tree>();
            for(ProcessThread t: thread.getBlockedThreads()) {
                newTrees.add(new Tree(t, buildDown(t)));
            }

            return newTrees;
        }

        private @Nonnull Set<Tree> filter(Set<Tree> roots, ThreadSet threads) {
            Set<Tree> filtered = new HashSet<Tree>();
            for (Tree r: roots) {
                // Add whitelisted items including their subtrees
                if (threads.contains(r.getRoot())) {
                    filtered.add(r);
                }

                // Remove nodes with all children filtered out
                final Set<Tree> filteredLeaves = filter(r.getLeaves(), threads);
                if (filteredLeaves.isEmpty()) continue;

                filtered.add(new Tree(r.getRoot(), filteredLeaves));
            }

            return filtered;
        }

        private void flatten(Tree tree, Set<ProcessThread> accumulator) {
            accumulator.add(tree.getRoot());
            for (Tree leaf: tree.getLeaves()) {
                flatten(leaf, accumulator);
            }
        }

        public @Nonnull Set<Tree> getTrees() {
            return trees;
        }

        @Override
        protected void printResult(PrintStream out) {
            for (Tree tree: trees) {
                out.println(tree);
            }
        }

        @Override
        protected ThreadSet involvedThreads() {
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
    public final static class Tree {

        private final @Nonnull ProcessThread root;
        private final @Nonnull Set<Tree> leaves;

        private Tree(@Nonnull ProcessThread root, @Nonnull Set<Tree> leaves) {
            this.root = root;
            this.leaves = Collections.unmodifiableSet(leaves);
        }

        /*package*/ Tree(@Nonnull ProcessThread root, @Nonnull Tree... leaves) {
            this.root = root;
            this.leaves = Collections.unmodifiableSet(new HashSet<Tree>(Arrays.asList(leaves)));
        }

        public @Nonnull ProcessThread getRoot() {
            return root;
        }

        public @Nonnull Set<Tree> getLeaves() {
            return leaves;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            writeInto("", sb);
            return sb.toString();
        }

        private void writeInto(String prefix, StringBuilder sb) {
            sb.append(prefix).append(root.getHeader()).append('\n');
            for (Tree l: leaves) {
                l.writeInto(prefix + "\t", sb);
            }
        }

        @Override
        public int hashCode() {
            int hashCode = 31 * root.hashCode();
            for (Tree l: leaves) {
                hashCode += l.hashCode() * 7;
            }

            return hashCode;
        }

        @Override
        public boolean equals(Object rhs) {
            if (rhs == null) return false;
            if (rhs == this) return true;

            if (!rhs.getClass().equals(this.getClass())) return false;

            Tree other = (Tree) rhs;
            return this.root.equals(other.root) && this.leaves.equals(other.leaves);
        }
    }
}
