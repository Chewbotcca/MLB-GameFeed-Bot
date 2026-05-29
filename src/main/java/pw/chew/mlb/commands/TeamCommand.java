package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.MiscUtil;
import pw.chew.chewbotcca.util.RestClient;
import pw.chew.mlb.util.AutocompleteUtil;
import pw.chew.mlb.util.MLBAPIUtil;
import pw.chew.mlb.util.TeamEmoji;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static pw.chew.mlb.MLBBot.SEASON;

public class TeamCommand extends SlashCommand {
    public TeamCommand() {
        this.name = "team";
        this.help = "Get information about a team.";
        this.options = List.of(
            new OptionData(OptionType.STRING, "team", "Type to search for a team!", true, true)
        );

        // Installable everywhere, since info and always ephemeral
        this.contexts = new InteractionContextType[]{
            InteractionContextType.GUILD,
            InteractionContextType.BOT_DM,
            InteractionContextType.PRIVATE_CHANNEL
        };
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        String teamId = event.optString("team", "140");
        List<MLBAPIUtil.Affiliate> affiliates = MLBAPIUtil.getAffiliates(teamId);
        MLBAPIUtil.Affiliate currentAffiliate = affiliates.stream().filter(af -> (af.id() + "").equals(teamId)).findFirst().orElse(null);
        if (currentAffiliate == null) {
            event.reply("I couldn't find a team with that ID!").setEphemeral(true).queue();
            return;
        }

        String componentKeyBase = "team:" + teamId + ":";

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Information for " + currentAffiliate.name())
            .setDescription("Yeah this sure is some information, huh?")
            .addField("Record", "%s-%s".formatted(currentAffiliate.wins(), currentAffiliate.losses()), true)
            .setFooter("Team ID: " + teamId);

        // Build the affiliate list :3
        List<SelectOption> options = new ArrayList<>();
        for (MLBAPIUtil.Affiliate affiliate : affiliates) {
            options.add(SelectOption.of(affiliate.name(), affiliate.id() + "").withDescription(affiliate.sport().name()).withEmoji(TeamEmoji.fromName(affiliate.name())));
        }

        StringSelectMenu menu = StringSelectMenu.create(componentKeyBase + "affiliates")
            .setPlaceholder("Select an Affiliate!")
            .addOptions(options)
            .build();

        event.replyEmbeds(embed.build())
            .addActionRow(menu)
            .addActionRow(
                Button.primary(componentKeyBase + "standings:" + currentAffiliate.leagueId(), "Standings").withEmoji(Emoji.fromUnicode("📊")),
                Button.primary(componentKeyBase + "roster", "Roster").withEmoji(Emoji.fromUnicode("📋")),
                Button.primary(componentKeyBase + "transactions", "Transactions").withEmoji(Emoji.fromUnicode("💸")),
                Button.primary(componentKeyBase + "schedule", "Schedule").withEmoji(Emoji.fromUnicode("📅")),
                Button.primary(componentKeyBase + "stats", "Stats").withEmoji(Emoji.fromUnicode("📈"))
            ).setEphemeral(true).queue();
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoices(AutocompleteUtil.getTeams("1", event.getFocusedOption().getValue())).queue();
    }

    public static void handleButton(ButtonInteractionEvent event) {
        // 0 => always "team", irrelevant
        // 1 => always team ID
        // 2 => action
        // 3 => sub-action if applicable
        String[] components = event.getComponentId().split(":");
        String teamId = components[1];
        String action = components[2];
        String subAction = components.length > 3 ? components[3] : null;

        switch (action) {
            case "standings" -> event.getInteraction().editMessageEmbeds(handleStandingsEmbed(teamId)).queue();
            case "roster" -> event.getInteraction().editMessageEmbeds(handleRosterEmbed(teamId)).queue();
            case "transactions" -> event.getInteraction().editMessageEmbeds(handleTransactionsEmbed(teamId)).queue();
            case "schedule" -> event.getInteraction().editMessageEmbeds(handleScheduleEmbed(teamId)).queue();
            case "stats" -> event.getInteraction().editMessageEmbeds(handleStatsEmbed(teamId)).queue();
        }
    }

    public static void handleSelect(StringSelectInteractionEvent event) {}

    private static List<ActionRow> buildBaseActionRows(String teamId) {
        return Collections.emptyList();
    }

    private static MessageEmbed handleStandingsEmbed(String teamId) {
        return new EmbedBuilder()
            .build();
    }

    private static MessageEmbed handleRosterEmbed(String teamId) {
        // get roster
        JSONObject roster = RestClient.get("https://statsapi.mlb.com/api/v1/teams/%s/roster?rosterType=depthChart&season=2025&fields=roster,person,primaryNumber,fullName,position,name,type,status,code,description"
            .formatted(teamId)).asJSONObject();

        Map<String, List<String>> players = new LinkedHashMap<>();
        players.put("Starting Pitcher", new ArrayList<>());
        players.put("Infielder", new ArrayList<>());
        players.put("Outfielder", new ArrayList<>());
        players.put("Bullpen", new ArrayList<>());
        players.put("Catcher", new ArrayList<>());
        players.put("Designated Hitter", new ArrayList<>());

        for (JSONObject player : MiscUtil.toList(roster.getJSONArray("roster"), JSONObject.class)) {
            JSONObject position = player.getJSONObject("position");
            String name = player.getJSONObject("person").getString("fullName");
            switch (player.getJSONObject("status").getString("code")) {
                case "D60":
                case "RM":
                    // skip this player entirely
                    break;
                case "D10":
                case "D15":
                    // append injury emoji to end of name
                    name += " " + "+"; // TODO: Replace with actual injury emoji
                case "A":
                    // time to get ready
                    switch (position.getString("type")) {
                        case "Catcher", "Infielder", "Outfielder" -> {
                            players.get(position.getString("type")).add(name);
                        }
                        case "Pitcher" -> {
                            if (position.getString("name").equals("Starting Pitcher")) {
                                players.get("Starting Pitcher").add(name);
                            } else {
                                players.get("Bullpen").add(name);
                            }
                        }
                        case "Hitter" -> {
                            players.get("Designated Hitter").add(name);
                        }
                    }
            }
        }



        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Roster")
            .setDescription("Only shows active roster.");

        for (Map.Entry<String, List<String>> entry : players.entrySet()) {
            String position = entry.getKey();
            List<String> playerList = entry.getValue();

            if (playerList.isEmpty()) {
                continue;
            }

            StringBuilder sb = new StringBuilder();
            for (String player : playerList) {
                sb.append(player).append("\n");
            }

            embed.addField(position + (position.equals("Bullpen") ? "" : "s"), sb.toString(), true);
        }

        return embed.build();
    }

    private static MessageEmbed handleTransactionsEmbed(String teamId) {
        return new EmbedBuilder()
            .build();
    }

    private static MessageEmbed handleScheduleEmbed(String teamId) {
        JSONObject data = RestClient.get("https://statsapi.mlb.com/api/v1/teams/%s?season=%s&hydrate=previousSchedule,nextSchedule"
            .formatted(teamId, SEASON)).asJSONObject().getJSONArray("teams").getJSONObject(0);

        List<MLBAPIUtil.Game> games = new ArrayList<>();
        List<Integer> gamePks = new ArrayList<>();
        JSONArray prevGames = data.getJSONObject("previousGameSchedule").getJSONArray("dates");
        JSONArray nextGames = data.getJSONObject("nextGameSchedule").getJSONArray("dates");

        for (JSONArray ar : new JSONArray[]{prevGames, nextGames}) {
            for (int i = 0; i < ar.length(); i++) {
                JSONArray gamesForDate = ar.getJSONObject(i).getJSONArray("games");
                for (int j = 0; j < gamesForDate.length(); j++) {
                    gamePks.add(gamesForDate.getJSONObject(j).getInt("gamePk"));
                }
            }
        }

        // now we get games cus mlb is silly :3
        JSONObject gameData = RestClient.get("https://statsapi.mlb.com/api/v1/schedule?language=en&gamePks=%s&useLatestGames=true&hydrate=team"
            .formatted(gamePks.stream().distinct().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""))).asJSONObject();

        // now we do the wrapping
        JSONArray gameDates = gameData.getJSONArray("dates");
        for (int i = 0; i < gameDates.length(); i++) {
            JSONArray gamesForDate = gameDates.getJSONObject(i).getJSONArray("games");
            for (int j = 0; j < gamesForDate.length(); j++) {
                games.add(new MLBAPIUtil.Game(gamesForDate.getJSONObject(j)));
            }
        }

        int teamIdInt = Integer.parseInt(teamId);

        StringBuilder res = new StringBuilder();
        for (MLBAPIUtil.Game game : games) {
            //res.append(ScheduleCommand.gameToString(game, teamIdInt, -1)).append("\n");
        }

        return new EmbedBuilder()
            .setTitle("Schedule for %s".formatted(data.getString("name")))
            .setDescription(res.toString())
            .build();
    }

    private static MessageEmbed handleStatsEmbed(String teamId) {
        return new EmbedBuilder()
            .build();
    }
}
