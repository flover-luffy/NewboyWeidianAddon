package net.luffy.sbwa.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.sbwa.model.PKGroup;
import net.luffy.sbwa.model.PKOpponent;

import java.util.*;
import java.util.stream.Collectors;

public class PKUtil {
    
    public static String getOutput(String groupMe, long feeAmountMe, JSONObject pk) {
        Objects.requireNonNull(pk, "PK数据不能为null");
        
        StringBuilder output = new StringBuilder("\n---------\n【PK】").append(pk.getStr("name"));
        List<PKOpponent> opponents = getOpponents(pk.getJSONArray("opponents"));
        
        boolean isGroupGame = opponents.stream()
            .anyMatch(opp -> opp.group != null);
        
        opponents.add(createMeAsOpponent(groupMe, feeAmountMe, pk));
        
        if (isGroupGame) {
            processGroupGame(output, opponents, pk.getJSONObject("pk_groups"));
        } else {
            processIndividualGame(output, opponents);
        }
        
        return output.toString();
    }

    private static PKOpponent createMeAsOpponent(String group, long amount, JSONObject pk) {
        // 计算最终金额，确保不为负数
        long finalAmount = Math.max(0L, amount + pk.getLong("deviation", 0L));
        return new PKOpponent(
            pk.getStr("myname"), 
            finalAmount, 
            false
        ).setGroup(group);
    }

    private static void processGroupGame(StringBuilder output, 
                                        List<PKOpponent> opponents,
                                        JSONObject groupProps) {
        Map<String, PKGroup> groups = opponents.stream()
            .collect(Collectors.toMap(
                PKOpponent::getGroup,
                opp -> PKGroup.construct(opp.getGroup(), groupProps),
                (existing, replacement) -> existing
            ));
            
        opponents.forEach(opp -> 
            groups.get(opp.getGroup()).addOpponent(opp));
            
        groups.values().stream()
            .sorted(Comparator.comparingDouble(PKGroup::getTotalInCoefficient).reversed())
            .forEach(group -> output.append(group.getMessage()));
    }

    private static void processIndividualGame(StringBuilder output, 
                                            List<PKOpponent> opponents) {
        opponents.stream()
            .sorted(Comparator.comparingLong(opp -> -opp.feeAmount))
            .forEach(opp -> output.append("\n")
                .append(opp.name)
                .append(": ")
                .append(opp.feeAmount / 100.0));
    }

    public static List<PKOpponent> getOpponents(JSONArray opponents) {
        if (opponents == null) return Collections.emptyList();
        
        return opponents.stream()
            .map(obj -> PKOpponent.construct(JSONUtil.parseObj(obj)))
            .collect(Collectors.toList());
    }

    public static boolean doGroupsHaveCookie(JSONObject pk) {
        return pk.getBeanList("groups", Long.class).stream()
            .allMatch(group -> 
                Newboy.INSTANCE.getProperties()
                    .weidian_cookie.containsKey(group));
    }

    public static PKOpponent meAsOpponent(JSONObject pk) {
        JSONObject asOpponent = new JSONObject()
            .set("name", null)
            .set("stock", pk.getLong("stock"));
            
        Optional.ofNullable(pk.getStr("group"))
            .ifPresent(group -> asOpponent.set("group", group));
            
        return PKOpponent.construct(asOpponent);
    }
}
