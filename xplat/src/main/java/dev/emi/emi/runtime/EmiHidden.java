package dev.emi.emi.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.data.IndexStackData;
import dev.emi.emi.registry.EmiStackList;
import net.minecraft.util.Identifier;

public class EmiHidden {
	// Data loaded
	private static volatile Set<EmiIngredient> disabledStacks = Sets.newHashSet();
	private static volatile List<IndexStackData.Filter> disabledFilters = Lists.newArrayList();
	private static final ConcurrentHashMap<Identifier, Boolean> disabledFilterLookup = new ConcurrentHashMap<>();
	// Plugin defined
	public static Set<EmiIngredient> pluginDisabledStacks = Sets.newHashSet();
	public static List<Predicate<EmiStack>> pluginDisabledFilters = Lists.newArrayList();
	// User edited
	public static Set<EmiIngredient> hiddenStacks = new LinkedHashSet<>();

	public static void clear() {
		disabledStacks.clear();
		disabledFilters.clear();
		disabledFilterLookup.clear();
		pluginDisabledStacks.clear();
		pluginDisabledFilters.clear();
	}

	public static PrepareResult prepare() {
		PrepareResult result = new PrepareResult(Sets.newHashSet(), Lists.newArrayList());
		List<IndexStackData> isds = EmiData.stackData.stream().map(i -> i.get()).filter(i -> i.disable() && (!i.filters().isEmpty() || !i.removed().isEmpty())).toList();
		for (IndexStackData data : isds) {
			for (EmiIngredient stack : data.removed()) {
				result.disabledStacks.add(stack);
				result.disabledStacks.addAll(stack.getEmiStacks());
			}
			result.disabledFilters.addAll(data.filters());
		}
		return result;
	}

	public static void apply(PrepareResult result) {
		disabledStacks = result.disabledStacks;
		disabledFilters = result.disabledFilters;
	}

	public static JsonArray save() {
		JsonArray arr = new JsonArray();
		for (EmiIngredient stack : hiddenStacks) {
			JsonElement el = EmiIngredientSerializer.getSerialized(stack);
			if (el != null && !el.isJsonNull()) {
				arr.add(el);
			}
		}
		return arr;
	}

	public static void load(JsonArray arr) {
		hiddenStacks.clear();
		for (JsonElement el : arr) {
			EmiIngredient stack = EmiIngredientSerializer.getDeserialized(el).copy();
			if (!stack.isEmpty()) {
				for (EmiStack es : stack.getEmiStacks()) {
					es.comparison(c -> EmiPort.compareStrict());
				}
				hiddenStacks.add(stack);
			}
		}
	}

	public static boolean isHidden(EmiIngredient stack) {
		return hiddenStacks.contains(stack);
	}

	public static boolean isDisabled(EmiIngredient stack) {
		outer:
		for (EmiStack s : stack.getEmiStacks()) {
			if (disabledStacks.contains(s) || pluginDisabledStacks.contains(s)) {
				continue;
			}
			for (Predicate<EmiStack> predicate : pluginDisabledFilters) {
				if (predicate.test(s)) {
					continue outer;
				}
			}
			boolean filtered = disabledFilterLookup.computeIfAbsent(s.getId(), id -> {
				String str = id.toString();
				for (IndexStackData.Filter filter : disabledFilters) {
					if (filter.filter().test(str)) {
						return true;
					}
				}
				return false;
			});
			if (filtered) {
				continue;
			}
			return false;
		}
		return !stack.isEmpty();
	}

	public static void setVisibility(EmiIngredient stack, boolean hide, boolean similar) {
		if (similar && stack.getEmiStacks().size() == 1) {
			EmiStack es = stack.getEmiStacks().get(0);
			for (EmiStack i : EmiStackList.stacks) {
				if (es.getId().equals(i.getId())) {
					if (hide) {
						hiddenStacks.add(i.copy().comparison(c -> EmiPort.compareStrict()));
					} else {
						hiddenStacks.remove(i);
					}
				}
			}
		} else {
			if (hide) {
				stack = stack.copy();
				for (EmiStack es : stack.getEmiStacks()) {
					es.comparison(c -> EmiPort.compareStrict());
				}
				hiddenStacks.add(stack);
			} else {
				hiddenStacks.remove(stack);
			}
		}
		EmiPersistentData.save();
		EmiStackList.bakeFiltered();
	}

	public record PrepareResult(Set<EmiIngredient> disabledStacks, List<IndexStackData.Filter> disabledFilters) {}
}