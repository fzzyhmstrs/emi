package dev.emi.emi.search;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.data.EmiAlias;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiReloadLog;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.search.SuffixArray;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import javax.naming.directory.SearchResult;

public class EmiSearch {
	public static final Pattern TOKENS = Pattern.compile(
		"-?[@#$]?" // Any query can be negated or prefixed with type
		+ "(" // Query contents
			+ "\\/(\\\\.|[^\\\\\\/])+\\/" // Any regex contents, for example `/some thing/`
			+ "|"
			+ "\\\"(\\.|[^\\\"])+\\\"" // Any quoted contents, for example, `"some thing"`
			+ "|"
			+ "[^\\s|]+" // Any raw contents, split on space
			+ "|"
			+ "\\|" // Literal OR symbol
			+ "|"
			+ "\\&" // Literal AND symbol (currently ignored since queries AND by deafult, but parsed)
		+ ")");
	private static volatile SearchWorker currentWorker = null;
	public static volatile Thread searchThread = null;
	public static volatile List<? extends EmiIngredient> stacks = EmiStackList.stacks;
	public static volatile CompiledQuery compiledQuery;
	public static volatile Set<EmiStack> bakedStacks;
	public static volatile SuffixArray<SearchStack> names, tooltips, mods;
	public static volatile SuffixArray<EmiStack> aliases;

	public static void bake(Executor executor) {
		SuffixArray<SearchStack> names = new SuffixArray<>();
		SuffixArray<SearchStack> tooltips = new SuffixArray<>();
		SuffixArray<SearchStack> mods = new SuffixArray<>();
		SuffixArray<EmiStack> aliases = new SuffixArray<>();
		Set<EmiStack> bakedStacks = Collections.newSetFromMap(new IdentityHashMap<>(EmiStackList.stacks.size()));
		boolean old = EmiConfig.appendItemModId;
		EmiConfig.appendItemModId = false;

		EmiReloadManager.profileStep("baking_stack_arrays", () -> {
			Queue<SearchBakeResult> bakeResult = EmiReloadManager.profileStep("creating_search_results", () -> {
				return EmiStackList.stacks
					.parallelStream()
					.unordered()
					.map((stack) -> {
						SearchStack ss = new SearchStack(stack);
						Text name = NameQuery.getText(stack);
						String nameString = name == null ? null : name.getString().toLowerCase();
						List<Text> tooltip = stack.getTooltipText();
						List<String> tooltipString = tooltip == null ? null : tooltip.stream().map((text) -> text.getString().toLowerCase()).toList();
						Identifier id = stack.getId();
						return new SearchBakeResult(ss, nameString, tooltipString, id);
					})
					.collect(Collector.of(
						ConcurrentLinkedQueue::new,
						Queue::add,
						(left, right) -> { left.addAll(right); return left; },
						Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED
					));
			});

			//Collectors.toCollection(() -> new ArrayList<>(EmiStackList.stacks.size() / 8))

			EmiLog.LOG.debug("Search list size {} vs. created results {}", EmiStackList.stacks.size(), bakeResult.size());

			EmiReloadManager.profileStep("processing_search_bake", () -> {
				for (SearchBakeResult searchBakeResult : bakeResult) {
					try {
						SearchStack searchStack = searchBakeResult.stack;
						bakedStacks.add(searchStack.stack);
						if (searchBakeResult.nameString != null) {
							names.add(searchStack, searchBakeResult.nameString);
						}
						if (searchBakeResult.tooltip != null) {
							for (int i = 1; i < searchBakeResult.tooltip.size(); i++) {
								String text = searchBakeResult.tooltip.get(i);
								if (text != null) {
									tooltips.add(searchStack, text);
								}
							}
						}
						Identifier id = searchBakeResult.id;
						if (id != null) {
							mods.add(searchStack, EmiUtil.getModName(id.getNamespace()).toLowerCase());
							mods.add(searchStack, id.getNamespace().toLowerCase());
							names.add(searchStack, id.getPath().toLowerCase());
						}
						if (searchBakeResult.stack.stack.getItemStack().getItem() == Items.ENCHANTED_BOOK) {
							for (RegistryEntry<Enchantment> e : searchBakeResult.stack.stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT).getEnchantments()) {
								Identifier eid = EmiPort.getEnchantmentRegistry().getId(e.value());
								if (eid != null && !eid.getNamespace().equals("minecraft")) {
									mods.add(searchStack, EmiUtil.getModName(eid.getNamespace()).toLowerCase());
								}
							}
						}
					} catch (Exception e) {
						EmiLog.error("EMI caught an exception while baking search for " + searchBakeResult, e);
					}
				}
			});
		});

		EmiReloadManager.profileStep("baking_alias_array", () -> {
			for (Supplier<EmiAlias> supplier : Lists.newArrayList(EmiData.aliases)) {
				EmiAlias alias = supplier.get();
				for (String key : alias.keys()) {
					if (!I18n.hasTranslation(key)) {
						EmiReloadLog.warn("Untranslated alias " + key);
					}
					String text = I18n.translate(key).toLowerCase();
					for (EmiIngredient ing : alias.stacks()) {
						for (EmiStack stack : ing.getEmiStacks()) {
							aliases.add(stack.copy().comparison(EmiPort.compareStrict()), text);
						}
					}
				}
			}
			for (EmiAlias.Baked alias : Lists.newArrayList(EmiStackList.registryAliases)) {
				for (Text text : alias.text()) {
					for (EmiIngredient ing : alias.stacks()) {
						for (EmiStack stack : ing.getEmiStacks()) {
							aliases.add(stack.copy().comparison(EmiPort.compareStrict()), text.getString().toLowerCase());
						}
					}
				}
			}
		});

		EmiConfig.appendItemModId = old;

		EmiReloadManager.profileStep("build_arrays", () -> {
			CompletableFuture.allOf(
				CompletableFuture.runAsync(() -> {
					EmiReloadManager.profileStep("build_name_array", names::build);
				}, executor),
				CompletableFuture.runAsync(() -> {
					EmiReloadManager.profileStep("build_tooltip_array", tooltips::build);
				}, executor),
				CompletableFuture.runAsync(() -> {
					EmiReloadManager.profileStep("build_mods_array", mods::build);
				}, executor),
				CompletableFuture.runAsync(() -> {
					EmiReloadManager.profileStep("build_alias_array", aliases::build);
				}, executor)
			).join();
		});
		EmiSearch.names = names;
		EmiSearch.tooltips = tooltips;
		EmiSearch.mods = mods;
		EmiSearch.aliases = aliases;
		EmiSearch.bakedStacks = bakedStacks;
	}

	public static void update() {
		search(EmiScreenManager.search.getText());
	}

	public static void search(String query) {
		synchronized (EmiSearch.class) {
			SearchWorker worker = new SearchWorker(query, EmiScreenManager.getSearchSource());
			currentWorker = worker;

			searchThread = new Thread(worker);
			searchThread.setName("EMI Search Worker");
			searchThread.setDaemon(true);
			searchThread.start();
		}
	}

	public static void apply(SearchWorker worker, List<? extends EmiIngredient> stacks) {
		synchronized (EmiSearch.class) {
			if (worker == currentWorker) {
				EmiSearch.stacks = stacks;
				currentWorker = null;
				searchThread = null;
			}
		}
	}

	public static class CompiledQuery {
		public final Query fullQuery;

		public CompiledQuery(String query) {
			List<Query> full = Lists.newArrayList();
			List<Query> queries = Lists.newArrayList();
			Matcher matcher = TOKENS.matcher(query);
			while (matcher.find()) {
				String q = matcher.group();
				boolean negated = q.startsWith("-");
				if (negated) {
					q = q.substring(1);
				}
				if (q.isEmpty()) {
					continue;
				}
				if (q.equals("&")) {
					// Default behavior
					continue;
				} else if (q.equals("|")) {
					if (!queries.isEmpty()) {
						full.add(new LogicalAndQuery(queries));
						queries = Lists.newArrayList();
					}
					continue;
				}
				QueryType type = QueryType.fromString(q);
				Function<String, Query> constructor = type.queryConstructor;
				Function<String, Query> regexConstructor = type.regexQueryConstructor;
				if (type == QueryType.DEFAULT) {
					List<Function<String, Query>> constructors = Lists.newArrayList();
					List<Function<String, Query>> regexConstructors = Lists.newArrayList();
					constructors.add(constructor);
					regexConstructors.add(regexConstructor);

					if (EmiConfig.searchTooltipByDefault) {
						constructors.add(QueryType.TOOLTIP.queryConstructor);
						regexConstructors.add(QueryType.TOOLTIP.regexQueryConstructor);
					}
					if (EmiConfig.searchModNameByDefault) {
						constructors.add(QueryType.MOD.queryConstructor);
						regexConstructors.add(QueryType.MOD.regexQueryConstructor);
					}
					if (EmiConfig.searchTagsByDefault) {
						constructors.add(QueryType.TAG.queryConstructor);
						regexConstructors.add(QueryType.TAG.regexQueryConstructor);
					}
					// TODO add config
					constructors.add(AliasQuery::new);
					if (constructors.size() > 1) {
						constructor = name -> new LogicalOrQuery(constructors.stream().map(c -> c.apply(name)).toList());
						regexConstructor = name -> new LogicalOrQuery(regexConstructors.stream().map(c -> c.apply(name)).toList());
					}
				}
				addQuery(q.substring(type.prefix.length()), negated, queries, constructor, regexConstructor);
			}
			if (!queries.isEmpty()) {
				full.add(new LogicalAndQuery(queries));
			}
			if (!full.isEmpty()) {
				fullQuery = new LogicalOrQuery(full);
			} else {
				fullQuery = null;
			}
		}

		public boolean isEmpty() {
			return fullQuery == null;
		}

		public boolean test(EmiStack stack) {
			if (fullQuery == null) {
				return true;
			} else if (EmiSearch.bakedStacks.contains(stack)) {
				return fullQuery.matches(stack);
			} else {
				return fullQuery.matchesUnbaked(stack);
			}
		}

		private static void addQuery(String s, boolean negated, List<Query> queries, Function<String, Query> normal, Function<String, Query> regex) {
			Query q;
			if (s.length() > 1 && s.startsWith("/") && s.endsWith("/")) {
				q = regex.apply(s.substring(1, s.length() - 1));
			} else if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
				q = normal.apply(s.substring(1, s.length() - 1));
			} else {
				q = normal.apply(s);
			}
			q.negated = negated;
			queries.add(q);
		}
	}

	private static class SearchWorker implements Runnable {
		private final String query;
		private final List<? extends EmiIngredient> source;

		public SearchWorker(String query, List<? extends EmiIngredient> source) {
			this.query = query;
			this.source = source;
		}

		@Override
		public void run() {
			try {
				CompiledQuery compiled = new CompiledQuery(query);
				compiledQuery = compiled;
				if (compiled.isEmpty()) {
					apply(this, source);
					return;
				}
				List<EmiIngredient> stacks = Lists.newArrayList();
				int processed = 0;
				for (EmiIngredient stack : source) {
					if (processed++ >= 1024) {
						processed = 0;
						if (this != currentWorker) {
							return;
						}
					}
					List<EmiStack> ess = stack.getEmiStacks();
					// TODO properly support ingredients?
					if (ess.size() == 1) {
						EmiStack es = ess.get(0);
						if (compiled.test(es)) {
							stacks.add(stack);
						}
					}
				}
				apply(this, List.copyOf(stacks));
			} catch (Exception e) {
				EmiLog.error("Error when attempting to search:", e);
			}
		}
	}

	private record SearchBakeResult(
			SearchStack stack,
			String nameString,
			List<String> tooltip,
			Identifier id) {}
}