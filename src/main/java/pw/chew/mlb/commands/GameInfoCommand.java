package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.internal.utils.Checks;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.MiscUtil;
import pw.chew.chewbotcca.util.RestClient;
import pw.chew.mlb.objects.GameState;
import pw.chew.mlb.objects.ImageUtil;
import pw.chew.mlb.util.AutocompleteUtil;
import pw.chew.mlb.util.EmbedUtil;
import pw.chew.mlb.util.MLBAPIUtil;
import pw.chew.mlb.util.TeamEmoji;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A command to get information about a specific game
 * <br>
 * This was partially inspired by <a href="https://github.com/AlecM33/mlb-gameday-bot">AlecM33's Discord bot</a>
 */
public class GameInfoCommand extends SlashCommand {
    public GameInfoCommand() {
        this.name = "gameinfo";
        this.help = "Get information about a specific game";
        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL};

        this.options = Collections.singletonList(
            new OptionData(OptionType.STRING, "game", "Pick a game from today to view info for.", true, true)
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        String gamePk = event.optString("game", "");
        GameState info = GameState.fromPk(gamePk);
        if (info.failed()) {
            event.replyEmbeds(EmbedUtil.failure("Failed to get game info")).setEphemeral(true).queue();
            return;
        }

        event.replyEmbeds(buildGameInfoEmbed(info))
            .setComponents(buildActionRows(info))
            .setEphemeral(true).queue();
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoices(AutocompleteUtil.getTodayGames(true)).queue();
    }

    /**
     * Builds an embed for the game info.
     * Doesn't include ActionRows, use {@link #buildActionRows(GameState)}.
     *
     * @param info the game info to build the embed for
     * @return the embed
     */
    public static MessageEmbed buildGameInfoEmbed(GameState info) {
        if (info.failed()) {
            return EmbedUtil.failure("Failed to get game info");
        }

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Game Info for %s @ %s on %s".formatted(info.away().clubName(), info.home().clubName(), info.friendlyDate()))
            .setDescription(info.summary())
            .addField("Attendance", info.friendlyAttendance(), true)
            .addField("Weather", info.weather(), true)
            .setFooter("Use the menus below to find info for the specified team.");

        return embed.build();
    }

    /**
     * Builds buttons for the response to the initial invocation of the command
     *
     * @param info the game info to build buttons for
     * @return the buttons
     */
    public static List<ActionRow> buildActionRows(GameState info) {
        String[] homeOrAway = {"away", "home"};
        StringSelectMenu away = null;
        StringSelectMenu home = null;

        for (String homeAway : homeOrAway) {
            GameState.TeamInfo team = homeAway.equals("away") ? info.away() : info.home();

            StringSelectMenu.Builder menu = StringSelectMenu.create("gameinfo:select:%s:%s".formatted(info.gamePk(), homeAway))
                .setPlaceholder("Select %s Info".formatted(team.clubName()))
                .addOptions(
                    SelectOption.of("%s Scoring Plays".formatted(team.clubName()), "scoring_plays")
                        .withDescription("View the scoring plays for the %s.".formatted(team.clubName()))
                        .withEmoji(TeamEmoji.fromTeamId(team.id())),
                    SelectOption.of("%s Lineup".formatted(team.clubName()), "lineup")
                        .withDescription("View the starting lineup for the %s.".formatted(team.clubName()))
                        .withEmoji(TeamEmoji.fromTeamId(team.id())),
                    SelectOption.of("%s Box Score".formatted(team.clubName()), "boxscore")
                        .withDescription("View the box score for %s.".formatted(team.clubName()))
                        .withEmoji(TeamEmoji.fromTeamId(team.id()))
                );

            if (homeAway.equals("home")) {
                home = menu.build();
            } else {
                away = menu.build();
            }
        }

        Button refreshButton = Button.secondary("gameinfo:refresh:%s".formatted(info.gamePk()), "Refresh").withDisabled(info.isFinal());
        Button onlineButton = Button.link("https://mlb.chew.pw/game/" + info.gamePk(), "View Online");

        return Arrays.asList(
            ActionRow.of(refreshButton, onlineButton),
            ActionRow.of(away),
            ActionRow.of(home)
        );
    }

    /**
     * Handles when this class's game info select menu is interacted with.
     *
     * @param event the event to handle
     */
    public static void handleSelectMenu(StringSelectInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        String gamePk = parts[2];
        String team = parts[3];

        // We get the team name the dirtiest way possible.
        String placeholder = event.getSelectMenu().getPlaceholder();
        Checks.notNull(placeholder, "Placeholder"); // We set a placeholder, so this should never be null
        String teamName = placeholder.substring(7, event.getSelectMenu().getPlaceholder().length() - 5);

        // Determine what the user clicked
        String action = event.getSelectedOptions().get(0).getValue();
        switch (action) {
            case "lineup" -> event.reply(buildLineup(gamePk, team, teamName)).setEphemeral(true).queue();
            case "scoring_plays" -> buildScoringPlays(gamePk, team, event);
            case "boxscore" -> buildBoxScore(gamePk, team, "batters", event);
        }
    }

    /**
     * Builds an embed of scoring plays for a team
     *
     * @param gamePk the gamePk to get the scoring plays for
     * @param team the team to get the scoring plays for, 'away' or 'home'
     * @param event the event to reply to
     */
    public static void buildScoringPlays(String gamePk, String team, GenericComponentInteractionCreateEvent event) {
        GameState gameInfo = GameState.fromPk(gamePk);
        if (gameInfo.failed()) {
            event.replyEmbeds(EmbedUtil.failure("Failed to get game info. Please try again later.")).setEphemeral(true).queue();
            return;
        }

        Map<String, List<String>> inningMap = new HashMap<>();
        GameState.TeamInfo selectedTeam = team.equals("home") ? gameInfo.home() : gameInfo.away();
        String inningState = team.equals("home") ? "Bottom" : "Top";
        String scoreTemplate = "%s%s %s%s - %s%s %s%s".formatted(
            team.equals("away") ? "**" : "", gameInfo.away().abbreviation(), "%d", team.equals("away") ? "**" : "",
            team.equals("home") ? "**" : "", "%d", gameInfo.home().abbreviation(), team.equals("home") ? "**" : ""
        );

        List<JSONObject> scoringPlays = gameInfo.scoringPlays();
        for (JSONObject play : scoringPlays) {
            JSONObject about = play.getJSONObject("about");
            if ((team.equals("home") && about.getBoolean("isTopInning") ||
                (team.equals("away") && !about.getBoolean("isTopInning")))) {
                continue;
            }

            JSONObject result = play.getJSONObject("result");

            String inning = about.getInt("inning") + "";
            String description = result.getString("description");
            String fullDesc = "- [%s] %s *(+%s RBI)*"
                .formatted(scoreTemplate.formatted(result.getInt("awayScore"), result.getInt("homeScore")), description, result.getInt("rbi"));

            inningMap.computeIfAbsent(inning, k -> new ArrayList<>()).add(fullDesc);
        }

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Scoring Plays for %s".formatted(selectedTeam.name()));

        inningMap.forEach((inning, plays) -> {
            embed.addField("%s %s".formatted(inningState, inning), String.join("\n", plays), false);
        });

        if (inningMap.isEmpty()) {
            embed.setDescription("No scoring plays for this team.");
        }

        Button refreshButton = Button.secondary("gameinfo:scoring_plays:%s:%s".formatted(gamePk, team), "Refresh").withDisabled(gameInfo.isFinal());

        // send the message, if the initial button is pressed
        if (event instanceof StringSelectInteractionEvent) {
            event.replyEmbeds(embed.build()).setComponents(ActionRow.of(refreshButton)).setEphemeral(true).queue();
        } else { // edit it if they're just clicking through
            event.editMessageEmbeds(embed.build()).queue();
        }
    }

    /**
     * Builds a lineup for a team
     *
     * @param gamePk the gamePk to get the lineup for
     * @param awayOrHome the team to get the lineup for, 'away' or 'home'
     * @param teamName the name of the team
     * @return the lineup as a string
     */
    public static String buildLineup(String gamePk, String awayOrHome, String teamName) {
        var lineup = MLBAPIUtil.getLineup(gamePk, awayOrHome);

        List<String> friendly = new ArrayList<>();

        friendly.add("# " + teamName + " Lineup");
        friendly.add("The following is the lineup for this team. It is subject to change at any time.");
        friendly.add("## Batting Order");

        var battingOrder = lineup.get("Batting Order");

        if (battingOrder.isEmpty()) {
            friendly.add("The batting order is currently not available. Please try again closer to the scheduled game time.");
        } else {
            for (var player : battingOrder) {
                friendly.add(player.friendlyString());
            }
        }

        // add a string before the next-to-last element. e.g. "a", "b", "c" <-- between b and c
        friendly.add("## Probable Pitcher");

        var probablePitcher = lineup.get("Probable Pitcher");

        if (probablePitcher.isEmpty()) {
            friendly.add("The probable pitcher is currently not available. Please try again closer to the scheduled game time.");
        } else {
            friendly.add(probablePitcher.get(0).friendlyString());
        }

        return String.join("\n", friendly);
    }

    /**
     * Builds the box score response. Mostly table images, but also info as text.
     *
     * @param gamePk the gamePk to get the box score for
     * @param homeOrAway the team to get the box score for, 'home' or 'away'
     * @param type the type of box score to get, 'batters', 'pitchers', 'bench', 'bullpen', or 'info'
     * @param event the event to reply to
     */
    public static void buildBoxScore(String gamePk, String homeOrAway, String type, GenericComponentInteractionCreateEvent event) {
        // get game info
        GameState info = GameState.fromPk(gamePk);
        if (info.failed()) {
            event.replyEmbeds(EmbedUtil.failure("Failed to get game info")).queue();
            return;
        }

        // get box score data
        JSONObject data = RestClient.get("https://api.chew.pro/sports/mlb/%s/boxscore".formatted(gamePk)).asJSONObject();
        GameState.TeamInfo team = homeOrAway.equals("home") ? info.home() : info.away();

        String title = """
            # Box Score for %s @ %s
            Viewing box score for team %s.
            Use the buttons on the bottom to navigate.
            """
            .formatted(
                info.away().clubName(), info.home().clubName(),
                team.name()
            );

        // create the action row, allowing users to switch between different box score types
        ActionRow row = ActionRow.of(
            Button.primary("gameinfo:boxscore:" + gamePk + ":" + homeOrAway + ":batters", "Batters"),
            Button.primary("gameinfo:boxscore:" + gamePk + ":" + homeOrAway + ":pitchers", "Pitchers"),
            Button.primary("gameinfo:boxscore:" + gamePk + ":" + homeOrAway + ":bench", "Bench").withDisabled(info.isFinal()),
            Button.primary("gameinfo:boxscore:" + gamePk + ":" + homeOrAway + ":bullpen", "Bullpen").withDisabled(info.isFinal()),
            Button.primary("gameinfo:boxscore:" + gamePk + ":" + homeOrAway + ":info", "Info")
        );

        // we do our own stuff :)
        if (type.equals("info")) {
            // 4 types of info for team: batting, baserunning, fielding, and notes. Notes are special.
            JSONObject teamData = data.getJSONObject("teams").getJSONObject(homeOrAway).getJSONObject("info");

            List<String> infoResponse = new ArrayList<>();
            infoResponse.add(title);

            JSONArray notes = teamData.getJSONArray("notes");
            if (!notes.isEmpty()) {
                infoResponse.add("Notes");
                for (JSONObject note : MiscUtil.toList(notes, JSONObject.class)) {
                    infoResponse.add("-# %s-%s".formatted(note.getString("label"), note.getString("value")));
                }

                infoResponse.add("");
            }

            String[] types = {"batting", "baserunning", "fielding"};
            for (String noteType : types) {
                if (teamData.isNull(noteType))
                    continue;

                JSONArray noteData = teamData.getJSONArray(noteType);
                infoResponse.add("## " + MiscUtil.capitalize(noteType));
                for (JSONObject note : MiscUtil.toList(noteData, JSONObject.class)) {
                    infoResponse.add("**%s** %s".formatted(note.getString("label"), note.getString("value")));
                }
            }

            // send the message, if the initial button is pressed
            if (event instanceof StringSelectInteractionEvent) {
                event.reply(String.join("\n", infoResponse)).addComponents(row).setEphemeral(true).queue();
            } else { // edit it if they're just clicking through
                event.editMessage(String.join("\n", infoResponse)).setFiles(Collections.emptyList()).setComponents(row).queue();
            }

            return;
        }

        // get batters
        List<JSONObject> batters = MiscUtil.toList(data.getJSONObject("teams").getJSONObject(homeOrAway).getJSONArray(type), JSONObject.class);

        // build the table
        BoxScoreDataSets set = BoxScoreDataSets.valueOf(type.toUpperCase());
        String[][] values = new String[batters.size() + 1][set.length()];
        values[0] = set.headers(team.abbreviation());

        for (int i = 0; i < batters.size(); i++) {
            JSONObject batter = batters.get(i);
            JSONObject stats = batter.getJSONObject("stats");
            values[i + 1] = set.parseFromLine(batter.getString("name"), stats);
        }

        // create the image
        ImageUtil.GeneratedImage image = ImageUtil.createTable(values, set);
        if (image.failed()) {
            event.replyEmbeds(EmbedUtil.failure("Failed to create box score image")).queue();
            return;
        }

        // send the message, if the initial button is pressed
        if (event instanceof StringSelectInteractionEvent) {
            event.reply(title).addComponents(row).addFiles(image.asFileUpload()).setEphemeral(true).queue();
        } else { // edit it if they're just clicking through
            event.editMessage(title).setComponents(row).setFiles(image.asFileUpload()).queue();
        }
    }

    /**
     * Enum of the different data sets for the box score. Contains headers and widths for the image.
     */
    public enum BoxScoreDataSets {
        /**
         * Showing batters who had a plate appearance
         */
        BATTERS(
            new String[]{"Batters", "AB", "R", "H", "RBI", "BB", "K", "AVG", "OPS"},
            new int[]{100, 30, 30, 30, 30, 30, 30, 50, 50}
        ),
        /**
         * Showing pitchers who pitched in the game
         */
        PITCHERS(
            new String[]{"Pitchers", "IP", "H", "R", "ER", "BB", "K", "ERA"},
            new int[]{100, 30, 30, 30, 30, 30, 30, 50}
        ),
        /**
         * Showing batters who haven't been subbed in yet
         */
        BENCH(
            new String[]{"Bench", "B", "POS", "AVG", "G", "R", "H", "HR", "RBI", "SB"},
            new int[]{100, 30, 30, 50, 30, 30, 30, 30, 30, 30}
        ),
        /**
         * Showing pitchers who haven't pitched yet
         */
        BULLPEN(
            new String[]{"Bullpen", "T", "ERA", "IP", "H", "BB", "K"},
            new int[]{100, 30, 50, 30, 30, 30, 30}
        );

        private final String[] headers;
        private final int[] widths;

        BoxScoreDataSets(String[] headers, int[] widths) {
            this.headers = headers;
            this.widths = widths;
        }

        public String[] headers(String abbrev) {
            String[] team_headers = new String[headers.length];
            team_headers[0] = headers[0] + " - " + abbrev;
            System.arraycopy(headers, 1, team_headers, 1, headers.length - 1);

            return team_headers;
        }

        public int[] columnWidths() {
            return widths;
        }

        /**
         * Parses out the data from a line of the box score
         *
         * @param name the name of the player
         * @param stats their box score stats
         * @return the parsed line
         */
        public String[] parseFromLine(String name, JSONObject stats) {
            String[] line = new String[headers.length];
            line[0] = name;
            for (int i = 1; i < headers.length; i++) {
                line[i] = stats.optString(headers[i].toLowerCase(Locale.ROOT), "");
            }

            return line;
        }

        /**
         * The total width of all the columns
         *
         * @return the total width
         */
        public int totalWidth() {
            int total = 0;
            for (int width : widths) {
                total += width;
            }
            return total;
        }

        public int length() {
            return headers.length;
        }
    }
}
