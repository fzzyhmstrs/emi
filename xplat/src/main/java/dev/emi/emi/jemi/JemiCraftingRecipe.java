package dev.emi.emi.jemi;

import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.util.Identifier;

import java.util.List;

public class JemiCraftingRecipe extends EmiCraftingRecipe implements JemiReplacementRecipe {

	public JemiCraftingRecipe(List<EmiIngredient> input, EmiStack output, Identifier id) {
		super(input, output, id);
	}

	public JemiCraftingRecipe(List<EmiIngredient> input, EmiStack output, Identifier id, boolean shapeless) {
		super(input, output, id, shapeless);
	}
}