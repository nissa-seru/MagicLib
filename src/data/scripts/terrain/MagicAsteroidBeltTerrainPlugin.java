package data.scripts.terrain;

import java.awt.Color;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.AsteroidAPI;
import com.fs.starfarer.api.campaign.CampaignEngineLayers;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TerrainAIFlags;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidBeltTerrainPlugin.AsteroidBeltParams;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidSource;
import com.fs.starfarer.api.impl.campaign.terrain.BaseRingTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.RingRenderer;
import com.fs.starfarer.api.impl.campaign.terrain.RingSystemTerrainPlugin;
import com.fs.starfarer.api.loading.Description.Type;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import static data.scripts.util.MagicTxt.getString;

public class MagicAsteroidBeltTerrainPlugin extends BaseRingTerrain implements AsteroidSource {

    // Stats
    public static final String IMPACT_CHANCE = "magicAsteroidImpactChance";
    public static final String IMPACT_FORCE = "magicAsteroidImpactforce";
    public static final String IMPACT_DURATION = "magicAsteroidImpactDuration";
    // Stats for whole fleet and individual members
    public static final String IMPACT_DAMAGE_CHANCE = "magicAsteroidImpactDamageChance";
    public static final String IMPACT_DAMAGE = "magicAsteroidImpactDamage";

    // MemKeys
    public static final String IMPACT_TIMEOUT = "$asteroidImpactTimeout";
    public static final String IMPACT_SKIPPED = "$skippedImpacts";
    public static final String IMPACT_RECENT = "$recentImpact";

    // Constants
    public static final float PROB_PER_SKIP = 0.15f;
    public static final float MAX_PROBABILITY = 1f;
    public static final float MAX_SKIPS_TO_TRACK = 7;
    public static final float DURATION_PER_SKIP = 0.2f;

    public static final float MAX_DAMAGE_WINDOW = 1.5f;
    public static final float MIN_DAMAGE_WINDOW = 0.5f;
    public static final float MAX_TIMEOUT = 0.15f;
    public static final float MIN_TIMEOUT = 0.05f;

    @Override
    protected Object readResolve() {
        super.readResolve();
        return this;
    }

    private transient RingRenderer rr;

    @Override
    public void renderOnMap(float factor, float alphaMult) {
        if (beltParams == null) {
            return;
        }
        if (rr == null) {
            rr = new RingRenderer("systemMap", "map_asteroid_belt");
        }
        Color color = Global.getSettings().getColor("asteroidBeltMapColor");
        float bandWidth = beltParams.bandWidthInEngine;
        bandWidth = 300f;
        rr.render(entity.getLocation(),
                beltParams.middleRadius - bandWidth * 0.5f,
                beltParams.middleRadius + bandWidth * 0.5f,
                color,
                false, factor, alphaMult);
    }

    @Override
    public void regenerateAsteroids() {
        createAsteroids();
    }

    protected boolean needToCreateAsteroids = true;

    protected void createAsteroids() {
        if (!(beltParams instanceof AsteroidBeltParams)) {
            return;
        }

        Random rand = new Random(Global.getSector().getClock().getTimestamp() + entity.getId().hashCode());

        LocationAPI location = entity.getContainingLocation();
        for (int i = 0; i < beltParams.numAsteroids; i++) {
            //float size = 8f + (float) Math.random() * 25f;
            float size = beltParams.minSize + rand.nextFloat() * (beltParams.maxSize - beltParams.minSize);
            AsteroidAPI asteroid = location.addAsteroid(size);

            asteroid.setFacing(rand.nextFloat() * 360f);
            float currRadius = beltParams.middleRadius - beltParams.bandWidthInEngine / 2f + rand.nextFloat() * beltParams.bandWidthInEngine;
            float angle = rand.nextFloat() * 360f;
            float orbitDays = beltParams.minOrbitDays + rand.nextFloat() * (beltParams.maxOrbitDays - beltParams.minOrbitDays);
            asteroid.setCircularOrbit(this.entity, angle, currRadius, orbitDays);
            Misc.setAsteroidSource(asteroid, this);
        }
        needToCreateAsteroids = false;
    }

    @Override
    public void advance(float amount) {
        if (needToCreateAsteroids) {
            createAsteroids();
        }

        super.advance(amount);
    }

    public AsteroidBeltParams beltParams;

    @Override
    public void init(String terrainId, SectorEntityToken entity, Object param) {
        super.init(terrainId, entity, param);
        if (param instanceof AsteroidBeltParams) {
            beltParams = (AsteroidBeltParams) param;
            name = beltParams.name;
            if (name == null) {
                //"af_beltName" : "Asteroid Belt",
                name = getString("af_beltName");
            }
        }
    }

    @Override
    public void render(CampaignEngineLayers layer, ViewportAPI viewport) {
        super.render(layer, viewport);
    }

    @Override
    public void applyEffect(SectorEntityToken entity, float days) {
        CampaignFleetAPI fleet;
        if (entity instanceof CampaignFleetAPI) {
            fleet = (CampaignFleetAPI) entity;
        } else {
            return;
        }

        if (Misc.isSlowMoving(fleet)) {
            fleet.getStats().addTemporaryModMult(0.1f, getModId() + "_2",
                    //"af_hiding" : "Hiding inside ",
                    getString("af_hiding") + getNameForTooltip().toLowerCase(),
                    RingSystemTerrainPlugin.getVisibilityMult(fleet),
                    fleet.getStats().getDetectedRangeMod());
        }

        if (fleet.isInHyperspaceTransition()) {
            return;
        }

        MutableStat chanceMod = fleet.getCommanderStats().getDynamic().getStat(IMPACT_CHANCE);
        if (chanceMod == null) {
            chanceMod = new MutableStat(1f);
        }
        MutableStat damageChanceMod = fleet.getCommanderStats().getDynamic().getStat(IMPACT_DAMAGE_CHANCE);
        if (damageChanceMod == null) {
            damageChanceMod = new MutableStat(1f);
        }
        MutableStat damageMod = fleet.getCommanderStats().getDynamic().getStat(IMPACT_DAMAGE);
        if (damageMod == null) {
            damageMod = new MutableStat(1f);
        }

        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem.contains(IMPACT_TIMEOUT)) {
            return;
        }

        float expire = mem.getExpire(IMPACT_SKIPPED);
        if (expire < 0) {
            expire = 0;
        }

        float hitProb = expire / DURATION_PER_SKIP * PROB_PER_SKIP;
        hitProb *= chanceMod.getModifiedValue();

        if (hitProb > MAX_PROBABILITY) {
            hitProb = MAX_PROBABILITY;
        }

        boolean impact = (float) Math.random() < hitProb;

        if (impact) {
            FleetMemberAPI target = null;
            float damageMult = fleet.getCurrBurnLevel() - Misc.getGoSlowBurnLevel(fleet);

            boolean doDamage = mem.is(IMPACT_RECENT, true);
            doDamage &= (float) Math.random() * damageChanceMod.getModifiedValue() > 0.5f;

            if (doDamage) {
                WeightedRandomPicker<FleetMemberAPI> targets = new WeightedRandomPicker<>();
                for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                    float w = 1f;
                    switch (m.getHullSpec().getHullSize()) {
                        case CAPITAL_SHIP:
                            w = 20f;
                            break;
                        case CRUISER:
                            w = 10f;
                            break;
                        case DESTROYER:
                            w = 5f;
                            break;
                        case FRIGATE:
                            w = 1f;
                            break;
                    }

                    MutableStat memberDamageChanceMod = m.getStats().getDynamic().getStat(IMPACT_DAMAGE_CHANCE);
                    if (memberDamageChanceMod == null) {
                        memberDamageChanceMod = new MutableStat(1f);
                    }

                    w *= memberDamageChanceMod.getModifiedValue();

                    targets.add(m, w);
                }

                target = targets.pick();

                if (target != null) {

                    damageMult *= damageMod.getModifiedValue();

                    MutableStat memberDamageMod = target.getStats().getDynamic().getStat(IMPACT_DAMAGE);
                    if (memberDamageMod == null) {
                        memberDamageMod = new MutableStat(1f);
                    }

                    damageMult *= memberDamageMod.getModifiedValue();
                }
            }

            fleet.addScript(new MagicAsteroidImpact(fleet, target, damageMult));

            float damageWindow = MIN_DAMAGE_WINDOW;
            damageWindow += (MAX_DAMAGE_WINDOW - MIN_DAMAGE_WINDOW) * (float) Math.random();

            mem.set(IMPACT_SKIPPED, true, 0);
            mem.set(IMPACT_RECENT, true, damageWindow);
        } else {
            mem.set(IMPACT_SKIPPED, true, Math.min(expire + DURATION_PER_SKIP,
                    MAX_SKIPS_TO_TRACK * DURATION_PER_SKIP));
        }

        float timeout = MIN_TIMEOUT + (MAX_TIMEOUT - MIN_TIMEOUT) * (float) Math.random();
        mem.set(IMPACT_TIMEOUT, true, timeout);
    }

    @Override
    public boolean hasTooltip() {
        return true;
    }

    @Override
    public String getNameForTooltip() {
        //"af_beltName" : "Asteroid Belt",
        return getString("af_beltName");
    }

    @Override
    public String getNameAOrAn() {
        return getString("an");
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        float pad = 10f;
        float small = 5f;
        Color highlight = Misc.getHighlightColor();

        tooltip.addTitle(getNameForTooltip());
        tooltip.addPara(Global.getSettings().getDescription(getTerrainId(), Type.TERRAIN).getText1(), pad);

        float nextPad = pad;
        if (expanded) {
            tooltip.addSectionHeading("Travel", Alignment.MID, pad);
            nextPad = small;
        }
        //"af_impact1" : "Chance of asteroid impacts that briefly knock the fleet off course and ",
        //"af_impact2" : "may occasionally impact ships directly, dealing moderate damage.",
        tooltip.addPara(getString("af_impact1")
                + getString("af_impact2"), nextPad);
        //"af_impact3" : "Smaller fleets are usually able to avoid the heavier impacts, ",
        //"af_impact4" : "and slow-moving fleets do not risk impacts at all.",
        //"af_impact5" : "slow-moving",
        tooltip.addPara(getString("af_impact3")
                + getString("af_impact4"), pad,
                highlight,
                getString("af_impact5")
        );

        String stop = Global.getSettings().getControlStringForEnumName("GO_SLOW");

        //"af_sensor1" : "Reduces the range at which stationary or slow-moving* fleets inside it can be detected by %s.",
        tooltip.addPara(getString("af_sensor1"), nextPad,
                highlight,
                "" + (int) ((1f - RingSystemTerrainPlugin.getVisibilityMult(Global.getSector().getPlayerFleet())) * 100) + getString("%")
        );
        //"af_sensor2" : "*Press and hold %s to stop; combine with holding the left mouse button down to move slowly. ",
        //"af_sensor3" : "A slow-moving fleet moves at a burn level of half that of its slowest ship.",
        tooltip.addPara(getString("af_sensor2")
                + getString("af_sensor3"), nextPad,
                Misc.getGrayColor(), highlight,
                stop
        );

        if (expanded) {
            //"af_combat1" : "Combat",
            //"af_combat2" : "Numerous asteroids present on the battlefield. Large enough to be an in-combat navigational hazard.",
            tooltip.addSectionHeading(getString("af_combat1"), Alignment.MID, pad);
            tooltip.addPara(getString("af_combat2"), small);
        }
    }

    @Override
    public boolean isTooltipExpandable() {
        return true;
    }

    @Override
    public float getTooltipWidth() {
        return 350f;
    }

    @Override
    public String getEffectCategory() {
        return "asteroid_belt";
    }

    @Override
    public boolean hasAIFlag(Object flag) {
        return flag == TerrainAIFlags.REDUCES_SPEED_LARGE || flag == TerrainAIFlags.DANGEROUS_UNLESS_GO_SLOW;
    }

    @Override
    public void reportAsteroidPersisted(SectorEntityToken asteroid) {
        if (Misc.getAsteroidSource(asteroid) == this) {
            beltParams.numAsteroids--;
        }
    }
}
