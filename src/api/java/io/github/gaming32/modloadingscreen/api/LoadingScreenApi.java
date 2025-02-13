package io.github.gaming32.modloadingscreen.api;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.entrypoint.EntrypointUtils;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.awt.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class LoadingScreenApi {
    private static final long FEATURES;
    private static final MethodHandle FINAL_ENTRYPOINTS;
    private static final MethodHandle IS_HEADLESS;
    private static final MethodHandle ENABLE_IPC;
    private static final MethodHandle PROGRESS;
    private static final MethodHandle IS_OPEN;

    static {
        long features = 0;
        MethodHandle finalEntrypoints = null;
        MethodHandle isHeadless = null;
        MethodHandle enableIpc = null;
        MethodHandle progress = null;
        MethodHandle isOpen = null;

        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            final Class<?> alsClass = ClassLoader.getSystemClassLoader().loadClass(
                "io.github.gaming32.modloadingscreen.ActualLoadingScreen"
            );

            try {
                finalEntrypoints = lookup.findStaticGetter(alsClass, "FINAL_ENTRYPOINTS", Set.class);
                features |= AvailableFeatures.FINAL_ENTRYPOINTS;
            } catch (Exception e) {
                loadFailed(">=1.0.3", AvailableFeatures.FINAL_ENTRYPOINTS, e);
            }

            try {
                isHeadless = lookup.findStaticGetter(alsClass, "IS_HEADLESS", boolean.class);
                features |= AvailableFeatures.HEADLESS_CHECK;
            } catch (Exception e) {
                loadFailed(">=1.0.3", AvailableFeatures.HEADLESS_CHECK, e);
            }

            try {
                enableIpc = lookup.findStaticGetter(alsClass, "ENABLE_IPC", boolean.class);
                features |= AvailableFeatures.IPC_CHECK;
            } catch (Exception e) {
                loadFailed(">=1.0.3", AvailableFeatures.IPC_CHECK, e);
            }

            try {
                progress = lookup.findStaticGetter(alsClass, "progress", Map.class);
                features |= AvailableFeatures.GET_PROGRESS;
            } catch (Exception e) {
                loadFailed(">=1.0.3", AvailableFeatures.GET_PROGRESS, e);
            }

            try {
                isOpen = lookup.findStatic(alsClass, "isOpen", MethodType.methodType(boolean.class));
                features |= AvailableFeatures.OPEN_CHECK;
            } catch (Exception e) {
                loadFailed(">=1.0.3", AvailableFeatures.OPEN_CHECK, e);
            }
        } catch (Exception e) {
            final String message = "[ModLoadingScreen] Failed to load LoadingScreenApi. No API features are available.";
            if (FabricLoader.getInstance().isModLoaded("mod-loading-screen")) {
                System.err.println(message);
                e.printStackTrace();
            } else {
                // This API could be called with Mod Loading Screen simply absent, in which case this is *not* an error
                // condition
                System.out.println(message);
                System.out.println("[ModLoadingScreen] This is not an error, because Mod Loading Screen isn't installed anyway.");
            }
        }

        FEATURES = features;
        FINAL_ENTRYPOINTS = finalEntrypoints;
        IS_HEADLESS = isHeadless;
        ENABLE_IPC = enableIpc;
        PROGRESS = progress;
        IS_OPEN = isOpen;

        System.out.println("[ModLoadingScreen] API loaded with features: " + AvailableFeatures.toString(FEATURES));
    }

    private static void loadFailed(String mlsVersionRequired, long feature, Exception e) {
        FabricLoader.getInstance()
            .getModContainer("mod-loading-screen")
            .ifPresent(container -> {
                try {
                    final Version version = container.getMetadata().getVersion();
                    if (VersionPredicate.parse(mlsVersionRequired).test(version)) {
                        System.err.println(
                            "[ModLoadingScreen] Failed to load feature \"" +
                                AvailableFeatures.toString(feature) +
                                "\" from the API!"
                        );
                        System.err.println("[ModLoadingScreen] This should not have happened on the version " + version);
                        System.err.println("[ModLoadingScreen] This feature should be compatible with " + mlsVersionRequired);
                        e.printStackTrace();
                    }
                } catch (VersionParsingException versionParsingException) {
                    throw new RuntimeException(versionParsingException);
                }
            });
    }

    /**
     * Returns the features of the API that are available to use, as a bit mask of flags from
     * {@link AvailableFeatures}. Any features not available will default to no-op fallback implementations.
     *
     * @see AvailableFeatures
     */
    public static long getFeatures() {
        return FEATURES;
    }

    /**
     * Returns whether the bitmask of features is supported.
     *
     * @param requestedFeatures A bitmask of feature flags from {@link AvailableFeatures}
     *
     * @return Whether all the requested features are available
     *
     * @see AvailableFeatures
     * @see AvailableFeatures#hasFeatures
     */
    public static boolean hasFeatures(long requestedFeatures) {
        return AvailableFeatures.hasFeatures(FEATURES, requestedFeatures);
    }

    /**
     * Invokes an entrypoint with a clean API. If Mod Loading Screen is available, its progress will show up in the
     * loading screen. If you are developing a Quilt mod, you should use {@code EntrypointUtil} instead.
     *
     * @throws net.fabricmc.loader.api.EntrypointException If any entrypoints threw an exception
     *
     * @apiNote This feature is <i>always</i> available, regardless of the return value of {@link #getFeatures}.
     * Calling this without Mod Loading Screen will work always, but just won't show up in the (non-existent) loading
     * screen.
     */
    public static <T> void invokeEntrypoint(String name, Class<T> type, Consumer<? super T> invoker) {
        EntrypointUtils.invoke(name, type, invoker);
    }

    /**
     * Returns a set of "final entrypoint" names. "Final entrypoints" are entrypoints that, when finished invoking,
     * will close the loading screen. You can use the return value to add or remove entrypoints so that they don't
     * exit the loading screen, and you can add your own entrypoints that should close the loading screen instead. Be
     * careful about mod compatibility when using this!
     *
     * @apiNote If {@link #getFeatures} doesn't return {@link AvailableFeatures#FINAL_ENTRYPOINTS}, this will return an
     * empty {@link Set} that does nothing when modified, and changes to it will not be seen by other mods.
     *
     * @return The mutable set of "final entrypoints".
     *
     * @see AvailableFeatures#FINAL_ENTRYPOINTS
     *
     * @since 1.0.3
     */
    @SuppressWarnings("unchecked")
    public static Set<String> getFinalEntrypoints() {
        if (FINAL_ENTRYPOINTS == null) {
            return new HashSet<>();
        }
        try {
            return (Set<String>)FINAL_ENTRYPOINTS.invoke();
        } catch (Throwable t) {
            return rethrow(t);
        }
    }

    /**
     * Returns whether the Mod Loading Screen (and the game in general) is running in a headless environment. If
     * {@link #getFeatures} doesn't return {@link AvailableFeatures#HEADLESS_CHECK}, this will return the value of
     * {@link GraphicsEnvironment#isHeadless}.
     *
     * @return {@code true} if running in a headless environment.
     *
     * @see GraphicsEnvironment#isHeadless
     * @see AvailableFeatures#HEADLESS_CHECK
     *
     * @since 1.0.3
     */
    public static boolean isHeadless() {
        if (IS_HEADLESS == null) {
            return GraphicsEnvironment.isHeadless();
        }
        try {
            return (boolean)IS_HEADLESS.invoke();
        } catch (Throwable t) {
            return rethrow(t);
        }
    }

    /**
     * Returns whether IPC is being used for the loading screen, and hasn't been disabled with
     * {@code mod-loading-screen.disableIpc}. If {@link #isHeadless} returns {@code true}, this will return
     * {@code false}. If {@link #getFeatures} doesn't return {@link AvailableFeatures#IPC_CHECK}, this will return
     * {@code false}.
     *
     * @return {@code true} IPC is being used for the loading screen.
     *
     * @deprecated Non-IPC may be removed in the future, at which point this will always return the opposite of
     * {@link #isHeadless}
     *
     * @see AvailableFeatures#IPC_CHECK
     *
     * @since 1.0.3
     */
    @Deprecated
    public static boolean isUsingIpc() {
        if (ENABLE_IPC == null) {
            return false;
        }
        try {
            return (boolean)ENABLE_IPC.invoke();
        } catch (Throwable t) {
            return rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> getAllProgress() {
        if (PROGRESS == null) {
            return Collections.emptyMap();
        }
        try {
            return (Map<String, Integer>)PROGRESS.invoke();
        } catch (Throwable t) {
            return rethrow(t);
        }
    }

    /**
     * Returns an {@link Set} of progress bar names. This will be updated dynamically when bars are updated. If
     * {@link #getFeatures} doesn't return {@link AvailableFeatures#GET_PROGRESS}, this will return an empty set.
     *
     * @see AvailableFeatures#GET_PROGRESS
     *
     * @since 1.0.3
     */
    @UnmodifiableView
    public static Set<String> getActiveProgressBars() {
        final Set<String> bars = getAllProgress().keySet();
        return bars == Collections.EMPTY_SET ? bars : Collections.unmodifiableSet(bars);
    }

    /**
     * Returns the current progress of a progress bar, or {@code null} if there is no such progress bar. If
     * {@link #getFeatures} doesn't return {@link AvailableFeatures#GET_PROGRESS}, this will always return
     * {@code null}.
     *
     * @param barName The name of the progress bar. In the case of entrypoints, this is the name of the entrypoint.
     *
     * @see AvailableFeatures#GET_PROGRESS
     *
     * @since 1.0.3
     */
    @Nullable
    public static Integer getProgress(String barName) {
        return getAllProgress().get(barName);
    }

    // TODO: Put a note in this when custom progress bars are added.
    /**
     * Returns whether a loading screen is currently active. If {@link #getFeatures} doesn't return
     * {@link AvailableFeatures#OPEN_CHECK}, this will always return {@code false}.
     *
     * @return {@code true} if there is a loading screen open.
     *
     * @see AvailableFeatures#OPEN_CHECK
     *
     * @since 1.0.3
     */
    public static boolean isOpen() {
        if (IS_OPEN == null) {
            return false;
        }
        try {
            return (boolean)IS_OPEN.invoke();
        } catch (Throwable t) {
            return rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R rethrow(Throwable t) throws T {
        throw (T)t;
    }
}
