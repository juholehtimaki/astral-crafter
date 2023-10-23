package com.theplug.VardorvisPlugin.States;

import com.theplug.PaistiUtils.API.*;
import com.theplug.PaistiUtils.API.Loadouts.InventoryLoadout;
import com.theplug.PaistiUtils.API.Prayer.PPrayer;
import com.theplug.PaistiUtils.PathFinding.WebWalker;
import com.theplug.VardorvisPlugin.BankingMethod;
import com.theplug.VardorvisPlugin.VardorvisPlugin;
import com.theplug.VardorvisPlugin.VardorvisPluginConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

@Slf4j
public class RestockState implements State {
    static VardorvisPlugin plugin;
    VardorvisPluginConfig config;
    InventoryLoadout.InventoryLoadoutSetup loadout;
    private static int VARDORVIS_ENTRANCE_ROCKS = 49495;

    private static int fails = 0;

    public RestockState(VardorvisPlugin plugin, VardorvisPluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        loadout = InventoryLoadout.InventoryLoadoutSetup.deserializeFromString(config.gearLoadout());
    }

    @Override
    public String name() {
        return "Restocking";
    }

    @Override
    public boolean shouldExecuteState() {
        return !plugin.isInsideVardorvisArea();
    }

    @Override
    public void threadedOnGameTick() {

    }

    private static boolean handleRejuvenationPoolPoh() {
        var poolOfRefreshment = TileObjects.search().withName("Ornate pool of Rejuvenation").withAction("Drink").nearestToPlayer();
        if (poolOfRefreshment.isEmpty()) {
            Utility.sleepGaussian(1200, 1600);
            poolOfRefreshment = TileObjects.search().withName("Ornate pool of Rejuvenation").withAction("Drink").nearestToPlayer();
            if (poolOfRefreshment.isEmpty()) {
                return false;
            }
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            Interaction.clickTileObject(poolOfRefreshment.get(), "Drink");
            var restored = Utility.sleepUntilCondition(() -> {
                var _missingHp = Utility.getRealSkillLevel(Skill.HITPOINTS) - Utility.getBoostedSkillLevel(Skill.HITPOINTS);
                var _missingPrayer = Utility.getRealSkillLevel(Skill.PRAYER) - Utility.getBoostedSkillLevel(Skill.PRAYER);
                return _missingPrayer <= 0 && _missingHp <= 0;
            }, 10000, 300);
            if (restored) {
                Utility.sleepGaussian(600, 1100);
                return true;
            }
        }
        return false;
    }

    public boolean handleRestoreWithPool() {
        if (!House.isPlayerInsideHouse()) {
            if (!loadout.isSatisfied()) return false;
        }
        var missingHp = Utility.getRealSkillLevel(Skill.HITPOINTS) - Utility.getBoostedSkillLevel(Skill.HITPOINTS);
        var missingPrayer = Utility.getRealSkillLevel(Skill.PRAYER) - Utility.getBoostedSkillLevel(Skill.PRAYER);
        if (missingPrayer <= 0 && missingHp <= 0) {
            return false;
        }

        if (config.bankingMethod() == BankingMethod.HOUSE) {
            if (handleRejuvenationPoolPoh()) {
                return true;
            }
        }
        return false;
    }

    public boolean handleGenericBanking() {
        if (loadout.isSatisfied()) return false;

        if (!Bank.isNearBank()) {
            WebWalker.walkToNearestBank();
            Utility.sleepUntilCondition(Bank::isNearBank);
        }
        Bank.openBank();
        Utility.sleepUntilCondition(Bank::isOpen);
        int attempts = 3;
        boolean successfullyWithdrawn = false;
        for (int i = 0; i < attempts    ; i++) {
            if (loadout.handleWithdraw()) {
                successfullyWithdrawn = true;
                break;
            }
        }
        if (!successfullyWithdrawn) {
            Utility.sendGameMessage("Stopping because not enough supplies", "AutoVardorvis");
            plugin.stop();
            return false;
        }
        Bank.closeBank();
        return loadout.isSatisfied();
    }

    public boolean handleBanking() {
        if (loadout.isSatisfied()) return false;

        if (config.bankingMethod() == BankingMethod.HOUSE) {
            return handleGenericBanking();
        }
        return true;
    }


    public boolean handleToggleRun() {
        if (Walking.isRunEnabled()) return false;
        Walking.setRun(true);
        return true;
    }

    public boolean handleDisablePrayers() {
        boolean disabledPrayer = false;
        for (var prayer : PPrayer.values()) {
            if (prayer.isActive()) {
                disabledPrayer = true;
                prayer.setEnabled(false);
                Utility.sleepGaussian(100, 200);
            }
        }
        return disabledPrayer;
    }

    public boolean handleTravel() {
        var rocks = TileObjects.search().withId(VARDORVIS_ENTRANCE_ROCKS).nearestToPlayer();
        if (rocks.isEmpty() && !WebWalker.walkTo(VardorvisPlugin.NEAR_VARDORVIS_ENTRANCE.dx(Utility.random(-1, 1)).dy(Utility.random(-1, 1)))) {
            Utility.sendGameMessage("Failed to webwalk to Vardorvis", "AutoVardorvis");
            plugin.stop();
            return false;
        }

        rocks = TileObjects.search().withId(VARDORVIS_ENTRANCE_ROCKS).nearestToPlayer();
        if (rocks.isEmpty()) {
            return false;
        }

        Interaction.clickTileObject(rocks.get(), "Climb-over");
        return Utility.sleepUntilCondition(() -> plugin.isInsideVardorvisArea());
    }


    public boolean handleWalkToVardorvis() {
        if (!loadout.isSatisfied()) return false;

        if (config.bankingMethod() == BankingMethod.HOUSE) {
            if (handleTravel()){
                return false;
            }
        }
        return false;
    }

    @Override
    public void threadedLoop() {
        if (fails > 3) {
            Utility.sendGameMessage("Restock state failed action handling 3 times, stopping", "AutoVardorvis");
            plugin.stop();
            return;
        }
        if (handleToggleRun()) {
            Utility.sleepGaussian(600, 800);
            return;
        }
        if (handleDisablePrayers()) {
            Utility.sleepGaussian(200, 400);
            return;
        }
        if (handleRestoreWithPool()) {
            Utility.sleepGaussian(200, 300);
            Utility.sendGameMessage("Restored stats using pool", "AutoVardorvis");
            return;
        }
        if (handleBanking()) {
            if (config.stopAfterMinutes() > 0 && plugin.getRunTimeDuration().toMillis() / 60000 > config.stopAfterMinutes()) {
                Utility.sendGameMessage("Stopping after configured " + config.stopAfterMinutes() + " minutes", "AutoVardorvis");
                plugin.stop();
                return;
            }
        }
        if (handleWalkToVardorvis()){
            Utility.sleepGaussian(200, 400);
            return;
        }
    }
}