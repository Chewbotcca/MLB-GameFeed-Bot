package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import pw.chew.mlb.util.MLBAPIUtil;
import pw.chew.mlb.util.TeamEmoji;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StandingsCommand extends SlashCommand {

    public StandingsCommand() {
        this.name = "standings";
        this.help = "Get the current standings.";
        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL};
        this.options = List.of(
            new OptionData(OptionType.STRING, "division", "Select a division to view standings for!", true)
                // hardcode MLB for now. eventually milb will be added
                .addChoice("AL East", "American League East")
                .addChoice("AL Central", "American League Central")
                .addChoice("AL West", "American League West")
                .addChoice("NL East", "National League East")
                .addChoice("NL Central", "National League Central")
                .addChoice("NL West", "National League West")
        );

        this.descriptionLocalization = Map.of(
            DiscordLocale.ENGLISH_US, "Get the current standings.",
            DiscordLocale.SPANISH, "Obtenga las posiciones actuales.",
            DiscordLocale.GERMAN, "Erhalten Sie die aktuellen Platzierungen."
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        String division = event.optString("division", "American League West");

        // first we get standings
        var standings = MLBAPIUtil.getStandings("103,104").get(division);

        List<String> teams = new ArrayList<>();
        for (MLBAPIUtil.Standing standing : standings) {
            teams.add(
                """
                **%s) %s %s**
                **%s** W | **%s** L | **%s** pct | **%s** GB | **%s** Home | **%s** Away | **%s** L10
                """.formatted(
                    standing.rank(), TeamEmoji.fromName(standing.teamName()).getFormatted(), standing.teamName(),
                    standing.wins(), standing.losses(), standing.winPct(),
                    standing.gamesBack(), standing.homeRecord(), standing.awayRecord(), standing.lastTen()
                )
            );
        }

        // make a cute lil embed
        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Standings for " + division)
            .setDescription(String.join("\n", teams));

        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }
}
