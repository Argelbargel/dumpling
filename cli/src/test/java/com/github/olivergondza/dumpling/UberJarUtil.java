package com.github.olivergondza.dumpling;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.github.olivergondza.dumpling.Util.processBuilder;
import static java.util.Arrays.asList;


public class UberJarUtil {
    public static ProcessBuilder buildUberJarProcess(String... args) {
        return buildUberJarProcess(false, args);
    }

    public static ProcessBuilder buildUberJarProcess(boolean redirectErr, String... args) {
        List<String> procArgs = new ArrayList<>();
        procArgs.add("java");
        procArgs.add("-jar");
        procArgs.add(getUberjar());
        procArgs.addAll(asList(args));

        return redirectErr ? processBuilder(procArgs) : new ProcessBuilder(procArgs);
    }


    private static String getUberjar() {
        Collection<String> dirs = asList("./target", "./build/libs");
        File targetDir = getTargetDir(dirs)
                .orElseThrow(() -> new AssertionError("none of target-dirs " + dirs + " does exist"));


        try {
            List<File> files = asList(targetDir.listFiles((dir, name) -> name.startsWith("dumpling-cli") && name.endsWith("-shaded.jar")));

            if (files.size() != 1) throw new AssertionError("One uberjar expected: " + files);

            return files.get(0).getCanonicalPath();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Optional<File> getTargetDir(Collection<String> paths) {
        return paths.stream().map(File::new).filter(File::exists).findFirst();
    }
}
