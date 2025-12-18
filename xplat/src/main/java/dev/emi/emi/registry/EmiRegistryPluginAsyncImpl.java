package dev.emi.emi.registry;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiExclusionArea;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.EmiStackProvider;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeDecorator;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.data.EmiAlias;
import dev.emi.emi.runtime.EmiHidden;
import dev.emi.emi.runtime.EmiReloadLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;

import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class EmiRegistryPluginAsyncImpl implements EmiRegistry {
	private static final MinecraftClient client = MinecraftClient.getInstance();

	private final List<EmiRecipeCategory> categories = Lists.newArrayList();
	private final Map<EmiRecipeCategory, List<EmiIngredient>> workstations = Maps.newHashMap();
	private final List<EmiRecipe> recipes = Lists.newArrayList();
	private final List<Predicate<EmiRecipe>> recipeInvalidators = Lists.newArrayList();
	private final List<Consumer<Consumer<EmiRecipe>>> lateRecipes = Lists.newArrayList();
	private final List<EmiStack> stacks = Lists.newArrayList();
	private final List<Pair<EmiStack, Predicate<EmiStack>>> stackAdditions = Lists.newArrayList();
	private final List<Predicate<EmiStack>> stackInvalidators = Lists.newArrayList();
	private final Map<Class<?>, EmiIngredientSerializer<?>> SERIALiZERS_BY_CLASS = Maps.newHashMap();
	private final Map<String, EmiIngredientSerializer<?>> SERIALIZERS_BY_TYPE = Maps.newHashMap();
	private final Map<Class<?>, List<EmiExclusionArea<?>>> fromClassExclusionAreas = Maps.newHashMap();
	private final List<EmiExclusionArea<?>> genericExclusionAreas = Lists.newArrayList();
	private final Map<Class<?>, List<EmiDragDropHandler<?>>> fromClassDragDropHandlers = Maps.newHashMap();
	private final List<EmiDragDropHandler<?>> genericDragDropHandlers = Lists.newArrayList();
	private final Map<Class<?>, List<EmiStackProvider<?>>> fromClassStackProviders = Maps.newHashMap();
	private final List<EmiStackProvider<?>> genericStackProviders = Lists.newArrayList();
	private final Map<ScreenHandlerType<?>, List<EmiRecipeHandler<?>>> recipeHandlers = Maps.newHashMap();
	private final Map<Object, Function<Comparison, Comparison>> comparisons = Maps.newHashMap();
	private final List<EmiAlias.Baked> registryAliases = Lists.newArrayList();
	private final List<EmiRecipeDecorator> decorators = Lists.newArrayList();

	final int order;
	private final String id;

	public EmiRegistryPluginAsyncImpl(String id, int order) {
		this.id = id;
		this.order = order;
	}

	@Override
	public boolean isStackDisabled(EmiIngredient stack) {
		return EmiHidden.isDisabled(stack);
	}

	@Override
	public RecipeManager getRecipeManager() {
		return client.world.getRecipeManager();
	}

	@Override
	public void addCategory(EmiRecipeCategory category) {
		categories.add(category);
	}

	@Override
	public void addWorkstation(EmiRecipeCategory category, EmiIngredient workstation) {
		workstations.computeIfAbsent(category, k -> Lists.newArrayList()).add(workstation);
	}

	@Override
	public void addRecipe(EmiRecipe recipe) {
		if (recipe.getInputs() == null) {
			EmiReloadLog.warn("Recipe " + recipe.getId() + " from plugin " + id + " provides null inputs and cannot be added");
		} else if (recipe.getOutputs() == null) {
			EmiReloadLog.warn("Recipe " + recipe.getId() + " from plugin " + id + " provides null outputs and cannot be added");
		} else {
			recipes.add(recipe);
		}
	}

	@Override
	public void removeRecipes(Predicate<EmiRecipe> predicate) {
		recipeInvalidators.add(predicate);
	}

	@Override
	public void addDeferredRecipes(Consumer<Consumer<EmiRecipe>> consumer) {
		lateRecipes.add(consumer);
	}

	@Override
	public void addEmiStack(EmiStack stack) {
		stacks.add(stack);
	}

	@Override
	public void addEmiStackAfter(EmiStack stack, Predicate<EmiStack> predicate) {
		stackAdditions.add(new Pair<>(stack, predicate));
		/*ListIterator<EmiStack> listIterator = EmiStackList.stacks.listIterator();
		while (listIterator.hasNext()) {
			EmiStack candidate = listIterator.next();
			if (predicate.test(candidate)) {
				listIterator.add(stack);
				return;
			}
		}*/
	}

	@Override
	public void removeEmiStacks(Predicate<EmiStack> predicate) {
		stackInvalidators.add(predicate);
	}

	@Override
	public <T extends EmiIngredient> void addIngredientSerializer(Class<T> clazz, EmiIngredientSerializer<T> serializer) {
		SERIALiZERS_BY_CLASS.put(clazz, serializer);
		SERIALIZERS_BY_TYPE.put(serializer.getType(), serializer);
	}

	@Override
	public <T extends Screen> void addExclusionArea(Class<T> clazz, EmiExclusionArea<T> area) {
		fromClassExclusionAreas.computeIfAbsent(clazz, c -> Lists.newArrayList()).add(area);
	}

	@Override
	public void addGenericExclusionArea(EmiExclusionArea<Screen> area) {
		genericExclusionAreas.add(area);
	}

	@Override
	public <T extends Screen> void addDragDropHandler(Class<T> clazz, EmiDragDropHandler<T> handler) {
		fromClassDragDropHandlers.computeIfAbsent(clazz, c -> Lists.newArrayList()).add(handler);
	}

	@Override
	public void addGenericDragDropHandler(EmiDragDropHandler<Screen> handler) {
		genericDragDropHandlers.add(handler);
	}

	@Override
	public <T extends Screen> void addStackProvider(Class<T> clazz, EmiStackProvider<T> provider) {
		fromClassStackProviders.computeIfAbsent(clazz, c -> Lists.newArrayList()).add(provider);
	}

	@Override
	public void addGenericStackProvider(EmiStackProvider<Screen> provider) {
		genericStackProviders.add(provider);
	}

	@Override
	public <T extends ScreenHandler> void addRecipeHandler(ScreenHandlerType<T> type, EmiRecipeHandler<T> handler) {
		recipeHandlers.computeIfAbsent(type, (c) -> Lists.newArrayList()).add(handler);
	}

	@Override
	public void setDefaultComparison(Object key, Function<Comparison, Comparison> comparison) {
		comparisons.put(key, comparison);
	}

	@Override
	public void addAlias(EmiIngredient stack, Text text) {
		registryAliases.add(new EmiAlias.Baked(List.of(stack), List.of(text)));
	}

	@Override
	public void addRecipeDecorator(EmiRecipeDecorator decorator) {
		decorators.add(decorator);
	}

	public void collectRegistry() {
		categories.forEach(EmiRecipes::addCategory);
		for (Map.Entry<EmiRecipeCategory, List<EmiIngredient>> entry : workstations.entrySet()) {
			for (EmiIngredient ingredient : entry.getValue()) {
				EmiRecipes.addWorkstation(entry.getKey(), ingredient);
			}
		}
		recipes.forEach(EmiRecipes::addRecipe);
		EmiRecipes.invalidators.addAll(recipeInvalidators);
		EmiRecipes.lateRecipes.addAll(lateRecipes); //////////////
		stacks.forEach(EmiStackList::addStack);
		ListIterator<EmiStack> listIterator = EmiStackList.stacks.listIterator();
		while (listIterator.hasNext()) {
			EmiStack candidate = listIterator.next();
			addStackAfter(candidate, listIterator::add);
		}
		EmiStackList.invalidators.addAll(stackInvalidators);
		EmiIngredientSerializers.BY_CLASS.putAll(SERIALiZERS_BY_CLASS);
		EmiIngredientSerializers.BY_TYPE.putAll(SERIALIZERS_BY_TYPE);
		for (Map.Entry<Class<?>, List<EmiExclusionArea<?>>> entry : fromClassExclusionAreas.entrySet()) {
			EmiExclusionAreas.fromClass.computeIfAbsent(entry.getKey(), c -> Lists.newArrayList()).addAll(entry.getValue());
		}
		EmiExclusionAreas.generic.addAll(genericExclusionAreas);
		for (Map.Entry<Class<?>, List<EmiDragDropHandler<?>>> entry : fromClassDragDropHandlers.entrySet()) {
			EmiDragDropHandlers.fromClass.computeIfAbsent(entry.getKey(), c -> Lists.newArrayList()).addAll(entry.getValue());
		}
		EmiDragDropHandlers.generic.addAll(genericDragDropHandlers);
		for (Map.Entry<Class<?>, List<EmiStackProvider<?>>> entry : fromClassStackProviders.entrySet()) {
			EmiStackProviders.fromClass.computeIfAbsent(entry.getKey(), c -> Lists.newArrayList()).addAll(entry.getValue());
		}
		EmiStackProviders.generic.addAll(genericStackProviders);
		for (Map.Entry<ScreenHandlerType<?>, List<EmiRecipeHandler<?>>> entry : recipeHandlers.entrySet()) {
			EmiRecipeFiller.handlers.computeIfAbsent(entry.getKey(), c -> Lists.newArrayList()).addAll(entry.getValue());
		}
		for (Map.Entry<Object, Function<Comparison, Comparison>> entry : comparisons.entrySet()) {
			EmiComparisonDefaults.comparisons.put(entry.getKey(), entry.getValue().apply(EmiComparisonDefaults.get(entry.getValue())));
		}
		EmiStackList.registryAliases.addAll(registryAliases);
		EmiRecipes.decorators.addAll(decorators);
	}

	public void addStackAfter(EmiStack candidate, Consumer<EmiStack> onSuccess) {
		for (Pair<EmiStack, Predicate<EmiStack>> predicatePair : stackAdditions) {
			if (predicatePair.getRight().test(candidate)) {
				onSuccess.accept(predicatePair.getLeft());
			}
		}
	}
}