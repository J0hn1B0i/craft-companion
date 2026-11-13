package dev.craftcompanion;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** The little grass-block buddy on the screen. Bottom-right by default; drag it to move it. */
final class BuddyHud {
    private BuddyHud() {}

    static final Identifier ICON = new Identifier("craftcompanion", "icon.png");
    static final int SIZE = 32;
    private static final int MARGIN = 6;
    private static final int TAN = 0xFFE8B27A;

    /** Top-left corner of the buddy for a window of this size, from the saved position. */
    static int[] position(int width, int height) {
        ClaudeRunner.Config c = CraftCompanionClient.config();
        int maxX = Math.max(0, width - SIZE), maxY = Math.max(0, height - SIZE);
        int x = c.buddyX < 0 ? maxX - MARGIN : (int) Math.round(c.buddyX * maxX);
        int y = c.buddyY < 0 ? maxY - MARGIN : (int) Math.round(c.buddyY * maxY);
        return new int[]{clamp(x, 0, maxX), clamp(y, 0, maxY)};
    }

    /** Remembers a new position as a fraction of the screen so it survives window resizes. */
    static void savePosition(int x, int y, int width, int height) {
        ClaudeRunner.Config c = CraftCompanionClient.config();
        c.buddyX = clamp(x / (double) Math.max(1, width - SIZE), 0, 1);
        c.buddyY = clamp(y / (double) Math.max(1, height - SIZE), 0, 1);
        CraftCompanionClient.saveConfig();
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Draws the buddy at (x, y) with size SIZE. */
    static void drawBuddy(DrawContext ctx, int x, int y) {
        long t = System.currentTimeMillis();
        int bob = (int) Math.round(Math.sin(t / 450.0) * 1.5);
        y += bob;
        ctx.drawTexture(ICON, x, y, SIZE, SIZE, 0, 0, 128, 128, 128, 128);
        // Blink for 150 ms every 4 seconds: cover the eyes with skin colour.
        if (t % 4000 < 150) {
            ctx.fill(x + 9, y + 18, x + 11, y + 21, TAN);
            ctx.fill(x + 19, y + 18, x + 21, y + 21, TAN);
            ctx.fill(x + 9, y + 20, x + 11, y + 21, 0xFF222034);
            ctx.fill(x + 19, y + 20, x + 21, y + 21, 0xFF222034);
        }
        // Speech bubble: "..." while thinking, "!" when waiting for a y/n answer.
        String bubble = CraftCompanionClient.isBusy()
                ? ".".repeat(1 + (int) (t / 400 % 3))
                : CraftCompanionClient.hasQuestion() ? "!" : null;
        if (bubble != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int w = Math.max(14, mc.textRenderer.getWidth(bubble) + 8);
            int bx = Math.max(2, x - w + 6), by = Math.max(0, y - 10);
            ctx.fill(bx, by, bx + w, by + 11, 0xF0FFFFFF);
            ctx.fill(bx + w - 6, by + 11, bx + w - 3, by + 13, 0xF0FFFFFF);
            int color = bubble.equals("!") ? 0xFFD97757 : 0xFF4A4A5A;
            ctx.drawText(mc.textRenderer, bubble, bx + (w - mc.textRenderer.getWidth(bubble)) / 2, by + 2, color, false);
        }
    }

    /** HUD callback: shows the buddy when summoned, thinking or waiting for an answer. */
    static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof BuddyScreen) return; // the panel draws its own buddy
        if (!CraftCompanionClient.buddyShown()) return;
        int[] p = position(ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        drawBuddy(ctx, p[0], p[1]);
    }
}
