package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.entities.emoji.CustomEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.internal.utils.Checks;
import org.slf4j.LoggerFactory;
import pw.chew.mlb.objects.MLBTeam;
import pw.chew.mlb.util.AutocompleteUtil;
import pw.chew.mlb.util.MLBAPIUtil;
import pw.chew.mlb.util.TeamEmoji;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScheduleCommand extends SlashCommand {

    public ScheduleCommand() {
        this.name = "schedule";
        this.help = "Manages the schedule for the server.";
        this.children = new SlashCommand[] {
            new ScheduleCreateSubcommand(),
            new ScheduleUpdateSubcommand()
        };
    }

    @Override
    protected void execute(SlashCommandEvent slashCommandEvent) {
        // unused
    }

    public class ScheduleCreateSubcommand extends SlashCommand {
        public ScheduleCreateSubcommand() {
            this.name = "create";
            this.help = "Sets up a schedule channel.";
            this.options = Arrays.asList(
                new OptionData(OptionType.INTEGER, "team", "The name of the schedule.", true, true),
                new OptionData(OptionType.CHANNEL, "channel", "The channel to send the schedule to. Leave blank to try to make a channel.", false)
                    .setChannelTypes(ChannelType.TEXT),
                new OptionData(OptionType.INTEGER, "sport", "The sport to find teams for, MLB by default.", false, true)
            );
        }

        @Override
        protected void execute(SlashCommandEvent event) {
            // checks
            Checks.notNull(event.getGuild(), "Server");

            long teamId = event.optLong("team", -1);
            long sportId = event.optLong("sport", 1);
            GuildChannelUnion channel = event.getOption("channel", OptionMapping::getAsChannel);

            if (teamId == -1) {
                event.reply("Please select a team first!").queue();
                return;
            }

            Member self = event.getGuild().getSelfMember();

            // check to see if we have a channel
            if (channel == null) {
                // attempt to make a schedule channel
                boolean canWeEvenMakeAChannel = self.hasPermission(Permission.MANAGE_CHANNEL);

                if (!canWeEvenMakeAChannel) {
                    event.reply("I don't have permission to make a channel! Please specify a channel to set as the schedule channel or give me manage channel permissions in this server.").queue();
                    return;
                }

                // make the channel
                event.getGuild().createTextChannel("schedule").setTopic("Schedule channel")
                    .addMemberPermissionOverride(self.getIdLong(), EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_EXT_EMOJI), null)
                    .queue(textChannel -> event.reply("Created a schedule channel in %s".formatted(textChannel.getAsMention()))
                        .setEphemeral(true)
                        .queue(
                            hook -> setupChannel(hook, textChannel, teamId, sportId)),
                            error -> event.reply("Failed to create a schedule channel! Make sure I have the ability to create the channel and have embed links, use external emoji, and send messages in this server."
                        ).queue());
            } else {
                boolean canWeProperlySetUpTheChannel = self.hasPermission(channel.asTextChannel(), Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_EXT_EMOJI);

                if (!canWeProperlySetUpTheChannel) {
                    event.reply("I don't have permission the proper permissions in that channel! Make sure I have the ability to embed links, use external emoji, and send messages in that channel.").queue();
                    return;
                }

                event.deferReply(true).queue(hook -> setupChannel(hook, channel.asTextChannel(), teamId, sportId));
            }
        }

        @Override
        public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
            switch (event.getFocusedOption().getName()) {
                case "team" -> {
                    // get current value of sport
                    String sport = event.getOption("sport", "1", OptionMapping::getAsString);
                    String input = event.getFocusedOption().getValue();

                    event.replyChoices(AutocompleteUtil.getTeams(sport, input)).queue();
                    return;
                }
                case "sport" -> {
                    event.replyChoices(AutocompleteUtil.getSports()).queue();
                    return;
                }
            }

            event.replyChoices().queue();
        }
    }

    public static class ScheduleUpdateSubcommand extends SlashCommand {
        public ScheduleUpdateSubcommand() {
            this.name = "update";
            this.help = "Updates an existing schedule.";
        }

        @Override
        protected void execute(SlashCommandEvent slashCommandEvent) {
            // unused
        }
    }

    /**
     * Sets up the channel with the schedule information for a specified team and sport. The method
     * retrieves the schedule, builds the corresponding message embeds, and sends them synchronously
     * to the provided text channel. After all embeds are sent, it notifies the user through the
     * provided interaction hook.
     *
     * @param hook     The interaction hook used to send a final notification or update regarding
     *                 the schedule setup.
     * @param channel  The text channel where the schedule embeds will be sent.
     * @param teamId   The unique identifier of the team whose schedule is being set up.
     * @param sportId  The unique identifier of the sport associated with the team.
     */
    private void setupChannel(InteractionHook hook, TextChannel channel, long teamId, long sportId) {
        // get the schedule for the team
        var embeds = buildScheduleEmbed((int) teamId, String.valueOf(sportId));

        // send the embeds one by one, synchronously
        new Thread(() -> {
            for (MessageEmbed embed : embeds) {
                channel.sendMessageEmbeds(embed).complete();
            }

            hook.editOriginal("Schedule set up!").queue();

            // exit thread
            Thread.currentThread().interrupt();
        }, "Schedule").start();
    }

    /**
     * Constructs a list of message embeds representing the schedule of games for a specific team
     * in a given sport. The schedule is grouped into categories (e.g., Spring Training, Regular
     * Season) and organized by series and month. Each embed provides detailed information about
     * the games, including results, series records, and monthly schedules.
     *
     * @param teamId The unique identifier of the team whose schedule is being retrieved and embedded.
     * @param sportId The unique identifier of the sport associated with the team.
     * @return A list of MessageEmbed objects containing the structured schedule information
     *         for the specified team and sport.
     */
    public List<MessageEmbed> buildScheduleEmbed(int teamId, String sportId) {
        List<MLBAPIUtil.Game> schedule = MLBAPIUtil.getSchedule(teamId, sportId);

        // First, sort games by the seriesDescription
        Map<String, List<MLBAPIUtil.Game>> gamesBySeries = new HashMap<>();

        for (MLBAPIUtil.Game game : schedule) {
            String series = game.seriesDescription();
            if (!gamesBySeries.containsKey(series)) {
                gamesBySeries.put(series, new ArrayList<>());
            }
            gamesBySeries.get(series).add(game);

        }

        // Now we can build the Spring Training Embed.
        List<MessageEmbed> embeds = new ArrayList<>();

        EmbedBuilder springTraining = new EmbedBuilder()
            .setTitle("Spring Training Schedule");
        // iterate through Spring Training games
        List<String> gameStrings = new ArrayList<>();
        int wins = 0, losses = 0, ties = 0;
        for (MLBAPIUtil.Game game : gamesBySeries.get("Spring Training")) {
            if (game.isFinal() && !game.isCancelled()) {
                if (game.home().score() == game.away().score()) {
                    ties++;
                } else if ((game.home().score() > game.away().score() && game.home().id() == teamId) || (game.away().score() > game.home().score() && game.away().id() == teamId)) {
                    wins++;
                } else {
                    losses++;
                }
            }
            gameStrings.add(gameToString(game, teamId, -1));
        }
        float pct = (float) (wins + (ties/2)) / (wins + losses + ties);
        springTraining.setDescription(String.join("\n", gameStrings))
            .setFooter("Record: %d-%d-%d (%.3f)".formatted(wins, losses, ties, pct));

        embeds.add(springTraining.build());

        // Now it's for regular season... For this, we group by series. A series is when you play the same team multiple times in a row.
        List<Series> series = new ArrayList<>();

        // iterate through the games
        int currentAway = 0, currentHome = 0;
        List<MLBAPIUtil.Game> currentSeries = new ArrayList<>();
        for (MLBAPIUtil.Game game : gamesBySeries.get("Regular Season")) {
            if (currentSeries.isEmpty()) {
                currentSeries.add(game);
                currentAway = game.away().id();
                currentHome = game.home().id();
            } else if (game.away().id() == currentAway && game.home().id() == currentHome) {
                currentSeries.add(game);
            } else {
                series.add(new Series(currentSeries));
                currentSeries = new ArrayList<>();
                currentSeries.add(game);
                currentAway = game.away().id();
                currentHome = game.home().id();
            }
        }

        Map<String, List<Series>> seriesByMonth = new HashMap<>();

        // iterate series
        for (Series s : series) {
            // get the LAST game of the series, determine the month it's in, then put it in the map.
            String month = s.month();

            if (!seriesByMonth.containsKey(month)) {
                seriesByMonth.put(month, new ArrayList<>());
            }

            seriesByMonth.get(month).add(s);
        }

        // merge March and April if the 1st one has 1 series
        boolean hadMarch = false;
        if (seriesByMonth.containsKey("March") && seriesByMonth.get("March").size() == 1) {
            hadMarch = true;
            seriesByMonth.get("April").addAll(0, seriesByMonth.get("March"));
            seriesByMonth.remove("March");
        }

        // same with September and October
        boolean hadOctober = false;
        if (seriesByMonth.containsKey("September") && seriesByMonth.get("September").size() == 1) {
            hadOctober = true;
            seriesByMonth.get("October").addAll(0, seriesByMonth.get("September"));
            seriesByMonth.remove("September");
        }

        // Now we can build the regular season embeds.

        // sort the months
        List<String> months = new ArrayList<>(seriesByMonth.keySet());
        // March, April, May, June, July, August, September, October,
        String[] sortingKey = {"March", "April", "May", "June", "July", "August", "September", "October"};
        months.sort((a, b) -> {
            int aIndex = Arrays.asList(sortingKey).indexOf(a);
            int bIndex = Arrays.asList(sortingKey).indexOf(b);
            return Integer.compare(aIndex, bIndex);
        });

        // 1. Iterate through months
        for (String month : seriesByMonth.keySet()) {
            EmbedBuilder monthEmbed = new EmbedBuilder()
                .setTitle("%s".formatted(month));

            if (month.equals("April") && hadMarch) {
                monthEmbed.setTitle("March/April");
            }
            if (month.equals("September") && hadOctober) {
                monthEmbed.setTitle("September/October");
            }

            List<String> monthGames = new ArrayList<>();
            for (Series s : seriesByMonth.get(month)) {
                monthGames.add("");
                for (MLBAPIUtil.Game game : s.gamesList) {
                    monthGames.add(gameToString(game, teamId, s.gamesList.indexOf(game) + 1));
                }
            }

            monthEmbed.setDescription(String.join("\n", monthGames));
            embeds.add(monthEmbed.build());
        }

        // sort embed titles by sortingKey
        String[] sortingKeyImproved = {"Spring Training", "March/April", "April", "May", "June", "July", "August", "September", "September/October", "Post-Season"};
        embeds.sort((a, b) -> {
            int aIndex = Arrays.asList(sortingKeyImproved).indexOf(a.getTitle());
            int bIndex = Arrays.asList(sortingKeyImproved).indexOf(b.getTitle());
            return Integer.compare(aIndex, bIndex);
        });

        return embeds;
    }

    public static String gameToString(MLBAPIUtil.Game game, int teamId, int gameNumber) {
        // We MUST Format as "Day. Month/Day"
        // E.g. "Mon. 3/1"
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("E. M/d");

        boolean teamIsHome = game.home().id() == teamId;

        // Shows the date, the team, and the opponent.
        String baseInfo = "%s`%s` - %s %s -".formatted(
            gameNumber > 0 ? "%s |".formatted(gameNumber) : "",
            game.gameDate().atZoneSimilarLocal(ZoneId.of("America/New_York")).format(formatter),
            (teamIsHome ? "vs" : "@"),
            TeamEmoji.fromTeamId((teamIsHome ? game.away() : game.home()).id())
        );

        // Check if the game is final.
        if (game.isCancelled()) {
            return "~~%s~~ Cancelled".formatted(baseInfo);
        } else if (game.isFinal()) {
            int awayScore = game.away().score();
            int homeScore = game.home().score();

            CustomEmoji emoji = null;
            if (awayScore == homeScore) {
                emoji = Emoji.fromFormatted("<:t_icon:1249579207952961557>").asCustom();
            } else if ((homeScore > awayScore && teamIsHome) || (awayScore > homeScore && !teamIsHome)) {
                emoji = Emoji.fromFormatted("<:w_icon:1139606326004166746>").asCustom();
            } else {
                emoji = Emoji.fromFormatted("<:l_icon:1139606451841662997>").asCustom();
            }

            return "%s %s %s %s - %s %s".formatted(
                emoji.getAsMention(), baseInfo,
                game.away().abbreviation(), game.away().score(),
                game.home().score(), game.home().abbreviation()
            );
        } else {
            return baseInfo + " **" + TimeFormat.TIME_SHORT.format(game.gameDate()) + "**";
        }
    }

    public record Series(List<MLBAPIUtil.Game> gamesList) {
        public int games() {
            return gamesList.size();
        }

        public String month() {
            // last game of series
            String month = gamesList.get(gamesList.size() - 1).gameDate().getMonth().name();
            // capitalize it. e.g. MARCH -> March
            return month.charAt(0) + month.substring(1).toLowerCase();
        }

        public MLBTeam home() {
            return gamesList.get(0).home();
        }

        public MLBTeam away() {
            return gamesList.get(0).away();
        }
    }
}
