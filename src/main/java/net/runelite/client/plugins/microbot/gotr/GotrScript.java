package net.runelite.client.plugins.microbot.gotr;

import com.google.common.collect.ImmutableList;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.gotr.data.CellType;
import net.runelite.client.plugins.microbot.gotr.data.GuardianPortalInfo;
import net.runelite.client.plugins.microbot.gotr.data.Mode;
import net.runelite.client.plugins.microbot.gotr.data.RuneType;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.Microbot.log;


public class GotrScript extends Script {

    public static long totalTime = 0;
    public static boolean shouldMineGuardianRemains = true;
    public static final String rewardPointRegex = "Total elemental energy:[^>]+>([\\d,]+).*Total catalytic energy:[^>]+>([\\d,]+).";
    public static final Pattern rewardPointPattern = Pattern.compile(rewardPointRegex);

    public static boolean isInMiniGame = false;
    public static boolean isFirstPortal = true;
    public static final int portalId = ObjectID.PORTAL_43729;
    public static final int greatGuardianId = 11403;
    public static final Map<Integer, GuardianPortalInfo> guardianPortalInfo = new HashMap<>();
    public static Optional<Instant> nextGameStart = Optional.empty();
    public static Optional<Instant> timeSincePortal = Optional.empty();
    public static final Set<GameObject> guardians = new HashSet<>();
    public static final List<GameObject> activeGuardianPortals = new ArrayList<>();
    public static NPC greatGuardian;
    public static int elementalRewardPoints;
    public static int catalyticRewardPoints;
    public static GotrState state;
    static GotrConfig config;
    String GUARDIAN_FRAGMENTS = "guardian fragments";
    String GUARDIAN_ESSENCE = "guardian essence";

    boolean initCheck = false;
    boolean optimizedEssenceLoop = false;

    static boolean useNpcContact = true;
    // Set once we've opened the lobby bank and found no abyssal pearls, so we stop popping
    // out to fetch one (which would otherwise oscillate exit/re-enter forever). Reset on run().
    static boolean outOfAbyssalPearls = false;

    // --- Humanized, non-stationary action-cooldown frequency ------------------------------------
    // A fixed actionCooldownChance is itself a flat statistical fingerprint: over a long session
    // an almost-constant fraction of actions pause. Real attention isn't stationary — you pause in
    // bursts, settle, and tire as the session wears on. So we don't pin the chance: cooldownBase is
    // a randomized per-session baseline (no two runs identical), cooldownChance is a bounded,
    // mean-reverting random walk re-sampled on a randomized multi-minute cadence (nextChanceDrift)
    // with a sub-linear fatigue drift keyed off sessionStart. See driftCooldownChance(). All reset
    // in run() so a restart doesn't inherit a stale curve.
    static double cooldownBase;
    static double cooldownChance;
    static Instant sessionStart;
    static Instant nextChanceDrift;

    // --- Per-round behavioral intent ------------------------------------------------------------
    // A bot that plays a flawless, identical, max-effort round every single game is the loudest
    // behavioral tell here — no timing jitter hides it. So each round rolls a discrete "mood" that
    // reshapes how it plays: a FOCUSED round is near-optimal, a CASUAL round mines/gathers a bit
    // less and moves on sooner, and an occasional DISTRACTED round settles for clearly less (and so
    // sometimes won't bother chasing the last portal). rollRoundIntent() freezes this round's
    // effective targets once at the round boundary, so play is internally consistent within a game
    // but varies game-to-game like a real player's attention. Reset in run(); rerolled per round.
    enum RoundIntent { FOCUSED, CASUAL, DISTRACTED }
    static RoundIntent roundIntent = RoundIntent.FOCUSED;
    static int effMaxFragments = 100;  // this round's fragment target (the mine→craft pivot)
    static int effMaxEssence = 20;     // this round's essence target (when to stop chasing portals)
    static int effPowerThreshold = 70; // this round's guardian-power pivot for the mining strategy

    // At most one tiny self-correcting "misclick" per round, and only on a small fraction of rounds
    // — humans misclick the ground and wander a step; a bot's pathing is always perfect. See
    // maybeMisstep(); both latches are (re)set in rollRoundIntent().
    static boolean misstepThisRound = false;
    static boolean misstepDoneThisRound = false;
    private final List<Integer> runeIds = ImmutableList.of(
            ItemID.NATURE_RUNE,
            ItemID.LAW_RUNE,
            ItemID.BODY_RUNE,
            ItemID.DUST_RUNE,
            ItemID.LAVA_RUNE,
            ItemID.STEAM_RUNE,
            ItemID.SMOKE_RUNE,
            ItemID.SOUL_RUNE,
            ItemID.WATER_RUNE,
            ItemID.AIR_RUNE,
            ItemID.EARTH_RUNE,
            ItemID.FIRE_RUNE,
            ItemID.MIND_RUNE,
            ItemID.CHAOS_RUNE,
            ItemID.DEATH_RUNE,
            ItemID.BLOOD_RUNE,
            ItemID.COSMIC_RUNE,
            ItemID.ASTRAL_RUNE,
            ItemID.MIST_RUNE,
            ItemID.MUD_RUNE,
            ItemID.WRATH_RUNE);

    private void initializeGuardianPortalInfo() {
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_AIR, new GuardianPortalInfo("AIR", 1, ItemID.AIR_RUNE, 26887, 4353, RuneType.ELEMENTAL, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_MIND, new GuardianPortalInfo("MIND", 2, ItemID.MIND_RUNE, 26891, 4354, RuneType.CATALYTIC, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_WATER, new GuardianPortalInfo("WATER", 5, ItemID.WATER_RUNE, 26888, 4355, RuneType.ELEMENTAL, CellType.MEDIUM, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_EARTH, new GuardianPortalInfo("EARTH", 9, ItemID.EARTH_RUNE, 26889, 4356, RuneType.ELEMENTAL, CellType.STRONG, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_FIRE, new GuardianPortalInfo("FIRE", 14, ItemID.FIRE_RUNE, 26890, 4357, RuneType.ELEMENTAL, CellType.OVERCHARGED, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_BODY, new GuardianPortalInfo("BODY", 20, ItemID.BODY_RUNE, 26895, 4358, RuneType.CATALYTIC, CellType.WEAK, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_COSMIC, new GuardianPortalInfo("COSMIC", 27, ItemID.COSMIC_RUNE, 26896, 4359, RuneType.CATALYTIC, CellType.MEDIUM, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.LOST_CITY.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_CHAOS, new GuardianPortalInfo("CHAOS", 35, ItemID.CHAOS_RUNE, 26892, 4360, RuneType.CATALYTIC, CellType.MEDIUM, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_NATURE, new GuardianPortalInfo("NATURE", 44, ItemID.NATURE_RUNE, 26897, 4361, RuneType.CATALYTIC, CellType.STRONG, QuestState.FINISHED));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_LAW, new GuardianPortalInfo("LAW", 54, ItemID.LAW_RUNE, 26898, 4362, RuneType.CATALYTIC, CellType.STRONG, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.TROLL_STRONGHOLD.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_DEATH, new GuardianPortalInfo("DEATH", 65, ItemID.DEATH_RUNE, 26893, 4363, RuneType.CATALYTIC, CellType.OVERCHARGED, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.MOURNINGS_END_PART_II.getState(Microbot.getClient())).orElse(null)));
        guardianPortalInfo.put(ObjectID.GUARDIAN_OF_BLOOD, new GuardianPortalInfo("BLOOD", 77, ItemID.BLOOD_RUNE, 26894, 4364, RuneType.CATALYTIC, CellType.OVERCHARGED, Microbot.getClientThread().runOnClientThreadOptional(() -> Quest.SINS_OF_THE_FATHER.getState(Microbot.getClient())).orElse(null)));
    }

    public boolean run(GotrConfig config) {
        this.config = config;
        // Static (and singleton-instance) state persists for the whole JVM session and leaks
        // across plugin disable/re-enable (see docs/PLUGIN_DEBUGGING_NOTES.md §5). Reset it here
        // so a restart behaves like a first start instead of inheriting a stale state machine.
        shouldMineGuardianRemains = true;
        isInMiniGame = false;
        isFirstPortal = true;
        state = null;
        nextGameStart = Optional.empty();
        timeSincePortal = Optional.empty();
        elementalRewardPoints = 0;
        catalyticRewardPoints = 0;
        useNpcContact = true;
        outOfAbyssalPearls = false;
        initCheck = false;
        optimizedEssenceLoop = false;
        guardians.clear();
        activeGuardianPortals.clear();
        greatGuardian = null;

        // Anti-ban: GOTR previously ran with no humanization beyond per-action gaussian sleeps —
        // no action cooldowns, micro-breaks, fatigue or attention-span simulation. Wire in the
        // framework's runecrafting profile (the always-on AntibanPlugin drives the actual pauses).
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyRunecraftingSetup();
        // The template defaults actionCooldownChance to 1.0 (a pause after EVERY action) — too
        // aggressive for GOTR's hard ~108s round timer, and a flat constant either way. Seed a
        // randomized per-session baseline instead; driftCooldownChance() then wanders it
        // non-linearly over the run so the pause frequency is never a detectable constant.
        sessionStart = Instant.now();
        cooldownBase = Rs2Random.truncatedNormalSample(0.07, 0.16, 0.11, 0.025);
        cooldownChance = cooldownBase;
        nextChanceDrift = null; // forces a fresh sample on the first loop tick
        Rs2AntibanSettings.actionCooldownChance = cooldownChance;

        // Seed an initial round mood; GotrPlugin rerolls it at each rift-active boundary.
        rollRoundIntent();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                // Let the cooldown frequency itself drift (non-linear, mean-reverting) so it's
                // never a flat detectable constant. Done before the guard so the rate keeps
                // advancing on wall-clock time even while we're idling out a cooldown.
                driftCooldownChance();

                // Anti-ban action cooldown: when the always-on AntibanPlugin has scheduled a
                // human-like pause, idle this tick instead of acting. AntibanPlugin.onGameTick
                // (alwaysOn) clears the flag once the cooldown elapses, so this can't deadlock.
                if (Rs2AntibanSettings.actionCooldownActive) return;

                long startTime = System.currentTimeMillis();

                if (!initCheck) {
                    initializeGuardianPortalInfo();
                    if (!Rs2Magic.isSpellbook(Rs2Spellbook.LUNAR)) {
                        Microbot.log("Lunar spellbook not found...disabling npc contact");
                        useNpcContact = false;
                    }
                    initCheck = true;
                }

                if (!Rs2Inventory.hasItem("pickaxe") && !Rs2Equipment.isWearing("pickaxe")) {
                    log("You need to have a pickaxe before you can participate in this minigame.");
                    return;
                }

                checkPouches(Rs2Inventory.anyPouchUnknown(), 1500, 300);

                //IS INSIDE THE MINIGAME
                int timeToStart = 0;
                if (nextGameStart.isPresent()) {
                    timeToStart = ((int) ChronoUnit.SECONDS.between(Instant.now(), nextGameStart.get()));
                }

                if (Rs2Inventory.hasItem("portal talisman") && !Rs2Inventory.hasItem(GUARDIAN_ESSENCE) && !Rs2Inventory.anyPouchFull()) {
                    Rs2Inventory.drop("portal talisman");
                    log("Dropping portal talisman...");
                }
                //Repair colossal pouch asap to avoid disintegrate completely
                if (Rs2Inventory.hasItem("colossal pouch") && Rs2Inventory.hasDegradedPouch()) {
                    if (!repairPouches()) {
                        return;
                    }
                }

                GotrScript.isInMiniGame = !isOutsideBarrier() && isInMainRegion();


                if (isInMiniGame) {

                    // Once we hold a pearl, repair the broken pouch with Cordelia before ANYTHING
                    // else — never let pre-mining for the next round (or any mining) run first.
                    if (repairBrokenPouchWithPearl()) return;

                    if (waitingForGameToStart(timeToStart)) return;
            

                    if (!Rs2Inventory.hasItem("Uncharged cell") && !isInLargeMine() && !isInHugeMine()) {
                        takeUnchargedCells();
                        return;
                    }

                    if (usePortal()) return;
                    //mine huge guardian remains
                    if (mineHugeGuardianRemain()) return;

                    if (powerUpGreatGuardian()) return;
                    if (repairCells()) return;


                    if (!shouldMineGuardianRemains) {
                        //Create fragments into whatever
                        if (isOutOfFragments()) return;

                        //deposit runes
                        if (depositRunesIntoPool()) return;

                        if (fillPouches()) {
                            craftGuardianEssences();
                            return;
                        }
                        if (!Rs2Inventory.isFull() && !optimizedEssenceLoop) {
                            if (leaveLargeMine()) return;

                            if (state == GotrState.CRAFT_GUARDIAN_ESSENCE && (Rs2Player.isAnimating() || Rs2Player.isMoving())) return;

                            if (craftGuardianEssences()) return;

                        } else if (Rs2Inventory.hasItem(GUARDIAN_ESSENCE)) {
                            if (leaveLargeMine()) return;
                            if (enterAltar()) return;
                        }
                    } else {
                        if (maybeMisstep()) return;
                        if (getGuardiansPower() > effPowerThreshold) {
                            if (Rs2Inventory.hasItemAmount(GUARDIAN_FRAGMENTS, Rs2Random.between(Rs2Inventory.emptySlotCount()+Rs2Inventory.getRemainingCapacityInPouches(), Rs2Inventory.emptySlotCount()+Rs2Inventory.getRemainingCapacityInPouches()+3))) {
                                shouldMineGuardianRemains = false;
                            }
                        } else {
                            if (Rs2Inventory.hasItemAmount(GUARDIAN_FRAGMENTS, effMaxFragments)) {
                                shouldMineGuardianRemains = false;
                            }
                        }
                        mineGuardianRemains();
                    }
                    return;
                }


                //IS NOT IN THE MINIGAME

                if (craftRunes()) return;

                if (repairBrokenPouchesAtBank()) return;

                if (enterMinigame()) return;

                if (waitForMinigameToStart()) return;


                long endTime = System.currentTimeMillis();
                totalTime = endTime - startTime;
                System.out.println("Total time for loop " + totalTime);

            } catch (Exception ex) {
                Microbot.log("Something went wrong in the GOTR Script: " + ex.getMessage() + ". If the script is stuck, please contact us on discord with this log.");
                ex.printStackTrace();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Advance the action-cooldown frequency as a bounded, mean-reverting random walk so the pause
     * rate is non-stationary and autocorrelated over minutes — never a flat, detectable constant.
     *
     * <p>The chance is held between drifts (a randomized ~45–210s) so it stays roughly steady for a
     * stretch like a human's attention, then shifts. The reversion target creeps up sub-linearly
     * with session fatigue (capped) so we pause a little more the longer we play, and every sample
     * is drawn from a truncated normal so it's gaussian-shaped and bounded rather than uniform.
     */
    private static void driftCooldownChance() {
        Instant now = Instant.now();
        if (nextChanceDrift != null && now.isBefore(nextChanceDrift)) {
            return; // hold the current rate so it's autocorrelated over minutes, not flat per-tick
        }
        // Fatigue: tendency to pause rises with session length — sub-linear (sqrt) and capped at
        // +5% so it never runs away and starves the round timer late in a long session.
        long mins = sessionStart == null ? 0 : ChronoUnit.MINUTES.between(sessionStart, now);
        double fatigue = Math.min(0.05, Math.sqrt(mins) * 0.006);
        double target = cooldownBase + fatigue;
        // Mean-reverting step: pull part-way toward the target by a randomized 25–75%, then sample
        // around that centre. Wanders over minutes but always reverts, so it can't pin or run off.
        double pull = Rs2Random.truncatedNormalSample(0.25, 0.75, 0.5, 0.12);
        double centre = cooldownChance + (target - cooldownChance) * pull;
        centre = Math.max(0.05, Math.min(0.23, centre)); // keep the sampler's mean strictly in-band
        cooldownChance = Rs2Random.truncatedNormalSample(0.04, 0.24, centre, 0.03);
        Rs2AntibanSettings.actionCooldownChance = cooldownChance;
        // Re-sample after an irregular ~45–210s, so even the cadence of change is non-uniform.
        nextChanceDrift = now.plusSeconds((long) Rs2Random.truncatedNormalSample(45.0, 210.0, 110.0, 45.0));
    }

    /**
     * Roll this round's play "mood" and freeze its effective targets. Called once per round (at the
     * rift-active boundary) so behaviour is consistent within a game but differs between games — the
     * behavioural antidote to grinding an identical, optimal round every time. Magnitudes are tuned
     * for MODERATE chaos: about half the rounds stay near-optimal, the rest ease off, and a slim
     * slice settles for clearly less — the one place we deliberately leave a few points on the table.
     */
    static void rollRoundIntent() {
        int fragBase = config == null ? 100 : config.maxFragmentAmount();
        int essBase  = config == null ? 20  : config.maxAmountEssence();
        int roll = Rs2Random.between(1, 100);
        if (roll <= 50) {
            roundIntent = RoundIntent.FOCUSED;
            effMaxFragments = (int) Math.round(fragBase * Rs2Random.truncatedNormalSample(0.96, 1.06, 1.0, 0.025));
            effMaxEssence   = essBase;
            effPowerThreshold = (int) Math.round(Rs2Random.truncatedNormalSample(68.0, 74.0, 71.0, 1.5));
        } else if (roll <= 84) {
            roundIntent = RoundIntent.CASUAL;
            effMaxFragments = (int) Math.round(fragBase * Rs2Random.truncatedNormalSample(0.80, 0.93, 0.87, 0.03));
            effMaxEssence   = (int) Math.round(essBase  * Rs2Random.truncatedNormalSample(0.85, 1.00, 0.93, 0.04));
            effPowerThreshold = (int) Math.round(Rs2Random.truncatedNormalSample(62.0, 70.0, 66.0, 2.0));
        } else {
            roundIntent = RoundIntent.DISTRACTED;
            effMaxFragments = (int) Math.round(fragBase * Rs2Random.truncatedNormalSample(0.72, 0.85, 0.78, 0.03));
            effMaxEssence   = (int) Math.round(essBase  * Rs2Random.truncatedNormalSample(0.70, 0.85, 0.77, 0.04));
            effPowerThreshold = (int) Math.round(Rs2Random.truncatedNormalSample(56.0, 66.0, 61.0, 2.5));
        }
        // Floors so a low roll can never starve the minigame loop (e.g. never gathering enough to craft).
        effMaxFragments = Math.max(20, effMaxFragments);
        effMaxEssence   = Math.max(8, effMaxEssence);
        // ~10% of rounds carry a single harmless misstep (see maybeMisstep); reset the per-round latch.
        misstepThisRound = Rs2Random.between(1, 100) <= 10;
        misstepDoneThisRound = false;
        log("Round intent: " + roundIntent + " (fragments=" + effMaxFragments
                + ", essence=" + effMaxEssence + ", powerPivot=" + effPowerThreshold + ")");
    }

    /**
     * Fire at most one tiny human "misclick" per round: a brief step toward a slightly wrong nearby
     * tile during the relaxed mining phase. The next loop tick re-issues the correct destination, so
     * it always self-corrects — and because it's a plain ground walk (never an object click) it can
     * physically never trigger a barrier/mine transition, so "wrong" here can't strand us.
     *
     * @return true if we took the misstep this tick (caller yields the tick); false otherwise.
     */
    private boolean maybeMisstep() {
        if (!misstepThisRound || misstepDoneThisRound) return false;
        // Only in the open central rift, idle, and not mid-portal-pursuit — the one relaxed window
        // where a stray step is harmless and reads as natural.
        if (isInLargeMine() || isInHugeMine() || isOutsideBarrier()) return false;
        if (Microbot.getClient().hasHintArrow()) return false;
        if (Rs2Player.isMoving() || Rs2Player.isAnimating()) return false;
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return false;

        int dx = Rs2Random.between(-3, 3);
        int dy = Rs2Random.between(-3, 3);
        if (dx == 0 && dy == 0) dx = 2; // ensure we actually move
        misstepDoneThisRound = true;
        log("(human) misclicked the ground — stepping the wrong way, will correct next tick");
        Rs2Walker.walkFastCanvas(new WorldPoint(me.getX() + dx, me.getY() + dy, me.getPlane()));
        return true;
    }

    private boolean waitingForGameToStart(int timeToStart) {
        if (isInHugeMine()) return false;

        if (getStartTimer() > Rs2Random.randomGaussian(35, Rs2Random.between(1, 5)) || getStartTimer() == -1 || timeToStart > 10) {

            // Cordelia (inside the rift) repairs pouches but needs an abyssal pearl. To avoid
            // permanently carrying pearls, on round end with a broken pouch and NO pearl, pop out
            // to the lobby bank; repairBrokenPouchesAtBank() grabs one and re-enters, then the
            // repairPouches() call below fixes the pouch with Cordelia in-game. If we already hold
            // a pearl, stay in and repair here.
            if (!useNpcContact && Rs2Inventory.hasDegradedPouch()
                    && !Rs2Inventory.hasItem(ItemID.ABYSSAL_PEARLS) && !outOfAbyssalPearls) {
                leaveMinigame();
                return true;
            }

            // A round just ended (or hasn't started yet) and this path runs instead of the
            // craft branch — bank any crafted runes into the pool before prepping for the next
            // game, so we never carry runes over.
            if (depositRunesIntoPool()) return true;

            // Only take cells if we don't already have them
            if (!Rs2Inventory.hasItem("Uncharged cell")) {
                // If in large mine and need cells, leave first
                if (isInLargeMine()) {
                    if (leaveLargeMine()) return true;
                }
                takeUnchargedCells();
                // Return to large mine if we were there before
                if (!isInLargeMine() && shouldMineGuardianRemains) {
                    if (Rs2Walker.walkTo(new WorldPoint(3632, 9503, 0), 20)) {
                        interactObject(ObjectID.RUBBLE_43724);
                        return true;
                    }
                }
            }

            repairPouches();
    
            if (!shouldMineGuardianRemains) return true;
    
            mineGuardianRemains();
            return true;
        }
        return false;
    }

    private boolean repairCells() {
        Rs2ItemModel cell = Rs2Inventory.get(CellType.PoweredCellList().stream().mapToInt(i -> i).toArray());
        if (cell == null || !isInMainRegion() || !isInMiniGame() || shouldMineGuardianRemains || isInLargeMine() || isInHugeMine()) {
            return false;
        }
        int cellTier = CellType.GetCellTier(cell.getId());
        // Identify the shield pylons by object id (CellType.GetShieldTier knows them all and
        // returns -1 for anything else). The previous filter matched on a name containing
        // "cell_tile", but the real pylon objects aren't named that, so the query always came
        // back empty — yet the method still returned true unconditionally below. That made the
        // main loop short-circuit at `if (repairCells()) return;` on every tick whenever a
        // powered cell was held, leaving the bot standing idle until the next game start. Match
        // by id, and only claim the tick when we actually place/use a cell.
        List<Rs2TileObjectModel> shieldCells = Microbot.getRs2TileObjectCache().query()
            .where(o -> CellType.GetShieldTier(o.getId()) >= 0)
            .toListOnClientThread();

        if (Rs2Inventory.hasItemAmount(GUARDIAN_ESSENCE, 10)) {
            for (Rs2TileObjectModel shieldCell : shieldCells) {
                if (CellType.GetShieldTier(shieldCell.getId()) < cellTier) {
                    Microbot.log("Upgrading power cell at " + shieldCell.getWorldLocation());
                    shieldCell.click("Place-cell");
                    sleepUntil(() -> !Rs2Player.isMoving());
                    return true;
                }
            }
        }
        Rs2TileObjectModel cellToUse = shieldCells.stream()
            .filter(o -> CellType.GetShieldTier(o.getId()) > 0)
            .findFirst().orElse(null);
        if (cellToUse != null) {
            cellToUse.click();
            log("Using cell with id " + cellToUse.getId());
            sleep(Rs2Random.randomGaussian(1000, 300));
            sleepUntil(() -> !Rs2Player.isMoving());
            return true;
        }
        // Nothing to place — don't pretend we handled the tick, or the loop will never craft.
        return false;
    }

    private boolean powerUpGreatGuardian() {
        if (Rs2Inventory.hasItem("guardian stone") && !shouldMineGuardianRemains && !isInLargeMine() && !isInHugeMine()) {
            state = GotrState.POWERING_UP;
            Microbot.getClientThread().invoke(() -> Microbot.getRs2NpcCache().query().withName("The great guardian").interact("power-up"));
            log("Powering up the great guardian...");
            sleepUntil(Rs2Player::isAnimating);
            sleep(Rs2Random.randomGaussian(Rs2Random.between(1000, 2000), Rs2Random.between(100, 300)));
            return true;
        }
        return false;
    }


    private void takeUnchargedCells() {

        if (!Rs2Inventory.hasItem("Uncharged cell")) {
            // Drop one guardian essence if inventory is full
            if (Rs2Inventory.isFull()) {
                if (Rs2Inventory.drop(ItemID.GUARDIAN_ESSENCE)) {
                    Microbot.log("Dropped one Guardian essence to make space for Uncharged cell");
                }
            }

            interactObject(ObjectID.UNCHARGED_CELLS_43732, "Take-10");
            log("Taking uncharged cells...");
            Rs2Player.waitForAnimation();
        }
    }

    private boolean usePortal() {
        if (!isInHugeMine() && Microbot.getClient().hasHintArrow() && Rs2Inventory.count() < effMaxEssence) {
            if (leaveLargeMine()) return true;
            Rs2Walker.walkFastCanvas(Microbot.getClient().getHintArrowPoint());
            sleepUntil(Rs2Player::isMoving);
            Microbot.getRs2TileObjectCache().query().within(Microbot.getClient().getHintArrowPoint(), 0).interact();
            log("Found a portal spawn...interacting with it...");
            Rs2Player.waitForWalking();
            sleepUntil(() -> isInHugeMine());
            sleepUntil(() -> getGuardiansPower() > 0);
            return true;
        }
        return false;
    }

    private boolean depositRunesIntoPool() {
        if (!config.shouldDepositRunes()
                || !Rs2Inventory.hasItem(runeIds.stream().mapToInt(i -> i).toArray())
                || isInLargeMine() || isInHugeMine()) {
            return false;
        }
        if (Rs2Player.isMoving()) return true;
        // Walk-first interaction, but only claim the tick when the pool actually exists — otherwise
        // return false so we never lock the loop standing around holding runes. Dropped the old
        // !isFull / !optimizedEssenceLoop guards: they skipped exactly the end-of-round case, where
        // a full inventory of crafted runes would otherwise never be deposited and carried into the
        // next round.
        Rs2TileObjectModel pool = Microbot.getRs2TileObjectCache().query().withId(ObjectID.DEPOSIT_POOL).nearest();
        if (pool == null) return false;
        if (interactObject(pool, null)) {
            log("Deposit runes into pool...");
            sleep(600, 2400);
        }
        return true;
    }

    private boolean enterAltar() {
        GameObject availableAltar = getAvailableAltars().stream().findFirst().orElse(null);
        if (availableAltar != null && !Rs2Player.isMoving()) {
            log("Entering with altar " + availableAltar.getId());
            Rs2GameObject.interact(availableAltar);
            state = GotrState.ENTER_ALTAR;
            Global.sleepUntil(() -> !isInMainRegion() || !Objects.equals(getAvailableAltars().stream().findFirst().orElse(null), availableAltar), 5000);
            sleep(Rs2Random.randomGaussian(1000, 300));

            return true;
        }
        return false;
    }

    private boolean craftGuardianEssences() {
        if (interactObject(ObjectID.WORKBENCH_43754)) {
            state = GotrState.CRAFT_GUARDIAN_ESSENCE;
            sleep(Rs2Random.randomGaussian(Rs2Random.between(600, 900), Rs2Random.between(150, 300)));
            log("Crafting guardian essences...");
            Rs2Antiban.actionCooldown();
            Rs2Antiban.takeMicroBreakByChance();
            return true;
        }
       return false;
    }

    private boolean leaveLargeMine() {
        if (isInLargeMine()) {
            interactObject(ObjectID.RUBBLE_43726);
            Rs2Player.waitForAnimation();
            log("Leaving large mine...");
            state = GotrState.LEAVING_LARGE_MINE;
            return true;
        }
        return false;
    }

    private boolean fillPouches() {
        if (Rs2Inventory.isFull() && Rs2Inventory.anyPouchEmpty() && getGuardiansPower() < 90) {
            Rs2Inventory.fillPouches();
            sleep(Rs2Random.randomGaussian(600, 300));
            return true;
        }
        return false;
    }

    private boolean isOutOfFragments() {
        if ((!Rs2Inventory.hasItem(GUARDIAN_FRAGMENTS) && !Rs2Inventory.isFull()) || (getTimeSincePortal() > 85 && !Rs2Inventory.hasItem(GUARDIAN_ESSENCE))) {
            shouldMineGuardianRemains = true;
            if(!Rs2Inventory.hasItem(GUARDIAN_FRAGMENTS))
                log("Memorize that we no longer have guardian fragments...");

            return true;
        }
        shouldMineGuardianRemains = false;
        return false;
    }

    private boolean craftRunes() {
        if (!isInMainRegion() && isInMiniGame()) {
            Rs2TileObjectModel rcAltar = findRcAltar();
            if (rcAltar != null) {
                if (Rs2Player.isMoving()) return true;
                if (Rs2Inventory.anyPouchFull() && !Rs2Inventory.isFull()) {
                    Rs2Inventory.emptyPouches();
                    Rs2Inventory.waitForInventoryChanges(5000);
                    sleep(Rs2Random.randomGaussian(350, 150));
                }
                if (Rs2Inventory.hasItem(GUARDIAN_ESSENCE)) {
                    state = GotrState.CRAFTING_RUNES;
                    optimizedEssenceLoop = false;
                    interactObject(rcAltar, null);
                    log("Crafting runes on altar " + rcAltar.getId());
                    sleep(Rs2Random.randomGaussian(Rs2Random.between(1000, 1500), 300));
                    Rs2Antiban.actionCooldown();
                    Rs2Antiban.takeMicroBreakByChance();
                } else if (!Rs2Player.isMoving()) {
                    state = GotrState.LEAVING_ALTAR;
                    Rs2TileObjectModel rcPortal = findPortalToLeaveAltar();
                    if (interactObject(rcPortal, null)) {
                        log("Leaving the altar...");
                        sleepUntilTrue(GotrScript::isInMainRegion,100,10000);
                        sleep(Rs2Random.randomGaussian(750, 150));
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean waitForMinigameToStart() {
        if (!isInMainRegion()) {
            Rs2TileObjectModel rcPortal = findPortalToLeaveAltar();
            if (rcPortal != null && interactObject(rcPortal, null)) {
                state = GotrState.LEAVING_ALTAR;
                return true;
            }
        }
        resetPlugin();
        if (state != GotrState.WAITING) {
            state = GotrState.WAITING;
            log("Make sure to start the script near the minigame barrier.");
            interactObject(ObjectID.BARRIER_43849, "Peek");
        }
        return state == GotrState.WAITING;
    }

    private static boolean enterMinigame() {
        if (interactObject(ObjectID.BARRIER_43700, "quick-pass")) {
            Rs2Player.waitForWalking();
            state = GotrState.ENTER_GAME;
            GotrScript.shouldMineGuardianRemains = true;
            log("Entering game...");
            return true;
        }
        return false;
    }

    private void checkPouches(boolean anyPouchUnknown, int mean, int stddev) {
        if (anyPouchUnknown) {
            Rs2Inventory.checkPouches();
            sleep(Rs2Random.randomGaussian(mean, stddev));
        }
    }

    private boolean mineHugeGuardianRemain() {
        if (isInHugeMine()) {
            if (getGuardiansPower() == 0) {
                repairPouches();
                leaveHugeMine();
                optimizedEssenceLoop = false;
                return false;
            }
            if (!Rs2Inventory.isFull()) {
                if (!Rs2Player.isAnimating()) {
                    interactObject(ObjectID.HUGE_GUARDIAN_REMAINS);
                    Rs2Player.waitForAnimation();
                    if (!Rs2Player.isAnimating())
                        interactObject(ObjectID.HUGE_GUARDIAN_REMAINS);
                }
            } else {
                if (Rs2Inventory.allPouchesFull()) {
                    if(Rs2Inventory.hasItem("guardian stone"))
                        optimizedEssenceLoop = true;
                    leaveHugeMine();
                } else {
                    Rs2Inventory.fillPouches();
                    sleep(Rs2Random.randomGaussian(Rs2Random.between(600, 1200), Rs2Random.between(100, 300)));
                    if (!Rs2Inventory.isFull()) {
                        interactObject(ObjectID.HUGE_GUARDIAN_REMAINS);
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void mineGuardianRemains() {
        if (Microbot.getClient().hasHintArrow())
            return;
        if (Rs2Inventory.isFull()) {
            shouldMineGuardianRemains = false;
            return;
        }
        state = GotrState.MINE_LARGE_GUARDIAN_REMAINS;
        if (isInHugeMine()) {
            leaveHugeMine();
            return;
        }
        if (Rs2Player.getSkillRequirement(Skill.AGILITY, 56) && getTimeSincePortal() < 85 && !Rs2Inventory.hasItem(GUARDIAN_ESSENCE)) {
            if (!isInLargeMine() && !isInHugeMine() && (!Rs2Inventory.hasItem(GUARDIAN_FRAGMENTS) || getStartTimer() == -1)) {
                if (Rs2Walker.walkTo(new WorldPoint(3632, 9503, 0), 20)) {
                    log("Traveling to large mine...");
                    interactObject(ObjectID.RUBBLE_43724);
                    if (sleepUntil(Rs2Player::isAnimating)) {
                        sleepUntil(GotrScript::isInLargeMine);
                        if (isInLargeMine()) {
                            sleep(Rs2Random.randomGaussian(Rs2Random.between(2000, 2400), Rs2Random.between(100, 300)));
                            log("Interacting with large guardian remains...");
                            interactObject(ObjectID.LARGE_GUARDIAN_REMAINS);
                            sleepGaussian(1200, 150);
                        }
                    }
                }
                sleepGaussian(600, 150);
            } else {
                if (!Rs2Player.isAnimating() && getStartTimer() != -1) {
                    if (Rs2Equipment.isWearing("dragon pickaxe")) {
                        Rs2Combat.setSpecState(true, 1000);
                    }
                    checkPouches(Rs2Random.between(1, 20) == 2, Rs2Random.between(100, 600), Rs2Random.between(100, 300));

                    repairPouches();
                    interactObject(ObjectID.LARGE_GUARDIAN_REMAINS);
                    sleepGaussian(1200, 150);
                    Rs2Antiban.actionCooldown();
                    Rs2Antiban.takeMicroBreakByChance();
                }
            }
        } else {
            //guardian parts
            if (!Rs2Player.isAnimating() && getStartTimer() != -1) {
                if(isInLargeMine()) {
                    leaveLargeMine();
                }
                if (Rs2Equipment.isWearing("dragon pickaxe")) {
                    Rs2Combat.setSpecState(true, 1000);
                }
                repairPouches();
                interactObject(ObjectID.GUARDIAN_PARTS_43716);
                sleepGaussian(1200, 150);
                Rs2Antiban.actionCooldown();
                Rs2Antiban.takeMicroBreakByChance();
                // we can assume that if the player is mining within the startTimer range, he will get enough guardian remains for the game
                shouldMineGuardianRemains = false;
            }
        }
    }

    private void leaveHugeMine() {
        interactObject(38044);
        log("Leave huge mine...");
        Global.sleepUntil(() -> !isInHugeMine(), 5000);

    }

    private static boolean repairPouches() {
        if (!useNpcContact) {
            repairWithCordelia();
            return true;
        }
        if (Rs2Inventory.hasDegradedPouch()) {
            return Rs2Magic.repairPouchesWithLunar();
        }
        return false;
    }

    /**
     * Repair pouch by talking to cordelia
     * make sure to have the repair unlocked for 25 pearls
     */
    private static void repairWithCordelia() {
        if (!Rs2Inventory.hasDegradedPouch()) return;
        if (!Rs2Inventory.hasItem(ItemID.ABYSSAL_PEARLS)) return;
        Rs2NpcModel pouchRepairNpc = Microbot.getRs2NpcCache().query().withId(NpcID.APPRENTICE_CORDELIA_12180).nearest();
        if (pouchRepairNpc == null) return;
        if (!Rs2Npc.hasAction(pouchRepairNpc.getId(), "Repair")) return;
        if (!Rs2Npc.canWalkTo(pouchRepairNpc.getNpc(), 10)) return;
        if (!pouchRepairNpc.click("Repair")) return;

        Microbot.log("Repairing pouches...");

        Global.sleepUntil(() -> {
            Rs2Dialogue.clickContinue();
            return !Rs2Inventory.hasDegradedPouch();
        }, 10000);

    }

    /**
     * Fetches a single abyssal pearl from the lobby bank when a pouch is broken, so we don't
     * permanently carry pearls (and waste an inventory slot). The actual repair is done by
     * Cordelia INSIDE the rift (see repairWithCordelia / repairPouches), so once we hold a pearl
     * this returns false and the caller's enterMinigame() takes us back in to repair. One action
     * per tick.
     *
     * @return true while we still need to bank a pearl (caller must not re-enter this tick);
     *         false when we already hold a pearl, the bank is out, or it's not our job.
     */
    private static boolean repairBrokenPouchesAtBank() {
        // Already hold a pearl (-> go back in and repair), Lunar path, no broken pouch, or the
        // bank is known-empty: nothing to fetch, let enterMinigame() proceed.
        if (useNpcContact || !Rs2Inventory.hasDegradedPouch()
                || Rs2Inventory.hasItem(ItemID.ABYSSAL_PEARLS) || outOfAbyssalPearls) {
            return false;
        }

        // Get to the lobby bank (finish leaving the rift if leaveMinigame() needed retries).
        if (!isOutsideBarrier()) {
            leaveMinigame();
            return true;
        }
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.walkToBankAndUseBank(BankLocation.GUARDIANS_OF_THE_RIFT);
            return true;
        }
        // Bank open — its contents are authoritative now.
        if (!Rs2Bank.hasItem(ItemID.ABYSSAL_PEARLS)) {
            log("No abyssal pearls in bank to repair pouch; continuing without repair.");
            outOfAbyssalPearls = true; // stop popping out until the script is restarted
            Rs2Bank.closeBank();
            return false;
        }
        Rs2Bank.withdrawOne(ItemID.ABYSSAL_PEARLS);
        Rs2Inventory.waitForInventoryChanges(2000);
        Rs2Bank.closeBank();
        return true; // next tick we hold a pearl -> returns false -> enterMinigame() re-enters
    }

    /**
     * Top-priority pouch repair: once we hold an abyssal pearl and a pouch is degraded (Cordelia
     * path), fix it before doing anything else — especially before any mining. Cordelia is in the
     * central rift and repairWithCordelia() only acts within ~10 tiles, so leave any mine and walk
     * to her first. One action per tick.
     *
     * @return true while the repair is still pending (caller must not mine/continue this tick);
     *         false when it's not our job or the pouch is already fixed.
     */
    private boolean repairBrokenPouchWithPearl() {
        if (useNpcContact || !Rs2Inventory.hasDegradedPouch() || !Rs2Inventory.hasItem(ItemID.ABYSSAL_PEARLS)) {
            return false;
        }
        // Cordelia is in the central rift — get out of the mines so we can reach her.
        if (isInLargeMine()) { leaveLargeMine(); return true; }
        if (isInHugeMine())  { leaveHugeMine();  return true; }

        Rs2NpcModel cordelia = Microbot.getRs2NpcCache().query().withId(NpcID.APPRENTICE_CORDELIA_12180).nearest();
        if (cordelia == null) return false; // not loaded from here — fall through (we're not mining yet)
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me != null && cordelia.getWorldLocation().distanceTo(me) > 9) {
            Rs2Walker.walkTo(cordelia.getWorldLocation(), 6); // repairWithCordelia only acts <=10 tiles
            return true;
        }
        repairWithCordelia();
        return true;
    }

    @Override
    public void shutdown() {
        state = null;
        super.shutdown();
    }

    public static boolean isOutsideBarrier() {
        int outsideBarrierY = 9482;
        return Rs2Player.getWorldLocation().getY() <= outsideBarrierY
                && Rs2Player.getWorldLocation().getRegionID() == 14484;
    }

    public  static boolean isInLargeMine() {
        int largeMineX = 3637;
        return Rs2Player.getWorldLocation().getRegionID() == 14484
                && Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getX()) >= largeMineX;
    }

    public  boolean isInHugeMine() {
        int hugeMineX = 3594;
        return Rs2Player.getWorldLocation().getRegionID() == 14484
                && Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getX()) <= hugeMineX;
    }

    public static boolean isGuardianPortal(GameObject gameObject) {
        return guardianPortalInfo.containsKey(gameObject.getId());
    }

    public ItemManager getItemManager() {
        return Microbot.getItemManager();
    }

    public boolean isInMiniGame() {
        int parentWidgetId = 48889857;
        Widget elementalRuneWidget = Microbot.getClient().getWidget(parentWidgetId);
        return elementalRuneWidget != null;
    }

    public static boolean isInMainRegion() {
        return Rs2Player.getWorldLocation().getRegionID() == 14484;
    }

    public static int getStartTimer() {
        Widget timerWidget = Rs2Widget.getWidget(48889861);
        if (timerWidget != null) {
            String timer = timerWidget.getText();
            if (timer == null) return -1;
            // Split the timer string into minutes and seconds
            String[] timeParts = timer.split(":");

            // Ensure there are two parts (minutes and seconds)
            if (timeParts.length == 2) {
                int minutes = Integer.parseInt(timeParts[0]);
                int seconds = Integer.parseInt(timeParts[1]);

                // Convert the timer to total seconds
                int totalSeconds = (minutes * 60) + seconds;
                return totalSeconds;
            }
        }
        return -1;
    }

    public static int getTimeSincePortal() {
        if(getStartTimer() == -1) {
            return -1;
        }
        int firstPortalTimeAdjustment = isFirstPortal ? 40 : 0;
        return timeSincePortal.map(instant -> (int) ChronoUnit.SECONDS.between(instant, Instant.now())-firstPortalTimeAdjustment).orElse(-1);

    }

    public static List<GameObject> getAvailableAltars() {
        int elementalPoints = elementalRewardPoints;
        int catalyticPoints = catalyticRewardPoints;
        List<GameObject> availableAltars = Rs2GameObject.getGameObjects().stream()
                .filter(x -> {

                    if (!guardianPortalInfo.containsKey(x.getId())) return false;

                    GuardianPortalInfo portalInfo = GotrScript.guardianPortalInfo.get(x.getId());

                    if (portalInfo.getRequiredLevel()
                            > Microbot.getClient().getBoostedSkillLevel(Skill.RUNECRAFT)) {
                        Microbot.log("Filtered altar " + portalInfo.getName() + " – insufficient RC level");
                        return false;
                    }
                    if (portalInfo.getQuestState() != QuestState.FINISHED) {
                        Microbot.log("Filtered altar " + portalInfo.getName() + " – quest not complete");
                        return false;
                    }

                    // Active altars animate (DynamicObject renderable); inactive ones carry a
                    // static model. Blindly casting threw ClassCastException ("ds cannot be cast
                    // to DynamicObject") on the static ones, collapsing the whole stream and
                    // sporadically breaking altar entry — guard the cast first.
                    if (!(x.getRenderable() instanceof DynamicObject)) {
                        return false;
                    }
                    DynamicObject altar = (DynamicObject) x.getRenderable();
                    if (altar.getAnimation() == null || altar.getAnimation().getId() != 9363) {
                        return false;
                    }
                    Microbot.log("Adding " + portalInfo.getName() + " to list of available altars");
                    return true;

                })
                .collect(Collectors.toList());

        Microbot.log("Found " + availableAltars.size() + " active altars after filtering.");

        if (config.Mode() == Mode.POINTS) {
            // Sort by strongest → weakest CellType; if equal, fall back to balancing points
            Microbot.log("Sorting by CellType (strongest→weakest) for POINTS mode...");
            return availableAltars.stream()
                    .sorted(
                            Comparator.<GameObject>comparingInt(
                                            o -> GotrScript.guardianPortalInfo.get(o.getId()).getCellType().ordinal()
                                    ).reversed()
                                    .thenComparingInt(o -> {
                                        RuneType rt = GotrScript.guardianPortalInfo.get(o.getId()).getRuneType();
                                        boolean preferElemental = elementalPoints < catalyticPoints;
                                        return ((preferElemental && rt == RuneType.ELEMENTAL) ||
                                                (!preferElemental && rt == RuneType.CATALYTIC)) ? 0 : 1;
                                    })
                    )
                    .peek(o -> Microbot.log("Altar " +
                            GotrScript.guardianPortalInfo.get(o.getId()).getName() + " – " +
                            GotrScript.guardianPortalInfo.get(o.getId()).getCellType()))
                    .collect(Collectors.toList());
        }

        if ((config.Mode() == Mode.BALANCED && elementalPoints < catalyticPoints) || config.Mode() == Mode.ELEMENTAL) {
            Microbot.log(elementalPoints < catalyticPoints
                    ? "We have " + elementalPoints + " elemental points, looking for elemental altar..."
                    : "We have " + catalyticPoints +" catalytic points, looking for catalytic altar...");

            Microbot.log("Sorting for BALANCED/ELEMENTAL mode (" +
                    (elementalPoints < catalyticPoints ? "Elemental priority" : "Catalytic priority") + ")");

            return availableAltars.stream()
                    .sorted(
                            (elementalPoints < catalyticPoints)
                                    ? Comparator.comparingInt(TileObject::getId)
                                    : Comparator.comparingInt(TileObject::getId).reversed()
                    )
                    .collect(Collectors.toList());
        }
        Microbot.log("Returning unsorted altars (default mode).");
        return availableAltars;
    }

    private int getGuardiansPower() {
        Widget pWidget = Rs2Widget.getWidget(48889874);
        if (pWidget == null) {
            return 0;
        }

        Matcher matcher = Pattern.compile("(\\d+)%").matcher(pWidget.getText());

        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    public static void resetPlugin() {
        guardians.clear();
        activeGuardianPortals.clear();
        greatGuardian = null;
        Microbot.getClient().clearHintArrow();
    }

    /**
     * Walk-first object interaction.
     *
     * <p>The migrated Queryable API ({@code cache.query().interact(id, action)}) resolves
     * {@code nearestReachable()} and clicks at the player's current tile — it does NOT walk into
     * range. Legacy {@code Rs2GameObject.interact(id, action)} auto-walked when the target was
     * more than 51 tiles away. After the query-API migration GOTR lost that auto-walk, so any
     * interaction issued while out of range silently no-ops every tick and the bot just stands
     * there (see docs/PLUGIN_DEBUGGING_NOTES.md §3). This restores the legacy behaviour: web-walk
     * when far, hand off to the game's click-to-walk once close.
     */
    private static boolean interactObject(int id) {
        return interactObject(id, null);
    }

    private static boolean interactObject(int id, String action) {
        return interactObject(Microbot.getRs2TileObjectCache().query().withId(id).nearest(), action);
    }

    private static boolean interactObject(Rs2TileObjectModel obj, String action) {
        if (obj == null) return false;
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        WorldPoint objLoc = obj.getWorldLocation();
        if (playerLoc != null && objLoc != null && playerLoc.distanceTo(objLoc) > 51) {
            log("Object " + obj.getId() + " is " + playerLoc.distanceTo(objLoc) + " tiles away, walking into range...");
            Rs2Walker.walkTo(objLoc);
            return false;
        }
        // In click range: drop any lingering web-walk target so the game's click-to-walk drives
        // the final approach, then interact.
        Rs2Walker.setTarget(null);
        return (action == null || action.isEmpty()) ? obj.click() : obj.click(action);
    }

    public static Rs2TileObjectModel findRcAltar() {
        return Microbot.getRs2TileObjectCache().query().withIds(
                ObjectID.ALTAR_34760, ObjectID.ALTAR_34761, ObjectID.ALTAR_34762, ObjectID.ALTAR_34763, ObjectID.ALTAR_34764,
                ObjectID.ALTAR_34765, ObjectID.ALTAR_34766, ObjectID.ALTAR_34767, ObjectID.ALTAR_34768, ObjectID.ALTAR_34769, ObjectID.ALTAR_34770,
                ObjectID.ALTAR_34771, ObjectID.ALTAR_34772, ObjectID.ALTAR_43479).nearest();
    }

    public static Rs2TileObjectModel findPortalToLeaveAltar() {
        return Microbot.getRs2TileObjectCache().query().withIds(
                ObjectID.PORTAL_34748, ObjectID.PORTAL_34749, ObjectID.PORTAL_34750, ObjectID.PORTAL_34751, ObjectID.PORTAL_34752,
                ObjectID.PORTAL_34753, ObjectID.PORTAL_34754, ObjectID.PORTAL_34755, ObjectID.PORTAL_34756, ObjectID.PORTAL_34757, ObjectID.PORTAL_34758,
                ObjectID.PORTAL_34758, ObjectID.PORTAL_34759, ObjectID.PORTAL_43478).nearest();
    }
    public static boolean leaveMinigame() {
        GotrScript.isInMiniGame = !isOutsideBarrier() && isInMainRegion(); 
        if (!isInMiniGame) {
            return true;    // Already outside the minigame, successfully left     
        }
        if(isInLargeMine()) {
            interactObject(ObjectID.RUBBLE_43726);
            Rs2Player.waitForAnimation();
            sleepUntil(()-> !isInLargeMine());
            if (isInLargeMine()){
                log("Failed to leave large mine, retrying...");
                return false;
            }

        }
        interactObject(ObjectID.BARRIER_43700, "quick-pass");
        Rs2Player.waitForWalking();
        sleepUntil( ()-> {return !(!isOutsideBarrier() && isInMainRegion());}, 200);
        GotrScript.isInMiniGame  = !isOutsideBarrier() && isInMainRegion();
        return !GotrScript.isInMiniGame;// Successfully left the minigame
    }
}
