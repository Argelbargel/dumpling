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
package com.github.olivergondza.dumpling.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import javax.annotation.Nonnull;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.TypeSafeMatcher;

public abstract class AbstractCliTest {
    public boolean forked;

    protected InputStream in = new ByteArrayInputStream(new byte[0]);
    protected ByteArrayOutputStream err;
    protected ByteArrayOutputStream out;
    protected int exitValue;

    public AbstractCliTest() {
        this(false);
    }

    public AbstractCliTest(boolean forked) {
        this.forked = forked;
    }

    protected int run(@Nonnull String... args) {
        err = new ByteArrayOutputStream();
        out = new ByteArrayOutputStream();
        return exitValue = new Main().run(args, new ProcessStream(in, new PrintStream(out), new PrintStream(err)));
    }

    protected void stdin(InputStream stream) {
        in = stream;
    }

    protected void stdin(String string) {
        try {
            in = new ByteArrayInputStream(String.format(string).getBytes("UTF-8"));
        } catch (UnsupportedEncodingException ex) {
            throw new AssertionError(ex);
        }
    }

    protected void stdin(File file){
        try {
            in = new FileInputStream(file.getAbsolutePath());
        } catch (FileNotFoundException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * Contains string with platform dependent newlines.
     *
     * Clients are supposed to use <tt>%n</tt> instead of newline char.
     */
    protected Matcher<String> containsString(String str) {
        return org.hamcrest.Matchers.containsString(
                String.format(str)
        );
    }

    /**
     * SqualTo string with platform dependent newlines.
     *
     * Clients are supposed to use <tt>%n</tt> instead of newline char.
     */
    protected Matcher<String> equalToString(String str) {
        return org.hamcrest.Matchers.equalTo(
                String.format(str)
        );
    }

    protected Matcher<String> isEmptyString() {
        return org.hamcrest.text.IsEmptyString.isEmptyString();
    }

    protected Matcher<AbstractCliTest> reportedNoError() {
        return new TypeSafeDiagnosingMatcher<AbstractCliTest>() {

            @Override protected boolean matchesSafely(AbstractCliTest item, Description mismatchDescription) {
                String stderr = item.err.toString();
                if ("".equals(stderr)) return true;

                mismatchDescription.appendText("Got >>>" + stderr + "<<<");

                // Java 9+ spits this warnings for libraries
                return stderr.startsWith("WARNING: An illegal reflective access operation has occurred")
                        && stderr.endsWith(String.format("WARNING: All illegal access operations will be denied in a future release%n"))
                ;
            }

            @Override public void describeTo(Description description) {
                description.appendText("No error was expected");
            }
        };
    }

    protected Matcher<AbstractCliTest> succeeded() {
        return new TypeSafeMatcher<AbstractCliTest>() {

            @Override
            public void describeTo(Description description) {
                description.appendText("Successful execution");
            }

            @Override
            protected void describeMismatchSafely(AbstractCliTest item, Description mismatchDescription) {
                mismatchDescription.appendText("Failed with: ").appendValue(item.exitValue).appendText("\n").appendValue(item.err);
            }

            @Override
            protected boolean matchesSafely(AbstractCliTest item) {
                return item.exitValue == 0;
            }
        };
    }
}
