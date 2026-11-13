package dev.craftcompanion;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Small chat panel next to the buddy. Doesn't pause the game or darken the world.
 * Drag the buddy to move it; the panel follows.
 */
final class BuddyScreen extends Screen {

    private static final int PANEL_W = 210;
    private static final int PANEL_H = 140;
    private static final int PAD = 6;
    private static final int GAP = 4;

    private TextFieldWidget input;
    private ButtonWidget yes, no, hide;
    private int scroll; // lines scrolled up from the bottom
    private int px, py, pw, ph; // panel
    private int bx, by;          // buddy
    private boolean dragging;
    private int dragDX, dragDY;

    // Wrapped history, rebuilt only when the log or the panel width changes.
    private List<OrderedText> wrapped = List.of();
    private int wrappedVersion = -1, wrappedWidth = -1;

    BuddyScreen() {
        super(Text.literal(CraftCompanionClient.buddyName()));
    }

    @Override
    protected void init() {
        int[] p = BuddyHud.position(width, height);
        bx = p[0];
        by = p[1];

        String typed = input == null ? "" : input.getText();
        input = new TextFieldWidget(textRenderer, 0, 0, 100, 14, Text.literal("Message"));
        input.setMaxLength(400);
        input.setText(typed);
        input.setPlaceholder(Text.literal("Ask " + CraftCompanionClient.buddyName() + "...").formatted(Formatting.DARK_GRAY));
        addDrawableChild(input);
        setInitialFocus(input);

        yes = addDrawableChild(ButtonWidget.builder(Text.literal("Yes"), b -> CraftCompanionClient.answer(true))
                .dimensions(0, 0, 50, 16).build());
        no = addDrawableChild(ButtonWidget.builder(Text.literal("No"), b -> CraftCompanionClient.answer(false))
                .dimensions(0, 0, 50, 16).build());
        hide = addDrawableChild(ButtonWidget.builder(Text.literal("Hide"), b -> {
            CraftCompanionClient.setSummoned(false);
            close();
        }).dimensions(0, 0, 30, 12).build());
        place();
    }

    /** Puts the panel next to the buddy (above it in the lower half of the screen, below it otherwise). */
    private void place() {
        pw = Math.min(PANEL_W, width - 8);
        ph = Math.min(PANEL_H, height - 8);
        boolean above = by + BuddyHud.SIZE / 2 > height / 2;
        py = BuddyHud.clamp(above ? by - ph - GAP : by + BuddyHud.SIZE + GAP, 4, height - ph - 4);
        px = BuddyHud.clamp(bx + BuddyHud.SIZE - pw, 4, width - pw - 4);

        input.setX(px + PAD);
        input.setY(py + ph - 20);
        input.setWidth(pw - PAD * 2);
        int half = (pw - PAD * 3) / 2;
        yes.setX(px + PAD);
        yes.setY(py + ph - 40);
        yes.setWidth(half);
        no.setX(px + PAD * 2 + half);
        no.setY(py + ph - 40);
        no.setWidth(half);
        hide.setX(px + pw - 34);
        hide.setY(py + 3);
    }

    @Override
    public void tick() {
        input.tick();
        boolean q = CraftCompanionClient.hasQuestion();
        yes.visible = q;
        no.visible = q;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            String text = input.getText().trim();
            if (!text.isEmpty()) {
                input.setText("");
                scroll = 0;
                CraftCompanionClient.fromPanel(text);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------- dragging the buddy

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && mx >= bx && mx < bx + BuddyHud.SIZE && my >= by && my < by + BuddyHud.SIZE) {
            dragging = true;
            dragDX = (int) mx - bx;
            dragDY = (int) my - by;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            bx = BuddyHud.clamp((int) mx - dragDX, 0, width - BuddyHud.SIZE);
            by = BuddyHud.clamp((int) my - dragDY, 0, height - BuddyHud.SIZE);
            place();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            dragging = false;
            BuddyHud.savePosition(bx, by, width, height);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll = Math.max(0, scroll + (int) Math.signum(amount) * 2);
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------------------------------------------------------------- drawing

    private List<OrderedText> wrappedLog(int textWidth) {
        int v = CraftCompanionClient.logVersion();
        if (v != wrappedVersion || textWidth != wrappedWidth) {
            List<OrderedText> lines = new ArrayList<>();
            for (Text t : CraftCompanionClient.log()) {
                lines.addAll(textRenderer.wrapLines(t, textWidth));
                lines.add(OrderedText.EMPTY);
            }
            wrapped = lines;
            wrappedVersion = v;
            wrappedWidth = textWidth;
        }
        return wrapped;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(px, py, px + pw, py + ph, 0xD0141420);
        ctx.fill(px, py, px + pw, py + 1, 0xFF6ABE30);
        ctx.drawText(textRenderer, CraftCompanionClient.buddyName(), px + PAD, py + 5, 0xFF99E550, false);

        // History, newest at the bottom
        boolean question = CraftCompanionClient.hasQuestion();
        int top = py + 18;
        int bottom = py + ph - 24 - (question ? 20 : 0);
        List<OrderedText> lines = wrappedLog(pw - PAD * 2);
        int lineH = 10;
        int visible = Math.max(1, (bottom - top) / lineH);
        int maxScroll = Math.max(0, lines.size() - visible);
        scroll = Math.min(scroll, maxScroll);
        int end = lines.size() - scroll;
        int start = Math.max(0, end - visible);
        int y = bottom - (end - start) * lineH;
        ctx.enableScissor(px, top, px + pw, bottom);
        for (int i = start; i < end; i++) {
            ctx.drawText(textRenderer, lines.get(i), px + PAD, y, 0xFFFFFFFF, false);
            y += lineH;
        }
        ctx.disableScissor();
        if (lines.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("Hi! Ask me about recipes,"), px + PAD, top + 2, 0xFFAAAAAA, false);
            ctx.drawText(textRenderer, Text.literal("mods, or what you're looking at."), px + PAD, top + 12, 0xFFAAAAAA, false);
            ctx.drawText(textRenderer, Text.literal("Drag me to move me!"), px + PAD, top + 28, 0xFF99E550, false);
        }

        super.render(ctx, mouseX, mouseY, delta);

        // The buddy itself, wherever it has been dragged to
        BuddyHud.drawBuddy(ctx, bx, by);
    }
}
