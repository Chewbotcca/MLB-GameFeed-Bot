package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.MiscUtil;
import pw.chew.chewbotcca.util.RestClient;
import pw.chew.mlb.util.EmbedUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class PlayerCommand extends SlashCommand {
    public PlayerCommand() {
        this.name = "player";
        this.help = "Show information for a provided player.";
        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL};
        this.options = List.of(
            new OptionData(OptionType.INTEGER, "player", "The player to show information for.", true, true),
            new OptionData(OptionType.INTEGER, "season", "The season to show information for.", false, true),
            new OptionData(OptionType.STRING, "type", "Type of information to show.", false)
                .addChoice("Batting", "batting")
                .addChoice("Fielding", "fielding")
                .addChoice("Pitching", "pitching")
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        long playerId = event.optLong("player", 0);

        if (playerId == 0) {
            event.replyEmbeds(EmbedUtil.failure("Invalid player returned. Try searching again!")).setEphemeral(true).queue();
            return;
        }

        event.replyEmbeds(buildInfoEmbed(playerId))
            .addComponents(buildButtons("info", playerId))
            .setEphemeral(true)
            .queue();
    }

    public static MessageEmbed buildInfoEmbed(long playerId) {
        JSONObject player = RestClient.get("https://statsapi.mlb.com/api/v1/people/%s?hydrate=currentTeam,team,stats(type=[yearByYear,yearByYearAdvanced,careerRegularSeason,careerAdvanced,availableStats](team(league)),leagueListId=mlb_hist)"
                .formatted(playerId))
            .asJSONObject()
            .getJSONArray("people")
            .getJSONObject(0);

        DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy");

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("%s (#%s)".formatted(player.getString("fullName"), player.getString("primaryNumber")))
            .setThumbnail("https://img.mlbstatic.com/mlb-photos/image/upload/d_people:generic:headshot:67:current.png/w_426,q_auto:best/v1/people/%s/headshot/67/current".formatted(playerId))
            .setDescription("""
                    %s | B/T: %s/%s | %s %s | Age: %s
                    """.formatted(
                    player.getJSONObject("primaryPosition").getString("abbreviation"),
                    player.getJSONObject("batSide").getString("code"),
                    player.getJSONObject("pitchHand").getString("code"),
                    player.getString("height"),
                    player.getInt("weight"),
                    player.getInt("currentAge")
                )
            ).addField("Born", "%s\n%s, %s, %s".formatted(
                LocalDate.parse(player.getString("birthDate"), inputFormatter).format(outputFormatter),
                player.getString("birthCity"),
                player.optString("birthStateProvince"),
                player.getString("birthCountry")
            ), true)
            .addField("MLB Debut", LocalDate.parse(player.getString("mlbDebutDate"), inputFormatter).format(outputFormatter), true)
            .addField("Status", player.getBoolean("active") ? "Active" : "Inactive", true);

        return embed.build();
    }

    public static MessageEmbed buildHittingStatsEmbed(long playerId) {
        // get stats
        JSONObject player = RestClient.get("https://statsapi.mlb.com/api/v1/people/%s?hydrate=currentTeam,team,stats(type=[yearByYear,yearByYearAdvanced,careerRegularSeason,careerAdvanced,availableStats](team(league)),leagueListId=mlb_hist),draft,awards,education"
                .formatted(playerId))
            .asJSONObject()
            .getJSONArray("people")
            .getJSONObject(0);

        // get hitting stats
        JSONObject stats = MiscUtil.toList(player.getJSONArray("stats"), JSONObject.class)
            .stream()
            .filter(stat -> stat.getJSONObject("type").getString("displayName").equals("yearByYear"))
            .filter(stat -> stat.getJSONObject("group").getString("displayName").equals("hitting"))
            .findFirst()
            .orElse(null);

        if (stats == null) {
            return EmbedUtil.failure("Failed to find hitting stats for this player.");
        }

        // get the stats
        JSONObject splits = stats.getJSONArray("splits").getJSONObject(stats.getJSONArray("splits").length() - 1);
        JSONObject stat = splits.getJSONObject("stat");

        String name = player.getString("fullName");

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Hitting stats for %s".formatted(name))
            .addField("Overall", String.join("\n", List.of(
                "Games: %s".formatted(stat.getInt("gamesPlayed")),
                "At Bats: %s".formatted(stat.getInt("atBats")),
                "Runs: %s".formatted(stat.getInt("runs")),
                "Runs Batted In: %s".formatted(stat.getInt("rbi")),
                "Walks: %s".formatted(stat.getInt("baseOnBalls")),
                "Intentional Walks: %s".formatted(stat.getInt("intentionalWalks")),
                "Strikeouts: %s".formatted(stat.getInt("strikeOuts")),
                "Stolen Bases: %s".formatted(stat.getInt("stolenBases")),
                "Caught Stealing: %s".formatted(stat.getInt("caughtStealing"))
            )), true)
            .addField("Hits", String.join("\n", List.of(
                "Hits: %s".formatted(stat.getInt("hits")),
                "Total Bases: %s".formatted(stat.getInt("totalBases")),
                "Doubles: %s".formatted(stat.getInt("doubles")),
                "Triples: %s".formatted(stat.getInt("triples")),
                "Home Runs: %s".formatted(stat.getInt("homeRuns"))
            )), true)
            .addField("Percentages", String.join("\n", List.of(
                "Average: %s".formatted(stat.getString("avg")),
                "On Base: %s".formatted(stat.getString("obp")),
                "Slugging: %s".formatted(stat.getString("slg")),
                "On Base + Slugging: %s".formatted(stat.getString("ops")),
                "Ground Outs/Air Outs: %s".formatted(stat.getString("groundOutsToAirouts"))
            )), true);

        return embed.build();
    }

    public static List<ActionRow> buildButtons(String current, long playerId) {
        ActionRow statButtons = ActionRow.of(
            Button.primary("player:info:%s".formatted(playerId), "Info").withDisabled(current.equals("info")),
            Button.primary("player:hitting:%s".formatted(playerId), "Hitting").withDisabled(current.equals("hitting")),
            Button.primary("player:fielding:%s".formatted(playerId), "Fielding").withDisabled(current.equals("fielding")),
            Button.primary("player:pitching:%s".formatted(playerId), "Pitching").withDisabled(current.equals("pitching"))
        );

        ActionRow otherButtons = ActionRow.of(
            Button.secondary("player:%s:%s:refresh".formatted(current, playerId), "Refresh"),
            Button.secondary("player:sites:%s".formatted(playerId), "View Online")
        );

        JSONArray splits = RestClient.get("https://statsapi.mlb.com/api/v1/people/%s?hydrate=stats(type=[yearByYear])&fields=people,stats,splits,season".formatted(playerId))
            .asJSONObject()
            .getJSONArray("people")
            .getJSONObject(0)
            .getJSONArray("stats")
            .getJSONObject(0)
            .getJSONArray("splits");
        List<SelectOption> seasons = MiscUtil.toList(splits, JSONObject.class)
            .stream()
            .map(split -> split.getString("season"))
            .distinct()
            .map(season -> SelectOption.of(season, "player:season:%s:%s".formatted(playerId, season)))
            .toList();

        StringSelectMenu menu = StringSelectMenu.create("player:season:%s".formatted(playerId))
            .setPlaceholder("Select a season")
            .addOptions(seasons)
            .setDefaultOptions(seasons.get(seasons.size() - 1))
            .build();

        return List.of(
            statButtons,
            ActionRow.of(menu),
            otherButtons
        );
    }

    public static List<ActionRow> updateButtons(List<ActionRow> rows, String updated) {
        // first row is all we are changing
        List<ActionRow> rowsArray = new ArrayList<>(rows);
        ActionRow row = rowsArray.remove(0);

        List<ActionRow> newRows = new ArrayList<>();
        List<Button> newButtons = new ArrayList<>();
        for (Button button : row.getButtons()) {
            String type = button.getId().split(":")[1];
            if (!type.equals(updated)) {
                newButtons.add(button.withDisabled(false));
            } else {
                newButtons.add(button.withDisabled(true));
            }
        }

        newRows.add(ActionRow.of(newButtons));
        newRows.addAll(rowsArray);

        return newRows;
    }

    public static void handleViewOnlineResponse(ButtonInteractionEvent event, int playerId) {
        WikidataPerson person = retrieveWikiData(playerId);

        List<String> validSources = new ArrayList<>();
        List<String> validSocials = new ArrayList<>();
        validSources.add("* [MLB](https://mlb.com/player/%s)".formatted(playerId));
        if (person.baseballReference() != null)
            validSources.add("* [Baseball Reference](%s)".formatted(person.baseballReference()));
        if (person.baseballReferenceMinors() != null)
            validSources.add("* [Baseball Reference Minors](%s)".formatted(person.baseballReferenceMinors()));
        if (person.espn() != null) validSources.add("* [ESPN](%s)".formatted(person.espn()));
        if (person.fangraphs() != null) validSources.add("* [FanGraphs](%s)".formatted(person.fangraphs()));
        if (person.retrosheet() != null) validSources.add("* [Retrosheet](%s)".formatted(person.retrosheet()));
        if (person.almanac() != null) validSources.add("* [Baseball Almanac](%s)".formatted(person.almanac()));
        if (person.cube() != null) validSources.add("* [Baseball Cube](%s)".formatted(person.cube()));
        if (person.twitter() != null) validSocials.add("* [Twitter](%s)".formatted(person.twitter()));
        if (person.instagram() != null) validSocials.add("* [Instagram](%s)".formatted(person.instagram()));

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("View this player online at...")
            .addField("Sources", String.join("\n", validSources), false)
            .addField("Socials", String.join("\n", validSocials), false);

        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        String type = event.getFocusedOption().getName();

        switch (type) {
            case "player": {
                String value = event.getFocusedOption().getValue();

                if (value.isBlank()) {
                    event.replyChoices(new Command.Choice("Please begin typing a name to search!", 0)).queue();
                    return;
                }

                String urlEncoded = URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

                JSONObject data = RestClient.get("https://typeahead.mlb.com/api/v1/typeahead/suggestions/" + urlEncoded).asJSONObject();

                JSONArray players = data.getJSONArray("players");

                if (players.isEmpty()) {
                    event.replyChoices(new Command.Choice("No players found!", 0)).queue();
                    return;
                }

                List<Command.Choice> choices = new ArrayList<>();

                for (JSONObject player : MiscUtil.toList(players, JSONObject.class)) {
                    String name = "%s (%s)".formatted(player.getString("name"), player.getString("teamTriCode"));
                    choices.add(new Command.Choice(name, player.getLong("playerId")));
                }

                // dedupe, limit to 25
                choices = choices.stream().distinct().limit(25).toList();

                event.replyChoices(choices).queue();
            }
        }
    }

    public static WikidataPerson retrieveWikiData(int playerId) {
        String query = """
            SELECT DISTINCT ?item ?itemLabel ?baseballref ?baseballref_minors ?espn ?twitter ?instagram ?fangraphs ?retrosheet ?almanac ?cube WHERE {
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
              {
                SELECT DISTINCT ?item WHERE {
                  ?item p:P3541 ?statement0.
                  ?statement0 ps:P3541 "ID_GOES_HERE".
                }
                LIMIT 100
              }
              OPTIONAL { ?item wdt:P1825 ?baseballref. }
              OPTIONAL { ?item wdt:P1826 ?baseballref_minors. }
              OPTIONAL { ?item wdt:P3571 ?espn. }
              OPTIONAL { ?item wdt:P2002 ?twitter. }
              OPTIONAL { ?item wdt:P2003 ?instagram. }
              OPTIONAL { ?item wdt:P3574 ?fangraphs. }
              OPTIONAL { ?item wdt:P6976 ?retrosheet. }
              OPTIONAL { ?item wdt:P4409 ?almanac. }
              OPTIONAL { ?item wdt:P4731 ?cube. }
            }
            """
            .replace("ID_GOES_HERE", String.valueOf(playerId));

        // URL encode the query
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        String url = "https://query.wikidata.org/sparql?query=" + encodedQuery;

        return new WikidataPerson(RestClient.get(url, "Accept: application/sparql-results+json").asJSONObject(), playerId);
    }

    public record WikidataPerson(JSONObject data, int mlbId) {
        private JSONObject hit() {
            return data.getJSONObject("results").getJSONArray("bindings").getJSONObject(0);
        }

        @Nullable
        public String get(String key) {
            if (!hit().has(key)) return null;

            return hit().getJSONObject(key).getString("value");
        }

        @Nullable
        public String baseballReference() {
            String id = get("baseballref");
            if (id == null) return null;

            return String.format("https://www.baseball-reference.com/players/%s.shtml", id);
        }

        @Nullable
        public String baseballReferenceMinors() {
            String id = get("baseballref_minors");
            if (id == null) return null;

            return String.format("https://www.baseball-reference.com/minors/player.cgi?id=%s", id);
        }

        @Nullable
        public String espn() {
            String id = get("espn");
            if (id == null) return null;

            return String.format("https://www.espn.com/mlb/player/_/id/%s", id);
        }

        public String twitter() {
            String id = get("twitter");
            if (id == null) return null;

            return String.format("https://twitter.com/%s", id);
        }

        public String instagram() {
            String id = get("instagram");
            if (id == null) return null;

            return String.format("https://www.instagram.com/%s", id);
        }

        public String fangraphs() {
            String id = get("fangraphs");
            if (id == null) return null;

            return String.format("https://www.fangraphs.com/statss.aspx?playerid=%s", id);
        }

        public String retrosheet() {
            String id = get("retrosheet");
            if (id == null) return null;

            return String.format("https://retrosheet.org/boxesetc/P%s.htm", id);
        }

        public String almanac() {
            String id = get("almanac");
            if (id == null) return null;

            return String.format("https://www.baseball-almanac.com/players/player.php?p=%s", id);
        }

        public String cube() {
            String id = get("cube");
            if (id == null) return null;

            return String.format("https://www.thebaseballcube.com/players/profile.asp?ID=%s", id);
        }
    }
}
