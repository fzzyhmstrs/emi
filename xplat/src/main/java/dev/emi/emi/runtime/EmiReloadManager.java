package dev.emi.emi.runtime;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.common.collect.Lists;

import com.google.common.collect.Streams;
import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.jemi.JemiPlugin;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.registry.EmiComparisonDefaults;
import dev.emi.emi.registry.EmiDragDropHandlers;
import dev.emi.emi.registry.EmiExclusionAreas;
import dev.emi.emi.registry.EmiIngredientSerializers;
import dev.emi.emi.registry.EmiInitRegistryAsyncImpl;
import dev.emi.emi.registry.EmiPluginContainer;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.registry.EmiRegistryPluginAsyncImpl;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.registry.EmiStackProviders;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.search.EmiSearch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

public class EmiReloadManager {
	private static int loadedResourcesMask = 0;
	private static volatile boolean clear = false, restart = false;
	// 0 - empty, 1 - reloading, 2 - loaded, -1 - error
	private static volatile int status = 0;
	private static Thread thread;
	private static final ExecutorService executor = Executors.newFixedThreadPool( Math.max(1, ForkJoinPool.getCommonPoolParallelism() - 2), Thread.ofPlatform().daemon().name("EMI Reload Worker-", 1).factory());

	private static volatile Text reloadStep = EmiPort.literal("");
	private static final Profile reloadProfile = new Profile();
	public static final ConcurrentHashMap<String, Long> reloadWorries = new ConcurrentHashMap<>(16);
	public static final ConcurrentHashMap<String, Long> reloadStarts = new ConcurrentHashMap<>(16);

	public static void reloadTags() {
		loadedResourcesMask |= 1;
		if (loadedResourcesMask == 3) {
			EmiLog.info("Recipes synchronized, reloading EMI");
			loadedResourcesMask = 0;
			reload();
		} else {
			EmiLog.info("Recipes synchronized, waiting for tags to reload EMI...");
		}
	}

	public static void reloadRecipes() {
		loadedResourcesMask |= 2;
		if (loadedResourcesMask == 3) {
			EmiLog.info("Tags synchronized, reloading EMI");
			loadedResourcesMask = 0;
			reload();
		} else {
			EmiLog.info("Tags synchronized, waiting for recipes to reload EMI...");
		}
	}

	public static void clear() {
		synchronized (EmiReloadManager.class) {
			loadedResourcesMask = 0;
			clear = true;
			status = 0;
			reloadWorries.clear();
			reloadStarts.clear();
			if (thread != null && thread.isAlive()) {
				restart = true;
			} else {
				thread = new Thread(new ReloadWorker(false));
				thread.setName("EMI Reload Worker");
				thread.setDaemon(true);
				thread.start();
			}
		}
	}

	public static void reload() {
		synchronized (EmiReloadManager.class) {
			reloadProfile.reset();
			runStep(EmiPort.literal("Starting Reload"), "start_reload", () -> {
				status = 1;
				if (thread != null && thread.isAlive()) {
					restart = true;
				} else {
					clear = false;
					thread = new Thread(new ReloadWorker(true));
					thread.setName("EMI Reload Worker");
					thread.setDaemon(false);
					thread.start();
				}
			});
		}
	}

	public static Text stepText() {
		if (reloadWorries.size() < 2) {
			return reloadStep;
		} else {
			return EmiPort.literal("Running " + reloadWorries.size() + " steps");
		}
	}

	public static boolean shouldWorry() {
		long time = System.currentTimeMillis();
		for (Map.Entry<String, Long> entry : reloadWorries.entrySet()) {
			if (time > entry.getValue()) {
				return true;
			}
		}
		return false;
	}

	public static void pushStep(Text text, String key) {
		pushStep(text, key, 5_000);
	}

	public static void pushStep(Text text, String key, long worry) {
		EmiLog.info(text.getString());
		reloadProfile.pushProfile(key);
		reloadStep = text;
		reloadStarts.put(key, System.currentTimeMillis());
		reloadWorries.put(key, System.currentTimeMillis() + worry);
	}


	public static void popStep(String key) {
		EmiLog.LOG.debug("Reload step {} completed in {}ms", key, System.currentTimeMillis() - reloadStarts.getOrDefault(key, 0L));
		reloadProfile.popProfile(key);
		reloadStarts.remove(key);
		reloadWorries.remove(key);
	}

	public static void runStep(Text text, String key, Runnable runnable) {
		pushStep(text, key);
		runnable.run();
		popStep(key);
	}

	public static void runStep(Text text, String key, long worry, Runnable runnable) {
		pushStep(text, key, worry);
		runnable.run();
		popStep(key);
	}

	public static <T>  T supplyStep(Text text, String key, long worry, Supplier<T> supplier) {
		pushStep(text, key, worry);
		T t = supplier.get();
		popStep(key);
		return t;
	}

	public static <T>  T profileStep(String key, Supplier<T> supplier) {
		reloadProfile.pushProfile(key);
		reloadStarts.put(key, System.currentTimeMillis());
		T t = supplier.get();
		popStep(key);
		return t;
	}

	public static void profileStep(String key, Runnable runnable) {
		reloadProfile.pushProfile(key);
		reloadStarts.put(key, System.currentTimeMillis());
		runnable.run();
		popStep(key);
	}

	public static void profileStep(String key) {
		reloadProfile.pushProfile(key);
		reloadStarts.put(key, System.currentTimeMillis());
	}

	public static void step(Text text) {
		step(text, 5_000);
	}

	public static void step(Text text, long worry) {
		EmiLog.info(text.getString());
	}

	public static boolean isLoaded() {
		return status == 2 && (thread == null || !thread.isAlive());
	}

	public static int getStatus() {
		return status;
	}

	private record ReloadWorker(boolean profile) implements Runnable {

		@Override
			public void run() {
				AtomicBoolean flag = new AtomicBoolean(false);
				if (profile) {
					CompletableFuture.runAsync(() -> {
						while (!flag.get()) {
							try {
								reloadProfile.poll();
								Thread.sleep(Duration.ofNanos(50_000));
							} catch (Throwable ignored) {
							}
						}
					});
				}
				runReload(3);
				flag.set(true);
			}

			private void testRestart(int retries) {
				if (restart) {
					runReload(retries);
				}
			}

			private void runReload(int retries) {
				do {
					try {
						if (!clear) {
							EmiLog.info("Starting EMI reload...");
						}
						long reloadStart = System.currentTimeMillis();
						restart = false;

						runStep(EmiPort.literal("Clearing data"), "clear_data", () -> {
							EmiRecipes.clear();
							EmiStackList.clear();
							EmiIngredientSerializers.clear();
							EmiExclusionAreas.clear();
							EmiDragDropHandlers.clear();
							EmiStackProviders.clear();
							EmiRecipeFiller.clear();
							EmiHidden.clear();
							EmiTags.ADAPTERS_BY_CLASS.map().clear();
							EmiTags.ADAPTERS_BY_REGISTRY.clear();
						});
						if (clear) {
							clear = false;
							continue;
						}

						MinecraftClient client = MinecraftClient.getInstance();
						if (client.world == null) {
							EmiReloadLog.warn("World is null");
							break;
						} else if (client.world.getRecipeManager() == null) {
							EmiReloadLog.warn("Recipe Manager is null");
							break;
						}

						CompletableFuture<Void> indexFuture = CompletableFuture.supplyAsync(() -> {
							pushStep(EmiPort.literal("Constructing index"), "construct_index");
							return EmiStackList.prepare();
						}, executor).thenAccept((result) -> {
							EmiStackList.apply(result);
							popStep("construct_index");
						});

						List<EmiPluginContainer> plugins = Lists.newArrayList();
						List<EmiPluginContainer> lastPlugins = Lists.newArrayList();

						profileStep("sort_plugin", () -> {
							List<EmiPluginContainer> foundPlugins = EmiAgnos.getPlugins();
							for (EmiPluginContainer plugin : foundPlugins) {
								if (entrypointPriority(plugin) == 0) {
									plugins.add(plugin);
								}
							}
							for (EmiPluginContainer plugin : foundPlugins) {
								if (entrypointPriority(plugin) != 0) {
									plugins.add(plugin);
								}
							}
							if (EmiAgnos.isModLoaded("jei")) {
								lastPlugins.add(new EmiPluginContainer(new JemiPlugin(), "jemi"));
							}
						});
						testRestart(retries);

						//collect and start plugin workers. "last plugins" have no affect here
						List<CompletableFuture<InitContext>> whenCompleteInitFutures = Streams.concat(plugins.stream(), lastPlugins.stream()).map(this::initializePlugin).toList();
						//wait in the worker thread for the initialization to complete
						CompletableFuture.allOf(whenCompleteInitFutures.toArray(CompletableFuture[]::new)).join();
						testRestart(retries);

						//collect initializations
						profileStep("collect_init", () -> {
							for (CompletableFuture<InitContext> initFuture : whenCompleteInitFutures) {
								initFuture.join().initRegistry.ifPresent(EmiInitRegistryAsyncImpl::collectInit);
							}
						});

						CompletableFuture<Void> tagsFuture = CompletableFuture.supplyAsync(() -> {
							pushStep(EmiPort.literal("Processing tags"), "process_tags");
							return EmiTags.prepare();
						}, executor).thenAccept((result) -> {
							EmiTags.apply(result);
							popStep("process_tags");
						});

						EmiComparisonDefaults.comparisons = new HashMap<>();

						CompletableFuture<Void> filtersFuture = CompletableFuture.supplyAsync(() -> {
							pushStep(EmiPort.literal("Processing filters"), "process_filters");
							return EmiHidden.prepare();
						}, executor).thenAccept((result) -> {
							EmiHidden.apply(result);
							popStep("process_filters");
						});

						CompletableFuture<Void> miscFuture = CompletableFuture.runAsync(() -> {
							runStep(EmiPort.literal("Loading persistent data"), "load_data", 15_000, EmiPersistentData::load);
						}, executor).orTimeout(60_000, TimeUnit.MILLISECONDS).whenComplete((result, exception) -> {
							if (exception instanceof TimeoutException) {
								EmiReloadLog.warn("EMI reload timed out while loading persistent data. EMI reload may be incomplete.");
							}
						});

						//wait for filters, tags, and the index
						//the persistent data can run past here though
						CompletableFuture.allOf(filtersFuture, tagsFuture).join();
						testRestart(retries);

						//build plugins with separate futures by plugin
						List<CompletableFuture<RegisterContext>> whenCompleteRegisterFutures = plugins.stream().map(this::registerPlugin).toList();

						//wait for initialization to complete
						CompletableFuture.allOf(whenCompleteRegisterFutures.toArray(CompletableFuture[]::new)).join();
						testRestart(retries);

						//make sure the stack list is built
						indexFuture.join();
						testRestart(retries);
						miscFuture.join();
						testRestart(retries);

						//apply all initialized plugins to the needed places
						profileStep("collect_registration", () -> {
							for (CompletableFuture<RegisterContext> future : whenCompleteRegisterFutures) {
								future.join().registry.ifPresent(EmiRegistryPluginAsyncImpl::collectRegistry);
							}
						});

						//plugins to run after "native" plugins are registered (JEI)
						for (EmiPluginContainer container : lastPlugins) {
							registerPlugin(container).join().registry().ifPresent(EmiRegistryPluginAsyncImpl::collectRegistry);
							testRestart(retries);
						}


						CompletableFuture<Void> bakeIndexFuture = CompletableFuture.runAsync(() -> {
							runStep(EmiPort.literal("Baking index"), "bake_index", EmiStackList::bake);
						}, executor);

						int finalRetries = retries;
						CompletableFuture<Void> recipesFuture = CompletableFuture.runAsync(() -> {
							runStep(EmiPort.literal("Registering late recipes"), "register_late", 10_000, () -> {
								Consumer<EmiRecipe> registerLateRecipe = (recipe) -> {
									if (recipe.getInputs() == null) {
										EmiReloadLog.warn("Late Recipe " + recipe.getId() + " provides null inputs and cannot be added");
									} else if (recipe.getOutputs() == null) {
										EmiReloadLog.warn("Late Recipe " + recipe.getId() + " provides null outputs and cannot be added");
									} else {
										EmiRecipes.addRecipe(recipe);
									}
								};
								for (Consumer<Consumer<EmiRecipe>> consumer : EmiRecipes.lateRecipes) {
									try {
										consumer.accept(registerLateRecipe);
									} catch (Exception e) {
										EmiReloadLog.warn("Exception loading late recipes for plugins:", e);

									}
								}
							});
							testRestart(finalRetries);

						}, executor);

						CompletableFuture<Void> bakeRecipesFuture = CompletableFuture.allOf(bakeIndexFuture, recipesFuture).thenRunAsync(() -> {
							pushStep(EmiPort.literal("Baking recipes"), "baking_recipes", 15_000);
							EmiRecipes.bake(executor);
						}, executor).orTimeout(60_000, TimeUnit.MILLISECONDS).whenComplete((result, exception) -> {
							popStep("baking_recipes");
							//reload the BoM after recipes are baked
							runStep(EmiPort.literal("Reloading BoM"), "reload_bom", BoM::reload);
							if (exception instanceof TimeoutException) {
								EmiReloadLog.warn("EMI reload timed out while baking recipes. EMI recipes may be not work correctly.");
							}
						});

						//wait for index bake and then misc loading to finish and then filter the stack list and bake the search
						CompletableFuture<Void> filteredSearchFuture = bakeIndexFuture.thenRunAsync(() -> {
							runStep(EmiPort.literal("Filtering index"), "filter_index", EmiStackList::bakeFiltered);
						}, executor);

						CompletableFuture<Void> searchFuture = bakeIndexFuture.thenRunAsync(() -> {
							pushStep(EmiPort.literal("Baking search"), "baking_search", 15_000);
							EmiSearch.bake(executor);
						}, executor).orTimeout(60_000, TimeUnit.MILLISECONDS).whenComplete((result, exception) -> {
							popStep("baking_search");
							if (exception instanceof TimeoutException) {
								EmiReloadLog.warn("EMI reload timed out while baking search. EMI searching may be not work correctly.");
							}
						});

						//wait for filter baking and search baking to finish
						CompletableFuture.allOf(recipesFuture, filteredSearchFuture, searchFuture, bakeRecipesFuture).join();
						testRestart(retries);

						runStep(EmiPort.literal("Finishing up"), "finish_up", () -> {
							EmiScreenManager.search.update();
							EmiScreenManager.forceRecalculate();
							EmiReloadLog.bake();
							EmiLog.info("Reloaded EMI in " + (System.currentTimeMillis() - reloadStart) + "ms");
						});
						reloadProfile.log();
						status = 2;
					} catch (Throwable e) {
						EmiReloadLog.warn("Critical error occurred during reload:", e);
						status = -1;
						if (retries-- > 0) {
							restart = true;
						}
					}
				} while (restart);
				thread = null;
			}

			private CompletableFuture<InitContext> initializePlugin(EmiPluginContainer container) {
				return CompletableFuture.supplyAsync(() -> {
					EmiInitRegistryAsyncImpl initRegistry = new EmiInitRegistryAsyncImpl();
					return supplyStep(EmiPort.literal("Initializing plugin from " + container.id()), container.id() + "_init", 5_000, () -> {
						long start = System.currentTimeMillis();
						try {
							container.plugin().initialize(initRegistry);
							EmiLog.info("Initialized plugin from " + container.id() + " in " + (System.currentTimeMillis() - start) + "ms");
						} catch (Throwable e) {
							EmiReloadLog.warn("Exception initializing plugin provided by " + container.id(), e);
							return new InitContext(Optional.empty(), container.id());
						}
						return new InitContext(Optional.of(initRegistry), container.id());
					});
				}, executor).orTimeout(15_000, TimeUnit.MILLISECONDS).whenComplete((result, exception) -> {
					if (exception instanceof TimeoutException) {
						EmiReloadLog.warn("Plugin provided by " + container.id() + " timed out while registering. Registration of EMI may be incomplete.");
					}
				});
			}

			private CompletableFuture<RegisterContext> registerPlugin(EmiPluginContainer container) {
				return CompletableFuture.supplyAsync(() -> {
					EmiRegistryPluginAsyncImpl registry = new EmiRegistryPluginAsyncImpl(container.id());
					return supplyStep(EmiPort.literal("Loading plugin from " + container.id()), container.id() + "_register", 10_000, () -> {
						long start = System.currentTimeMillis();
						try {
							container.plugin().register(registry);
							EmiLog.info("Reloaded plugin from " + container.id() + " in " + (System.currentTimeMillis() - start) + "ms");
						} catch (Throwable e) {
							EmiReloadLog.warn("Exception loading plugin provided by " + container.id(), e);
							return new RegisterContext(Optional.empty(), container);
						}
						return new RegisterContext(Optional.of(registry), container);
					});

				}, executor).orTimeout(60_000, TimeUnit.MILLISECONDS).whenComplete((result, exception) -> {
					if (exception instanceof TimeoutException) {
						EmiReloadLog.warn("Plugin provided by " + container.id() + " timed out while registering. Registration of EMI may be incomplete.");
					}
				});

			}

			private static int entrypointPriority(EmiPluginContainer container) {
				return container.id().equals("emi") ? 0 : 1;
			}

			private record InitContext(Optional<EmiInitRegistryAsyncImpl> initRegistry, String currentContainer) { }

			private record RegisterContext(Optional<EmiRegistryPluginAsyncImpl> registry, EmiPluginContainer container) { }
		}

	private static class Profile {
		private final ConcurrentSkipListSet<ProfileStep> steps = new ConcurrentSkipListSet<>();
		private long start = Long.MIN_VALUE;
		private long lastPoll = Long.MIN_VALUE;
		private final AtomicInteger index = new AtomicInteger();

		void pushProfile(String key) {
			try {
				steps.getLast().infos.add(" s(" + key + "),");
			} catch (Throwable ignored) {
			}
		}

		void popProfile(String key) {
			try {
				steps.getLast().infos.add(" f(" + key + "),");
			} catch (Throwable ignored) {
			}
		}

		void poll() {
			long time = System.currentTimeMillis();
			if (time - lastPoll >= 50) {
				lastPoll = time;
				int taskSize = reloadStarts.size() + 1;
				steps.add(new ProfileStep(index.getAndIncrement(), (time - start), taskSize, new ConcurrentSkipListSet<>(Comparator.reverseOrder())));
			}
		}

		void log() {
			EmiLog.LOG.debug("Reload Profile Results");
			for (ProfileStep step : steps) {
				String timeStr = Long.toString(step.time);
				int l = timeStr.length();
				StringBuilder builder = new StringBuilder("  [" + timeStr + "ms]");
				builder.append(" ".repeat(Math.max(0, 6 - l)));
				builder.append("|".repeat(Math.max(0, step.size)));
				for (String str : step.infos) {
					builder.append(str);
				}
				EmiLog.LOG.debug(builder.toString());
			}
			if (!reloadStarts.isEmpty()) {
				EmiLog.LOG.debug("Un-popped steps: {}", reloadStarts.keySet());
			}
		}

		void reset() {
			start = System.currentTimeMillis();
			lastPoll = start;
			index.set(0);
			steps.clear();
			steps.add(new ProfileStep(index.getAndIncrement(), 0, 1, new ConcurrentSkipListSet<>(Comparator.reverseOrder())));
		}

		private record ProfileStep(Integer index, long time, int size, ConcurrentSkipListSet<String> infos) implements Comparable<ProfileStep> {
			@Override
			public int compareTo(@NotNull EmiReloadManager.Profile.ProfileStep o) {
				return this.index.compareTo(o.index);
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) return true;
				if (!(o instanceof ProfileStep that)) return false;
				return Objects.equals(index, that.index);
			}

			@Override
			public int hashCode() {
				return Objects.hashCode(index);
			}
		}
	}
}