package io.github.gaming32.modloadingscreen;

import com.formdev.flatlaf.FlatDarkLaf;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static io.github.gaming32.modloadingscreen.ModLoadingScreen.ACTUAL_LOADING_SCREEN;

public class ActualLoadingScreen {
    private static final boolean IS_IPC_CLIENT = Boolean.getBoolean("mlsipc.present");
    private static final boolean RUNNING_ON_QUILT = Boolean.getBoolean("mlsipc.quilt") ||
        (!IS_IPC_CLIENT && FabricLoader.getInstance().isModLoaded("quilt_loader"));
    private static final Path CONFIG_DIR = IS_IPC_CLIENT
        ? Paths.get(System.getProperty("mlsipc.config"))
        : FabricLoader.getInstance().getConfigDir().resolve("mod-loading-screen");
    private static final Set<String> IGNORED_BUILTIN = new HashSet<>(Arrays.asList(
        RUNNING_ON_QUILT ? "quilt_loader" : "fabricloader", "java"
    ));
    public static final Set<String> FINAL_ENTRYPOINTS = new HashSet<>(Arrays.asList(
        "client", "server", "client_init", "server_init"
    ));
    public static final boolean IS_HEADLESS = GraphicsEnvironment.isHeadless();
    public static final boolean ENABLE_IPC =
        !IS_IPC_CLIENT && !IS_HEADLESS && !Boolean.getBoolean("mod-loading-screen.disableIpc");

    // Unlike progressBars, this is populated on both the IPC client and IPC server, allowing it to be used from the API
    public static final Map<String, Integer> progress = new LinkedHashMap<>();
    private static final Map<String, JProgressBar> progressBars = new LinkedHashMap<>();
    private static JFrame dialog;
    private static JLabel label;
    private static JProgressBar memoryBar;
    private static DataOutputStream ipcOut;
    private static PrintStream logFile;
    private static Thread memoryThread;

    private static boolean enableMemoryDisplay = true;

    public static void startLoadingScreen() {
        if (IS_HEADLESS) {
            println("Mod Loading Screen is on a headless environment. Only some logging will be performed.");
            return;
        }
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            println("Failed to create config dir", e);
        }

        println("Opening loading screen");

        final String gameNameAndVersion = IS_IPC_CLIENT
            ? System.getProperty("mlsipc.game")
            : FabricLoader.getInstance()
                .getAllMods()
                .stream()
                .filter(m -> m.getMetadata().getType().equals("builtin"))
                .filter(m -> !IGNORED_BUILTIN.contains(m.getMetadata().getId()))
                .findFirst()
                .map(m -> m.getMetadata().getName() + ' ' + m.getMetadata().getVersion())
                .orElse("Unknown Game");

        loadConfig();

        if (ENABLE_IPC) {
            final Path runDir = FabricLoader.getInstance().getGameDir().resolve(".cache/mod-loading-screen");
            final Path flatlafDestPath = runDir.resolve("flatlaf.jar");
            try {
                Files.createDirectories(flatlafDestPath.getParent());
                Files.copy(
                    FabricLoader.getInstance()
                        .getModContainer("mod-loading-screen")
                        .orElseThrow(AssertionError::new)
                        .getRootPaths().get(0)
                        .resolve("META-INF/jars/flatlaf-3.0.jar"),
                    flatlafDestPath, StandardCopyOption.REPLACE_EXISTING
                );
                println("Extracted flatlaf.jar");
                ipcOut = new DataOutputStream(
                    new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java",
                        "-Dmlsipc.present=true",
                        "-Dmlsipc.quilt=" + RUNNING_ON_QUILT,
                        "-Dmlsipc.game=" + gameNameAndVersion,
                        "-Dmlsipc.config=" + CONFIG_DIR,
                        "-cp", String.join(
                            File.pathSeparator,
                            FabricLoader.getInstance()
                                .getModContainer("mod-loading-screen")
                                .orElseThrow(AssertionError::new)
                                .getOrigin()
                                .getPaths().get(0)
                                .toString(),
                            flatlafDestPath.toString()
                        ),
                        ACTUAL_LOADING_SCREEN.replace('/', '.')
                    )
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .redirectInput(ProcessBuilder.Redirect.PIPE)
                        .directory(runDir.toFile())
                        .start()
                        .getOutputStream()
                );
            } catch (Exception e) {
                println("Failed to setup IPC client. Aborting.", e);
                return;
            }
            startMemoryThread();
            return;
        }

        FlatDarkLaf.setup();
        UIManager.getDefaults().put("ProgressBar.horizontalSize", new Dimension(146, 18));
        UIManager.getDefaults().put("ProgressBar.font", UIManager.getFont("ProgressBar.font").deriveFont(18f));
        UIManager.getDefaults().put("ProgressBar.selectionForeground", new Color(255, 255, 255));

        dialog = new JFrame();
        dialog.setTitle("Loading " + gameNameAndVersion);
        dialog.setResizable(false);

        try {
            dialog.setIconImage(ImageIO.read(
                ClassLoader.getSystemResource("assets/mod-loading-screen/icon.png"))
            );
        } catch (Exception e) {
            println("Failed to load icon.png", e);
        }

        ImageIcon icon;
        try {
            final Path backgroundPath = CONFIG_DIR.resolve("background.png");
            icon = new ImageIcon(
                Files.exists(backgroundPath)
                    ? backgroundPath.toUri().toURL()
                    : ClassLoader.getSystemResource("assets/mod-loading-screen/" + (RUNNING_ON_QUILT ? "quilt-banner.png" : "xpixel.png"))
            );
            icon.setImage(icon.getImage().getScaledInstance(960, 540, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            println("Failed to load background.png", e);
            icon = null;
        }
        label = new JLabel(icon);
        final BoxLayout layout = new BoxLayout(label, BoxLayout.Y_AXIS);
        label.setLayout(layout);
        label.add(Box.createVerticalGlue());
        dialog.add(label);

        if (enableMemoryDisplay) {
            memoryBar = new JProgressBar();
            memoryBar.setStringPainted(true);
            dialog.add(memoryBar, BorderLayout.NORTH);
        }

        dialog.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        startMemoryThread();
    }

    private static void loadConfig() {
        final Path configFile = CONFIG_DIR.resolve("config.properties");

        final Properties configProperties = new Properties();
        try (InputStream is = Files.newInputStream(configFile)) {
            configProperties.load(is);
        } catch (NoSuchFileException ignored) {
        } catch (Exception e) {
            println("Failed to load config", e);
        }

        if (configProperties.getProperty("enableMemoryDisplay") != null) {
            enableMemoryDisplay = Boolean.parseBoolean(configProperties.getProperty("enableMemoryDisplay"));
        }

        configProperties.clear();
        configProperties.setProperty("enableMemoryDisplay", Boolean.toString(enableMemoryDisplay));

        try (OutputStream os = Files.newOutputStream(configFile)) {
            configProperties.store(os,
                "To use a custom background image, create a file named background.png in this folder. The recommended size is 960x540."
            );
        } catch (Exception e) {
            println("Failed to write config", e);
        }
    }

    private static void startMemoryThread() {
        if (IS_IPC_CLIENT || !enableMemoryDisplay) return;
        updateMemoryUsage();
        memoryThread = new Thread(() -> {
            while (true) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    break;
                }
                updateMemoryUsage();
            }
        }, "MemoryUsageListener");
        memoryThread.setDaemon(true);
        memoryThread.start();
    }

    public static void beforeEntrypointType(String name, Class<?> type) {
        beforeEntrypointType(
            name,
            type.getSimpleName(),
            FabricLoader.getInstance().getEntrypointContainers(name, type).size()
        );
    }

    private static void beforeEntrypointType(String name, String type, int entrypointCount) {
        progress.put(name, 0);

        if (sendIpc(0, name, type, Integer.toString(entrypointCount))) return;

        println("Preparing loading screen for entrypoint '" + name + "'");
        if (dialog == null) return;

        final JProgressBar progressBar = new JProgressBar(0, entrypointCount);
        progressBar.setStringPainted(true);
        setLabel(progressBar, name, type, null);
        progressBars.put(name, progressBar);
        label.add(progressBar, BorderLayout.SOUTH);
        dialog.pack();
    }

    public static void beforeSingleEntrypoint(String typeName, String typeType, String modId, String modName) {
        final Integer oldProgress = progress.get(typeName);
        progress.put(typeName, oldProgress != null ? oldProgress + 1 : 1);

        if (sendIpc(1, typeName, typeType, modId, modName)) return;

        println("Calling entrypoint container for mod '" + modId + "'");
        if (dialog == null) return;

        final JProgressBar progressBar = progressBars.get(typeName);
        if (progressBar == null) return;
        progressBar.setValue(progress.get(typeName));
        setLabel(progressBar, typeName, typeType, modName);
    }

    public static void afterEntrypointType(String name) {
        progress.remove(name);

        if (sendIpc(2, name)) return;

        println("Finished loading screen for entrypoint '" + name + "'");
        if (dialog == null) return;

        final JProgressBar progressBar = progressBars.remove(name);
        if (progressBar == null) return;
        label.remove(progressBar);
        dialog.pack();
    }

    public static void maybeCloseAfter(String type) {
        if (
            !FINAL_ENTRYPOINTS.contains(type) ||
                (
                    RUNNING_ON_QUILT &&
                    FabricLoader.getInstance()
                        .getModContainer("quilt_base")
                        .map(c -> {
                            try {
                                return VersionPredicate.parse(">=5.0.0-beta.4").test(c.getMetadata().getVersion());
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            }
                        })
                        .orElse(false) &&
                    !FabricLoader.getInstance().getEntrypointContainers(type + "_init", Object.class).isEmpty()
                )
        ) return;
        sendIpc(4);
        close();
    }

    private static void close() {
        if (memoryThread != null) {
            memoryThread.interrupt();
        }
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
            progress.clear();
            progressBars.clear();
        }
        if (ipcOut != null) {
            try {
                ipcOut.close();
            } catch (IOException e) {
                println("Failed to close ipcOut", e);
            }
            ipcOut = null;
        }
    }

    public static boolean isOpen() {
        return dialog != null || ipcOut != null;
    }

    private static void updateMemoryUsage() {
        if (IS_IPC_CLIENT || !enableMemoryDisplay) return;

        final Runtime runtime = Runtime.getRuntime();
        final long usage = runtime.totalMemory() - runtime.freeMemory();
        final long total = runtime.maxMemory();

        if (sendIpc(3, Long.toString(usage), Long.toString(total))) return;

        updateMemoryUsage0(usage, total);
    }

    private static void updateMemoryUsage0(long usage, long total) {
        if (memoryBar == null) return;

        final double bytesPerMb = 1024L * 1024L;
        final int usageMb = (int)Math.round(usage / bytesPerMb);
        final int totalMb = (int)Math.round(total / bytesPerMb);

        memoryBar.setMaximum(totalMb);
        memoryBar.setValue(usageMb);
        memoryBar.setString(usageMb + " MB / " + totalMb + " MB");
    }

    private static void setLabel(JProgressBar progressBar, String typeName, String typeType, @Nullable String modName) {
        final StringBuilder message = new StringBuilder("Loading '").append(typeName)
            .append("' (").append(typeType).append(") \u2014 ")
            .append(progressBar.getValue()).append('/').append(progressBar.getMaximum());
        if (modName != null) {
            message.append(" \u2014 ").append(modName);
        }
        progressBar.setString(message.toString());
    }

    private static void println(String message) {
        println(message, null);
    }

    private static void println(String message, Throwable t) {
        final String prefix = IS_IPC_CLIENT
            ? "[ModLoadingScreen (IPC client)] "
            : ENABLE_IPC
                ? "[ModLoadingScreen (IPC server)] "
                : "[ModLoadingScreen] ";
        System.out.println(prefix + message);
        if (logFile != null) {
            logFile.println(message);
        }
        if (t != null) {
            t.printStackTrace();
            if (logFile != null) {
                t.printStackTrace(logFile);
            }
        }
    }

    private static boolean sendIpc(int id, String... args) {
        if (!ENABLE_IPC) {
            return false;
        }
        if (ipcOut != null) {
            try {
                //noinspection SynchronizeOnNonFinalField
                synchronized (ipcOut) {
                    ipcOut.writeByte(id);
                    ipcOut.writeByte(args.length);
                    for (final String arg : args) {
                        ipcOut.writeUTF(arg);
                    }
                    ipcOut.flush();
                }
            } catch (IOException e) {
                if (e.getMessage().equals("The pipe is being closed")) {
                    System.exit(0);
                }
                println("Failed to send IPC message (id " + id + "): " + String.join("\t", args), e);
            }
        }
        return true;
    }

    // IPC client
    public static void main(String[] args) {
        try (PrintStream logFile = new PrintStream("ipc-client-log.txt")) {
            ActualLoadingScreen.logFile = logFile;
            startLoadingScreen();
            final DataInputStream in = new DataInputStream(System.in);
            mainLoop:
            while (true) {
                final int packetId = in.readByte();
                final String[] packetArgs = new String[in.readByte()];
                for (int i = 0; i < packetArgs.length; i++) {
                    packetArgs[i] = in.readUTF();
                }
                switch (packetId) {
                    case 0:
                        beforeEntrypointType(packetArgs[0], packetArgs[1], Integer.parseInt(packetArgs[2]));
                        break;
                    case 1:
                        beforeSingleEntrypoint(packetArgs[0], packetArgs[1], packetArgs[2], packetArgs[3]);
                        break;
                    case 2:
                        afterEntrypointType(packetArgs[0]);
                        break;
                    case 3:
                        updateMemoryUsage0(Long.parseLong(packetArgs[0]), Long.parseLong(packetArgs[1]));
                        break;
                    case 4:
                        break mainLoop;
                }
            }
            println("IPC client exiting cleanly");
        } catch (Exception e) {
            println("Error in IPC client", e);
        }
        close();
    }
}
