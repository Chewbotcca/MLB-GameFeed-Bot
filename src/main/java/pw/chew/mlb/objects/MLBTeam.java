package pw.chew.mlb.objects;

import org.json.JSONObject;

public record MLBTeam(JSONObject data) {
    public int id() {
        return data.getJSONObject("team").getInt("id");
    }

    public String name() {
        return data.getJSONObject("team").getString("teamName");
    }

    public JSONObject record() {
        return data.getJSONObject("leagueRecord");
    }

    public int wins() {
        return record().getInt("wins");
    }

    public int losses() {
        return record().getInt("losses");
    }

    public String probablePitcher() {
        JSONObject fallback = new JSONObject().put("fullName", "TBD");
        return data.optJSONObject("probablePitcher", fallback).getString("fullName");
    }
}
