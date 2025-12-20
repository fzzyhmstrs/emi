package dev.emi.emi.jemi;

import dev.emi.emi.api.recipe.EmiPatternCraftingRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.util.Identifier;

import java.util.List;

public abstract class JemiPatternCraftingRecipe extends EmiPatternCraftingRecipe implements JemiReplacementRecipe {
	public JemiPatternCraftingRecipe(List<EmiIngredient> input, EmiStack output, Identifier id) {
		super(input, output, id);
	}

	public JemiPatternCraftingRecipe(List<EmiIngredient> input, EmiStack output, Identifier id, boolean shapeless) {
		super(input, output, id, shapeless);
	}
}