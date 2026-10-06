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
 * Provides support for Ahead-of-Time (AOT) class loading and linking, introduced by
 * JEP 483 in Java 24 and streamlined by JEP 514 in Java 25.
 * <p>
 * This utility class helps in managing the creation and usage of AOT cache files.
 * It aims to simplify the process of leveraging AOT compilation to potentially improve
 * application startup performance.
 * <p>
 * The {@link #enable()} methods check for the existence of an AOT cache file.
 * If the file doesn't exist, this class orchestrates the necessary steps to
 * generate it. This involves restarting the application in a training run and
 * creating the cache from the recorded configuration. The workflow is selected
 * automatically from the running JDK version: a single step on JDK 25 or later,
 * and a two-step record/create sequence on JDK 24.
 *
 * @see <a href="https://openjdk.org/jeps/483">JEP 483: Ahead-of-Time Class Loading & Linking</a>
 * @see <a href="https://openjdk.org/jeps/514">JEP 514: Ahead-of-Time Command-Line Ergonomics</a>
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
     * will restart the current application in a training run and wait until it terminates.
     * On JDK 25 or later it uses the single-step workflow introduced by JEP 514
     * ({@code -XX:AOTCacheOutput}); on JDK 24 it uses the two-step workflow of JEP 483
     * ({@code -XX:AOTMode=record} followed by {@code -XX:AOTMode=create}). The current
     * process is then terminated with the exit code of the process which ran the application.
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
     *            On JDK 24 this name is also used for the temporary AOT configuration
     *            file ({@code -XX:AOTConfiguration}, with "conf" appended); on JDK 25
     *            or later the JVM manages that temporary file automatically.
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
            // Restart the application in training mode and wait until it terminates.
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                // A non-empty directory can not be recorded, so keep only existing files
                // (especially relevant for JARs). JDK 25 uses this classpath for both the
                // training run and the cache creation, so it must be filtered here.
                String filteredClasspath = classpath.stream()
                        .filter(path -> Files.isRegularFile(Path.of(path)))
                        .collect(Collectors.joining(";"));

                // The one-step workflow of JEP 514 is available on JDK 25 or later.
                boolean oneStep = Runtime.version().feature() >= 25;

                List<String> record = new ArrayList<>();
                record.add(ProcessHandle.current().info().command().get());
                record.addAll(jvmArgs.stream().filter(value -> !value.startsWith("-XX:AOT")).toList());
                if (oneStep) {
                    // JEP 514: a single invocation performs the training run and creates
                    // the AOT cache, using a temporary configuration file.
                    record.add("-XX:AOTCacheOutput=" + cacheFileName);
                    record.add("-Xlog:cds=error");
                    record.add("-cp");
                    record.add(filteredClasspath);
                } else {
                    // JEP 483: record the training run, then create the cache afterwards.
                    record.add("-XX:AOTMode=record");
                    record.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                    record.add("-Xlog:cds=error");
                    record.add("-cp");
                    record.add(String.join(";", classpath));
                }
                record.addAll(List.of(System.getProperty("sun.java.command").split(" ")));

                // The AOT cache is written while the training process shuts down (JDK 24) or
                // by the same process (JDK 25+), so wait for it to terminate first.
                int exit = new ProcessBuilder(record).inheritIO().start().waitFor();

                if (!oneStep) {
                    // Create the AOT cache from the completed configuration file.
                    List<String> create = new ArrayList<>();
                    create.add(ProcessHandle.current().info().command().get());
                    create.add("-XX:AOTMode=create");
                    create.add("-XX:AOTConfiguration=" + cacheFileName + "conf");
                    create.add("-XX:AOTCache=" + cacheFileName);
                    create.add("-Xlog:cds=error");
                    create.add("-cp");
                    create.add(filteredClasspath);

                    new ProcessBuilder(create).inheritIO().start().waitFor();
                }

                // The current process only exists to create the cache, so terminate with the exit
                // code of the application which actually ran in training mode.
                System.exit(exit);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }
}
