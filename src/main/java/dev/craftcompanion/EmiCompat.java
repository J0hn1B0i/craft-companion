package dev.craftcompanion;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.item.ItemStack;

/** Only loaded when EMI is installed. */
final class EmiCompat {
    private EmiCompat() {}

    static void show(ItemStack stack, boolean uses) {
        EmiStack s = EmiStack.of(stack);
        if (uses) EmiApi.displayUses(s);
        else EmiApi.displayRecipes(s);
    }
}
