package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.internal.utils.Checks;
import pw.chew.mlb.listeners.GameFeedHandler;
import pw.chew.mlb.objects.ActiveGame;
import pw.chew.mlb.objects.GameState;

import java.util.Map;

public class ScoreCommand extends SlashCommand {
    public ScoreCommand() {
        this.name = "score";
        this.help = "Shows the score and match-ups of the current game";
        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD};
        this.descriptionLocalization = Map.of(
            DiscordLocale.ENGLISH_US, "Shows the score and match-ups of the current game",
            DiscordLocale.SPANISH, "Muestra la puntuación y los enfrentamientos del juego actual"
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        Checks.notNull(event.getGuild(), "server");

        String currentGame = GameFeedHandler.currentGame(event.getGuildChannel());
        if (currentGame == null) {
            ActiveGame currentServerGame = GameFeedHandler.currentServerGame(event.getGuild());

            if (currentServerGame == null) {
                event.reply("No active game in this server, please start a game somewhere first.").setEphemeral(true).queue();
                return;
            }

            event.reply(buildScore(currentServerGame.gamePk(), currentServerGame.channelId()))
                .setComponents(ActionRow.of(Button.primary("gameinfo:send:%s".formatted(currentServerGame.gamePk()), "View Game Info")))
                .setEphemeral(true).queue();
        } else {
            event.reply(buildScore(currentGame, null))
                .setComponents(ActionRow.of(Button.primary("gameinfo:send:%s".formatted(currentGame), "View Game Info")))
                .setEphemeral(true).queue();
        }
    }

    public String buildScore(String gamePk, String channelId) {
        GameState state = GameState.fromPk(gamePk);

        String channelMention = "";
        if (channelId != null) {
            channelMention = String.format("*Showing score from <#%s>*\n\n", channelId);
        }

        return String.format(channelMention + """
                    Score: %s %s - %s %s
                    Inning: %s %s, %s out(s)
                    
                    Summary: %s
                    
                    Pitching: %s
                    Batting: %s
                    
                    On Base:
                    %s
                    """,
            state.away().clubName(), state.away().runs(), state.home().runs(), state.home().clubName(),
            state.inningState(), state.inningOrdinal(), state.outs(),
            state.summary(),
            state.currentPitcher(), state.currentBatter(),
            state.currentBases()
        );
    }
}
