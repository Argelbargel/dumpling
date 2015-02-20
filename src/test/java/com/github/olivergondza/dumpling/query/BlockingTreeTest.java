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

import static com.github.olivergondza.dumpling.model.ProcessThread.nameContains;
import static com.github.olivergondza.dumpling.model.ProcessThread.nameIs;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.Before;
import org.junit.Test;

import com.github.olivergondza.dumpling.Util;
import com.github.olivergondza.dumpling.cli.AbstractCliTest;
import com.github.olivergondza.dumpling.factory.ThreadDumpFactory;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpRuntime;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpThread;
import com.github.olivergondza.dumpling.model.dump.ThreadDumpThreadSet;
import com.github.olivergondza.dumpling.query.BlockingTree.Tree;

public class BlockingTreeTest extends AbstractCliTest {

    private ThreadDumpRuntime runtime;
    private ThreadDumpThread a, aa, aaa, ab, b, ba;
    private File blockingTreeLog;

    @Before
    public void setUp() throws Exception {
        blockingTreeLog = Util.resourceFile("blocking-tree.log");
        runtime = new ThreadDumpFactory().fromFile(blockingTreeLog);
        a = singleThread("a");
        aa = singleThread("aa");
        aaa = singleThread("aaa");
        ab = singleThread("ab");
        b = singleThread("b");
        ba = singleThread("ba");
    }

    private ThreadDumpThread singleThread(@Nonnull String name) {
        return runtime.getThreads().where(nameIs(name)).onlyThread();
    }

    @Test
    public void fullForest() {
        Set<Tree<ThreadDumpThread>> full = new BlockingTree().query(runtime.getThreads()).getTrees();
        Set<Tree<ThreadDumpThread>> expected = new HashSet<Tree<ThreadDumpThread>>(Arrays.asList(
                tree(a,
                        tree(aa, tree(aaa)),
                        tree(ab)
                ),
                tree(b, tree(ba))
        ));

        assertThat(full, equalTo(expected));
    }

    @Test
    public void oneChainFromBottom() {
        Set<Tree<ThreadDumpThread>> as = new BlockingTree().query(runtime.getThreads().where(nameIs("aaa"))).getTrees();
        Set<Tree<ThreadDumpThread>> expected = new HashSet<Tree<ThreadDumpThread>>(Arrays.asList(
                tree(a, tree(aa, tree(aaa)))
        ));

        assertThat(as, equalTo(expected));
    }

    @Test
    public void oneChainFromMiddle() {
        Set<Tree<ThreadDumpThread>> as = new BlockingTree().query(runtime.getThreads().where(nameIs("aa"))).getTrees();
        Set<Tree<ThreadDumpThread>> expected = new HashSet<Tree<ThreadDumpThread>>(Arrays.asList(
                tree(a, tree(aa, tree(aaa)))
        ));

        assertThat(as, equalTo(expected));
    }

    @Test
    public void fullRoot() {
        Set<Tree<ThreadDumpThread>> as = new BlockingTree().query(runtime.getThreads().where(nameIs("b"))).getTrees();
        Set<Tree<ThreadDumpThread>> expected = new HashSet<Tree<ThreadDumpThread>>(Arrays.asList(
                tree(b, tree(ba))
        ));

        assertThat(as, equalTo(expected));
    }

    @Test
    public void severalChains() {
        Set<Tree<ThreadDumpThread>> as = new BlockingTree().query(
                runtime.getThreads().where(nameContains(Pattern.compile("^(aaa|ba)$")))
        ).getTrees();
        Set<Tree<ThreadDumpThread>> expected = new HashSet<Tree<ThreadDumpThread>>(Arrays.asList(
                tree(a, tree(aa, tree(aaa))),
                tree(b, tree(ba))
        ));

        assertThat(as, equalTo(expected));
    }

    private Tree<ThreadDumpThread> tree(@Nonnull ThreadDumpThread root, @Nonnull Tree<ThreadDumpThread>... leaves) {
        return new BlockingTree.Tree<ThreadDumpThread>(root, leaves);
    }

    @Test
    public void roots() {
        ThreadDumpThreadSet as = new BlockingTree().query(runtime.getThreads()).getRoots();
        ThreadDumpThreadSet expected = runtime.getThreads().where(nameContains(Pattern.compile("^[ab]$")));

        assertThat(as, equalTo(expected));
    }

    @Test
    public void cliQuery() {
        run("blocking-tree", "--in", "threaddump", blockingTreeLog.getAbsolutePath());
        assertThat(err.toString(), equalTo(""));

        assertQueryListing(out.toString());
    }

    @Test
    public void toStringNoTraces() {
        assertQueryListing(runtime.query(new BlockingTree()).toString());
    }

    private void assertQueryListing(String out) {
        // Roots
        assertThat(out, containsString("\"a\""));
        assertThat(out, containsString("%n\"b\""));

        // Blocked by roots
        assertThat(out, containsString("%n\t\"aa\""));
        assertThat(out, containsString("%n\t\"ab\""));
        assertThat(out, containsString("%n\t\"ba\""));

        // Deeply nested
        assertThat(out, containsString("%n\t\t\"aaa\""));

        assertThat(out, not(containsString("%n\"aaa\" prio=10 tid=139918763419648 nid=31957%n")));
    }

    @Test
    public void cliQueryTraces() {
        run("blocking-tree", "--show-stack-traces", "--in", "threaddump", blockingTreeLog.getAbsolutePath());
        assertThat(err.toString(), equalTo(""));

        final String stdout = out.toString();
        assertLongQueryListing(stdout);
    }

    @Test
    public void toStringTraces() {
        assertLongQueryListing(runtime.query(new BlockingTree().showStackTraces()).toString());
    }

    private void assertLongQueryListing(final String out) {
        // Roots
        assertThat(out, containsString("\"a\""));
        assertThat(out, containsString("%n\"b\""));

        // Blocked by roots
        assertThat(out, containsString("%n\t\"aa\""));
        assertThat(out, containsString("%n\t\"ab\""));
        assertThat(out, containsString("%n\t\"ba\""));

        // Deeply nested
        assertThat(out, containsString("%n\t\t\"aaa\""));

        assertThat(out, containsString("%n\"aaa\" prio=10 tid=139918763419648 nid=31957%n"));
    }
}
