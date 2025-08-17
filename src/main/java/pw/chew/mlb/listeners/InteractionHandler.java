package pw.chew.mlb.listeners;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;
import pw.chew.mlb.commands.GameInfoCommand;
import pw.chew.mlb.commands.PlanGameCommand;
import pw.chew.mlb.commands.StartGameCommand;
import pw.chew.mlb.objects.GameBlurb;
import pw.chew.mlb.objects.GameState;
import pw.chew.mlb.util.EmbedUtil;

public class InteractionHandler extends ListenerAdapter {
    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (event.getComponentId().startsWith("plangame:")) {
            String action = event.getComponentId().split(":")[1];
            String gamePk = event.getComponentId().split(":")[2];
            switch (action) {
                case "refresh" -> event.deferEdit().queue(e -> {
                    GameBlurb blurb = new GameBlurb(gamePk);
                    e.editOriginalEmbeds(blurb.blurb())
                        .setComponents(PlanGameCommand.buildButtons(gamePk, blurb))
                        .queue();
                });
                case "start" -> {
                    try {
                        MessageEmbed startGame = StartGameCommand.startGame(gamePk, event.getGuildChannel(), event.getUser());
                        event.replyEmbeds(startGame).queue();
                    } catch (IllegalStateException e) {
                        event.replyEmbeds(EmbedUtil.failure(e.getMessage())).setEphemeral(true).queue();
                    }
                }
                case "lineup" -> {
                    String homeOrAway = event.getComponentId().split(":")[3];
                    String teamName = event.getButton().getLabel();

                    event.reply(GameInfoCommand.buildLineup(gamePk, homeOrAway, teamName)).setEphemeral(true).queue();
                }
            }
        }

        if (event.getComponentId().startsWith("gameinfo:")) {
            String[] parts = event.getComponentId().split(":");

            String action = parts[1];
            String gamePk = parts[2];
            switch (action) {
                case "boxscore" -> {
                    String homeOrAway = parts[3];
                    String type = parts[4];

                    GameInfoCommand.buildBoxScore(gamePk, homeOrAway, type, event);
                }
                case "scoring_plays" -> {
                    String homeOrAway = parts[3];
                    GameInfoCommand.buildScoringPlays(gamePk, homeOrAway, event);
                }
                case "send", "refresh" -> {
                    GameState state = GameState.fromPk(gamePk);
                    if (state.failed()) {
                        event.replyEmbeds(EmbedUtil.failure("Failed to fetch game state. Please try again.")).setEphemeral(true).queue();
                        return;
                    }

                    if (action.equals("send")) {
                        var rows = GameInfoCommand.buildActionRows(state);
                        event.replyEmbeds(GameInfoCommand.buildGameInfoEmbed(state)).setComponents(rows).setEphemeral(true).queue();
                    } else {
                        event.editMessageEmbeds(GameInfoCommand.buildGameInfoEmbed(state)).queue();
                    }
                }
            }
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        LoggerFactory.getLogger(InteractionHandler.class).debug("Detected signs of {} in the StringSelectInteractionEvent Galaxy", event.getComponentId());

        String[] parts = event.getComponentId().split(":");
        String type = parts[0];

        // Future proofing
        //noinspection SwitchStatementWithTooFewBranches
        switch (type) {
            case "gameinfo" -> {
                GameInfoCommand.handleSelectMenu(event);
            }
        }
    }
}
