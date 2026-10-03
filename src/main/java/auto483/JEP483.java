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
 * "record" mode and then creating the cache from the recorded configuration.
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
     * will restart the current application with the {@code -XX:AOTMode=record} flag
     * and wait until it terminates. Because the JVM writes the AOT configuration file
     * while it shuts down, the cache is created only after the record process has
     * completely exited. The current process is then terminated with the exit code of
     * the record process.
     * </li>
     * <li>
     * <b>Record Mode:</b> If the JVM was started with {@code -XX:AOTMode=record},
     * this method does nothing. The process which started this one is responsible for
     * creating the cache after this process has terminated.
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
     * @throws IOError If an I/O error occurs during process creation or file system operations
     *             (like creating parent directories), or while waiting for the cache
     *             creation process to complete.
     */
    public static void enable(String cacheFileName) {
        if (Runtime.version().feature() < 24) {
            return;
        }

        Path file = Path.of(cacheFileName);
        if (Files.exists(file)) {
            // The AOT cache already exists, so the JVM has loaded it while starting up.
            return;
        }

        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> classpath = List.of(System.getProperty("java.class.path").split(";"));

        if (jvmArgs.contains("-XX:AOTMode=record")) {
            // This application is running in training/record mode. The JVM writes the AOT
            // configuration file during its shutdown sequence, so the cache can not be created
            // until this process has completely terminated. The process which started this one
            // creates the cache after waiting for this process to exit.
            return;
        }

        if (jvmArgs.contains("-XX:AOTCache=" + cacheFileName)) {
            // This application was started with the AOT cache option but the cache file is missing.
            // Run the application again in training/record mode and wait until it terminates, then
            // create the AOT cache from the completed configuration file.
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // Restart this application in training/record mode.
                List<String> record = new ArrayList<>();
                record.add(ProcessHandle.current().info().command().get());
                record.addAll(jvmArgs.stream().filter(value -> !value.startsWith("-XX:AOT")).toList());
                record.add("-XX:AOTMode=record");
                record.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                record.add("-Xlog:cds=error");
                record.add("-cp");
                record.add(String.join(";", classpath));
                record.addAll(List.of(System.getProperty("sun.java.command").split(" ")));

                // The AOT configuration file is written while the record process shuts down, so
                // wait for it to terminate before creating the cache.
                int exit = new ProcessBuilder(record).inheritIO().start().waitFor();

                // Create the AOT cache from the completed configuration file.
                List<String> create = new ArrayList<>();
                create.add(ProcessHandle.current().info().command().get());
                create.add("-XX:AOTMode=create");
                create.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                create.add("-XX:AOTCache=" + cacheFileName);
                create.add("-Xlog:cds=error");
                create.add("-cp");
                // Filter the classpath to include only existing files (especially relevant for
                // JARs) because a non-empty directory can not be recorded.
                create.add(classpath.stream().filter(path -> Files.isRegularFile(Path.of(path))).collect(Collectors.joining(";")));

                new ProcessBuilder(create).inheritIO().start().waitFor();

                // The current process only exists to create the cache, so terminate with the exit
                // code of the application which actually ran in record mode.
                System.exit(exit);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }
}
