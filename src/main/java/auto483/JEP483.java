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
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides support for JEP 483 (Ahead-of-Time Class Loading & Linking).
 * <p>
 * This utility class helps in managing the creation and usage of Ahead-of-Time (AOT)
 * cache files introduced in Java 24. It aims to simplify the process of leveraging
 * AOT compilation to potentially improve application startup performance.
 * <p>
 * The {@link #enable()} methods check for the existence of an AOT cache file.
 * If the file doesn't exist, this class orchestrates the necessary steps to
 * generate it. This might involve restarting the application in a special
 * "record" mode or setting up a shutdown hook to create the cache upon exit.
 *
 * @see <a href="https://openjdk.org/jeps/483">JEP 483: Ahead-of-Time Class Loading & Linking</a>
 */
public class JEP483 {

    /**
     * Enables Ahead-of-Time (AOT) loading and linking using the default cache file name
     * {@code .aot}.
     * <p>
     * This is a convenience method that calls {@link #enable(String)} with ".aot" as the cache file
     * name.
     * </p>
     *
     * @throws IOError If an I/O error occurs during process creation or file system operations
     *             when attempting to generate the AOT cache.
     */
    public static void enable() {
        enable(".aot");
    }

    /**
     * Enables Ahead-of-Time (AOT) loading and linking using the specified cache file name.
     * <p>
     * This method checks if the specified AOT cache file exists. If it does not exist,
     * it manages the process of generating the cache based on the current JVM execution mode:
     * </p>
     * <ul>
     * <li>
     * <b>Normal Mode (Attempting to use cache):</b> If the JVM was started with
     * {@code -XX:AOTCache=<cacheFileName>} but the file is missing, this method
     * will restart the current application with the {@code -XX:AOTMode=record} flag.
     * The current process will be terminated ({@code System.exit(0)}). The restarted
     * process will then run the application and record AOT compilation data.
     * </li>
     * <li>
     * <b>Record Mode:</b> If the JVM was started with {@code -XX:AOTMode=record},
     * this method registers a shutdown hook. When the application exits normally,
     * the shutdown hook will launch a new JVM process with {@code -XX:AOTMode=create}
     * to compile the recorded data into the final AOT cache file.
     * </li>
     * </ul>
     * <p>
     * If the cache file already exists, this method does nothing, allowing the JVM to
     * load the AOT cache as configured.
     * </p>
     * <p>
     * Note: The cache generation process involves starting new JVM processes. Ensure that
     * the necessary permissions and environment setup are in place for this to succeed.
     * The classpath used for the cache generation process is derived from the current
     * application's classpath.
     * </p>
     *
     * @param cacheFileName The name (or path) of the AOT cache file to use or create.
     *            This name is used for both the AOT cache ({@code -XX:AOTCache})
     *            and the AOT configuration file ({@code -XX:AOTConfiguration},
     *            with "conf" appended).
     * @throws IOError If an I/O error occurs during process creation, file system operations
     *             (like creating parent directories), or while waiting for the cache
     *             creation process to complete.
     */
    public static void enable(String cacheFileName) {
        Path file = Path.of(cacheFileName);
        if (Files.notExists(file)) {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> classpath = List.of(System.getProperty("java.class.path").split(";"));

            if (jvmArgs.contains("-XX:AOTMode=record")) {
                // This application is running in training/record mode.
                // Register a shutdown hook to create the AOT Cache when this app exits.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        List<String> command = new ArrayList<>();
                        command.add(ProcessHandle.current().info().command().get());
                        command.add("-XX:AOTMode=create");
                        command.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                        command.add("-XX:AOTCache=" + cacheFileName);
                        command.add("-Xlog:cds=error");
                        command.add("-cp");
                        // Filter classpath to include only existing files/directories, especially
                        // relevant for JARs
                        command.add(classpath.stream().filter(path -> Files.isRegularFile(Path.of(path))).collect(Collectors.joining(";")));

                        new ProcessBuilder(command).inheritIO().start().waitFor();
                    } catch (Exception e) {
                        throw new Error(e);
                    }
                }, "JEP483-Cache-Creator"));
            } else if (jvmArgs.contains("-XX:AOTCache=" + cacheFileName)) {
                // This application is running normally or trying to use the cache.
                // Since the cache file doesn't exist, restart with training/record mode.
                List<String> javaArgs = List.of(System.getProperty("sun.java.command").split(" "));

                try {
                    Files.createDirectories(file.getParent());

                    // This application is running normally.
                    // Restart with training mode.
                    List<String> command = new ArrayList<>();
                    command.add(ProcessHandle.current().info().command().get());
                    command.addAll(jvmArgs.stream().filter(value -> !value.startsWith("-XX:AOT")).toList());
                    command.add("-XX:AOTMode=record");
                    command.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                    command.add("-Xlog:cds=error");
                    command.add("-cp");
                    command.add(classpath.stream().collect(Collectors.joining(";")));
                    command.addAll(javaArgs);

                    // Restart the process in record mode
                    new ProcessBuilder(command).inheritIO().start();
                } catch (Exception e) {
                    throw new Error(e);
                } finally {
                    // Exit the current process cleanly after initiating the restart
                    System.exit(0);
                }
            }
        }
    }
}
