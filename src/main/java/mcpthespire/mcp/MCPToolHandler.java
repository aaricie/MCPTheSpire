package mcpthespire.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import mcpthespire.ChoiceScreenUtils;
import mcpthespire.CommandExecutor;
import mcpthespire.GameStateListener;
import mcpthespire.InfoLookup;
import mcpthespire.InvalidCommandException;
import mcpthespire.ProfileStateConverter;
import mcpthespire.StateRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles MCP tool definitions and tool call execution.
 */
public class MCPToolHandler {

    private static final Logger logger = LogManager.getLogger(MCPToolHandler.class.getName());

    /** Sent once in the initialize result; clients put it in the model's system context. */
    public static final String INSTRUCTIONS =
        "Plays Slay the Spire. Loop: get_state, then act; act returns what each action did plus the new state, " +
        "so you rarely need get_state after it.\n" +
        "- All numbers (hand, enemy, choice, potion slot) are 1-based and match the latest state. Within one act " +
        "call they refer to the state at call time; they do not shift as cards are played or rewards taken.\n" +
        "- A batch stops early when the screen or an enemy intent changes; read the new state and continue.\n" +
        "- Card text in the state already includes current damage/block numbers. Use get_info for relics, " +
        "potions, powers, or unfamiliar cards.\n" +
        "- If a result says an operation is still running, poll get_action_status; never resend the action.";

    public static final String ACT = "act";

    private static final Set<String> READ_ONLY_TOOLS = new HashSet<>(Arrays.asList(
        "get_state", "get_info", "get_profile_progress"));

    private static final List<String> ACTION_TYPES = Arrays.asList(
        "play", "end_turn", "choose", "proceed", "confirm", "skip", "cancel", "select",
        "use_potion", "discard_potion", "wait");

    private static final Map<String, String> ACTION_ALIASES = new HashMap<>();
    private static final Map<String, String> PARAM_ALIASES = new HashMap<>();
    static {
        ACTION_ALIASES.put("play_card", "play");
        ACTION_ALIASES.put("end", "end_turn");
        ACTION_ALIASES.put("select_cards", "select");
        ACTION_ALIASES.put("potion", "use_potion");
        PARAM_ALIASES.put("card_index", "card");
        PARAM_ALIASES.put("card_name", "card");
        PARAM_ALIASES.put("card_id", "card");
        PARAM_ALIASES.put("target_index", "target");
        PARAM_ALIASES.put("choice_index", "choice");
        PARAM_ALIASES.put("potion_slot", "slot");
    }

    public boolean isReadOnlyTool(String toolName) {
        return READ_ONLY_TOOLS.contains(toolName);
    }

    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        Map<String, Object> stateProps = new LinkedHashMap<>();
        Map<String, Object> include = new LinkedHashMap<>();
        include.put("type", "array");
        include.put("items", MCPProtocol.createEnumProperty(null, Arrays.asList("deck", "relics", "piles", "map")));
        stateProps.put("include", include);
        tools.add(MCPProtocol.createToolDefinition("get_state",
            "Current screen as text: HP/gold/energy, enemies with intents, hand with card text, potions, numbered " +
            "choices, and available actions. Optional include: deck, relics, piles (draw/discard/exhaust contents), " +
            "map (shown automatically on the map screen).",
            MCPProtocol.createInputSchema(stateProps, null), true, false));

        Map<String, Object> actionProps = new LinkedHashMap<>();
        actionProps.put("action", MCPProtocol.createEnumProperty(null, ACTION_TYPES));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", Arrays.asList("integer", "string"));
        actionProps.put("card", card);
        actionProps.put("target", MCPProtocol.createProperty("integer", null));
        actionProps.put("choice", MCPProtocol.createProperty("integer", null));
        actionProps.put("slot", MCPProtocol.createProperty("integer", null));
        Map<String, Object> cardList = new LinkedHashMap<>();
        cardList.put("type", "array");
        cardList.put("items", card);
        actionProps.put("drop", cardList);
        actionProps.put("keep", cardList);
        actionProps.put("ms", MCPProtocol.createProperty("integer", null));
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "array");
        actions.put("items", MCPProtocol.createInputSchema(actionProps, Collections.singletonList("action")));
        Map<String, Object> actProps = new LinkedHashMap<>();
        actProps.put("actions", actions);
        tools.add(MCPProtocol.createToolDefinition(ACT,
            "Run one or more actions in order; returns a line per action describing its effect, then the new state. " +
            "Actions: play {card: hand number or name, target?: enemy number}; end_turn; choose {choice}; " +
            "proceed; confirm; skip; cancel; select {drop: [cards]} or {keep: [cards]} (hand-selection screens, " +
            "auto-confirms); use_potion {slot, target?}; discard_potion {slot}; wait {ms <= 500}. " +
            "Example: [{\"action\":\"play\",\"card\":3,\"target\":1},{\"action\":\"play\",\"card\":\"Defend\"},{\"action\":\"end_turn\"}]",
            MCPProtocol.createInputSchema(actProps, Collections.singletonList("actions")), false, false));

        Map<String, Object> infoProps = new LinkedHashMap<>();
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("type", "array");
        names.put("items", MCPProtocol.createProperty("string", null));
        infoProps.put("names", names);
        tools.add(MCPProtocol.createToolDefinition("get_info",
            "Describe cards (with upgraded version), relics, potions, or powers active in combat, by name or ID.",
            MCPProtocol.createInputSchema(infoProps, Collections.singletonList("names")), true, false));

        Map<String, Object> progressProps = new LinkedHashMap<>();
        progressProps.put("include_cards", MCPProtocol.createProperty("boolean", "List every unlocked card"));
        progressProps.put("include_achievements", MCPProtocol.createProperty("boolean", null));
        tools.add(MCPProtocol.createToolDefinition("get_profile_progress",
            "Profile statistics and unlock progress per character, including locked cards and relics. Works from the menu.",
            MCPProtocol.createInputSchema(progressProps, null), true, false));

        Map<String, Object> statusProps = new LinkedHashMap<>();
        statusProps.put("operation_id", MCPProtocol.createProperty("string", null));
        tools.add(MCPProtocol.createToolDefinition("get_action_status",
            "Result of an action reported as still running. Never resend that action.",
            MCPProtocol.createInputSchema(statusProps, Collections.singletonList("operation_id")), true, false));

        Map<String, Object> startProps = new LinkedHashMap<>();
        startProps.put("character", MCPProtocol.createEnumProperty(null,
            Arrays.asList("IRONCLAD", "SILENT", "DEFECT", "WATCHER")));
        startProps.put("ascension", MCPProtocol.createProperty("integer", "0-20"));
        startProps.put("seed", MCPProtocol.createProperty("string", null));
        tools.add(MCPProtocol.createToolDefinition("start_game", "Start a new run from the main menu.",
            MCPProtocol.createInputSchema(startProps, Collections.singletonList("character")), false, false));
        tools.add(MCPProtocol.createToolDefinition("continue_game", "Continue the saved run from the main menu.",
            MCPProtocol.createInputSchema(new LinkedHashMap<>(), null), false, false));
        tools.add(MCPProtocol.createToolDefinition("abandon_run",
            "Abandon the saved run from the main menu (counts as a loss; cannot be undone).",
            MCPProtocol.createInputSchema(new LinkedHashMap<>(), null), false, true));
        tools.add(MCPProtocol.createToolDefinition("save_game",
            "Return to the main menu. continue_game resumes from the latest autosave (start of the current room).",
            MCPProtocol.createInputSchema(new LinkedHashMap<>(), null), false, false));
        return tools;
    }

    /**
     * Execute a non-batch tool call and return the result.
     */
    public Map<String, Object> executeTool(String toolName, JsonObject arguments) {
        try {
            switch (toolName) {
                case "get_state":
                    return text(StateRenderer.render(stringSet(arguments, "include")));
                case "get_info":
                    if (arguments == null || !arguments.has("names") || !arguments.get("names").isJsonArray()) {
                        return MCPProtocol.buildToolCallResult("names (array of card/relic/potion/power names) is required", true);
                    }
                    List<String> names = new ArrayList<>();
                    for (JsonElement e : arguments.getAsJsonArray("names")) names.add(e.getAsString());
                    return text(InfoLookup.describe(names));
                case "get_profile_progress":
                    return text(ProfileStateConverter.getProfileProgress(flag(arguments, "include_cards"),
                        flag(arguments, "include_achievements")));
                case "start_game":
                    return executeStartGame(arguments);
                case "continue_game":
                    CommandExecutor.executeCommand("continue");
                    GameStateListener.registerCommandExecution();
                    return text("Continued saved run.");
                case "abandon_run":
                    CommandExecutor.executeCommand("abandon");
                    GameStateListener.registerCommandExecution();
                    return text("Run abandoned.");
                case "save_game":
                    CommandExecutor.executeCommand("save");
                    GameStateListener.registerCommandExecution();
                    return text("Returned to menu; continue_game resumes from the latest autosave.");
                default:
                    return MCPProtocol.buildToolCallResult("Unknown tool: " + toolName, true);
            }
        } catch (InvalidCommandException e) {
            return MCPProtocol.buildToolCallResult("Error: " + e.getMessage(), true);
        } catch (Exception e) {
            logger.error("Error executing tool: " + toolName, e);
            return MCPProtocol.buildToolCallResult("Internal error: " + e.getMessage(), true);
        }
    }

    private static Map<String, Object> text(String text) {
        return MCPProtocol.buildToolCallResult(text, false);
    }

    private static boolean flag(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() && args.get(key).getAsBoolean();
    }

    private static Set<String> stringSet(JsonObject args, String key) {
        Set<String> result = new HashSet<>();
        if (args != null && args.has(key) && args.get(key).isJsonArray()) {
            for (JsonElement e : args.getAsJsonArray(key)) result.add(e.getAsString().toLowerCase());
        }
        return result;
    }

    private Map<String, Object> executeStartGame(JsonObject args) throws InvalidCommandException {
        if (args == null || !args.has("character")) throw new InvalidCommandException("character is required");
        String character = args.get("character").getAsString().toLowerCase();
        StringBuilder command = new StringBuilder("start " + character);
        if (args.has("ascension") && !args.get("ascension").isJsonNull()) {
            command.append(" ").append(args.get("ascension").getAsInt());
        }
        if (args.has("seed") && !args.get("seed").isJsonNull()) {
            command.append(" ").append(args.get("seed").getAsString());
        }
        CommandExecutor.executeCommand(command.toString());
        GameStateListener.registerCommandExecution();
        return text("Started " + character.toUpperCase() + ".");
    }

    // ---------------------------------------------------------------- act

    /**
     * Normalize one action object: accept legacy names (play_card, card_index, ...) and
     * reject unknown actions early.
     */
    public static JsonObject normalizeAction(JsonObject action, int position) {
        JsonObject normalized = new JsonObject();
        for (Map.Entry<String, JsonElement> e : action.entrySet()) {
            String key = PARAM_ALIASES.containsKey(e.getKey()) ? PARAM_ALIASES.get(e.getKey()) : e.getKey();
            if (!normalized.has(key)) normalized.add(key, e.getValue());
        }
        String type = normalized.get("action").getAsString().toLowerCase();
        if (ACTION_ALIASES.containsKey(type)) type = ACTION_ALIASES.get(type);
        if (!ACTION_TYPES.contains(type)) {
            throw new IllegalArgumentException("Action " + position + ": unknown action '" + type + "'. Use one of " + ACTION_TYPES);
        }
        normalized.addProperty("action", type);
        return normalized;
    }

    /**
     * Execute a single action (already normalized, with batch-stable indices resolved by the server).
     *
     * @return a short description of what was attempted, e.g. "play Strike -> Jaw Worm"
     */
    public String executeAction(String type, JsonObject params) throws InvalidCommandException {
        requireAvailable(type);
        String description;
        switch (type) {
            case "play": {
                ArrayList<AbstractCard> hand = AbstractDungeon.player.hand.group;
                int index = resolveCardIndex(params.get("card"), hand);
                AbstractCard card = hand.get(index - 1);
                StringBuilder command = new StringBuilder("play " + index);
                description = "play " + card.name;
                Integer target = optInt(params, "target");
                List<AbstractMonster> monsters = StateRenderer.aliveMonsters();
                boolean targeted = card.target == AbstractCard.CardTarget.ENEMY ||
                    card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
                if (target != null) {
                    checkTarget(target, monsters);
                    command.append(' ').append(target);
                    if (targeted) description += " -> " + monsters.get(target - 1).name;
                } else if (targeted && monsters.size() > 1) {
                    throw new InvalidCommandException(card.name + " needs a target (enemy 1-" + monsters.size() + ")");
                } else if (targeted && monsters.size() == 1) {
                    description += " -> " + monsters.get(0).name;
                }
                if (!card.canUse(AbstractDungeon.player, null)) {
                    throw new InvalidCommandException(card.name + " can't be played now (cost " +
                        StateRenderer.cost(card) + ", energy " + com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount + ")");
                }
                CommandExecutor.executeCommand(command.toString());
                break;
            }
            case "end_turn":
                description = "end_turn";
                CommandExecutor.executeCommand("end");
                break;
            case "choose": {
                Integer choice = optInt(params, "choice");
                if (choice == null) throw new InvalidCommandException("choose needs choice");
                ArrayList<String> choices = ChoiceScreenUtils.getCurrentChoiceList();
                if (choice < 1 || choice > choices.size()) {
                    throw new InvalidCommandException("choice " + choice + " out of range 1-" + choices.size());
                }
                description = "choose " + choiceLabel(choices.get(choice - 1));
                CommandExecutor.executeCommand("choose " + choice);
                break;
            }
            case "proceed":
            case "confirm":
                description = type;
                CommandExecutor.executeCommand("proceed");
                break;
            case "skip":
            case "cancel":
                description = type;
                CommandExecutor.executeCommand("cancel");
                break;
            case "select":
                description = executeSelectCards(params);
                break;
            case "use_potion": {
                int slot = requireSlot(params);
                AbstractPotion potion = AbstractDungeon.player.potions.get(slot - 1);
                description = "use_potion " + potion.name;
                StringBuilder command = new StringBuilder("potion use " + slot);
                Integer target = optInt(params, "target");
                if (target != null) {
                    List<AbstractMonster> monsters = StateRenderer.aliveMonsters();
                    checkTarget(target, monsters);
                    command.append(' ').append(target);
                    if (potion.targetRequired) description += " -> " + monsters.get(target - 1).name;
                }
                CommandExecutor.executeCommand(command.toString());
                break;
            }
            case "discard_potion": {
                int slot = requireSlot(params);
                description = "discard_potion " + AbstractDungeon.player.potions.get(slot - 1).name;
                CommandExecutor.executeCommand("potion discard " + slot);
                break;
            }
            default:
                throw new InvalidCommandException("Unknown action: " + type);
        }
        GameStateListener.registerCommandExecution();
        return description;
    }

    private static void requireAvailable(String type) throws InvalidCommandException {
        boolean available;
        switch (type) {
            case "play":
                available = CommandExecutor.isPlayCommandAvailable();
                break;
            case "end_turn":
                available = CommandExecutor.isEndCommandAvailable();
                break;
            case "choose":
                available = CommandExecutor.isChooseCommandAvailable();
                break;
            case "proceed":
            case "confirm":
                available = CommandExecutor.isConfirmCommandAvailable();
                break;
            case "skip":
            case "cancel":
                available = CommandExecutor.isCancelCommandAvailable();
                break;
            case "select":
                available = CommandExecutor.isInDungeon() &&
                    ChoiceScreenUtils.getCurrentChoiceType() == ChoiceScreenUtils.ChoiceType.HAND_SELECT;
                break;
            case "use_potion":
            case "discard_potion":
                available = CommandExecutor.isPotionCommandAvailable();
                break;
            default:
                available = true;
        }
        if (!available) {
            if ("play".equals(type) && CommandExecutor.isEndCommandAvailable()) {
                throw new InvalidCommandException("no card in hand can be played now (energy " +
                    com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount + ")");
            }
            String screen = CommandExecutor.isInDungeon() ? StateRenderer.screenLabel() : "MAIN_MENU";
            String actions = CommandExecutor.isInDungeon() ? StateRenderer.availableActions()
                : "start_game, continue_game, abandon_run (separate tools)";
            throw new InvalidCommandException(type + " is not available on " + screen + ". Available: " + actions);
        }
    }

    private static void checkTarget(int target, List<AbstractMonster> monsters) throws InvalidCommandException {
        if (target < 1 || target > monsters.size()) {
            throw new InvalidCommandException("target " + target + " out of range 1-" + monsters.size());
        }
    }

    private static int requireSlot(JsonObject params) throws InvalidCommandException {
        Integer slot = optInt(params, "slot");
        int slots = AbstractDungeon.player.potions.size();
        if (slot == null || slot < 1 || slot > slots) throw new InvalidCommandException("slot must be 1-" + slots);
        if (AbstractDungeon.player.potions.get(slot - 1) instanceof PotionSlot) {
            throw new InvalidCommandException("potion slot " + slot + " is empty");
        }
        return slot;
    }

    private static Integer optInt(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) return null;
        return params.get(key).getAsInt();
    }

    private static final java.util.regex.Pattern BRACKETED = java.util.regex.Pattern.compile("\\[\\s*([^\\]]+?)\\s*\\]");
    private static final java.util.regex.Pattern GOLD = java.util.regex.Pattern.compile("^(\\d+)\\(\\+(\\d+) bonus\\) \\w+");

    /** Turn ChoiceScreenUtils' internal choice strings into short labels: "Anger", "10 gold", "[Pray] ...". */
    static String choiceLabel(String raw) {
        java.util.regex.Matcher gold = GOLD.matcher(raw);
        if (gold.find()) return (Integer.parseInt(gold.group(1)) + Integer.parseInt(gold.group(2))) + " gold";
        java.util.regex.Matcher bracket = BRACKETED.matcher(raw);
        if (bracket.find()) {
            boolean wholeString = bracket.start() == 0 && bracket.end() == raw.trim().length();
            if (wholeString || raw.contains("] Cost:") || raw.contains(": [")) return bracket.group(1);
        }
        return shorten(raw.replaceFirst("^\\[\\s*(.*?)\\s*\\]", "[$1]"));
    }

    private static String shorten(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    /**
     * select: 'drop' selects the listed cards, 'keep' selects every other card. Auto-confirms.
     */
    private String executeSelectCards(JsonObject params) throws InvalidCommandException {
        ArrayList<AbstractCard> hand = AbstractDungeon.player.hand.group;
        if (hand.isEmpty()) throw new InvalidCommandException("No cards in hand to select");
        boolean hasDrop = params.has("drop") && params.get("drop").isJsonArray();
        boolean hasKeep = params.has("keep") && params.get("keep").isJsonArray();
        if (hasDrop == hasKeep) throw new InvalidCommandException("select needs exactly one of drop or keep");

        Set<Integer> chosen = new HashSet<>();
        for (JsonElement spec : params.getAsJsonArray(hasDrop ? "drop" : "keep")) {
            chosen.add(resolveCardIndex(spec, hand) - 1);
        }
        List<Integer> toSelect = new ArrayList<>();
        for (int i = 0; i < hand.size(); i++) {
            if (hasDrop == chosen.contains(i)) toSelect.add(i);
        }
        List<AbstractCard> cards = new ArrayList<>();
        for (int i : toSelect) cards.add(hand.get(i));
        for (AbstractCard card : cards) {
            ChoiceScreenUtils.makeHandSelectScreenChoice(hand.indexOf(card));
        }
        if (ChoiceScreenUtils.isConfirmButtonAvailable()) ChoiceScreenUtils.pressConfirmButton();
        List<String> names = new ArrayList<>();
        for (AbstractCard card : cards) names.add(card.name);
        return "select " + (names.isEmpty() ? "nothing" : String.join(", ", names));
    }

    /**
     * Resolve a card spec to a 1-based hand index: a number, "uuid:..." (batch-stable),
     * a display name ("Strike+"), or a card ID ("Strike_R").
     */
    private static int resolveCardIndex(JsonElement spec, List<AbstractCard> hand) throws InvalidCommandException {
        if (spec == null || spec.isJsonNull()) throw new InvalidCommandException("play needs card (hand number or name)");
        if (spec.isJsonPrimitive() && spec.getAsJsonPrimitive().isNumber()) {
            int index = spec.getAsInt();
            if (index < 1 || index > hand.size()) {
                throw new InvalidCommandException("card " + index + " out of range 1-" + hand.size());
            }
            return index;
        }
        String text = spec.getAsString();
        if (text.startsWith("uuid:")) {
            String uuid = text.substring(5);
            for (int i = 0; i < hand.size(); i++) {
                if (hand.get(i).uuid.toString().equals(uuid)) return i + 1;
            }
            throw new InvalidCommandException("that card is no longer in hand");
        }
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).name.equalsIgnoreCase(text)) return i + 1;
        }
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).cardID.equalsIgnoreCase(text)) return i + 1;
        }
        List<String> names = new ArrayList<>();
        for (AbstractCard c : hand) names.add(c.name);
        throw new InvalidCommandException("no '" + text + "' in hand " + names);
    }
}
