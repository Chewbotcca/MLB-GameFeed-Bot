package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.geysermc.discordbot.util.DicesCoefficient;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static pw.chew.mlb.MLBBot.SEASON;
import static pw.chew.mlb.MLBBot.TEAMS;

public class SetInfoCommand extends SlashCommand {
    // Fallback if the MLB API doesn't return a venue time zone
    static final String DEFAULT_TIME_ZONE = "America/New_York";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    public SetInfoCommand() {
        this.name = "setinfo";
        this.help = "Sets a voice channel name to a specified piece of info.";
        this.descriptionLocalization = Map.of(
            DiscordLocale.ENGLISH_US, "Sets a voice channel name to a specified piece of info.",
            DiscordLocale.SPANISH, "Establece el nombre de un canal de voz en una pieza de información especificada."
        );

        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD};
        this.userPermissions = new Permission[]{Permission.MANAGE_CHANNEL};
        this.botPermissions = new Permission[]{Permission.MANAGE_CHANNEL};

        this.options = Arrays.asList(
            new OptionData(OptionType.CHANNEL, "channel", "The channel to set the info in.", true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "El canal para establecer la información.")
                .setChannelTypes(ChannelType.VOICE),
            new OptionData(OptionType.STRING, "team", "What team to grab info for.", true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "Qué equipo para obtener información.")
                .setAutoComplete(true),
            new OptionData(OptionType.STRING, "info", "The info to set the channel name to.", true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "La información para establecer el nombre del canal.")
                .addChoices(
                    new Command.Choice("Standings (Team: WINS-LOSS, xth in division)", "standings")
                        .setNameLocalization(DiscordLocale.SPANISH, "Clasificación (Equipo: WINS-LOSS, xth en división)"),
                    new Command.Choice("Next Game (Team: DATE: vs/@ OPP, hh:mm A/PM)", "nextgame")
                        .setNameLocalization(DiscordLocale.SPANISH, "Próximo juego (Equipo: FECHA: vs/@ OPP, hh:mm A/PM)")
                )
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        VoiceChannel channel = event.getOption("channel", OptionMapping::getAsChannel).asVoiceChannel();
        String team = event.getOption("team", OptionMapping::getAsString);

        String info = "uh oh";

        JSONObject teamInfo = getTeamInfo(team);

        if (teamInfo == null) {
            event.reply("Could not find team " + team).setEphemeral(true).queue();
            return;
        }

        switch (event.getOption("info", "", OptionMapping::getAsString)) {
            case "standings" -> {

                String abbreviation = teamInfo.getString("abbreviation");
                int wins = 0;
                int losses = 0;
                int position = 0;
                String divisionAbbreviation = "";

                int division = teamInfo.getJSONObject("division").getInt("id");
                String divisionName = teamInfo.getJSONObject("division").getString("name");
                // Get the first letter of each word in the division name
                for (String word : divisionName.split(" ")) {
                    divisionAbbreviation += word.substring(0, 1);
                }

                JSONObject standings = RestClient.get("https://statsapi.mlb.com/api/v1/standings?leagueId=103,104&standingsTypes=regularSeason&hydrate=team&season=" + SEASON).asJSONObject();

                // Gotta find the team now...
                JSONArray records = standings.getJSONArray("records");
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.getJSONObject(i);
                    if (record.getJSONObject("division").getInt("id") != division) continue;

                    JSONArray teamRecords = record.getJSONArray("teamRecords");

                    for (int j = 0; j < teamRecords.length(); j++) {
                        JSONObject teamRecord = teamRecords.getJSONObject(j);
                        if (!teamRecord.getJSONObject("team").getString("name").equals(team)) {
                            continue;
                        }

                        position = j + 1;
                        wins = teamRecord.getJSONObject("leagueRecord").getInt("wins");
                        losses = teamRecord.getJSONObject("leagueRecord").getInt("losses");
                    }
                }

                // Ordinalize the position
                String ordinal = switch (position) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };

                info = String.format("%s: %s-%s, %s%s in %s", abbreviation, wins, losses, position, ordinal, divisionAbbreviation);
            }
            case "nextgame" -> {
                int teamId = teamInfo.getInt("id");

                JSONObject teamSchedule = RestClient.get(String.format("https://statsapi.mlb.com/api/v1/teams/%s?season=%s&hydrate=nextSchedule,venue(timezone)", teamId, SEASON)).asJSONObject();

                JSONObject nextGame = null;

                JSONArray upcomingGames = teamSchedule.getJSONArray("teams")
                    .getJSONObject(0)
                    .getJSONObject("nextGameSchedule")
                    .getJSONArray("dates");

                for (int i = 0; i < upcomingGames.length(); i++) {
                    JSONArray games = upcomingGames.getJSONObject(i).getJSONArray("games");
                    for (int j = 0; j < games.length(); j++) {
                        JSONObject game = games.getJSONObject(j);
                        if (game.getJSONObject("status").getString("abstractGameState").equals("Final")) continue;
                        if (nextGame != null) continue;

                        nextGame = game;

                        break;
                    }
                }

                if (nextGame == null) {
                    event.reply("Could not find a future game for " + team).setEphemeral(true).queue();
                    return;
                }

                int away = nextGame.getJSONObject("teams").getJSONObject("away").getJSONObject("team").getInt("id");
                int home = nextGame.getJSONObject("teams").getJSONObject("home").getJSONObject("team").getInt("id");

                boolean isHome = teamId == home;

                String opponent = getTeamInfo(nextGame.getJSONObject("teams").getJSONObject(isHome ? "away" : "home").getJSONObject("team").getString("name")).getString("abbreviation");

                // Show the start time in the selected team's home time zone, not the bot host's
                String teamTimeZone = getVenueTimeZone(teamSchedule.getJSONArray("teams").getJSONObject(0));

                info = String.format("%s: %s %s, %s",
                    formatGameDate(nextGame.getString("gameDate"), teamTimeZone),
                    isHome ? "vs" : "@",
                    opponent,
                    formatGameTime(nextGame.getString("gameDate"), teamTimeZone));
            }
        }

        String finalInfo = info;
        channel.getManager().setName(info).queue(unused ->
            event.reply("Set channel name to " + finalInfo).setEphemeral(true).queue()
        );
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        // Get the query
        String query = event.getFocusedOption().getValue();

        // Get the providers
        List<String> teams = potentialTeams(query);

        event.replyChoices(teams.stream()
                .distinct()
                .map(team -> new Command.Choice(team, team))
                .limit(25)
                .toArray(Command.Choice[]::new))
            .queue();
    }

    private List<String> potentialTeams(String query) {
        List<String> potential = new ArrayList<>();

        // Collect the providers
        List<String> teams = new ArrayList<>();
        for (JSONObject json : TEAMS) {
            String name = json.getString("name");
            teams.add(name);
        }

        // Search the providers by an exact starting match
        for (String team : teams) {
            if (team.toLowerCase().startsWith(query.toLowerCase())) {
                potential.add(team);
            }
        }

        // Find the best similarity
        for (String team : teams) {
            double similar = DicesCoefficient.diceCoefficientOptimized(query.toLowerCase(), team.toLowerCase());
            if (similar > 0.2d) {
                potential.add(team);
            }
        }

        // Send a message if we don't know what team
        return potential;
    }

    /**
     * Reads the venue time zone from a team object hydrated with venue(timezone).
     *
     * @param team a team object from the MLB API /teams endpoint
     * @return the IANA time zone ID, e.g. "America/Chicago", or {@link #DEFAULT_TIME_ZONE} if missing
     */
    static String getVenueTimeZone(JSONObject team) {
        JSONObject venue = team.optJSONObject("venue");
        if (venue == null) return DEFAULT_TIME_ZONE;
        JSONObject timeZone = venue.optJSONObject("timeZone");
        if (timeZone == null) return DEFAULT_TIME_ZONE;
        return timeZone.optString("id", DEFAULT_TIME_ZONE);
    }

    /**
     * Converts an MLB API game date (UTC, ISO-8601) to the given time zone.
     *
     * @param gameDate the "gameDate" value from the MLB API, e.g. "2026-06-10T22:50:00Z"
     * @param timeZoneId an IANA time zone ID, e.g. "America/Chicago"
     * @return the game start time in that zone
     */
    static ZonedDateTime toTimeZone(String gameDate, String timeZoneId) {
        return OffsetDateTime.parse(gameDate).atZoneSameInstant(ZoneId.of(timeZoneId));
    }

    /**
     * @return the game date as M/D in the given time zone, e.g. "6/10"
     */
    static String formatGameDate(String gameDate, String timeZoneId) {
        ZonedDateTime time = toTimeZone(gameDate, timeZoneId);
        return String.format("%s/%s", time.getMonthValue(), time.getDayOfMonth());
    }

    /**
     * @return the game start time in the given time zone, e.g. "5:50 PM"
     */
    static String formatGameTime(String gameDate, String timeZoneId) {
        return toTimeZone(gameDate, timeZoneId).format(TIME_FORMATTER);
    }

    private JSONObject getTeamInfo(String teamName) {
        for (JSONObject json : TEAMS) {
            if (json.getString("name").equals(teamName)) {
                return json;
            }
        }
        return null;
    }
}
