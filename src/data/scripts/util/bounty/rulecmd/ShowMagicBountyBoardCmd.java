package data.scripts.util.bounty.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.BarEventDialogPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.missions.BarCMD;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.bounty.MagicBountyBarEvent;

import java.util.List;
import java.util.Map;

public class ShowMagicBountyBoardCmd extends BaseCommandPlugin {
    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {

        MagicBountyBarEvent event = new MagicBountyBarEvent();
        BarCMD cmd = (BarCMD) getEntityMemory(memoryMap).get("$BarCMD");
        BarEventDialogPlugin plugin = new BarEventDialogPlugin(cmd, dialog.getPlugin(), event, memoryMap);
        dialog.setPlugin(plugin);
        plugin.init(dialog);
        return true;
    }
}
