package dev.emi.emi.registry;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import dev.emi.emi.api.EmiInitRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiRegistryAdapter;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.runtime.EmiHidden;
import net.minecraft.registry.Registry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class EmiInitRegistryAsyncImpl implements EmiInitRegistry {

	private final Map<Class<?>, EmiIngredientSerializer<?>> BY_CLASS = Maps.newHashMap();
	private final Map<String, EmiIngredientSerializer<?>> BY_TYPE = Maps.newHashMap();

	private final Set<EmiIngredient> DISABLED_STACKS = Sets.newHashSet();
	private final List<Predicate<EmiStack>> DISABLED_FILTERS = Lists.newArrayList();

	public static final Map<Class<?>, EmiRegistryAdapter<?>> ADAPTERS_BY_CLASS = Maps.newHashMap();
	public static final Map<Registry<?>, EmiRegistryAdapter<?>> ADAPTERS_BY_REGISTRY = Maps.newHashMap();

	@Override
	public <T extends EmiIngredient> void addIngredientSerializer(Class<T> clazz, EmiIngredientSerializer<T> serializer) {
		BY_CLASS.put(clazz, serializer);
		BY_TYPE.put(serializer.getType(), serializer);
	}

	@Override
	public void disableStacks(Predicate<EmiStack> predicate) {
		DISABLED_FILTERS.add(predicate);
	}

	@Override
	public void disableStack(EmiStack stack) {
		DISABLED_STACKS.add(stack);
	}

	@Override
	public void addRegistryAdapter(EmiRegistryAdapter<?> adapter) {
		ADAPTERS_BY_CLASS.put(adapter.getBaseClass(), adapter);
		ADAPTERS_BY_REGISTRY.put(adapter.getRegistry(), adapter);
	}

	public void collectInit() {
		EmiIngredientSerializers.BY_CLASS.putAll(BY_CLASS);
		EmiIngredientSerializers.BY_TYPE.putAll(BY_TYPE);
		EmiHidden.pluginDisabledFilters.addAll(DISABLED_FILTERS);
		EmiHidden.pluginDisabledStacks.addAll(DISABLED_STACKS);
		EmiTags.ADAPTERS_BY_CLASS.map().putAll(ADAPTERS_BY_CLASS);
		EmiTags.ADAPTERS_BY_REGISTRY.putAll(ADAPTERS_BY_REGISTRY);
	}
}