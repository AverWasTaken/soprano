package adris.altoclef.util;

import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * For crafting table/inventory recipe book crafting, we need to figure out identifiers given a recipe.
 */
public class JankCraftingRecipeMapping {
    private static final HashMap<Item, List<RecipeHolder<?>>> _recipeMapping = new HashMap<>();

    /**
     * Reloads the recipe mapping.
     */
    private static void reloadRecipeMapping() {
        Minecraft client = Minecraft.getInstance();

        // Check if the network handler is available
        if (client.getConnection() != null) {
            RecipeManager recipes = client.getConnection().getRecipeManager();
            ClientLevel world = client.level;

            // Check if the recipe manager is available
            if (recipes != null) {
                for (RecipeHolder<?> recipe : recipes.getRecipes()) {
                    assert world != null;
                    Item output = recipe.value().getResultItem(world.registryAccess()).getItem();
                    _recipeMapping.computeIfAbsent(output, k -> new ArrayList<>()).add(recipe);
                }
            }
        }
    }

    /**
     * Retrieves the mapped recipe for a given output item from the Minecraft crafting recipe.
     *
     * @param recipe The crafting recipe to check against.
     * @param output The output item of the recipe.
     * @return An Optional containing the mapped recipe entry if found, or an empty Optional if not found.
     */
    public static Optional<RecipeHolder<?>> getMinecraftMappedRecipe(CraftingRecipe recipe, Item output) {
        reloadRecipeMapping();
        // Check if the output item is present in the recipe mapping
        if (_recipeMapping.containsKey(output)) {
            // Iterate through all the recipes mapped to the output item
            for (RecipeHolder<?> checkRecipe : _recipeMapping.get(output)) {
                // Create a list of item targets to satisfy
                List<ItemTarget> toSatisfy = Arrays.stream(recipe.getSlots())
                        .filter(itemTarget -> itemTarget != null && !itemTarget.isEmpty())
                        .collect(Collectors.toList());
                // Check if the recipe has ingredients
                if (!checkRecipe.value().getIngredients().isEmpty()) {
                    // Iterate through the ingredients of the recipe
                    for (Ingredient ingredient : checkRecipe.value().getIngredients()) {
                        // Skip empty ingredients
                        if (ingredient.isEmpty()) {
                            continue;
                        }
                        // Iterate through the items to satisfy
                        outer:
                        for (int i = 0; i < toSatisfy.size(); ++i) {
                            ItemTarget target = toSatisfy.get(i);
                            // Check if any of the ingredient's matching stacks matches the item target
                            for (ItemStack stack : ingredient.getItems()) {
                                if (target.matches(stack.getItem())) {
                                    toSatisfy.remove(i);
                                    break outer;
                                }
                            }
                        }
                    }
                }
                // Check if all the item targets have been satisfied
                if (toSatisfy.isEmpty()) {
                    return Optional.of(checkRecipe);
                }
            }
        }
        return Optional.empty();
    }
}
