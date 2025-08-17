package pw.chew.mlb.util;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.RestClient;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static pw.chew.mlb.MLBBot.SEASON;

public class AutocompleteUtil {
    /**
     * The time zone for New York.
     * We use New York because there is rarely games played east of it, and MLB is headquartered in New York.
     */
    private final static TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    // can't be instantiated
    private AutocompleteUtil() {}

    /**
     * Completely handles the input for the autocomplete command.
     *
     * @param event the event
     * @return a list of choices
     */
    public static List<Command.Choice> handleInput(CommandAutoCompleteInteractionEvent event) {
        switch (event.getFocusedOption().getName()) {
            case "team" -> {
                // get current value of sport
                String sport = event.getOption("sport", "1", OptionMapping::getAsString);
                String input = event.getFocusedOption().getValue();

                return AutocompleteUtil.getTeams(sport, input);
            }
            case "sport" -> {
                return AutocompleteUtil.getSports();
            }
            case "date", "game" -> {
                int teamId = event.getOption("team", -1, OptionMapping::getAsInt);
                String sport = event.getOption("sport", "1", OptionMapping::getAsString);

                return AutocompleteUtil.getTeamGames(teamId, sport);
            }
        }

        return Collections.emptyList();
    }

    /**
     * Gets a list of sports for autocomplete.
     *
     * @return a list of sports
     */
    public static List<Command.Choice> getSports() {
        return MLBAPIUtil.getSports().asChoices();
    }

    /**
     * Gets a list of teams for autocomplete. Defaults to MLB
     *
     * @param sport the sport ID
     * @param input never null but might be blank input
     * @return a list of teams
     */
    public static List<Command.Choice> getTeams(String sport, String input) {
        List<Command.Choice> choices = MLBAPIUtil.getTeams(sport).potentialChoices(input);

        // Ensure no duplicates and no more than 25 choices
        return choices.stream().distinct().limit(25).toList();
    }

    /**
     * Gets up to 25 upcoming games for a team.
     *
     * @param teamId the team ID
     * @param sportId the sport ID of the team
     * @return a list of games, or a "Please select a team first!" choice if the team ID is -1
     */
    public static List<Command.Choice> getTeamGames(int teamId, String sportId) {
        if (teamId == -1) {
            return List.of(new Command.Choice("Please select a team first!", -1));
        }

        JSONArray games = RestClient.get("https://statsapi.mlb.com/api/v1/schedule?lang=en&sportId=%S&season=%s&teamId=%S&fields=dates,date,games,gamePk,teams,away,team,teamName,id&hydrate=team".formatted(sportId, SEASON, teamId))
            .asJSONObject()
            .getJSONArray("dates");

        List<Command.Choice> choices = new ArrayList<>();
        for (int i = 0; i < games.length(); i++) {
            // Formatted as YYYY-MM-DD
            String date = games.getJSONObject(i).getString("date");

            // get today at eastern time
            Calendar yesterday = Calendar.getInstance(NEW_YORK);
            // yesterday
            yesterday.add(Calendar.DATE, -1);

            Calendar gameDate = Calendar.getInstance(NEW_YORK);
            gameDate.set(
                Integer.parseInt(date.split("-")[0]),
                Integer.parseInt(date.split("-")[1]) - 1,
                Integer.parseInt(date.split("-")[2])
            );

            // skip if before today
            if (gameDate.before(yesterday) || gameDate.equals(yesterday)) {
                continue;
            }

            JSONArray dayGames = games.getJSONObject(i).getJSONArray("games");
            for (int j = 0; j < dayGames.length(); j++) {
                JSONObject game = dayGames.getJSONObject(j);

                // find if we're home or away
                JSONObject away = game.getJSONObject("teams").getJSONObject("away").getJSONObject("team");
                JSONObject home = game.getJSONObject("teams").getJSONObject("home").getJSONObject("team");

                boolean isAway = away.getInt("id") == teamId;
                String opponent = isAway ? home.getString("teamName") : away.getString("teamName");

                String name = "%s %s - %s%s".formatted(isAway ? "@" : "vs", opponent, date, dayGames.length() > 1 ? " (Game %d)".formatted(j + 1) : "");
                choices.add(new Command.Choice(name, game.getInt("gamePk")));
            }
        }

        // Ensure no more than 25 choices, and no duplicates
        choices = choices.stream().distinct().limit(25).toList();

        return choices;
    }

    /**
     * Gets a list of games for today.
     *
     * @param showFinal whether to show final games
     * @return a list of games
     */
    public static List<Command.Choice> getTodayGames(boolean showFinal) {
        // Build the current date String as MM/DD/YYYY
        String today = OffsetDateTime.now(NEW_YORK.toZoneId()).format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));

        // Retrieve the current games
        JSONObject gameResponse = RestClient.get("https://statsapi.mlb.com/api/v1/schedule?language=en&sportId=1&date=" + today + "&hydrate=game,flags,team").asJSONObject();

        // Build games
        List<Command.Choice> choices = new ArrayList<>();

        JSONArray dates = gameResponse.getJSONArray("dates");

        // No games today
        if (dates.isEmpty()) {
            return choices;
        }

        JSONArray games = dates.getJSONObject(0).getJSONArray("games");

        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.getJSONObject(i);

            String status = game.getJSONObject("status").getString("abstractGameState");

            if (status.equals("Live") || showFinal) {
                String home = game.getJSONObject("teams").getJSONObject("home").getJSONObject("team").getString("clubName");
                String awa = game.getJSONObject("teams").getJSONObject("away").getJSONObject("team").getString("clubName");

                boolean isDoubleHeader = game.getString("doubleHeader").equals("Y");

                String name = String.format("%s @ %s", awa, home);
                if (isDoubleHeader) {
                    name += " (Game %d)".formatted(game.getInt("gameNumber"));
                }

                choices.add(new Command.Choice(name, game.getInt("gamePk")));
            }
        }

        return choices;
    }
}
