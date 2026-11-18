package patternrouter.client;

import static patternrouter.client.AeStyle.GUI_FILL;
import static patternrouter.client.AeStyle.GUI_TEXT;
import static patternrouter.client.AeStyle.GUI_TEXT_BAD;
import static patternrouter.client.AeStyle.GUI_TEXT_DIM;
import static patternrouter.client.AeStyle.drawInset;
import static patternrouter.client.AeStyle.drawItem;
import static patternrouter.client.AeStyle.drawRaised;
import static patternrouter.client.AeStyle.drawShadow;
import static patternrouter.client.AeStyle.drawSlot;
import static patternrouter.client.AeStyle.drawWindow;
import static patternrouter.client.EntryText.displayName;
import static patternrouter.client.EntryText.flagsLine;
import static patternrouter.client.EntryText.resolveName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiScreenEvent;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import appeng.container.implementations.ContainerPatternTerm;
import appeng.container.slot.SlotFake;
import appeng.container.slot.SlotRestrictedInput;
import appeng.items.misc.ItemEncodedPattern;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import patternrouter.Config;
import patternrouter.common.ListNote;
import patternrouter.net.ActionResultMessage;
import patternrouter.net.CandidateListMessage;
import patternrouter.net.ExtractConflictMessage;
import patternrouter.net.PatternRouterNetwork;
import patternrouter.net.ProviderEntry;
import patternrouter.net.RequestCandidatesMessage;
import patternrouter.net.UploadPatternMessage;

/**
 * Pattern terminal side panel in AE2 window style. Built from Forge GUI events and plain buttons; AE2 GUIs are not
 * patched. NEI makes room for the panel via INEIGuiAdapter (see NeiCompat).
 */
public class ClientPanel {

    private static final int MAX_W = 204;
    private static final int MIN_W = 150;
    /** Narrowest the panel gets to stay off the terminal on a small screen. */
    private static final int FIT_W = 110;
    /** Gap right of the terminal: AE2 view cell / upgrade column. */
    private static final int RIGHT_GAP = 28;
    /** Gap left of the terminal: AE2 sort / view button column. */
    private static final int LEFT_GAP = 54;
    /** Candidate row: machine name, CRIB caption (mold/circuit), flags. */
    private static final int ROW_H = 37;
    /** Most rows the player can stretch the list to (Config.panelRows); one more button for a half-scrolled row. */
    public static final int MAX_ROWS = 20;
    public static final int MIN_ROWS = 2;
    /** Scroll bar right of the list. */
    private static final int SB_W = 7;
    /** Resize grip in the bottom-right corner. */
    private static final int GRIP = 10;
    /** How much of the way to the target the list moves per frame. */
    private static final float EASE = 0.35f;
    /** Conflict row: machine with the old pattern and an Extract button. */
    private static final int CONF_H = 26;
    private static final int CONF_ROWS = 3;
    private static final int HEADER = 27;
    private static final int FOOTER = 22;
    /** Title-bar room on the right: the "hide full" toggle and the side arrow. */
    private static final int TITLE_BUTTONS = 40;
    /** Current footer height (the status line may wrap). */
    private int footer = FOOTER;

    private int statusWidth() {
        return pw - 16 - GRIP;
    }

    /** The panel of this client (the AE2 wheel mixin reaches it here). */
    public static ClientPanel INSTANCE;

    public ClientPanel() {
        INSTANCE = this;
    }

    private static final int BASE_ID = 0x6A5000;

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    // Terminal and layout
    private GuiContainer gui;
    private int px, py, pw, ph;
    private boolean visible;
    private Background bg;
    private final List<RowButton> rows = new ArrayList<>();
    private final List<ConflictRow> confRows = new ArrayList<>();
    private final List<GuiButton> extractButtons = new ArrayList<>();
    private ScrollBar bar;
    /** "Remove the duplicates?" over the list: the row clicked, null when closed. */
    private ProviderEntry dialogFor;
    private DialogBox dialog;
    private GuiButton dialogYes, dialogNo;
    /** After "yes": duplicates asked to be taken out, and taken out so far. */
    private int dupesExpected, dupesDone;
    private String uploadedTo = "";
    /** The current click closed the question; cleared next frame. */
    private boolean clickClosedDialog;
    /** Search field under the title: filters the list by name. */
    private GuiTextField search;
    private String query = "";
    private int searchH;
    private static final int SEARCH_H = 15;
    /** Fold/unfold button placed in AE2's button column left of the terminal. */
    private ToggleButton toggle;
    /** The terminal's button list (to find AE2's button column). */
    private List<GuiButton> guiButtons = Collections.emptyList();
    /** Dragging the scroll bar thumb: mouse offset from the thumb top. */
    private boolean barDragging;
    private int barGrab;
    /** Dragging the resize grip: mouse y and row count when it started. */
    private boolean resizing;
    private int resizeStartY, resizeStartRows;
    /** Title-bar arrow that moves the panel to the other side of the terminal. */
    private GuiButton side;
    /** Title-bar toggle: hide providers without a free slot. */
    private GuiButton hideFull;
    /** Where layoutHorizontal() actually put the panel. */
    private boolean onRight = true;
    /** Dragging the panel by its title: mouse offset from the panel corner. */
    private boolean dragging;
    private int dragDX, dragDY;
    private static final boolean NEI = Loader.isModLoaded("NotEnoughItems");
    // Block positions computed in relayout()
    private int listY, noteY, confY;

    // State
    private String lastKey;
    /** When to send the request for lastKey (ms), -1 = already sent. */
    private long sendAt = -1;
    private static final long DEBOUNCE_MS = 150;
    /** No answer this long after a request: the server most likely lacks the mod (or another channel version). */
    private static final long REPLY_TIMEOUT_MS = 10_000;
    /** When the last list request went out (ms), 0 = none outstanding. */
    private long requestSentAt;
    private int token;
    /** The list as the server sent it; uploads refer to its indexes. */
    private List<ProviderEntry> received = Collections.emptyList();
    /** What the panel shows: {@link #received}, without full providers when they are hidden. */
    private List<ProviderEntry> entries = Collections.emptyList();
    private List<ProviderEntry> conflicts = Collections.emptyList();
    private int note = -1;
    /** List scroll in pixels: shown, and where the wheel/thumb wants it (the list eases toward it). */
    private float scrollPx, scrollTarget;
    private int maxScrollPx;
    /** Height of the list viewport this frame. */
    private int viewH;
    private boolean waiting;
    private String status = "";
    private int statusColor;
    private long statusUntil;
    private String lastActionName;
    /** Pattern the list is shown for (title icon). */
    private ItemStack currentPattern;

    public void post(Runnable r) {
        tasks.add(r);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Runnable r;
        while ((r = tasks.poll()) != null) r.run();
        if (gui != null && Minecraft.getMinecraft().currentScreen != gui) {
            gui = null;
            resetList();
            query = "";
            if (search != null) search.setText("");
        }
        if (search != null) search.updateCursorCounter();
    }

    /**
     * A key in the terminal (from the AE2 GUI mixin): typed into the search field while it has focus, and eaten so AE2,
     * NEI and the inventory key don't act on it. Esc and Enter only leave the field.
     */
    public boolean onKey(Object screen, char typed, int key) {
        if (screen == gui && gui != null && dialogFor != null) {
            // The duplicates question: Enter = yes, Esc = cancel (nothing uploaded), other keys do nothing.
            if (key == org.lwjgl.input.Keyboard.KEY_RETURN) answerDialog(true);
            else if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) dialogFor = null;
            return true;
        }
        if (screen != gui || gui == null || search == null || !search.isFocused()) return false;
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE || key == org.lwjgl.input.Keyboard.KEY_RETURN) {
            search.setFocused(false);
            return true;
        }
        search.textboxKeyTyped(typed, key);
        if (!search.getText()
            .equals(query)) {
            query = search.getText();
            applyFilter();
            scrollPx = scrollTarget = 0;
        }
        return true;
    }

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post e) {
        if (!isPatternTerm(e.gui)) return;
        if (gui != e.gui) resetList();
        gui = (GuiContainer) e.gui;
        layoutHorizontal();
        rows.clear();
        confRows.clear();
        extractButtons.clear();
        bg = new Background(BASE_ID);
        e.buttonList.add(bg);
        for (int i = 0; i <= MAX_ROWS; i++) {
            RowButton b = new RowButton(BASE_ID + 1 + i, i);
            rows.add(b);
            e.buttonList.add(b);
        }
        for (int i = 0; i < CONF_ROWS; i++) {
            ConflictRow c = new ConflictRow(BASE_ID + 30 + i, i);
            confRows.add(c);
            e.buttonList.add(c);
            GuiButton x = new PanelButton(BASE_ID + 40 + i, 52, 16, I18n.format("patternrouter.button.extract"));
            extractButtons.add(x);
            e.buttonList.add(x);
        }
        search = new GuiTextField(Minecraft.getMinecraft().fontRenderer, 0, 0, 10, 10);
        search.setEnableBackgroundDrawing(false);
        search.setMaxStringLength(48);
        search.setTextColor(0xFFFFFF);
        search.setText(query);
        bar = new ScrollBar(BASE_ID + 100);
        e.buttonList.add(bar);
        side = new PanelButton(BASE_ID + 102, 14, 14, "");
        e.buttonList.add(side);
        hideFull = new PanelButton(BASE_ID + 103, 14, 14, "");
        e.buttonList.add(hideFull);
        toggle = new ToggleButton(BASE_ID + 104);
        e.buttonList.add(toggle);
        // Last: drawn over the list.
        dialog = new DialogBox(BASE_ID + 105);
        e.buttonList.add(dialog);
        PanelButton yes = new PanelButton(BASE_ID + 106, 44, 16, I18n.format("patternrouter.dupes.yes"));
        PanelButton no = new PanelButton(BASE_ID + 107, 44, 16, I18n.format("patternrouter.dupes.no"));
        yes.z = no.z = 310;
        dialogYes = yes;
        dialogNo = no;
        e.buttonList.add(dialogYes);
        e.buttonList.add(dialogNo);
        guiButtons = e.buttonList;
        relayout();
    }

    @SubscribeEvent
    public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre e) {
        if (e.gui != gui || gui == null || bg == null) return;
        if (Config.panelHidden) {
            // Folded by the button in AE2's column: no list, no requests until it is opened again.
            if (lastKey != null) resetList();
            visible = false;
            relayout();
            return;
        }
        ItemStack pattern = findPattern();
        if (pattern != null) {
            String key = keyOf(pattern);
            long now = System.currentTimeMillis();
            if (!key.equals(lastKey)) {
                // A fresh pattern is asked for at once; while it keeps changing (x2 / /2 clicks) wait until it
                // stays the same for a moment, so the server gets one request instead of one per click.
                boolean fresh = lastKey == null;
                lastKey = key;
                sendAt = fresh ? now : now + DEBOUNCE_MS;
                entries = received = Collections.emptyList();
                conflicts = Collections.emptyList();
                note = -1;
                waiting = true;
            }
            if (sendAt >= 0 && now >= sendAt) {
                sendAt = -1;
                request(pattern);
            }
        } else if (lastKey != null) {
            lastKey = null;
            resetList();
        }
        if (pattern != null) currentPattern = pattern;
        visible = pattern != null || System.currentTimeMillis() < statusUntil;
        clickClosedDialog = false;
        if (dragging) drag(e.mouseX, e.mouseY);
        if (resizing) resize(e.mouseY);
        if (barDragging) {
            if (Mouse.isButtonDown(0)) scrollToThumb(e.mouseY - barGrab);
            else {
                barDragging = false;
                // Let go: glide to the nearest whole row, no row is left cut by the list edge.
                scrollTarget = Math.round(scrollPx / ROW_H) * ROW_H;
            }
        }
        // Ease toward the target; the thumb drag sets both, so it follows the mouse exactly.
        float d = scrollTarget - scrollPx;
        scrollPx = Math.abs(d) < 0.5f ? scrollTarget : scrollPx + d * EASE;
        relayout();
    }

    /** Grip held: one row more or less per row height the mouse moved; saved on release. */
    private void resize(int my) {
        if (Mouse.isButtonDown(0)) {
            int rows = resizeStartRows + Math.round((my - resizeStartY) / (float) ROW_H);
            Config.panelRows = Math.max(MIN_ROWS, Math.min(rows, MAX_ROWS));
            return;
        }
        resizing = false;
        // Stretched past the screen: keep what fits.
        Config.setPanelRows(Math.max(MIN_ROWS, Math.min(Config.panelRows, rowsCap)));
    }

    /** Thumb top at this y: the matching scroll position. */
    private void scrollToThumb(int thumbY) {
        int room = viewH - thumbHeight();
        float f = room <= 0 ? 0 : (thumbY - listY) / (float) room;
        scrollTarget = scrollPx = Math.max(0, Math.min(1, f)) * maxScrollPx;
    }

    private int thumbHeight() {
        int content = entries.size() * ROW_H;
        return content <= 0 ? viewH : Math.max(12, Math.min(viewH, viewH * viewH / content));
    }

    private int thumbY() {
        return listY + (maxScrollPx <= 0 ? 0 : Math.round((viewH - thumbHeight()) * scrollPx / maxScrollPx));
    }

    /**
     * Mouse wheel over the panel (from the AE2 GUI mixin): scrolls the list and is eaten, so the terminal grid and NEI
     * under it don't scroll too. Returns false when the mouse is elsewhere.
     */
    public boolean onWheel(Object screen, int dwheel) {
        if (screen != gui || gui == null || !visible || dwheel == 0) return false;
        Minecraft mc = Minecraft.getMinecraft();
        int mx = Mouse.getEventX() * gui.width / mc.displayWidth;
        int my = gui.height - Mouse.getEventY() * gui.height / mc.displayHeight - 1;
        if (mx < px || mx >= px + pw || my < py || my >= py + ph) return false;
        // A row per notch, always to a whole row: the list glides there and stops with no row cut by its edge.
        int row = Math.round(scrollTarget / ROW_H) - Integer.signum(dwheel);
        scrollTarget = Math.max(0, Math.min(maxScrollPx, row * ROW_H));
        return true;
    }

    /** List index shown in this row button this frame. */
    private int rowIndex(int slot) {
        return (int) scrollPx / ROW_H + slot;
    }

    /** Follow the mouse while the button is held; on release push off NEI's page bar and save. */
    private void drag(int mx, int my) {
        int left = field("guiLeft", "field_147003_i");
        int top = field("guiTop", "field_147009_r");
        if (Mouse.isButtonDown(0)) {
            Config.panelCustom = true;
            Config.panelX = clamp(mx - dragDX, 2, gui.width - pw - 2) - left;
            Config.panelY = clamp(my - dragDY, 2, gui.height - ph - 2) - top;
            layoutHorizontal();
            return;
        }
        dragging = false;
        int y = top + Config.panelY;
        int bar = neiBarBottom(px, pw, y);
        // Dropped onto NEI's page bar: move just below it.
        if (bar > y) Config.panelY = Math.min(bar, gui.height - ph - 2) - top;
        Config.savePanelPos();
    }

    @SubscribeEvent
    public void onDrawPost(GuiScreenEvent.DrawScreenEvent.Post e) {
        if (e.gui != gui || gui == null) return;
        if (toggle != null && toggle.visible && AeStyle.isOver(toggle, e.mouseX, e.mouseY)) {
            drawTooltip(
                Arrays.asList(
                    I18n.format("patternrouter.tip.panel.title"),
                    "§7" + I18n
                        .format(Config.panelHidden ? "patternrouter.tip.panel.show" : "patternrouter.tip.panel.hide")),
                e.mouseX,
                e.mouseY);
        }
        if (!visible) return;
        for (RowButton b : rows) {
            if (b.visible && dialogFor == null && !barDragging && !resizing && b.hovered(e.mouseX, e.mouseY)) {
                ProviderEntry en = entries.get(rowIndex(b.slot));
                List<String> tip = new ArrayList<>();
                tip.add(displayName(en));
                tip.add(flagsLine(en, true));
                if (en.reasonText != null && !en.reasonText.isEmpty()) {
                    // Why it doesn't fit, wrapped so a long GT message stays readable.
                    for (String line : Minecraft.getMinecraft().fontRenderer
                        .listFormattedStringToWidth(en.reasonText, 220)) tip.add("§c" + line);
                }
                tip.add("§7" + I18n.format("patternrouter.tip.click"));
                tip.add("§7" + I18n.format("patternrouter.tip.highlight"));
                drawTooltip(tip, e.mouseX, e.mouseY);
            }
        }
        for (ConflictRow c : confRows) {
            if (c.visible && c.mousePressed(Minecraft.getMinecraft(), e.mouseX, e.mouseY)) {
                drawTooltip(
                    Collections.singletonList("§7" + I18n.format("patternrouter.tip.highlight.conflict")),
                    e.mouseX,
                    e.mouseY);
            }
        }
        for (int i = 0; i < extractButtons.size(); i++) {
            GuiButton extract = extractButtons.get(i);
            if (extract.visible && AeStyle.isOver(extract, e.mouseX, e.mouseY)) {
                List<String> tip = new ArrayList<>();
                tip.add(displayName(conflicts.get(i)));
                tip.add("§7" + I18n.format("patternrouter.tip.extract"));
                drawTooltip(tip, e.mouseX, e.mouseY);
            }
        }
        if (side != null && side.visible && AeStyle.isOver(side, e.mouseX, e.mouseY)) {
            drawTooltip(
                Collections.singletonList(
                    I18n.format(onRight ? "patternrouter.tip.side.left" : "patternrouter.tip.side.right")),
                e.mouseX,
                e.mouseY);
        }
        if (hideFull != null && hideFull.visible && AeStyle.isOver(hideFull, e.mouseX, e.mouseY)) {
            drawTooltip(
                Collections.singletonList(
                    I18n.format(Config.hideFull ? "patternrouter.tip.hidefull.on" : "patternrouter.tip.hidefull.off")),
                e.mouseX,
                e.mouseY);
        }
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.gui != gui || gui == null) return;
        int id = e.button.id;
        if (id < BASE_ID || id > BASE_ID + 200) return;
        e.setCanceled(true);
        if (dialogFor != null) {
            // While the duplicates question is open only its buttons work.
            if (e.button == dialogYes) answerDialog(true);
            else if (e.button == dialogNo) answerDialog(false);
            return;
        }
        if (e.button == toggle) {
            Config.setPanelHidden(!Config.panelHidden);
            lastKey = null; // opened again: ask for the current pattern
        } else if (e.button == side) {
            Config.setPanelSide(onRight ? "left" : "right");
            layoutHorizontal();
            relayout();
        } else if (e.button == hideFull) {
            Config.setHideFull(!Config.hideFull);
            applyFilter();
            scrollPx = scrollTarget = 0;
        } else if (e.button instanceof ConflictRow) {
            int i = ((ConflictRow) e.button).slot;
            if (i < conflicts.size()) highlight(conflicts.get(i));
        } else if (e.button instanceof RowButton && GuiScreen.isShiftKeyDown()) {
            int index = rowIndex(((RowButton) e.button).slot);
            if (index < entries.size()) highlight(entries.get(index));
        } else if (e.button instanceof RowButton) {
            int index = rowIndex(((RowButton) e.button).slot);
            if (index < entries.size()) {
                ProviderEntry picked = entries.get(index);
                // The same output is already elsewhere: ask whether to take those out (or keep them, to run the
                // recipe in parallel).
                if (!conflicts.isEmpty()) dialogFor = picked;
                else upload(picked, false);
            }
        } else {
            int i = extractButtons.indexOf(e.button);
            if (i >= 0 && i < conflicts.size()) {
                lastActionName = displayName(conflicts.get(i));
                PatternRouterNetwork.channel.sendToServer(new ExtractConflictMessage(token, i));
                setStatus(I18n.format("patternrouter.status.extracting"), GUI_TEXT_DIM, 3000);
            }
        }
    }

    /** Yes: upload, then move every listed pattern with the same output into the inventory. No: upload only. */
    private void answerDialog(boolean removeDupes) {
        ProviderEntry picked = dialogFor;
        dialogFor = null;
        if (picked != null) upload(picked, removeDupes);
    }

    private void upload(ProviderEntry picked, boolean removeDupes) {
        lastActionName = displayName(picked);
        PatternRouterNetwork.channel.sendToServer(new UploadPatternMessage(token, received.indexOf(picked)));
        setStatus(I18n.format("patternrouter.status.uploading"), GUI_TEXT_DIM, 3000);
        if (!removeDupes) return;
        // The server keeps this list after the upload; it takes them out one by one, checking each slot still
        // holds the listed pattern.
        uploadedTo = lastActionName;
        dupesExpected = conflicts.size();
        dupesDone = 0;
        for (int i = 0; i < conflicts.size(); i++) {
            PatternRouterNetwork.channel.sendToServer(new ExtractConflictMessage(token, i));
        }
    }

    /** Highlight the provider in the world with AE2's own highlighter (as its interface terminal does). */
    private void highlight(ProviderEntry en) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            appeng.client.render.highlighter.BlockPosHighlighter.highlightBlocks(
                mc.thePlayer,
                Collections.singletonList(new appeng.api.util.DimensionalCoord(en.x, en.y, en.z, en.dim)),
                appeng.core.localization.PlayerMessages.InterfaceHighlighted.getUnlocalized(),
                appeng.core.localization.PlayerMessages.InterfaceInOtherDim.getUnlocalized());
            mc.thePlayer.closeScreen();
        } catch (Throwable t) {
            // Older AE2 without the highlighter: at least say where it is.
            setStatus(displayName(en) + ": " + en.x + ", " + en.y + ", " + en.z, GUI_TEXT_DIM, 6000);
        }
    }

    // ------------------------------------------------------------------ server replies

    public void onCandidates(CandidateListMessage msg) {
        if (gui == null || sendAt >= 0) return; // the pattern changed since; its own request is about to go out
        for (ProviderEntry e : msg.entries) EntryText.localize(e);
        for (ProviderEntry e : msg.conflicts) EntryText.localize(e);
        token = msg.token;
        received = msg.entries;
        applyFilter();
        conflicts = msg.conflicts;
        note = msg.note;
        scrollPx = scrollTarget = 0;
        waiting = false;
        requestSentAt = 0;
    }

    private void applyFilter() {
        String q = query.trim()
            .toLowerCase();
        if (!Config.hideFull && q.isEmpty()) {
            entries = received;
            return;
        }
        entries = new ArrayList<>();
        for (ProviderEntry e : received) {
            // Full means all capacity cards in as well: a click on a full interface adds a card first.
            if (Config.hideFull && e.freeSlots == 0 && !e.takesCard) continue;
            if (!q.isEmpty() && !plain(displayName(e)).contains(q)) continue;
            entries.add(e);
        }
    }

    private static String plain(String s) {
        String t = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(s);
        return t == null ? "" : t.toLowerCase();
    }

    /** The search row shows once there is something to search, and stays while a query is typed. */
    private boolean searchShown() {
        return search != null && (received.size() > 1 || !query.isEmpty());
    }

    public void onResult(ActionResultMessage msg) {
        String name = lastActionName != null ? lastActionName : resolveName(msg.providerName, null);
        int good = 0x1E7A1E, bad = 0xB00000;
        switch (msg.code) {
            case ActionResultMessage.OK:
                setStatus(I18n.format("patternrouter.result.ok", name), good, 4000);
                break;
            case ActionResultMessage.OK_WITH_CARD:
                setStatus(I18n.format("patternrouter.result.okcard", name), good, 5000);
                break;
            case ActionResultMessage.NO_CARD:
                setStatus(I18n.format("patternrouter.result.nocard", name), bad, 5000);
                break;
            case ActionResultMessage.MAX_CARDS:
                setStatus(I18n.format("patternrouter.result.maxcards", name), bad, 5000);
                break;
            case ActionResultMessage.EXTRACTED:
                if (dupesExpected > 0) {
                    // One of the duplicates taken out after "yes": one status line for all of them.
                    dupesDone++;
                    setStatus(I18n.format("patternrouter.result.dupes", uploadedTo, dupesDone), good, 5000);
                    if (dupesDone >= dupesExpected) dupesExpected = 0;
                } else {
                    setStatus(I18n.format("patternrouter.result.extracted", name), good, 4000);
                }
                lastKey = null; // refresh the conflict list
                break;
            case ActionResultMessage.INVENTORY_FULL:
                setStatus(I18n.format("patternrouter.result.invfull"), bad, 4000);
                break;
            case ActionResultMessage.FULL:
                setStatus(I18n.format("patternrouter.result.full", name), bad, 4000);
                break;
            case ActionResultMessage.DENIED:
                setStatus(I18n.format("patternrouter.result.denied"), bad, 4000);
                break;
            case ActionResultMessage.NO_PATTERN:
                setStatus(I18n.format("patternrouter.result.nopattern"), bad, 4000);
                break;
            default:
                setStatus(I18n.format("patternrouter.result.gone"), bad, 4000);
                lastKey = null; // request the list again
        }
    }

    // ------------------------------------------------------------------ NEI

    public boolean overlaps(GuiContainer g, int x, int y, int w, int h) {
        return g == gui && visible && x < px + pw && x + w > px && y < py + ph && y + h > py;
    }

    // ------------------------------------------------------------------ layout

    private static boolean isPatternTerm(GuiScreen g) {
        return g instanceof GuiContainer && ((GuiContainer) g).inventorySlots instanceof ContainerPatternTerm;
    }

    /**
     * Width and side. Right: just past the AE2 cell column, width fits the free space (NEI makes
     * room). Left only if the right side is too narrow, placed past the AE2 button column so nothing is covered.
     */
    private void layoutHorizontal() {
        int left = field("guiLeft", "field_147003_i");
        int xSize = field("xSize", "field_146999_f");
        // The terminal with AE2's button column (left) and cell column (right) is never covered: Minecraft draws
        // slot items after buttons, so they would show through the panel and clicks would reach the slots.
        int rightX = left + xSize + RIGHT_GAP;
        int rightSpace = gui.width - rightX - 2;
        int leftEdge = left - LEFT_GAP;
        int leftSpace = leftEdge - 2;
        boolean useRight;
        if (Config.panelCustom) {
            // Dragged: keep the side the player chose (by the panel centre).
            int wanted = left + Config.panelX;
            useRight = wanted + pw / 2 >= left + xSize / 2;
            if (!useRight && leftSpace < FIT_W && rightSpace > leftSpace) useRight = true;
            if (useRight && rightSpace < FIT_W && leftSpace > rightSpace) useRight = false;
            pw = Math.max(FIT_W, Math.min(MAX_W, useRight ? rightSpace : leftSpace));
            px = useRight ? clamp(wanted, rightX, gui.width - pw - 2) : clamp(wanted, 2, leftEdge - pw);
        } else {
            boolean preferLeft = "left".equalsIgnoreCase(Config.panelSide);
            useRight = preferLeft ? leftSpace < MIN_W && rightSpace > leftSpace
                : rightSpace >= MIN_W || rightSpace >= leftSpace;
            pw = Math.max(FIT_W, Math.min(MAX_W, useRight ? rightSpace : leftSpace));
            px = useRight ? rightX : Math.max(2, leftEdge - pw);
        }
        onRight = useRight;
    }

    /** Full conflict title, or the short one when it doesn't fit (English is longer). */
    private String conflictTitle(FontRenderer fr, int w) {
        String full = I18n.format("patternrouter.conflict.title", conflicts.size());
        if (fr.getStringWidth(full) <= w) return full;
        return fr.trimStringToWidth(I18n.format("patternrouter.conflict.title.short", conflicts.size()), w);
    }

    /** Machine rows and conflict rows that fit on screen this frame (see relayout). */
    private int rowsCap = MIN_ROWS, confCap = CONF_ROWS;

    private int listHeight(int cap) {
        int shown = Math.min(entries.size(), cap);
        return shown * ROW_H + (shown > 0 ? 4 : 0);
    }

    private int confHeight(int cap) {
        int shown = Math.min(conflicts.size(), cap);
        return shown == 0 ? 0 : 14 + shown * CONF_H + (conflicts.size() > cap ? 10 : 0) + 4;
    }

    /** Vertical layout by content: list, note, conflicts, status line. */
    private void relayout() {
        int top = field("guiTop", "field_147009_r");
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        String n = noteText();
        int noteLines = n.isEmpty() ? 0
            : fr.listFormattedStringToWidth(n, pw - 16)
                .size();
        int noteBlock = noteLines > 0 ? noteLines * 10 + 4 : 0;
        // The footer grows for a long status line (wrapped, not cut).
        footer = FOOTER;
        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
            int lines = fr.listFormattedStringToWidth(status, statusWidth())
                .size();
            footer = Math.max(FOOTER, lines * 10 + 10);
        }
        // Fit the screen height: fewer conflict rows first ("…and N more"), then fewer machine rows (scrollable).
        searchH = searchShown() ? SEARCH_H : 0;
        int avail = gui.height - 4 - HEADER - searchH - footer - noteBlock;
        rowsCap = Math.max(MIN_ROWS, Math.min(Config.panelRows, MAX_ROWS));
        confCap = CONF_ROWS;
        while (listHeight(rowsCap) + confHeight(confCap) > avail && confCap > 1) confCap--;
        while (listHeight(rowsCap) + confHeight(confCap) > avail && rowsCap > 1) rowsCap--;
        int shown = Math.min(entries.size(), rowsCap);
        viewH = shown * ROW_H;
        maxScrollPx = Math.max(0, entries.size() * ROW_H - viewH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScrollPx));
        scrollPx = Math.max(0, Math.min(scrollPx, maxScrollPx));
        boolean scrollable = maxScrollPx > 0;
        int confShown = Math.min(conflicts.size(), confCap);
        int confBlock = confHeight(confCap);

        int body = shown * ROW_H + (shown > 0 ? 4 : 0) + noteBlock + confBlock;
        ph = HEADER + searchH + Math.max(body, 12) + footer;
        if (Config.panelCustom) {
            py = clamp(top + Config.panelY, 2, gui.height - ph - 2);
        } else {
            py = Math.max(2, Math.min(top, gui.height - ph - 2));
            // Don't cover NEI's page bar ("< 1/3 >") above its item/bookmark list.
            py = Math.max(py, neiBarBottom(px, pw, py));
        }
        listY = py + HEADER + searchH;
        if (search != null) {
            search.xPosition = px + 9;
            search.yPosition = py + HEADER + 2;
            search.width = pw - 18;
            search.height = 10;
            search.setVisible(visible && searchH > 0);
            if (!search.getVisible()) search.setFocused(false);
        }
        noteY = listY + shown * ROW_H + (shown > 0 ? 4 : 0);
        confY = noteY + (noteLines > 0 ? noteLines * 10 + 4 : 0);

        bg.xPosition = px;
        bg.yPosition = py;
        bg.width = pw;
        bg.height = ph;
        bg.visible = visible;
        int offset = (int) scrollPx % ROW_H;
        for (RowButton b : rows) {
            int y = listY + b.slot * ROW_H - offset;
            b.xPosition = px + 5;
            b.yPosition = y;
            b.width = pw - 10 - (scrollable ? SB_W + 1 : 0);
            b.height = ROW_H - 1;
            b.visible = visible && rowIndex(b.slot) < entries.size() && y < listY + viewH;
        }
        bar.xPosition = px + pw - 5 - SB_W;
        bar.yPosition = listY;
        bar.width = SB_W;
        bar.height = viewH;
        bar.visible = visible && scrollable;
        for (int i = 0; i < CONF_ROWS; i++) {
            ConflictRow c = confRows.get(i);
            GuiButton x = extractButtons.get(i);
            boolean on = visible && i < confShown;
            c.xPosition = px + 5;
            c.yPosition = confY + 14 + i * CONF_H;
            c.width = pw - 10;
            c.height = CONF_H - 1;
            c.visible = on;
            x.xPosition = px + pw - 5 - 54;
            x.yPosition = c.yPosition + (CONF_H - 1 - 16) / 2;
            x.visible = on;
        }
        side.xPosition = px + pw - 5 - side.width;
        side.yPosition = py + 6;
        side.displayString = onRight ? "◀" : "▶";
        side.visible = visible;
        hideFull.xPosition = side.xPosition - 2 - hideFull.width;
        hideFull.yPosition = side.yPosition;
        hideFull.displayString = Config.hideFull ? "■" : "□";
        hideFull.visible = visible;
        // NEI hides its item slots under every button of the GUI and ignores GuiButton.visible: park the hidden
        // ones off screen, or NEI keeps a hole where the panel was.
        park(bg);
        for (GuiButton b : rows) park(b);
        for (GuiButton b : confRows) park(b);
        for (GuiButton b : extractButtons) park(b);
        park(bar);
        placeToggle();
        layoutDialog();
        park(side);
        park(hideFull);
        // Refresh NEI's slot mask right away when the panel appears, disappears, moves or resizes.
        long key = visible ? (((long) px & 0xFFFF) << 48) | (((long) py & 0xFFFF) << 32) | ((long) pw << 16) | ph : -1;
        if (NEI && key != neiLayoutKey && !dragging) {
            neiLayoutKey = key;
            NeiCompat.refreshMask();
        }
    }

    private static void park(GuiButton b) {
        if (b != null && !b.visible) {
            b.xPosition = -10000;
            b.yPosition = -10000;
        }
    }

    /** Panel area NEI's mask was last refreshed for (-1: no panel). */
    private long neiLayoutKey = Long.MIN_VALUE;

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, Math.max(min, max)));
    }

    /** Bottom (plus a gap) of the lowest NEI page bar the panel at (x, y) would cover; 0 if none or no NEI. */
    private int neiBarBottom(int x, int w, int y) {
        if (!NEI) return 0;
        int bottom = 0;
        for (int[] r : NeiCompat.pageBars()) {
            boolean overlapX = x < r[0] + r[2] && x + w > r[0];
            boolean overlapY = r[1] < y + ph && r[1] + r[3] + 2 > y;
            if (overlapX && overlapY) bottom = Math.max(bottom, r[1] + r[3] + 2);
        }
        return bottom;
    }

    private int field(String dev, String srg) {
        Integer v = ObfuscationReflectionHelper.getPrivateValue(GuiContainer.class, gui, dev, srg);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ logic

    /**
     * The encoded pattern in the terminal's output slot (slot 1 of its pattern inventory, the one the server reads).
     * Ghost slots of the crafting grid may show an encoded pattern as an ingredient: never take those.
     */
    private ItemStack findPattern() {
        ItemStack fallback = null;
        for (Object o : gui.inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            if (s.inventory instanceof InventoryPlayer || s instanceof SlotFake) continue;
            ItemStack st = s.getStack();
            if (st == null || !(st.getItem() instanceof ItemEncodedPattern)) continue;
            if (s instanceof SlotRestrictedInput && s.getSlotIndex() == ENCODED_OUTPUT_SLOT) return st;
            if (fallback == null) fallback = st;
        }
        return fallback;
    }

    private static final int ENCODED_OUTPUT_SLOT = 1;

    private static String keyOf(ItemStack pattern) {
        return pattern.getTagCompound() == null ? ""
            : pattern.getTagCompound()
                .toString();
    }

    private void request(ItemStack pattern) {
        ItemStack out = null;
        try {
            out = ((ItemEncodedPattern) pattern.getItem()).getOutput(pattern);
        } catch (Throwable ignored) {}
        boolean ctx = RecipeContext.appliesTo(out);
        PatternRouterNetwork.channel.sendToServer(
            new RequestCandidatesMessage(
                ctx ? RecipeContext.category : "",
                ctx ? RecipeContext.nonConsumables : Collections.emptyList(),
                ctx ? RecipeContext.machineName : ""));
        requestSentAt = System.currentTimeMillis();
        entries = received = Collections.emptyList();
        conflicts = Collections.emptyList();
        note = -1;
        waiting = true;
    }

    private void resetList() {
        // Ask again for the same pattern next time; otherwise a reopened terminal showed an empty panel.
        lastKey = null;
        sendAt = -1;
        entries = received = Collections.emptyList();
        conflicts = Collections.emptyList();
        note = -1;
        waiting = false;
        requestSentAt = 0;
        scrollPx = scrollTarget = 0;
        barDragging = resizing = false;
    }

    private void setStatus(String text, int color, long ms) {
        status = text;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + ms;
    }

    private String noteText() {
        if (waiting && requestSentAt > 0 && System.currentTimeMillis() - requestSentAt > REPLY_TIMEOUT_MS) {
            return I18n.format("patternrouter.note.noreply");
        }
        if (waiting) return I18n.format("patternrouter.note.waiting");
        ListNote listNote = ListNote.fromWire(note);
        if (listNote == null) return "";
        switch (listNote) {
            case NO_PATTERN:
                return I18n.format("patternrouter.note.nopattern");
            case NO_GRID:
                return I18n.format("patternrouter.note.nogrid");
            case DENIED:
                return I18n.format("patternrouter.note.denied");
            case NO_MACHINE:
                return I18n.format("patternrouter.note.nomachine");
            case UNKNOWN_RECIPE:
                return I18n.format("patternrouter.note.unknown");
            case UNFORMED:
                return I18n.format("patternrouter.note.unformed");
            case NO_ASSEMBLER:
                return I18n.format("patternrouter.note.noassembler");
            default:
                if (!entries.isEmpty()) return "";
                if (!received.isEmpty() && !query.trim()
                    .isEmpty()) return I18n.format("patternrouter.note.nomatch");
                return received.isEmpty() ? I18n.format("patternrouter.note.empty")
                    : I18n.format("patternrouter.note.allfull");
        }
    }

    private void drawTooltip(List<String> lines, int x, int y) {
        AeStyle.drawTooltip(lines, x, y, gui.width);
    }

    /** Panel window, title, note, conflict header, status line. Drawn first. */
    private class Background extends GuiButton {

        Background(int id) {
            super(id, 0, 0, 1, 1, "");
            this.enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            FontRenderer fr = mc.fontRenderer;
            drawShadow(xPosition, yPosition, width, height);
            drawWindow(xPosition, yPosition, width, height);
            // Title: the pattern being uploaded in a slot, plus the caption.
            int tx = xPosition + 8;
            if (currentPattern != null) {
                drawSlot(xPosition + 5, yPosition + 4);
                drawItem(mc, currentPattern, xPosition + 6, yPosition + 5);
                tx = xPosition + 27;
            }
            int titleRoom = xPosition + width - TITLE_BUTTONS - tx;
            String title = I18n.format("patternrouter.title");
            if (fr.getStringWidth(title) > titleRoom) title = I18n.format("patternrouter.title.short");
            fr.drawString(ellipsize(fr, title, titleRoom), tx, yPosition + 9, GUI_TEXT);
            boolean statusShown = System.currentTimeMillis() < statusUntil && !status.isEmpty();
            if (entries.size() > rowsCap && !statusShown) {
                // Position in the footer, left of the resize grip (the title keeps its room).
                int first = rowIndex(0) + 1;
                String page = first + "-" + Math.min(first + rowsCap - 1, entries.size()) + "/" + entries.size();
                fr.drawString(
                    page,
                    xPosition + width - GRIP - 6 - fr.getStringWidth(page),
                    yPosition + height - footer + 7,
                    GUI_TEXT_DIM);
            }
            int shown = Math.min(entries.size(), rowsCap);
            if (shown > 0) drawInset(xPosition + 4, listY - 1, width - 8, shown * ROW_H + 1);
            drawGrip(xPosition + width - GRIP - 3, yPosition + height - GRIP - 3, resizing || overGrip(mx, my));
            if (search != null && search.getVisible()) {
                // Search row under the title, inset like AE2's own search box.
                drawInset(xPosition + 5, yPosition + HEADER - 1, width - 10, SEARCH_H - 2);
                search.drawTextBox();
                if (search.getText()
                    .isEmpty() && !search.isFocused()) {
                    fr.drawString(
                        I18n.format("patternrouter.search.hint"),
                        search.xPosition,
                        search.yPosition + 1,
                        0xC8C8C8);
                }
            }
            String n = noteText();
            if (!n.isEmpty()) {
                List<String> wrapped = fr.listFormattedStringToWidth(n, width - 16);
                for (int i = 0; i < wrapped.size(); i++) {
                    fr.drawString(wrapped.get(i), xPosition + 8, noteY + i * 10, GUI_TEXT_DIM);
                }
            }
            int confShown = Math.min(conflicts.size(), confCap);
            if (confShown > 0) {
                fr.drawString(conflictTitle(fr, width - 16), xPosition + 8, confY + 3, GUI_TEXT_BAD);
                drawInset(xPosition + 4, confY + 13, width - 8, confShown * CONF_H + 1);
                if (conflicts.size() > confCap) {
                    fr.drawString(
                        I18n.format("patternrouter.conflict.more", conflicts.size() - confCap),
                        xPosition + 8,
                        confY + 15 + confShown * CONF_H,
                        GUI_TEXT_DIM);
                }
            }
            if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
                List<String> lines = fr.listFormattedStringToWidth(status, statusWidth());
                for (int i = 0; i < lines.size(); i++) {
                    fr.drawString(lines.get(i), xPosition + 8, yPosition + height - footer + 6 + i * 10, statusColor);
                }
            }
        }

        /** A press on the title bar (not on the side arrow) starts dragging; never counts as a button click. */
        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            boolean inTitle = visible && mx >= xPosition
                && mx < xPosition + width
                && my >= yPosition
                && my < yPosition + HEADER;
            boolean onArrow = side != null && AeStyle.isOver(side, mx, my)
                || hideFull != null && AeStyle.isOver(hideFull, mx, my);
            if (dialogFor != null) {
                // A click off the question cancels it: nothing is uploaded.
                if (!AeStyle.isOver(dialog, mx, my)) {
                    dialogFor = null;
                    clickClosedDialog = true; // this click must not also hit a row under it
                }
                return false;
            }
            // Any click: focus the search field when on it, leave it otherwise.
            if (search != null && search.getVisible()) search.mouseClicked(mx, my, 0);
            if (visible && overGrip(mx, my)) {
                resizing = true;
                resizeStartY = my;
                resizeStartRows = rowsCap;
                return false;
            }
            if (inTitle && !onArrow) {
                dragging = true;
                dragDX = mx - px;
                dragDY = my - py;
            }
            return false;
        }
    }

    /** Candidate row: machine icon slot, name, CRIB caption, flags. */
    private class RowButton extends GuiButton {

        final int slot;

        RowButton(int id, int slot) {
            super(id, 0, 0, 1, 1, "");
            this.slot = slot;
        }

        /** Over the row and inside the list: a half-scrolled row's hidden part takes no hover or click. */
        boolean hovered(int mx, int my) {
            return AeStyle.isOver(this, mx, my) && my >= listY && my < listY + viewH;
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            return enabled && visible && dialogFor == null && !clickClosedDialog && hovered(mx, my);
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible || rowIndex(slot) >= entries.size()) return;
            withClip(mc, xPosition, listY, width, viewH, () -> drawRow(mc, mx, my));
        }

        private void drawRow(Minecraft mc, int mx, int my) {
            ProviderEntry e = entries.get(rowIndex(slot));
            boolean hover = hovered(mx, my) && !barDragging && !resizing;
            boolean pressed = hover && org.lwjgl.input.Mouse.isButtonDown(0);
            // Raised tile; bluish highlight on hover like AE2 selection.
            drawRaised(xPosition + 1, yPosition + 1, width - 2, height - 2, hover ? 0xFFD3DCE8 : GUI_FILL, pressed);
            int stripe = (e.flags & ProviderEntry.SETUP_FITS) != 0 ? 0xFF2E9E2E
                : (e.flags & ProviderEntry.SETUP_CONFLICTS) != 0 ? 0xFFC03030
                    : (e.flags & ProviderEntry.RECIPE_MATCH) != 0 ? 0xFF4A7FC0 : 0xFF8B8B8B;
            drawRect(xPosition + 2, yPosition + 2, xPosition + 5, yPosition + height - 2, stripe);
            drawRect(xPosition + 2, yPosition + 2, xPosition + 3, yPosition + height - 2, 0x40FFFFFF);
            int sy = yPosition + (height - 18) / 2;
            drawSlot(xPosition + 7, sy);
            drawItem(mc, e.icon, xPosition + 8, sy + 1);
            if (hover) drawRect(xPosition + 8, sy + 1, xPosition + 24, sy + 17, 0x60FFFFFF);
            FontRenderer fr = mc.fontRenderer;
            int tx = xPosition + 29, tw = width - 33;
            boolean hasSuffix = e.suffix != null && !e.suffix.isEmpty();
            if (!hasSuffix && fr.getStringWidth(e.name) > tw) {
                // No caption: the long name gets the second line too.
                List<String> lines = fr.listFormattedStringToWidth(e.name, tw);
                fr.drawString(lines.get(0), tx, yPosition + 4, GUI_TEXT);
                String rest = e.name.substring(
                    Math.min(
                        e.name.length(),
                        lines.get(0)
                            .length()))
                    .trim();
                fr.drawString(ellipsize(fr, rest, tw), tx, yPosition + 14, GUI_TEXT);
            } else {
                fr.drawString(ellipsize(fr, e.name, tw), tx, yPosition + 4, GUI_TEXT);
                if (hasSuffix) fr.drawString(ellipsize(fr, e.suffix, tw), tx, yPosition + 14, GUI_TEXT_DIM);
            }
            fr.drawString(ellipsize(fr, flagsLine(e, fr, tw), tw), tx, yPosition + 25, GUI_TEXT_DIM);
        }
    }

    /** Scroll bar: inset track, raised thumb; a press on the track jumps there, a drag moves the list with it. */
    private class ScrollBar extends GuiButton {

        ScrollBar(int id) {
            super(id, 0, 0, 1, 1, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            drawInset(xPosition, yPosition, width, height);
            boolean hot = barDragging || AeStyle.isOver(this, mx, my);
            drawRaised(xPosition, thumbY(), width, thumbHeight(), hot ? 0xFFD3DCE8 : GUI_FILL, false);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            if (!visible || dialogFor != null || !AeStyle.isOver(this, mx, my)) return false;
            int top = thumbY(), h = thumbHeight();
            barGrab = my >= top && my < top + h ? my - top : h / 2;
            barDragging = true;
            scrollToThumb(my - barGrab);
            return false; // no click sound, no action
        }
    }

    /**
     * The fold button goes under the last button of AE2's column next to the terminal, with AE2's own spacing; if that
     * runs off the screen, under the outer column. AE2 lays the columns out by terminal size and screen, so this is
     * worked out from where its buttons actually are.
     */
    private void placeToggle() {
        if (toggle == null) return;
        int left = field("guiLeft", "field_147003_i");
        java.util.TreeMap<Integer, List<GuiButton>> columns = new java.util.TreeMap<>();
        for (GuiButton b : guiButtons) {
            if (b == null || !b.visible || (b.id >= BASE_ID && b.id <= BASE_ID + 200)) continue;
            if (b.width > 22 || b.height > 22 || b.xPosition + b.width > left || b.xPosition < 0) continue;
            columns.computeIfAbsent(b.xPosition, k -> new ArrayList<>())
                .add(b);
        }
        toggle.visible = true;
        for (Integer x : columns.descendingKeySet()) {
            List<GuiButton> col = columns.get(x);
            int bottom = Integer.MIN_VALUE, step = Integer.MAX_VALUE;
            List<Integer> ys = new ArrayList<>();
            for (GuiButton b : col) {
                ys.add(b.yPosition);
                bottom = Math.max(bottom, b.yPosition);
            }
            Collections.sort(ys);
            for (int i = 1; i < ys.size(); i++) {
                int d = ys.get(i) - ys.get(i - 1);
                if (d > 0) step = Math.min(step, d);
            }
            if (step == Integer.MAX_VALUE) step = 20;
            int y = bottom + step;
            if (y + toggle.height <= gui.height - 2) {
                toggle.xPosition = x;
                toggle.yPosition = y;
                return;
            }
        }
        // No AE2 column found (another terminal look): just left of the terminal's top corner.
        toggle.xPosition = Math.max(2, left - toggle.width - 2);
        toggle.yPosition = field("guiTop", "field_147009_r") + 2;
    }

    /** AE2-style 16x16 image button: AE2's button background with an encoded pattern; greyed when folded. */
    private class ToggleButton extends GuiButton {

        private ItemStack icon;

        ToggleButton(int id) {
            super(id, 0, 0, 16, 16, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            GL11.glColor4f(1, 1, 1, 1);
            try {
                mc.getTextureManager()
                    .bindTexture(
                        new net.minecraft.util.ResourceLocation("appliedenergistics2", "textures/guis/states.png"));
                GL11.glEnable(GL11.GL_BLEND);
                drawTexturedModalRect(xPosition, yPosition, 240, 240, 16, 16);
            } catch (Throwable t) {
                drawRaised(xPosition, yPosition, 16, 16, GUI_FILL, false);
            }
            if (icon == null) icon = patternIcon();
            GL11.glPushMatrix();
            GL11.glTranslatef(xPosition + 2, yPosition + 2, 0);
            GL11.glScalef(0.75f, 0.75f, 1);
            drawItem(mc, icon, 0, 0);
            GL11.glPopMatrix();
            if (Config.panelHidden) drawRect(xPosition + 1, yPosition + 1, xPosition + 15, yPosition + 15, 0x90555555);
            if (AeStyle.isOver(this, mx, my))
                drawRect(xPosition + 1, yPosition + 1, xPosition + 15, yPosition + 15, 0x40FFFFFF);
        }

        private ItemStack patternIcon() {
            try {
                return appeng.api.AEApi.instance()
                    .definitions()
                    .items()
                    .encodedPattern()
                    .maybeStack(1)
                    .orNull();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** The question box over the list, its two buttons at the bottom; hidden (parked) when closed. */
    private void layoutDialog() {
        if (dialog == null) return;
        if (dialogFor != null && (!visible || !entries.contains(dialogFor))) dialogFor = null; // list changed
        boolean on = visible && dialogFor != null;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int w = pw - 16;
        int lines = on ? fr.listFormattedStringToWidth(dialogText(), w - 12)
            .size() : 0;
        int h = 8 + lines * 10 + 4 + 16 + 6;
        dialog.xPosition = px + 8;
        dialog.width = w;
        dialog.height = h;
        dialog.yPosition = Math.max(py + HEADER, Math.min(listY + 6, py + ph - footer - h));
        dialog.visible = on;
        dialogYes.yPosition = dialogNo.yPosition = dialog.yPosition + h - 16 - 6;
        dialogYes.xPosition = dialog.xPosition + w / 2 - dialogYes.width - 3;
        dialogNo.xPosition = dialog.xPosition + w / 2 + 3;
        dialogYes.visible = dialogNo.visible = on;
        park(dialog);
        park(dialogYes);
        park(dialogNo);
    }

    private String dialogText() {
        return I18n.format("patternrouter.dupes.ask", conflicts.size()) + "\n§8"
            + I18n.format("patternrouter.dupes.hint");
    }

    /** Raised box with the duplicates question; the panel behind is dimmed. */
    private class DialogBox extends GuiButton {

        DialogBox(int id) {
            super(id, 0, 0, 1, 1, "");
            this.enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            GL11.glTranslatef(0, 0, 300); // over the item icons of the rows
            drawRect(px + 1, py + 1, px + pw - 1, py + ph - 1, 0x80202020);
            drawShadow(xPosition, yPosition, width, height);
            drawWindow(xPosition, yPosition, width, height);
            FontRenderer fr = mc.fontRenderer;
            List<String> lines = fr.listFormattedStringToWidth(dialogText(), width - 12);
            for (int i = 0; i < lines.size(); i++) {
                fr.drawString(lines.get(i), xPosition + 6, yPosition + 8 + i * 10, GUI_TEXT);
            }
            GL11.glTranslatef(0, 0, -300);
        }
    }

    /** Bottom-right corner where the grip is. */
    private boolean overGrip(int mx, int my) {
        int gx = px + pw - GRIP - 3, gy = py + ph - GRIP - 3;
        return mx >= gx && mx < gx + GRIP && my >= gy && my < gy + GRIP;
    }

    /** Diagonal ridges like a window resize corner. */
    private static void drawGrip(int x, int y, boolean hot) {
        int dark = hot ? 0xFF2A2A2A : AeStyle.BEVEL_DARK;
        for (int i = 0; i < GRIP; i++) {
            for (int k = 0; k < 3; k++) {
                int d = GRIP - 1 - k * 3; // ridge: x + y == d
                int j = d - i;
                if (j < 0 || j >= GRIP) continue;
                GuiButton.drawRect(x + i, y + j, x + i + 1, y + j + 1, dark);
                if (i + 1 < GRIP && j + 1 < GRIP) {
                    GuiButton.drawRect(x + i + 1, y + j, x + i + 2, y + j + 1, AeStyle.BEVEL_LIGHT);
                }
            }
        }
    }

    /** Draws only inside this rectangle (GUI coordinates). */
    private static void withClip(Minecraft mc, int x, int y, int w, int h, Runnable draw) {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = sr.getScaleFactor();
        boolean was = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, mc.displayHeight - (y + h) * f, Math.max(0, w * f), Math.max(0, h * f));
        try {
            draw.run();
        } finally {
            if (!was) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** The text cut to this width, ending in "…" when something was cut. */
    static String ellipsize(FontRenderer fr, String s, int w) {
        if (s == null || fr.getStringWidth(s) <= w) return s == null ? "" : s;
        String dots = "…";
        return fr.trimStringToWidth(s, Math.max(0, w - fr.getStringWidth(dots))) + dots;
    }

    /** Conflict row: machine that already holds a pattern for this output. The Extract button is separate. */
    private class ConflictRow extends GuiButton {

        final int slot;

        ConflictRow(int id, int slot) {
            super(id, 0, 0, 1, 1, "");
            this.slot = slot;
            this.enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible || slot >= conflicts.size()) return;
            ProviderEntry e = conflicts.get(slot);
            drawRaised(xPosition + 1, yPosition + 1, width - 2, height - 2, 0xFFD9CDB8, false);
            drawRect(xPosition + 2, yPosition + 2, xPosition + 5, yPosition + height - 2, 0xFFD08A1E);
            int sy = yPosition + (height - 18) / 2;
            drawSlot(xPosition + 7, sy);
            drawItem(mc, e.icon, xPosition + 8, sy + 1);
            FontRenderer fr = mc.fontRenderer;
            int tx = xPosition + 29, tw = width - 29 - 60;
            fr.drawString(ellipsize(fr, e.name, tw), tx, yPosition + 4, GUI_TEXT);
            if (e.suffix != null && !e.suffix.isEmpty()) {
                fr.drawString(ellipsize(fr, e.suffix, tw), tx, yPosition + 14, GUI_TEXT_DIM);
            }
        }

        /** A click on the row (the Extract button is a separate button) highlights the old pattern's interface. */
        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            return visible && mx >= xPosition
                && my >= yPosition
                && mx < xPosition + width - 58
                && my < yPosition + height;
        }

        @Override
        public void func_146113_a(net.minecraft.client.audio.SoundHandler handler) {
            // no click sound for a highlight
        }
    }
}
