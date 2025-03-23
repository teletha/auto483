/*
 * Copyright (C) 2025 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package auto483;

import java.io.IOError;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JEP483 supporter.
 */
public class JEP483 {

    /**
     * Enable Ahead of time Loading and Linking.
     */
    public static void enable() {
        enable(".aot");
    }

    /**
     * Enable Ahead of time Loading and Linking.
     * 
     * @param cacheFileName The name of cache file.
     */
    public static void enable(String cacheFileName) {
        if (Files.notExists(Path.of(cacheFileName))) {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> classpath = List.of(System.getProperty("java.class.path").split(";"));

            if (jvmArgs.contains("-XX:AOTMode=record")) {
                // This application is running in training mode.
                // AOT Cache will be created when this app exits.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        List<String> command = new ArrayList<>();
                        command.add(ProcessHandle.current().info().command().get());
                        command.add("-XX:AOTMode=create");
                        command.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                        command.add("-XX:AOTCache=" + cacheFileName);
                        command.add("-cp");
                        command.add(classpath.stream().filter(path -> Files.isRegularFile(Path.of(path))).collect(Collectors.joining(";")));

                        new ProcessBuilder(command).inheritIO().start().waitFor();
                    } catch (Exception e) {
                        throw new IOError(e);
                    }
                }));
            } else if (jvmArgs.contains("-XX:AOTCache=" + cacheFileName)) {
                List<String> javaArgs = List.of(System.getProperty("sun.java.command").split(" "));

                try {
                    // This application is running normally.
                    // Restart with training mode.
                    List<String> command = new ArrayList<>();
                    command.add(ProcessHandle.current().info().command().get());
                    command.addAll(jvmArgs.stream().filter(value -> !value.startsWith("-XX:AOT")).toList());
                    command.add("-XX:AOTMode=record");
                    command.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                    command.add("-cp");
                    command.add(classpath.stream().collect(Collectors.joining(";")));
                    command.addAll(javaArgs);

                    // restart process
                    new ProcessBuilder(command).inheritIO().start();
                } catch (IOException e) {
                    throw new IOError(e);
                } finally {
                    System.exit(0);
                }
            }
        }
    }

    public static void main(String[] args) {
        JEP483.enable();
    }
}
