package dev.emi.emi.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeDecorator;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.recipe.EmiRecipeSorting;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.data.EmiRecipeCategoryProperties;
import dev.emi.emi.runtime.EmiHidden;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiReloadLog;
import dev.emi.emi.runtime.dev.EmiDev;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.util.Identifier;

public class EmiRecipes {
	//public static volatile Worker activeWorker = null;
	public static EmiRecipeManager manager = Manager.EMPTY;
	public static List<Consumer<Consumer<EmiRecipe>>> lateRecipes = Lists.newArrayList();
	public static List<Predicate<EmiRecipe>> invalidators = Lists.newArrayList();

	public static List<EmiRecipeCategory> categories = Lists.newArrayList();
	private static Map<EmiRecipeCategory, List<EmiIngredient>> workstations = Maps.newHashMap();
	private static List<EmiRecipe> recipes = Lists.newArrayList();

	public static Map<EmiStack, List<EmiRecipe>> byWorkstation = Maps.newHashMap();
	public static List<EmiRecipeDecorator> decorators = Lists.newArrayList();

	public static Map<Recipe<?>, Identifier> recipeIds = Map.of();

	public static void clear() {
		//setWorker(null);
		lateRecipes.clear();
		invalidators.clear();
		categories.clear();
		workstations.clear();
		recipes.clear();
		byWorkstation.clear();
		decorators.clear();
		manager = Manager.EMPTY;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world != null) {
			RecipeManager manager = client.world.getRecipeManager();
			recipeIds = new Reference2ObjectOpenHashMap<>();
			if (manager != null) {
				for (RecipeEntry<?> entry : manager.values()) {
					recipeIds.put(entry.value(), entry.id());
				}
			}
		}
	}

	public static void bake(Executor executor) {
		long start = System.currentTimeMillis();
		recipes.addAll(EmiData.recipes.stream().map(r -> r.get()).toList());
		categories.sort(Comparator.comparingInt(EmiRecipeCategoryProperties::getOrder));
		invalidators.addAll(EmiData.recipeFilters);

		invalidators.add(r -> {
			for (EmiIngredient i : r.getInputs()) {
				if (EmiHidden.isDisabled(i)) {
					return true;
				}
			}
			for (EmiIngredient i : r.getOutputs()) {
				if (EmiHidden.isDisabled(i)) {
					return true;
				}
			}
			for (EmiIngredient i : r.getCatalysts()) {
				if (EmiHidden.isDisabled(i)) {
					return true;
				}
			}
			return false;
		});

		List<EmiRecipe> filtered = EmiReloadManager.profileStep("bake_recipes_filter", () -> {
			return recipes.stream().filter(r -> {
				try {
					for (Predicate<EmiRecipe> predicate : invalidators) {
						if (predicate.test(r)) {
							return false;
						}
					}
				} catch (Throwable e) {
					EmiReloadLog.warn("Exception filtering recipe " + r.getId(), e);
				}
				return true;
			}).toList();
		});

		Map<EmiRecipeCategory, List<EmiIngredient>> filteredWorkstations = Maps.newHashMap();
		for (Map.Entry<EmiRecipeCategory, List<EmiIngredient>> entry : workstations.entrySet()) {
			List<EmiIngredient> w = entry.getValue().stream().filter(s -> !EmiHidden.isDisabled(s)).toList();
			if (!w.isEmpty()) {
				filteredWorkstations.put(entry.getKey(), w);
			}
		}
		manager = new Manager(executor, categories, filteredWorkstations, filtered, true);
		//setWorker(new Worker(categories, filteredWorkstations, filtered));
		EmiLog.info("Baked " + recipes.size() + " recipes in " + (System.currentTimeMillis() - start) + "ms");
	}

	public static void addCategory(EmiRecipeCategory category) {
		categories.add(category);
	}

	public static void addWorkstation(EmiRecipeCategory category, EmiIngredient workstation) {
		workstations.computeIfAbsent(category, k -> Lists.newArrayList()).add(workstation);
	}

	public static void addRecipe(EmiRecipe recipe) {
		recipes.add(recipe);
	}

	/*private static synchronized void setWorker(Worker worker) {
		activeWorker = worker;
		if (worker != null) {
			Thread thread = new Thread(activeWorker);
			thread.setName("EMI Recipe Worker");
			thread.start();
		}
	}*/

	private static class Manager implements EmiRecipeManager {
		public static final EmiRecipeManager EMPTY = new Manager();
		private final List<EmiRecipeCategory> categories;
		private final Map<EmiRecipeCategory, List<EmiIngredient>> workstations;
		private final List<EmiRecipe> recipes;
		private Map<EmiStack, List<EmiRecipe>> byInput = new Object2ObjectOpenCustomHashMap<>(new EmiStackList.ComparisonHashStrategy());
		private Map<EmiStack, List<EmiRecipe>> byOutput = new Object2ObjectOpenCustomHashMap<>(new EmiStackList.ComparisonHashStrategy());
		private Map<EmiRecipeCategory, List<EmiRecipe>> byCategory = Maps.newHashMap();
		private Map<Identifier, EmiRecipe> byId = Maps.newHashMap();

		private Manager() {
			this.categories = List.of();
			this.workstations = Map.of();
			this.recipes = List.of();
		}

		public Manager(Executor executor, List<EmiRecipeCategory> categories, Map<EmiRecipeCategory, List<EmiIngredient>> workstations, List<EmiRecipe> recipes, boolean doSort) {
			this.categories = categories.stream().distinct().toList();
			this.workstations = workstations;
			this.recipes = List.copyOf(recipes);

			Object2IntMap<Identifier> duplicateIds = new Object2IntOpenHashMap<>();
			Set<Identifier> incorrectIds = new ObjectArraySet<>();
			EmiReloadManager.profileStep("bake_recipe_categorize_" + doSort, () -> {
				for (EmiRecipe recipe : this.recipes) {
					Identifier id = recipe.getId();
					EmiRecipeCategory category = recipe.getCategory();
					if (!categories.contains(category)) {
						EmiReloadLog.warn("Recipe " + id + " loaded with unregistered category: " + category.getId());
					}
					if (EmiConfig.logNonTagIngredients && recipe.supportsRecipeTree()) {
						Set<EmiIngredient> seen = new ObjectArraySet<>(0);
						for (EmiIngredient ingredient : recipe.getInputs()) {
							if (ingredient instanceof ListEmiIngredient && !seen.contains(ingredient)) {
								EmiReloadLog.warn("Recipe " + recipe.getId() + " uses non-tag ingredient: " + ingredient);
								seen.add(ingredient);
							}
						}
					}
					byCategory.computeIfAbsent(category, a -> Lists.newArrayList()).add(recipe);
					if (id != null) {
						if (byId.containsKey(id)) {
							duplicateIds.put(id, duplicateIds.getOrDefault(id, 1) + 1);
						} else {
							byId.put(id, recipe);
						}

						if (EmiConfig.devMode && !id.getPath().startsWith("/") && !recipeIds.containsValue(id)) {
							incorrectIds.add(id);
						}
					}
				}
			});


			if (EmiConfig.devMode) {
				for (Identifier id : duplicateIds.keySet()) {
					EmiReloadLog.warn(duplicateIds.getInt(id) + " recipes loaded with the same id: " + id);
				}
				for (Identifier id : incorrectIds) {
					EmiReloadLog.warn("Recipe " + id + " not present in recipe manager. Consider prefixing its path with '/' if it is synthetic.");
				}
			}

			EmiReloadManager.profileStep("bake_recipe_bake_" + doSort);

			if (doSort) {
				for (Map.Entry<EmiRecipeCategory, List<EmiRecipe>> cEntries : byCategory.entrySet()) {
					EmiRecipeCategory category = cEntries.getKey();
					List<EmiRecipe> cRecipes = new ArrayList<>(cEntries.getValue());
					Comparator<EmiRecipe> sort = EmiRecipeCategoryProperties.getSort(category);
					if (sort != EmiRecipeSorting.none()) {
						cRecipes = cRecipes.stream().sorted(sort).collect(Collectors.toList());
						EmiRecipeSorter.clear();
					}
					byCategory.put(category, cRecipes);
				}
			}

			List<CompletableFuture<Pair<Map<EmiStack, Set<EmiRecipe>>, Map<EmiStack, Set<EmiRecipe>>>>> futures = Lists.newArrayList();

			for (Map.Entry<EmiRecipeCategory, List<EmiRecipe>> cEntries : byCategory.entrySet()) {
				futures.add(CompletableFuture.supplyAsync(() -> {
					Map<EmiStack, Set<EmiRecipe>> cByInputSets = new Object2ObjectOpenCustomHashMap<>(new EmiStackList.ComparisonHashStrategy());
					Map<EmiStack, Set<EmiRecipe>> cByOutputSets = new Object2ObjectOpenCustomHashMap<>(new EmiStackList.ComparisonHashStrategy());
					EmiRecipeCategory category = cEntries.getKey();
					List<EmiRecipe> cRecipes = cEntries.getValue();
					String key = EmiUtil.translateId("emi.category.", category.getId());
					if (category.getName().equals(EmiPort.translatable(key)) && !I18n.hasTranslation(key)) {
						EmiReloadLog.warn("Untranslated recipe category " + category.getId());
					}

					for (EmiRecipe recipe : cRecipes) {
						recipe.getInputs().stream().flatMap(i -> i.getEmiStacks().stream()).forEach(i -> {
							cByInputSets.computeIfAbsent(i.copy(), b -> Sets.newLinkedHashSet()).add(recipe);
						});
						recipe.getCatalysts().stream().flatMap(i -> i.getEmiStacks().stream()).forEach(i -> {
							cByInputSets.computeIfAbsent(i.copy(), b -> Sets.newLinkedHashSet()).add(recipe);
						});
						recipe.getOutputs().forEach(i -> {
							cByOutputSets.computeIfAbsent(i.copy(), b -> Sets.newLinkedHashSet()).add(recipe);
						});
					}
					return new Pair<>(cByInputSets, cByOutputSets);
				}, executor));
			}

			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

			Map<EmiStack, List<EmiRecipe>> byInputs = new Object2ObjectOpenCustomHashMap<>(new EmiStackList.ComparisonHashStrategy());
			Map<EmiStack, List<EmiRecipe>> byOutputs = new Object2ObjectOpenCustomHashMap<>(new EmiStackList.ComparisonHashStrategy());

			for (CompletableFuture<Pair<Map<EmiStack, Set<EmiRecipe>>, Map<EmiStack, Set<EmiRecipe>>>> future: futures) {
				Pair<Map<EmiStack, Set<EmiRecipe>>, Map<EmiStack, Set<EmiRecipe>>> pair = future.join();
				for (Map.Entry<EmiStack, Set<EmiRecipe>> entry : pair.getLeft().entrySet()) {
					byInput.computeIfAbsent(entry.getKey(), (s) -> Lists.newArrayList()).addAll(entry.getValue());
				}
				for (Map.Entry<EmiStack, Set<EmiRecipe>> entry : pair.getRight().entrySet()) {
					byOutputs.computeIfAbsent(entry.getKey(), (s) -> Lists.newArrayList()).addAll(entry.getValue());
				}
			}

			this.byInput = byInputs;
			this.byOutput = byOutputs;
			EmiReloadManager.popStep("bake_recipe_bake_" + doSort);

			EmiReloadManager.profileStep("bake_recipe_finalize_workstations_" + doSort, () -> {
				for (EmiRecipeCategory category : workstations.keySet()) {
					List<EmiIngredient> w = workstations.getOrDefault(category, null);
					if (w != null) {
						workstations.put(category, w.stream().distinct().toList());
					} else {
						EmiReloadLog.warn("Recipe category illegally self-mutated during recipe bake, causing recipe loss: " + category);
					}
				}
			});

			EmiReloadManager.profileStep("bake_recipe_finalize_by_stations_" + doSort, () -> {
				for (Map.Entry<EmiRecipeCategory, List<EmiRecipe>> entry : byCategory.entrySet()) {
					for (EmiIngredient ingredient : workstations.getOrDefault(entry.getKey(), List.of())) {
						for (EmiStack stack : ingredient.getEmiStacks()) {
							byWorkstation.computeIfAbsent(stack, (s) -> Lists.newArrayList()).addAll(entry.getValue());
						}
					}
				}
			});

			if (EmiConfig.devMode) {
				EmiDev.duplicateRecipeIds = duplicateIds.keySet();
				EmiDev.incorrectRecipeIds = incorrectIds;
			}
			EmiReloadManager.popStep("bake_recipe_finalize_" + doSort);
		}

		@Override
		public List<EmiRecipeCategory> getCategories() {
			return categories;
		}

		@Override
		public List<EmiIngredient> getWorkstations(EmiRecipeCategory category) {
			return workstations.getOrDefault(category, List.of());
		}

		@Override
		public List<EmiRecipe> getRecipes() {
			return recipes;
		}

		@Override
		public List<EmiRecipe> getRecipes(EmiRecipeCategory category) {
			return byCategory.getOrDefault(category, List.of());
		}

		@Override
		public @Nullable EmiRecipe getRecipe(Identifier id) {
			return byId.getOrDefault(id, null);
		}

		@Override
		public List<EmiRecipe> getRecipesByInput(EmiStack stack) {
			return byInput.getOrDefault(stack, List.of());
		}

		@Override
		public List<EmiRecipe> getRecipesByOutput(EmiStack stack) {
			return byOutput.getOrDefault(stack, List.of());
		}
	}

	/*private static class Worker implements Runnable {
		private List<EmiRecipeCategory> categories;
		private Map<EmiRecipeCategory, List<EmiIngredient>> workstations;
		private List<EmiRecipe> recipes;

		public Worker(List<EmiRecipeCategory> categories, Map<EmiRecipeCategory, List<EmiIngredient>> workstations, List<EmiRecipe> recipes) {
			this.categories = categories;
			this.workstations = workstations;
			this.recipes = recipes;
		}

		@Override
		public void run() {
			long startTime = System.currentTimeMillis();
			Manager manager = new Manager(categories, workstations, recipes, true);
			if (activeWorker == this) {
				long endTime = System.currentTimeMillis();
				EmiLog.info("Baked recipes after reload in " + (endTime - startTime) + "ms");
				EmiRecipes.manager = manager;
			}
			setWorker(null);
		}
	}*/
}