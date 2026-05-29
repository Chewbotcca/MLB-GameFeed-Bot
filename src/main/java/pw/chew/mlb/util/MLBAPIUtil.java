package pw.chew.mlb.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.geysermc.discordbot.util.DicesCoefficient;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.RestClient;
import pw.chew.mlb.objects.MLBTeam;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static pw.chew.mlb.MLBBot.SEASON;

public class MLBAPIUtil {
    /// CACHING ///
    public static final Cache<String, Sports> sportsCache = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofDays(1)).build();
    public static final Cache<String, Teams> teamsCache = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofDays(1)).build();

    // Prevent instantiation
    private MLBAPIUtil() {
    }

    public static Sports getSports() {
        if (sportsCache.getIfPresent("all") != null) {
            return sportsCache.getIfPresent("all");
        }
        Sports sports = new Sports(RestClient.get("https://statsapi.mlb.com/api/v1/sports?fields=").asJSONObject().getJSONArray("sports"));
        sportsCache.put("all", sports);
        return sports;
    }

    public static Teams getTeams() {
        return getTeams("1");
    }

    public static Teams getTeams(String sportId) {
        if (teamsCache.getIfPresent(sportId) != null) {
            return teamsCache.getIfPresent(sportId);
        }
        Teams teams = new Teams(RestClient.get("https://statsapi.mlb.com/api/v1/teams?sportIds=%s&season=%s".formatted(sportId, SEASON)).asJSONObject().getJSONArray("teams"));
        teamsCache.put(sportId, teams);
        return teams;
    }

    public static Map<String, List<Player>> getLineup(String gamePk, String homeAway) {
        JSONObject data = RestClient.get("https://statsapi.mlb.com/api/v1.1/game/%s/feed/live?language=en&fields=liveData,boxscore,teams,away,home,players,id,fullName,jerseyNumber,position,name,abbreviation,seasonStats,pitching,era,wins,losses,strikeOuts,batting,avg,ops,homeRuns,gameData,probablePitchers,away,home,id"
            .formatted(gamePk)).asJSONObject();
        JSONObject boxScore = data.getJSONObject("liveData")
            .getJSONObject("boxscore")
            .getJSONObject("teams");

        Map<String, List<Player>> response = new HashMap<>();

        Map<Integer, Player> players = new HashMap<>();
        for (String team :new String[]{"home", "away"}) {
            JSONObject teamPlayers = boxScore.getJSONObject(team).getJSONObject("players");
            for (String item : teamPlayers.keySet()) {
                Player player = new Player(teamPlayers.getJSONObject(item));
                players.put(player.id(), player);
            }
        }

        // get the batting order
        JSONArray lineup = boxScore.getJSONObject(homeAway).getJSONArray("battingOrder");
        List<Player> lineupPlayers = new ArrayList<>();
        for (Object item : lineup) {
            // json array of int player ID
            int playerId = (int) item;
            Player player = players.get(playerId);
            lineupPlayers.add(player);
        }

        response.put("Batting Order", lineupPlayers);

        // add pitcher to end
        JSONObject probablePitchers = data.getJSONObject("gameData").getJSONObject("probablePitchers");
        if (probablePitchers.has(homeAway)) {
            int probablePitcher = probablePitchers.getJSONObject(homeAway).getInt("id");
            Player pitcher = players.get(probablePitcher);
            response.put("Probable Pitcher", Collections.singletonList(pitcher));
        } else {
            // empty list
            response.put("Probable Pitcher", Collections.emptyList());
        }

        return response;
    }

    public static List<Affiliate> getAffiliates(String teamId) {
        JSONArray affiliates = RestClient.get("https://statsapi.mlb.com/api/v1/teams/affiliates?teamIds=%s&season=%s&hydrate=standings,sport&fields=teams,name,id,venue,name,league,division,sport,abbreviation,sortOrder,record,wins,losses,winningPercentage,active"
            .formatted(teamId, SEASON)).asJSONObject().getJSONArray("teams");

        // wrap each array item as an affiliate object
        List<Affiliate> affiliatesList = new ArrayList<>();
        for (int i = 0; i < affiliates.length(); i++) {
            affiliatesList.add(new Affiliate(affiliates.getJSONObject(i)));
        }
        affiliatesList.sort(Comparator.comparingInt(Affiliate::sortOrder));
        return affiliatesList;
    }

    /**
     * Gets the current MLB standings.
     *
     * TODO: Support MiLB.
     */
    public static Map<String, List<Standing>> getStandings(String leagueId) {
        JSONArray standings = RestClient.get("https://statsapi.mlb.com/api/v1/standings?leagueId=%s&hydrate=division&season=%s".formatted(leagueId, SEASON)).asJSONObject().getJSONArray("records");
        HashMap<String, List<Standing>> standingsMap = new HashMap<>();
        for (int i = 0; i < standings.length(); i++) {
            JSONObject division = standings.getJSONObject(i);
            // division > name
            String divisionName = division.getJSONObject("division").getString("name");

            // division records
            JSONArray teamRecords = division.getJSONArray("teamRecords");

            // convert each teamRecord object to a Standing object
            List<Standing> divisionStandings = new ArrayList<>();
            for (int j = 0; j < teamRecords.length(); j++) {
                divisionStandings.add(new Standing(teamRecords.getJSONObject(j)));
            }

            standingsMap.put(divisionName, divisionStandings);
        }
        return standingsMap;

    }


    public static List<Game> getSchedule(int teamId, String sportId) {
        JSONArray games = new JSONObject(RestClient.get("https://statsapi.mlb.com/api/v1/schedule?lang=en&sportId=%s&season=%s&teamId=%s&hydrate=team&fields=totalGames,totalGamesInProgress,dates,date,games,gamePk,gameType,gameDate,status,detailedState,abstractGameState,teams,away,home,team,name,id,clubName,abbreviation,score,seriesGameNumber,seriesDescription"
            .formatted(sportId, SEASON, teamId)))
            .getJSONArray("dates");

        // iterate through date
        List<Game> schedule = new ArrayList<>();
        for (int i = 0; i < games.length(); i++) {
            // Formatted as YYYY-MM-DD
            String date = games.getJSONObject(i).getString("date");

            // iterate through games
            JSONArray dayGames = games.getJSONObject(i).getJSONArray("games");
            for (int j = 0; j < dayGames.length(); j++) {
                JSONObject game = dayGames.getJSONObject(j);
                schedule.add(new Game(game));
            }
        }

        return schedule;
    }

    /// Records for data wrapping ///

    public record Sports(JSONArray raw) {
        public List<Command.Choice> asChoices() {
            List<Command.Choice> choices = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject sport = raw.getJSONObject(i);
                choices.add(new Command.Choice(sport.getString("name"), sport.getInt("id")));
            }
            return choices;
        }
    }

    public record Teams(JSONArray raw) {
        public List<Command.Choice> asChoices() {
            List<Command.Choice> choices = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject team = raw.getJSONObject(i);
                choices.add(new Command.Choice(team.getString("name"), team.getInt("id")));
            }
            // only up to 25 choices
            return choices.subList(0, Math.min(choices.size(), 25));
        }

        public List<Command.Choice> potentialChoices(String query) {
            // If we don't have an input, return all choices
            if (query.isBlank()) {
                return asChoices();
            }

            List<Command.Choice> potential = new ArrayList<>();

            // Search the teams by an exact starting match
            for (Object teamObj : raw) {
                JSONObject team = ((JSONObject) teamObj);
                if (team.getString("name").toLowerCase().startsWith(query.toLowerCase())) {
                    potential.add(new Command.Choice(team.getString("name"), team.getInt("id")));
                }
            }

            // Find the best similarity
            for (Object teamObj : raw) {
                JSONObject team = ((JSONObject) teamObj);
                double similar = DicesCoefficient.diceCoefficientOptimized(query.toLowerCase(), team.getString("name").toLowerCase());
                if (similar > 0.2d) {
                    potential.add(new Command.Choice(team.getString("name"), team.getInt("id")));
                }
            }

            // Send a message if we don't know what team
            return potential;
        }
    }

    public record Player(JSONObject raw) {
        public int id() {
            return raw.getJSONObject("person").getInt("id");
        }

        public String name() {
            return raw.getJSONObject("person").getString("fullName");
        }

        public String number() {
            return raw.getString("jerseyNumber");
        }

        public Position position() {
            return new Position(raw.getJSONObject("position"));
        }

        public boolean isPitcher() {
            return position().abbreviation().equals("P");
        }

        public String era() {
            return raw.getJSONObject("seasonStats").getJSONObject("pitching").getString("era");
        }

        public int wins() {
            return raw.getJSONObject("seasonStats").getJSONObject("pitching").getInt("wins");
        }

        public int losses() {
            return raw.getJSONObject("seasonStats").getJSONObject("pitching").getInt("losses");
        }

        public int strikeouts() {
            return raw.getJSONObject("seasonStats").getJSONObject("pitching").getInt("strikeOuts");
        }

        public String avg() {
            return raw.getJSONObject("seasonStats").getJSONObject("batting").getString("avg");
        }

        public String ops() {
            return raw.getJSONObject("seasonStats").getJSONObject("batting").getString("ops");
        }

        public int homers() {
            return raw.getJSONObject("seasonStats").getJSONObject("batting").getInt("homeRuns");
        }

        public String friendlyString() {
            if (isPitcher()) {
                return "%s (%s - %s | %s ERA | %s K)".formatted(name(), wins(), losses(), era(), strikeouts());
            } else {
                return "%s (%s) - %s AVG, %s OPS, %s HR".formatted(name(), position().abbreviation(), avg(), ops(), homers());
            }
        }
    }

    public record Position(JSONObject raw) {
        public String name() {
            return raw.getString("name");
        }

        public String abbreviation() {
            return raw.getString("abbreviation");
        }
    }

    public record Affiliate(JSONObject raw) {
        public int id() {
            return raw.getInt("id");
        }

        public String name() {
            return raw.getString("name");
        }

        public String venueName() {
            return raw.getJSONObject("venue").getString("name");
        }

        public String abbreviation() {
            return raw.getString("abbreviation");
        }

        public int leagueId() {
            return raw.getJSONObject("league").getInt("id");
        }

        public String leagueName() {
            return raw.getJSONObject("league").getString("name");
        }

        public String divisionName() {
            return raw.getJSONObject("division").getString("name");
        }

        public Sport sport() {
            return new Sport(raw.getJSONObject("sport"));
        }

        public int wins() {
            return raw.getJSONObject("record").getInt("wins");
        }

        public int losses() {
            return raw.getJSONObject("record").getInt("losses");
        }

        public String pct() {
            return raw.getJSONObject("record").getString("winningPercentage");
        }

        public boolean active() {
            return raw.getBoolean("active");
        }

        public record Sport(JSONObject raw) {
            public String name() {
                return raw.getString("name");
            }

            public String abbreviation() {
                return raw.getString("abbreviation");
            }
        }

        public int sortOrder() {
            return raw.getJSONObject("sport").getInt("sortOrder");
        }
    }

    public record Standing(JSONObject raw) {
        public String teamName() {
            // team > name
            return raw.getJSONObject("team").getString("name");
        }

        public String streak() {
            // streak > streakCode
            return raw.getJSONObject("streak").getString("streakCode");
        }

        public String rank() {
            // divisionRank
            return raw.getString("divisionRank");
        }

        public int wins() {
            // wins
            return raw.getInt("wins");
        }

        public int losses() {
            // losses
            return raw.getInt("losses");
        }

        public String winPct() {
            // winningPercentage
            return raw.getString("winningPercentage");
        }

        public int runDifferential() {
            // runDifferential
            return raw.getInt("runDifferential");
        }

        public String gamesBack() {
            // gamesBack
            return raw.getString("gamesBack");
        }

        public String homeRecord() {
            // records > splitRecords[0] *jsonobject*
            JSONObject home = raw.getJSONObject("records").getJSONArray("splitRecords").getJSONObject(0);
            // wins - losses
            return "%s-%s".formatted(home.getInt("wins"), home.getInt("losses"));
        }

        public String awayRecord() {
            // records > splitRecords[1] *jsonobject*
            JSONObject away = raw.getJSONObject("records").getJSONArray("splitRecords").getJSONObject(1);
            // wins - losses
            return "%s-%s".formatted(away.getInt("wins"), away.getInt("losses"));
        }

        public String lastTen() {
            // records > splitRecords[8] *jsonobject*
            JSONObject lastTen = raw.getJSONObject("records").getJSONArray("splitRecords").getJSONObject(8);
            // wins - losses
            return "%s-%s".formatted(lastTen.getInt("wins"), lastTen.getInt("losses"));
        }
    }

    public record Game(JSONObject raw) {
        public String gamePk() {
            return raw.getString("gamePk");
        }

        public String gameType() {
            return raw.getString("gameType");
        }

        public OffsetDateTime gameDate() {
            TemporalAccessor accessor = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(raw.getString("gameDate"));
            return OffsetDateTime.from(accessor);
        }

        public String abstractGameState() {
            return raw.getJSONObject("status").getString("abstractGameState");
        }

        public String detailedState() {
            return raw.getJSONObject("status").getString("detailedState");
        }

        public boolean isCancelled() {
            // detailedState == "Cancelled"
            return detailedState().equals("Cancelled");
        }

        public boolean isFinal() {
            return abstractGameState().equals("Final");
        }

        public MLBTeam away() {
            return new MLBTeam(raw.getJSONObject("teams").getJSONObject("away"));
        }

        public MLBTeam home() {
            return new MLBTeam(raw.getJSONObject("teams").getJSONObject("home"));
        }

        public int seriesGameNumber() {
            return raw.getInt("seriesGameNumber");
        }

        public String seriesDescription() {
            return raw.getString("seriesDescription");
        }
    }
}
