package dev.craftcompanion;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Small chat panel above the corner buddy. Doesn't pause the game or darken the world. */
final class BuddyScreen extends Screen {

    private static final int PAD = 6;
    private TextFieldWidget input;
    private ButtonWidget yes, no;
    private int scroll; // lines scrolled up from the bottom
    private int px, py, pw, ph;

    BuddyScreen() {
        super(Text.literal(CraftCompanionClient.buddyName()));
    }

    @Override
    protected void init() {
        pw = Math.min(300, width - 16);
        ph = Math.min(230, height - BuddyHud.SIZE - 30);
        px = width - pw - 6;
        py = height - BuddyHud.SIZE - 14 - ph;

        String typed = input == null ? "" : input.getText();
        input = new TextFieldWidget(textRenderer, px + PAD, py + ph - 20, pw - PAD * 2, 14,
                Text.literal("Message"));
        input.setMaxLength(400);
        input.setText(typed);
        input.setPlaceholder(Text.literal("Ask " + CraftCompanionClient.buddyName() + " anything...").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        addDrawableChild(input);
        setInitialFocus(input);

        yes = ButtonWidget.builder(Text.literal("Yes"), b -> CraftCompanionClient.answer(true))
                .dimensions(px + PAD, py + ph - 42, (pw - PAD * 3) / 2, 18).build();
        no = ButtonWidget.builder(Text.literal("No"), b -> CraftCompanionClient.answer(false))
                .dimensions(px + PAD * 2 + (pw - PAD * 3) / 2, py + ph - 42, (pw - PAD * 3) / 2, 18).build();
        addDrawableChild(yes);
        addDrawableChild(no);

        addDrawableChild(ButtonWidget.builder(Text.literal("Hide"), b -> {
            CraftCompanionClient.setSummoned(false);
            close();
        }).dimensions(px + pw - 36, py + 3, 32, 12).build());
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll = Math.max(0, scroll + (int) Math.signum(amount) * 2);
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Panel
        ctx.fill(px, py, px + pw, py + ph, 0xD0141420);
        ctx.fill(px, py, px + pw, py + 1, 0xFF6ABE30);
        ctx.drawText(textRenderer, CraftCompanionClient.buddyName(), px + PAD, py + 5, 0xFF99E550, false);

        // History, newest at the bottom
        int top = py + 18;
        int bottom = py + ph - 24 - (CraftCompanionClient.hasQuestion() ? 34 : 0);
        List<OrderedText> lines = new ArrayList<>();
        for (Text t : CraftCompanionClient.log()) {
            lines.addAll(textRenderer.wrapLines(t, pw - PAD * 2));
            lines.add(OrderedText.EMPTY);
        }
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
            ctx.drawText(textRenderer, Text.literal("Hi! Ask me about recipes, mods, bosses,"), px + PAD, top + 4, 0xFFAAAAAA, false);
            ctx.drawText(textRenderer, Text.literal("what you're looking at, or to change a setting."), px + PAD, top + 14, 0xFFAAAAAA, false);
        }
        if (CraftCompanionClient.hasQuestion()) {
            ctx.drawText(textRenderer, Text.literal("Type y or n, or click:"), px + PAD, py + ph - 54, 0xFFFFD27F, false);
        }

        super.render(ctx, mouseX, mouseY, delta);

        // The buddy itself, in its usual corner
        BuddyHud.drawBuddy(ctx, width - BuddyHud.SIZE - 6, height - BuddyHud.SIZE - 6);
    }
}
