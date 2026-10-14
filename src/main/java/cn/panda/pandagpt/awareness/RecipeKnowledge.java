package cn.panda.pandagpt.awareness;

import org.bukkit.Server;
import org.bukkit.inventory.*;
import java.util.*;

/** Main-thread recipe index. Reports ingredient matches, not guaranteed craft execution. */
public final class RecipeKnowledge {
    public record Entry(ItemStack result, List<RecipeChoice> ingredients, String id, boolean needsTable) {}
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<Entry>> targets = new HashMap<>();

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
        for (Entry entry : entries) targets.computeIfAbsent(entry.result().getType().name(), key -> new ArrayList<>()).add(entry);
        targets.replaceAll((key, values) -> List.copyOf(values));
    }
    public List<String> matching(ItemStack[] storage, int limit) {
        List<String> results = new ArrayList<>();
        if (limit == 0) return results;
        long deadline = System.nanoTime() + 1000000; int scanned = 0;
        for (Entry entry : entries) {
            if (++scanned > 128 || System.nanoTime() >= deadline) break;
            if (matches(entry.ingredients, storage, () -> System.nanoTime() < deadline)) {
                results.add(entry.id + " -> " + entry.result.getType().name() + " x" + entry.result.getAmount()
                        + (entry.needsTable ? " (requires crafting table)" : " (2x2 grid fits)")
                        + "; ingredients=" + entry.ingredients);
                if (results.size() >= limit) break;
            }
        }
        return List.copyOf(results);
    }
    public List<Entry> forTarget(String target) { return targets.getOrDefault(target, List.of()); }
    public List<Entry> entries() { return List.copyOf(entries); }
    public static boolean matches(List<RecipeChoice> choices, ItemStack[] stock) { return matches(choices, stock, () -> true); }
    public static boolean matches(List<RecipeChoice> choices, ItemStack[] stock, java.util.function.BooleanSupplier permit) {
        int[] counts = new int[stock.length];
        for (int i = 0; i < stock.length; i++) if (stock[i] != null) counts[i] = stock[i].getAmount();
        // Precompute choice predicates once per distinct choice; recursion uses primitive arrays only.
        Map<RecipeChoice, boolean[]> tested = new IdentityHashMap<>(); List<boolean[]> rows = new ArrayList<>();
        for (RecipeChoice choice : choices) {
            boolean[] row = tested.get(choice);
            if (row == null) {
                row = new boolean[stock.length];
                for (int i = 0; i < stock.length; i++) {
                    if (!permit.getAsBoolean()) return false;
                    if (counts[i] > 0) row[i] = choice.test(stock[i]);
                }
                tested.put(choice, row);
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt(row -> { int n = 0; for (boolean b : row) if (b) n++; return n; }));
        return allocate(rows, 0, counts, new int[]{1024}, permit);
    }
    private static boolean allocate(List<boolean[]> rows, int index, int[] counts, int[] budget, java.util.function.BooleanSupplier permit) {
        if (index == rows.size()) return true;
        if (--budget[0] < 0 || !permit.getAsBoolean()) return false;
        boolean[] row = rows.get(index);
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0 && row[i]) {
            counts[i]--;
            if (allocate(rows, index + 1, counts, budget, permit)) return true;
            counts[i]++;
        }
        return false;
    }
}
