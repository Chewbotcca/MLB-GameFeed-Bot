package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.TimeFormat;
import pw.chew.mlb.listeners.GameFeedHandler;
import pw.chew.mlb.objects.ActiveGame;
import pw.chew.mlb.objects.GameState;
import pw.chew.mlb.util.AutocompleteUtil;
import pw.chew.mlb.util.EmbedUtil;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class StartGameCommand extends SlashCommand {

    public StartGameCommand() {
        this.name = "startgame";
        this.help = "Starts a currently active MLB game";
        this.descriptionLocalization = Map.of(
            DiscordLocale.ENGLISH_US, "Starts a currently active MLB game",
            DiscordLocale.SPANISH, "Comienza un juego de MLB actualmente activo"
        );

        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD};
        this.options = Collections.singletonList(
            new OptionData(OptionType.INTEGER, "game", "Which game to listen to", true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "A qué juego escuchar")
                .setAutoComplete(true)
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        String gamePk = event.getOption("game", "0", OptionMapping::getAsString);
        try {
            MessageEmbed startGame = startGame(gamePk, event.getGuildChannel(), event.getUser());
            event.replyEmbeds(startGame).queue();
        } catch (IllegalStateException e) {
            event.replyEmbeds(EmbedUtil.failure(e.getMessage())).setEphemeral(true).queue();
        }
    }

    public static MessageEmbed startGame(String gamePk, GuildMessageChannel channel, User invoker) {
        String currentGame = GameFeedHandler.currentGame(channel);
        if (currentGame != null) {
            throw new IllegalStateException("This channel is already playing a game: " + currentGame + ". Please wait for it to finish, or stop it with `/stopgame`.");
        }

        // Check to see if we can send messages
        if (!channel.canTalk(channel.getGuild().getSelfMember())) {
            throw new IllegalStateException("I cannot send messages in this channel. Please give me permission to send messages in this channel or ask an admin to and try again.");
        }

        // Start a new thread
        ActiveGame activeGame = new ActiveGame(gamePk, channel.getId());
        GameState currentState = GameState.fromPk(gamePk);

        // Refuse to start if the game is already over
        if (currentState.isFinal()) {
            throw new IllegalStateException("This game is already over. Please start a different game.");
        }

        // We can only start games if the start time is less than 30 minutes away
        // E.g. if game starts at 2:30, and the time is 1:59, we cannot start the game
        // But, if it's 2:00, we can start the game
        // We can also start it if it's like, 2:56, who cares, maybe we forgot to start it
        if (OffsetDateTime.now().isBefore(currentState.officialDate().minusMinutes(30))) {
            throw new IllegalStateException("This game is not yet ready to start. Please wait until the game is within 30 minutes of starting.");
        }

        GameFeedHandler.addGame(activeGame);

        List<String> description = new ArrayList<>();
        description.add("First Pitch: %s".formatted(TimeFormat.RELATIVE.format(currentState.officialDate())));
        description.add("\n*Invoked by %s*".formatted(invoker.getAsMention()));

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Starting Game **%s @ %s**".formatted(currentState.away().clubName(), currentState.home().clubName()))
            .setDescription(String.join("\n", description))
            .setColor(0x4fc94f)
            .setFooter("Game PK: %s".formatted(gamePk));

        return embed.build();
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoices(AutocompleteUtil.getTodayGames(false)).queue();
    }
}
