package pw.chew.mlb.commands;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for /setinfo "nextgame" time formatting (issue #6).
 * <p>
 * The MLB API returns game times in UTC. The channel name should show the
 * start time in the selected team's home time zone, regardless of the time
 * zone of the machine running the bot.
 */
class SetInfoCommandTest {
    private TimeZone originalDefault;

    @BeforeEach
    void runAsUtcServer() {
        // Simulate the bot running on a typical UTC host, which is what caused the bug
        originalDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalDefault);
    }

    @Test
    void rangersVsRaysShowsCentralTime() {
        // The example from issue #6: 5:50 PM Central, not Eastern or UTC
        String gameDate = "2026-06-10T22:50:00Z";
        assertEquals("6/10", SetInfoCommand.formatGameDate(gameDate, "America/Chicago"));
        assertEquals("5:50 PM", SetInfoCommand.formatGameTime(gameDate, "America/Chicago"));
    }

    @Test
    void westCoastNightGameKeepsLocalDate() {
        // 7:10 PM Pacific on 7/14 is already 7/15 in UTC
        String gameDate = "2026-07-15T02:10:00Z";
        assertEquals("7/14", SetInfoCommand.formatGameDate(gameDate, "America/Los_Angeles"));
        assertEquals("7:10 PM", SetInfoCommand.formatGameTime(gameDate, "America/Los_Angeles"));
    }

    @Test
    void arizonaIgnoresDaylightSaving() {
        // Phoenix stays on MST (UTC-7) all year
        String gameDate = "2026-07-04T01:40:00Z";
        assertEquals("7/3", SetInfoCommand.formatGameDate(gameDate, "America/Phoenix"));
        assertEquals("6:40 PM", SetInfoCommand.formatGameTime(gameDate, "America/Phoenix"));
    }

    @Test
    void torontoShowsEasternTime() {
        String gameDate = "2026-04-01T23:07:00Z";
        assertEquals("4/1", SetInfoCommand.formatGameDate(gameDate, "America/Toronto"));
        assertEquals("7:07 PM", SetInfoCommand.formatGameTime(gameDate, "America/Toronto"));
    }

    @Test
    void handlesEndOfDaylightSaving() {
        // Same 4:08 PM Pacific start the day before and the day after DST ends (Nov 1, 2026)
        assertEquals("4:08 PM", SetInfoCommand.formatGameTime("2026-10-31T23:08:00Z", "America/Los_Angeles"));
        assertEquals("4:08 PM", SetInfoCommand.formatGameTime("2026-11-02T00:08:00Z", "America/Los_Angeles"));
    }

    @Test
    void readsVenueTimeZoneFromApiResponse() {
        // Trimmed from /api/v1/teams/140?hydrate=nextSchedule,venue(timezone)
        JSONObject team = new JSONObject("""
            {
              "id": 140,
              "name": "Texas Rangers",
              "venue": {
                "id": 5325,
                "name": "Globe Life Field",
                "timeZone": { "id": "America/Chicago", "offset": -5, "tz": "CDT" }
              }
            }
            """);
        assertEquals("America/Chicago", SetInfoCommand.getVenueTimeZone(team));
    }

    @Test
    void fallsBackWhenVenueTimeZoneMissing() {
        JSONObject noVenue = new JSONObject("{ \"id\": 140, \"name\": \"Texas Rangers\" }");
        JSONObject noTimeZone = new JSONObject("{ \"id\": 140, \"venue\": { \"id\": 5325 } }");
        assertEquals(SetInfoCommand.DEFAULT_TIME_ZONE, SetInfoCommand.getVenueTimeZone(noVenue));
        assertEquals(SetInfoCommand.DEFAULT_TIME_ZONE, SetInfoCommand.getVenueTimeZone(noTimeZone));
    }
}
