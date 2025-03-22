/*
 * Copyright (C) 2025 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package com.github.teletha;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        Path cache = Path.of(cacheFileName);
        if (Files.notExists(cache)) {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> javaArgs = List.of(System.getProperty("sun.java.command").split(" "));
            List<String> classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator));

            if (jvmArgs.contains("-XX:AOTMode=record")) {
                // This application is running in training mode.
                // AOT Cache will be created when this app exits.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        List<String> command = new ArrayList<>();
                        command.add(ProcessHandle.current().info().command().get());
                        command.add("-XX:AOTMode=create");
                        command.add("-XX:AOTConfiguration=.aotconf");
                        command.add("-XX:AOTCache=" + cacheFileName);
                        command.add("-cp");
                        command.addAll(classpath.stream().filter(path -> Files.isRegularFile(Path.of(path))).toList());

                        new ProcessBuilder(command).inheritIO().start();
                    } catch (IOException e) {
                        throw new IOError(e);
                    }
                }));
            } else {
                try {
                    // This application is running normally.
                    // Switch to training mode and restart.
                    List<String> command = new ArrayList<>();
                    command.add(ProcessHandle.current().info().command().get());
                    command.addAll(jvmArgs.stream().filter(value -> !value.startsWith("-XX:AOT")).toList());
                    command.add("-XX:AOTMode=record");
                    command.add("-XX:AOTConfiguration=.aotconf");
                    command.add("-cp");
                    command.addAll(classpath);
                    command.addAll(javaArgs);

                    // restart process
                    new ProcessBuilder(command).inheritIO().start();
                } catch (IOException e) {
                    throw new IOError(e);
                } finally {
                    System.exit(0);
                }
            }
        } else {
            System.out.println("Cache is found, do nothing.");
        }
    }

    public static void main(String[] args) {
        JEP483.enable();
    }
}
