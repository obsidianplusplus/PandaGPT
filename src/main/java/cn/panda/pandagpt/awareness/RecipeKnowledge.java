package cn.panda.pandagpt.awareness;

import org.bukkit.Server;
import org.bukkit.inventory.*;
import java.util.*;

/** Main-thread recipe index. Reports ingredient matches, not guaranteed craft execution. */
public final class RecipeKnowledge {
    public record Entry(ItemStack result, List<RecipeChoice> ingredients, String id, boolean needsTable) {}
    private final List<Entry> entries = new ArrayList<>();

    public RecipeKnowledge(Server server) {
        var iterator = server.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            List<RecipeChoice> choices = new ArrayList<>();
            boolean needsTable = false;
            if (recipe instanceof ShapedRecipe shaped) {
                String[] shape = shaped.getShape();
                needsTable = shape.length > 2 || Arrays.stream(shape).anyMatch(row -> row.length() > 2);
                Map<Character, RecipeChoice> map = shaped.getChoiceMap();
                for (String row : shape) for (char c : row.toCharArray()) if (c != ' ') {
                    RecipeChoice choice = map.get(c);
                    if (choice != null) choices.add(choice);
                }
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                choices.addAll(shapeless.getChoiceList()); needsTable = choices.size() > 4;
            } else continue;
            if (choices.isEmpty() || choices.size() > 9 || choices.stream().anyMatch(choice ->
                    !(choice instanceof RecipeChoice.MaterialChoice) && !(choice instanceof RecipeChoice.ExactChoice))) continue;
            String id = recipe instanceof org.bukkit.Keyed keyed ? keyed.getKey().toString() : recipe.getResult().getType().name();
            entries.add(new Entry(recipe.getResult().clone(), List.copyOf(choices), id, needsTable));
        }
        entries.sort(Comparator.comparing(Entry::id));
    }
    public List<String> matching(ItemStack[] storage, int limit) {
        List<String> results = new ArrayList<>();
        if (limit == 0) return results;
        for (Entry entry : entries) {
            if (matches(entry.ingredients, storage)) {
                results.add(entry.id + " -> " + entry.result.getType().name() + " x" + entry.result.getAmount()
                        + (entry.needsTable ? " (requires crafting table)" : " (2x2 grid fits)")
                        + "; ingredients=" + entry.ingredients);
                if (results.size() >= limit) break;
            }
        }
        return List.copyOf(results);
    }
    public static boolean matches(List<RecipeChoice> choices, ItemStack[] stock) {
        int[] counts = new int[stock.length];
        for (int i = 0; i < stock.length; i++) if (stock[i] != null) counts[i] = stock[i].getAmount();
        List<RecipeChoice> ordered = new ArrayList<>(choices);
        // Restrictive choices first; bounded backtracking avoids greedy allocation errors.
        ordered.sort(Comparator.comparingInt(choice -> {
            int matches = 0;
            for (ItemStack item : stock) if (item != null && item.getAmount() > 0 && choice.test(item)) matches++;
            return matches;
        }));
        return allocate(ordered, 0, stock, counts, new int[]{1024});
    }
    private static boolean allocate(List<RecipeChoice> choices, int index, ItemStack[] stock, int[] counts, int[] budget) {
        if (index == choices.size()) return true;
        if (--budget[0] < 0) return false; // Conservative: never claim a match we could not verify.
        for (int i = 0; i < stock.length; i++) {
            if (counts[i] > 0 && choices.get(index).test(stock[i])) {
                counts[i]--;
                if (allocate(choices, index + 1, stock, counts, budget)) return true;
                counts[i]++;
            }
        }
        return false;
    }
}
