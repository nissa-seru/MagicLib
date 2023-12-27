package org.magiclib.paintjobs

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.dark.shaders.util.ShaderLib
import org.json.JSONArray
import org.json.JSONObject
import org.magiclib.LunaWrapper
import org.magiclib.LunaWrapperSettingsListener
import org.magiclib.Magic_modPlugin
import org.magiclib.kotlin.toStringList
import org.magiclib.util.MagicMisc
import org.magiclib.util.MagicVariables

object MagicPaintjobManager {
    private val logger = Global.getLogger(MagicPaintjobManager::class.java)
    private const val commonFilename = "magic_paintjobs.json"
    const val specsFilename = "data/config/magic_paintjobs.csv"
    private val jsonObjectKey = "unlockedPaintjobs"
    private val unlockedPaintjobsInner = mutableSetOf<String>()
    private val paintjobsInner = mutableListOf<MagicPaintjobSpec>()
    private const val isIntelImportantMemKey = "\$magiclib_isPaintjobIntelImportant"

    const val PJTAG_PERMA_PJ = "MagicLib_PermanentPJ"
    const val PJTAG_SHINY = "MagicLib_ShinyPJ"

    @JvmStatic
    var isEnabled = true

    @JvmStatic
    val unlockedPaintjobIds: List<String>
        get() = unlockedPaintjobsInner.toList()

    @JvmStatic
    val unlockedPaintjobs: List<MagicPaintjobSpec>
        get() = unlockedPaintjobsInner.mapNotNull { id -> paintjobsInner.firstOrNull { it.id == id } }

    /**
     * Returns the paintjobs that are available to the player.
     * Shiny paintjobs should not be shown to the player and cannot be manually applied or unlocked.
     */
    @JvmStatic
    @JvmOverloads
    fun getPaintjobs(includeShiny: Boolean = false): Set<MagicPaintjobSpec> {
        return paintjobsInner.filter { it.isShiny == includeShiny }.toSet()
    }

    init {
        initIntel()
    }

    // make java users feel better
    @JvmStatic
    fun getInstance() = this

    @JvmStatic
    fun onApplicationLoad() {
        // Set up LunaLib settings.
        if (Global.getSettings().modManager.isModEnabled("lunalib")) {
            isEnabled = LunaWrapper.getBoolean(MagicVariables.MAGICLIB_ID, "magiclib_enablePaintjobs") ?: true

            // Add settings listener.
            LunaWrapper.addSettingsListener(object : LunaWrapperSettingsListener {
                override fun settingsChanged(modID: String) {
                    val lunaIsEnabled =
                        LunaWrapper.getBoolean(MagicVariables.MAGICLIB_ID, "magiclib_enablePaintjobs") ?: true

                    if (isEnabled != lunaIsEnabled) {
                        isEnabled = lunaIsEnabled
                        if (isEnabled) {
                            initIntel()
                        } else {
                            removeIntel()
                        }
                    }
                }
            })
        }

        paintjobsInner.addAll(loadPaintjobs().values)
    }

    @JvmStatic
    fun onGameLoad() {
        loadUnlockedPaintjobs()
        initIntel()

        Global.getSector().addTransientListener(MagicPaintjobShinyAdder())
    }


    @JvmStatic
    fun loadPaintjobs(): Map<String, MagicPaintjobSpec> {
        val newSpecs: MutableList<MagicPaintjobSpec> = mutableListOf()

        for (mod in Global.getSettings().modManager.enabledModsCopy) {
            var modCsv: JSONArray? = null
            try {
                modCsv = Global.getSettings().loadCSV(specsFilename, mod.id)
            } catch (e: java.lang.Exception) {
                if (e is RuntimeException && e.message!!.contains("not found in")) {
                    // Swallow exceptions caused by the mod not having pjs.
                } else {
                    logger.warn(
                        "Unable to load paintjobs in " + mod.id + " by " + MagicMisc.takeFirst(
                            mod.author,
                            50
                        ) + " from file " + specsFilename, e
                    )
                }
            }
            if (modCsv == null) continue

            logger.info(modCsv)

            for (i in 0 until modCsv.length()) {
                var id: String? = null
                try {
                    val item = modCsv.getJSONObject(i)
                    id = item.getString("id").trim()
                    val hullId = item.getString("hullId").trim()
                    val name = item.getString("name").trim()
                    val description = item.getString("description").trim()
                    val unlockConditions = item.getString("unlockConditions").trim()
                    val unlockedAutomatically = item.optBoolean("unlockedAutomatically", true)
                    val spriteId = item.getString("spriteId").trim()
                    val tags = item.optString("tags", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

                    var skip = false
                    for (paintjobSpec in newSpecs) {
                        if (paintjobSpec.id == id) {
                            skip = true
                            logger.warn(
                                String.format(
                                    "Paintjob with id %s in mod %s already exists in mod %s, skipping.",
                                    id, mod.id, paintjobSpec.modId
                                )
                            )
                            break
                        }
                    }

                    if (id.isBlank()) {
                        logger.warn("Paintjob #$i in ${mod.id} by '${mod.author}' has no id, skipping.")
                        skip = true
                    }
                    if (hullId.isBlank()) {
                        logger.warn("Paintjob #$i in ${mod.id} by '${mod.author}' has no hullId, skipping.")
                        skip = true
                    }
                    if (name.isBlank()) {
                        logger.warn("Paintjob #$i in ${mod.id} by '${mod.author}' has no name, skipping.")
                        skip = true
                    }
                    if (spriteId.isBlank()) {
                        logger.warn("Paintjob #$i in ${mod.id} by '${mod.author}' has no spriteId, skipping.")
                        skip = true
                    }

                    if (!skip) {
                        newSpecs.add(
                            MagicPaintjobSpec(
                                modId = mod.id,
                                modName = mod.name,
                                id = id,
                                hullId = hullId,
                                name = name,
                                unlockedAutomatically = unlockedAutomatically,
                                description = description,
                                unlockConditions = unlockConditions,
                                spriteId = spriteId,
                                tags = tags
                            )
                                .also {
                                    if (unlockedAutomatically)
                                        unlockPaintjob(it.id)
                                }
                        )
                    }
                } catch (e: java.lang.Exception) {
                    logger.warn(
                        "Unable to load paintjob #" + i + " (" + id + ") in " + mod.id + " by " + mod.author.substring(
                            0,
                            30
                        ) + " from file " + specsFilename, e
                    )
                }
            }
        }

        val newPaintjobSpecsById: MutableMap<String, MagicPaintjobSpec> = HashMap()

        for (newSpec in newSpecs) {
            newPaintjobSpecsById[newSpec.id] = newSpec
        }

        logger.info("Loaded " + newPaintjobSpecsById.size + " paintjobs.")
        return newPaintjobSpecsById
    }

    @JvmStatic
    fun getPaintjobsForHull(hullId: String): List<MagicPaintjobSpec> {
        return paintjobsInner.filter { it.hullId.equals(hullId, ignoreCase = true) }
    }

    @JvmStatic
    fun saveUnlockedPaintJobs() {
        // Ensure that we have the latest unlocked pjs before saving
        loadUnlockedPaintjobs()
        if (unlockedPaintjobsInner.isEmpty()) return
        val magicNumber = 3

        runCatching {
            val unlockedPJsObj = JSONObject()
            unlockedPJsObj.put(jsonObjectKey, unlockedPaintjobsInner.toList())
            Global.getSettings().writeTextFileToCommon(commonFilename, unlockedPJsObj.toString(magicNumber))
        }
            .onFailure { logger.error("Failed to save unlocked paintjobs.", it) }
    }

    @JvmStatic
    fun loadUnlockedPaintjobs() {
        runCatching {
            val unlockedPJsObj = runCatching {
                val result = JSONObject(Global.getSettings().readTextFileFromCommon(commonFilename))
                if (result.length() > 0) result
                else JSONObject()
            }.recover { JSONObject() }
                .getOrThrow()

            unlockedPaintjobsInner.addAll(unlockedPJsObj.getJSONArray(jsonObjectKey).toStringList())
        }
            .onFailure { logger.error("Failed to load unlocked paintjobs.", it) }
    }

    /**
     * Adds a paintjob to the list of paintjobs.
     * Replaces any existing paintjob with the same id.
     */
    @JvmStatic
    fun addPaintJob(paintjob: MagicPaintjobSpec) {
        if (runCatching { Global.getSettings().getHullSpec(paintjob.hullId) }.getOrNull() == null) {
            logger.error("Did not add paintjob ${paintjob.id}. Hull with id ${paintjob.hullId} does not exist.")
            return
        }

        if (Global.getSettings().getSprite(paintjob.spriteId) == null) {
            // Don't preload the sprite, if feature is disabled it will never be used.
            // Global.getSettings().loadTexture(paintjob.spriteId)
            logger.error("Did not add paintjob ${paintjob.id}. Sprite with id ${paintjob.spriteId} does not exist.")
            return
        }

        paintjobsInner.removeAll { it.id == paintjob.id }
        paintjobsInner.add(paintjob)

        if (Magic_modPlugin.isMagicLibTestMode()) {
            unlockPaintjob(paintjob.id)
        }
    }

    @JvmStatic
    fun unlockPaintjob(id: String) {
        unlockedPaintjobsInner.add(id)
        saveUnlockedPaintJobs()
    }

    @JvmStatic
    fun beforeGameSave() {
        // Save whether the intel is important.
        if (getIntel() != null)
            Global.getSector().memoryWithoutUpdate[isIntelImportantMemKey] = getIntel()!!.isImportant

        // No reason to add intel to the save.
        removeIntel()
    }

    @JvmStatic
    fun afterGameSave() = initIntel()

    @JvmStatic
    fun initIntel() {
        if (!isEnabled) return
        if (Global.getSector() == null) return
        removeIntel()

        // Don't show if there aren't any.
        if (getPaintjobs().isEmpty()) return

        val intel = MagicPaintjobIntel()
        Global.getSector().intelManager.addIntel(intel, true)
        intel.setImportant(Global.getSector().memoryWithoutUpdate.getBoolean(isIntelImportantMemKey))
        intel.isNew = false
    }

    @JvmStatic
    fun getIntel(): MagicPaintjobIntel? {
        if (!Global.getSector().intelManager.hasIntelOfClass(MagicPaintjobIntel::class.java))
            return null

        return try {
            Global.getSector().intelManager.getFirstIntel(MagicPaintjobIntel::class.java) as MagicPaintjobIntel
        } catch (ex: Exception) {
            logger.warn("Unable to get MagicPaintjobIntel.", ex)
            null
        }
    }

    @JvmStatic
    fun getPaintjob(paintjobId: String): MagicPaintjobSpec? = paintjobsInner.firstOrNull { it.id == paintjobId }

    @JvmStatic
    fun removePaintjobFromShip(fleetMember: FleetMemberAPI) {
        val variant = fleetMember.variant ?: return

        variant.tags.filter { it.startsWith(MagicPaintjobHullMod.PAINTJOB_TAG_PREFIX) }
            .forEach { variant.removeTag(it) }

        if (variant.hasHullMod(MagicPaintjobHullMod.ID)) {
            variant.removePermaMod(MagicPaintjobHullMod.ID)
        }

        fleetMember.spriteOverride = null
    }

    @JvmStatic
    fun applyPaintjob(fleetMember: FleetMemberAPI?, combatShip: ShipAPI?, paintjob: MagicPaintjobSpec) {
        if (!isEnabled) return
        // In case it's a sprite path that wasn't loaded, load it.
        val spriteId = paintjob.spriteId
        Global.getSettings().loadTexture(spriteId)

        if (fleetMember != null) {
            val variant = fleetMember.variant
            if (variant?.hasHullMod(MagicPaintjobHullMod.ID) != true) {
                variant.addPermaMod(MagicPaintjobHullMod.ID)
            }

            if (variant != null) {
                variant.tags.filter { it.startsWith(MagicPaintjobHullMod.PAINTJOB_TAG_PREFIX) }
                    .forEach { variant.removeTag(it) }
                variant.addTag(MagicPaintjobHullMod.PAINTJOB_TAG_PREFIX + paintjob.id)
            }

            // This causes the sprite to show at full size on game (re)load, ie massive hyperspace ships.
//            fleetMember.spriteOverride = paintjob.spriteId
        }

        if (combatShip != null) {
            val sprite = Global.getSettings().getSprite(spriteId) ?: return
            val x = combatShip.spriteAPI.centerX
            val y = combatShip.spriteAPI.centerY
            val alpha = combatShip.spriteAPI.alphaMult
            val angle = combatShip.spriteAPI.angle
            val color = combatShip.spriteAPI.color
            combatShip.setSprite(sprite)
            if (Global.getSettings().modManager.isModEnabled("shaderLib")) {
                ShaderLib.overrideShipTexture(combatShip, spriteId)
            }
            combatShip.spriteAPI.setCenter(x, y)
            combatShip.spriteAPI.alphaMult = alpha
            combatShip.spriteAPI.angle = angle
            combatShip.spriteAPI.color = color
        }
    }

    @JvmStatic
    fun getCurrentShipPaintjob(fleetMember: FleetMemberAPI): MagicPaintjobSpec? {
        val variant = fleetMember.variant ?: return null
        val pjTag = variant.tags.firstOrNull { it.startsWith(MagicPaintjobHullMod.PAINTJOB_TAG_PREFIX) }
        val paintjobId = pjTag?.removePrefix(MagicPaintjobHullMod.PAINTJOB_TAG_PREFIX) ?: return null

        // The player can remove the hullmod manually, so if it's not there, remove the paintjob.
        if (!fleetMember.variant.hasHullMod(MagicPaintjobHullMod.ID)) {
            removePaintjobFromShip(fleetMember)
            return null
        }

        return getPaintjob(paintjobId)
    }

    private fun removeIntel() {
        if (Global.getSector() == null) return
        val intelManager = Global.getSector().intelManager
        while (intelManager.hasIntelOfClass(MagicPaintjobIntel::class.java)) {
            intelManager.removeIntel(intelManager.getFirstIntel(MagicPaintjobIntel::class.java))
        }
    }
}