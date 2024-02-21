package org.magiclib.subsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

class MagicSubsystemsCombatPlugin : BaseEveryFrameCombatPlugin() {
    companion object {
        var displayAdditionalInfo = MagicSubsystemsManager.infoByDefault
        var infoHotkeyLastPressed = 0f
    }

    override fun init(engine: CombatEngineAPI?) {
        displayAdditionalInfo = MagicSubsystemsManager.infoByDefault
        infoHotkeyLastPressed = 0f
    }

    override fun advance(amount: Float, events: List<InputEventAPI>) {
        advanceSubsystems(amount)

        events
            .firstOrNull { it.isKeyUpEvent && it.eventValue == MagicSubsystemsManager.infoHotkey }
            ?.let {
                displayAdditionalInfo = !displayAdditionalInfo
                infoHotkeyLastPressed = Global.getCombatEngine().getTotalElapsedTime(true)
            }
    }

    override fun renderInUICoords(viewport: ViewportAPI?) {
        viewport?.let {
            drawSubsystemsUI(it)
        }
    }

    override fun renderInWorldCoords(viewport: ViewportAPI?) {
        viewport?.let {
            drawSubsystemsInWorld(it)
        }
    }

    fun advanceSubsystems(amount: Float) {
        val combatEngine = Global.getCombatEngine() ?: return
        for (ship in combatEngine.ships) {
            MagicSubsystemsManager.getSubsystemsForShipCopy(ship)?.forEach {
                if (!combatEngine.isPaused) {
                    it.advanceInternal(amount * ship.mutableStats.timeMult.modifiedValue)
                }

                it.advanceEveryFrame()
            }
        }
    }

    fun drawSubsystemsUI(viewport: ViewportAPI) {
        val combatEngine = Global.getCombatEngine() ?: return

        if (combatEngine.combatUI == null || combatEngine.combatUI.isShowingCommandUI || combatEngine.combatUI.isShowingDeploymentDialog || !combatEngine.isUIShowingHUD) {
            return
        }

        combatEngine.playerShip?.let { ship ->
            MagicSubsystemsManager.getSubsystemsForShipCopy(ship)?.let { subsystems ->
                CombatUI.hasRenderedSpatial = false

                val barHeight = 13f
                var totalBars = subsystems.sumOf { it.numHUDBars }
                if (displayAdditionalInfo) {
                    totalBars += subsystems.size
                }

                val rootVec = CombatUI.getSubsystemsRootLocation(ship, totalBars, barHeight)
                var lastVec = Vector2f(rootVec)
                MagicSubsystemsManager.sortSubsystems(subsystems)
                    .forEach { subsystem ->
                        val numBars = subsystem.numHUDBars + if (displayAdditionalInfo) 1 else 0
                        subsystem.drawHUDBar(viewport, rootVec, lastVec, displayAdditionalInfo)
                        lastVec = Vector2f.add(lastVec, Vector2f(0f, -barHeight * numBars), null)
                    }
                CombatUI.drawSubsystemsTitle(ship, true, rootVec)
            }
        }
    }

    fun drawSubsystemsInWorld(viewport: ViewportAPI) {
        for (ship in Global.getCombatEngine()?.ships.orEmpty()) {
            MagicSubsystemsManager.getSubsystemsForShipCopy(ship)?.forEach {
                it.renderWorld(viewport)
            }
        }
    }
}