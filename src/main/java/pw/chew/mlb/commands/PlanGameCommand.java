package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import pw.chew.mlb.objects.GameBlurb;
import pw.chew.mlb.objects.ImageUtil;
import pw.chew.mlb.util.AutocompleteUtil;
import pw.chew.mlb.util.TeamEmoji;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlanGameCommand extends SlashCommand {
    public PlanGameCommand() {
        this.name = "plangame";
        this.help = "Plans a game to be played. Makes a thread in text channels or a post in forum channels.";
        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD};
        this.descriptionLocalization = Map.of(
            DiscordLocale.ENGLISH_US, "Plans a game to be played. Makes a thread in text channels or a post in forum channels.",
            DiscordLocale.SPANISH, "Planifica un juego para ser jugado."
        );

        this.options = Arrays.asList(
            new OptionData(OptionType.STRING, "team", "The team to plan for", true, true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "El equipo para planificar"),
            new OptionData(OptionType.STRING, "date", "The date of the game. Select one from the list!", true, true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "La fecha del juego. ¡Seleccione uno de la lista!"),
            new OptionData(OptionType.CHANNEL, "channel", "The channel to plan for", false)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "El canal para planificar")
                .setChannelTypes(ChannelType.TEXT, ChannelType.FORUM),
            new OptionData(OptionType.STRING, "sport", "The sport to plan a game for, Majors by default.", false, true)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "El deporte para planificar un juego, Majors de forma predeterminada."),
            new OptionData(OptionType.BOOLEAN, "thread", "Whether to make a thread or not. Defaults to true, required true for forums.", false)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "Si hacer un hilo o no. De forma predeterminada es verdadero, se requiere verdadero para los foros."),
            new OptionData(OptionType.BOOLEAN, "event", "Whether to additionally create an event with all the information. Defaults to false.", false)
                .setDescriptionLocalization(DiscordLocale.SPANISH, "Si crear adicionalmente un evento con toda la información. De forma predeterminada es falso.")
        );
    }

    public static MessageEmbed generateGameBlurb(String gamePk) {
        GameBlurb blurb = new GameBlurb(gamePk);
        return blurb.blurb();
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        GuildChannel channel = event.getOption("channel", event.getGuildChannel(), OptionMapping::getAsChannel);

        String gamePk = event.optString("date", "1");
        GameBlurb blurb = new GameBlurb(gamePk);

        boolean makeThread = event.optBoolean("thread", false);
        boolean makeEvent = event.optBoolean("event", false);

        List<String> status = new ArrayList<>();
        status.add("Planning Game...\n");
        if (makeEvent) {
            status.add("Creating Event...");
        }
        if (makeThread && channel.getType() != ChannelType.FORUM) {
            status.add("Creating Thread...");
        }
        status.add("Sending Message...");

        event.reply(String.join("\n", status)).setEphemeral(true)
            .queue(interactionHook -> handle(interactionHook, gamePk, channel, blurb, makeThread, makeEvent, status));
    }

    public void handle(InteractionHook event, String gamePk, GuildChannel channel, GameBlurb blurb, boolean makeThread, boolean makeEvent, List<String> status) {
        if (makeEvent) {
            // async pathfinding???
            String name = blurb.name();

            OffsetDateTime start = blurb.time();
            if (start.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                start = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(15);
            }

            try {
                var ev = channel.getGuild().createScheduledEvent(name, blurb.ballpark(), start, start.plusHours(4))
                    .setDescription(blurb.blurbText());

                var matchupBanner = ImageUtil.matchUpBanner(blurb.away().id(), blurb.home().id());
                if (matchupBanner != null) ev = ev.setImage(matchupBanner.asIcon());

                ev.queue(
                    ent -> {
                        status.set(1, "Creating Event... Done!");
                        event.editOriginal(String.join("\n", status)).queue();
                    },
                    fail -> {
                        status.set(1, "Creating Event... Failed: " + fail.getMessage());
                        event.editOriginal(String.join("\n", status)).queue();
                    }
                );
            } catch (InsufficientPermissionException e) {
                status.set(1, "Creating Event... Failed: " + e.getMessage());
                event.editOriginal(String.join("\n", status)).queue();
            }
        }

        switch (channel.getType()) {
            case TEXT -> {
                TextChannel textChannel = (TextChannel) channel;

                if (!makeThread) {
                    textChannel.sendMessageEmbeds(blurb.blurb()).setComponents(buildButtons(gamePk, blurb)).queue(message -> {
                        int index = status.indexOf("Sending Message...");
                        status.set(index, "Sending Message... Done! " + message.getJumpUrl());

                        event.editOriginal(String.join("\n", status)).queue();
                    });
                    return;
                }

                textChannel.createThreadChannel(blurb.name()).queue(threadChannel -> {
                    int index = status.indexOf("Creating Thread...");
                    status.set(index, "Creating Thread... Done!");
                    event.editOriginal(String.join("\n", status)).queue();

                    threadChannel.sendMessageEmbeds(blurb.blurb()).setComponents(buildButtons(gamePk, blurb)).queue(msg -> {
                        try {
                            msg.pin().queue();
                        } catch (InsufficientPermissionException ignored) {
                        }

                        int index2 = status.indexOf("Sending Message...");
                        status.set(index2, "Sending Message... Done! " + threadChannel.getAsMention());

                        event.editOriginal(String.join("\n", status)).queue();
                    });
                });
            }
            case FORUM -> {
                ForumChannel forumChannel = (ForumChannel) channel;

                forumChannel.createForumPost(blurb.name(), MessageCreateData.fromEmbeds(blurb.blurb())).setComponents(buildButtons(gamePk, blurb)).queue(forumPost -> {
                    try {
                        forumPost.getMessage().pin().queue();
                    } catch (InsufficientPermissionException ignored) {
                    }

                    int index = status.indexOf("Sending Message...");
                    status.set(index, "Sending Message... Done! " + forumPost.getThreadChannel().getAsMention());

                    event.editOriginal(String.join("\n", status)).queue();
                });
            }
        }
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoices(AutocompleteUtil.handleInput(event)).queue();
    }

    public static ActionRow buildButtons(String gamePk, GameBlurb blurb) {
        return ActionRow.of(
            Button.success("plangame:start:"+gamePk, "Start"),
            Button.secondary("plangame:refresh:"+gamePk, "Refresh Embed"),
            Button.primary("plangame:lineup:"+gamePk+":away", blurb.away().name() + " Lineup").withEmoji(TeamEmoji.fromTeamId(blurb.away().id())),
            Button.primary("plangame:lineup:"+gamePk+":home", blurb.home().name() + " Lineup").withEmoji(TeamEmoji.fromTeamId(blurb.home().id()))
        );
    }

    public static void cleanDuplicates(List<String> list) {
        // map of team name -> list of broadcasts
        Map<String, List<String>> teamBroadcasts = new HashMap<>();
        for (String s : list) {
            String[] split = s.split(" - ");
            String team = split[0];
            String broadcast = split[1];

            if (!teamBroadcasts.containsKey(team)) teamBroadcasts.put(team, new ArrayList<>());
            teamBroadcasts.get(team).add(broadcast);
        }
        list.clear();
        for (Map.Entry<String, List<String>> entry : teamBroadcasts.entrySet()) {
            list.add("%s - %s".formatted(entry.getKey(), String.join(", ", entry.getValue())));
        }

        // also clean duplicates inside individual broadcasts
        for (String s : list) {
            String[] split = s.split(" - ");
            String team = split[0];
            String[] broadcasts = split[1].split(", ");

            List<String> newBroadcasts = new ArrayList<>();
            for (String broadcast : broadcasts) {
                if (!newBroadcasts.contains(broadcast)) newBroadcasts.add(broadcast);
            }

            list.set(list.indexOf(s), "%s - %s".formatted(team, String.join(", ", newBroadcasts)));
        }
    }
}
