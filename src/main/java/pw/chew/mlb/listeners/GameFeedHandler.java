package pw.chew.mlb.listeners;

import com.jagrosh.jdautilities.commons.utils.TableBuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pw.chew.mlb.commands.AdminCommand;
import pw.chew.mlb.objects.ActiveGame;
import pw.chew.mlb.objects.ChannelConfig;
import pw.chew.mlb.objects.GameState;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static pw.chew.mlb.MLBBot.jda;

public class GameFeedHandler {
    private static final Logger logger = LoggerFactory.getLogger(GameFeedHandler.class);
    public final static Map<String, Thread> GAME_THREADS = new HashMap<>();

    private static final DB db = DBMaker.fileDB("games.db").fileMmapEnable().closeOnJvmShutdown().checksumHeaderBypass().make();
    /**
     * A map of active games. "String" is the Channel ID. ActiveGame is an object containing the gamePk and channelId.
     */
    private static final HTreeMap<String, ActiveGame> gamesMap = db
        .hashMap("games", Serializer.STRING, new ActiveGame.EntrySerializer())
        .createOrOpen();

    public static boolean shutdownOnFinish = false;

    /**
     * Adds a game to the active games list.
     * If no game is currently active, the game will be started, otherwise it will be added to the active games list.
     *
     * @param game The game to add to the active games list.
     * @param modifyDb Whether to modify the database or not. This should only be false when booting.
     */
    public static void addGame(ActiveGame game, boolean modifyDb) {
        if (modifyDb) {
            gamesMap.put(game.channelId(), game);
        }
        // make sure config is cached
        ChannelConfig.getConfig(game.channelId());

        if (!GAME_THREADS.containsKey(game.gamePk())) {
            Thread thread = new Thread(() -> runGame(game.gamePk()), "Game-" + game.gamePk());

            GAME_THREADS.put(game.gamePk(), thread);
            thread.start();
            logger.info("Started game thread for gamePk: " + game.gamePk());
        }

        logger.info("Added game " + game.gamePk() + " with channel ID " + game.channelId() + " to the active games list");
    }

    /**
     * Adds a game to the active games list.
     * If no game is currently active, the game will be started, otherwise it will be added to the active games list.
     *
     * @param game The game to add to the active games list.
     */
    public static void addGame(ActiveGame game) {
        addGame(game, true);
    }

    /**
     * Stops (or "unsubscribes") a game from the active games list.
     *
     * @param game The game to stop from the active games list.
     */
    public static void stopGame(ActiveGame game) {
        int currentGames = 0;
        for (ActiveGame activeGame : gamesMap.values()) {
            if (game.gamePk().equals(activeGame.gamePk())) {
                currentGames++;
            }
        }

        gamesMap.remove(game.channelId());

        // If this is the last game running, stop the thread
        if (currentGames == 1) {
            Thread thread = GAME_THREADS.get(game.gamePk());
            thread.interrupt();
            removeThread(game.gamePk());
            logger.debug("Stopped game " + game.gamePk() + " thread");
        }

        logger.debug("Removed game " + game.gamePk() + " from the active games list");
    }

    /**
     * Returns a list of all active games.
     *
     * @return A list of all active games.
     */
    public static List<ActiveGame> allGames() {
        return new ArrayList<>(gamesMap.values());
    }

    /**
     * Removes a thread from GAME_THREADS, with checks.
     *
     * @param gamePk The gamePk of the thread to remove.
     */
    public static void removeThread(String gamePk) {
        LoggerFactory.getLogger(GameFeedHandler.class).debug("Removing thread for gamePk " + gamePk);

        GAME_THREADS.remove(gamePk);

        if (GAME_THREADS.isEmpty() && shutdownOnFinish) {
            AdminCommand.shutdown();
        }
    }

    /**
     * Stops a game with a provided text channel.
     * The game in the provided text channel will be stopped.
     *
     * @param channel The text channel to stop the game from.
     * @return The gamePk if the game was stopped, null if no game was found in the provided text channel.
     */
    public static String stopGame(GuildMessageChannel channel) {
        for (ActiveGame game : gamesMap.values()) {
            if (game.channelId().equals(channel.getId())) {
                stopGame(game);
                return game.gamePk();
            }
        }

        return null;
    }

    /**
     * Returns the current game for the provided text channel.
     *
     * @param channel The text channel to get the current game from.
     * @return The current game for the provided text channel, null if no game is currently running in the provided text channel.
     */
    public static String currentGame(GuildMessageChannel channel) {
        for (ActiveGame game : gamesMap.values()) {
            if (game.channelId().equals(channel.getId())) {
                return game.gamePk();
            }
        }

        return null;
    }

    /**
     * Finds the first channel with an ongoing game and returns the score of it.
     *
     * @param server The server to get the score from.
     * @return The score of the first ongoing game in the server.
     */
    public static ActiveGame currentServerGame(@NotNull Guild server) {
        for (ActiveGame game : gamesMap.values()) {
            if (server.getGuildChannelById(game.channelId()) != null) {
                return game;
            }
        }

        return null;
    }

    /**
     * Runs a game. This should be run on a separate thread.
     *
     * @param gamePk The gamePk of the game to run.
     */
    private static void runGame(String gamePk) {
        logger.debug("Starting game with gamePk: {}", gamePk);

        if (gamePk.isEmpty()) {
            return;
        }

        GameState currentState = GameState.fromPk(gamePk);
        List<JSONObject> postedAdvisories = currentState.gameAdvisories();

//        boolean canEdit = channel.getGuild().getSelfMember().hasPermission(channel, Permission.MANAGE_CHANNEL);

        // Start the looping thread until the game is stopped
        int fails = 0;
        while (!currentState.gameState().equals("Final")) {
            GameState recentState = GameState.fromPk(gamePk);

            if (recentState.failed()) {
                int retryIn = fails + 3;
                retryIn = Math.min(20, retryIn);

                logger.warn("Failed to get game state for gamePk: %s! Retrying in %ss...".formatted(gamePk, retryIn));
                if (fails == 5) {
                    EmbedBuilder notifier = new EmbedBuilder()
                        .setTitle("Connection Problems")
                        .setDescription("""
                            We're having trouble connecting to MLB's servers.
                            We've tried 5 times now to connect, but we're still having issues.
                            
                            Once we reconnect, we'll let you know.
                            """)
                        .setColor(Color.RED);

                    sendMessages(notifier.build(), gamePk);
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(retryIn * 1000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    // Shutdown the thread
                    removeThread(gamePk);
                    return;
                }
                fails++;
                continue;
            }
            if (fails > 5) {
                EmbedBuilder notifier = new EmbedBuilder()
                    .setTitle("Connection Restored")
                    .setDescription("""
                        We've reconnected to MLB's servers.
                        We'll continue to update you on the game.
                        """)
                    .setColor(Color.GREEN);

                sendMessages(notifier.build(), gamePk);
            }
            fails = 0;

            if (recentState.isCancelled()) {
                endGame(gamePk, recentState, "\nUnfortunately, this game was cancelled.");
                return;
            }

            if (recentState.isSuspended() || recentState.isPostponed()) {
                endGame(gamePk, recentState, "\nUnfortunately, this game has been suspended. It will resume at a later time.");
                return;
            }

            // Check to see if the game has changed state
            if (recentState.gameState().equals("Final")) {
                currentState = recentState;
                break;
            }

            // Check for new changes in the description
            if (recentState.atBatIndex() >= 0 && !recentState.currentPlayDescription().equals(currentState.currentPlayDescription())) {
                logger.debug("New play description for gamePk {}: {}", gamePk, recentState.currentPlayDescription());

                boolean scoringPlay = recentState.home().runs() != currentState.home().runs() || recentState.away().runs() != currentState.away().runs();
                boolean hasOut = recentState.outs() != currentState.outs() && recentState.outs() > 0;

                EmbedBuilder embed = new EmbedBuilder()
                    .setDescription(recentState.currentPlayDescription());

                // Display Hit info if there is any. This only shows for balls that are in-play.
                String hitInfo = recentState.hitInfo();
                if (hitInfo != null) {
                    embed.addField("Hit Info", hitInfo, false);
                }

                // Check potential homers
                if (recentState.potentialHomer()) {
                    embed.addField("Homer Info", "Please wait while we calculate...", false);
                }

                // Check if score changed
                if (scoringPlay) {
                    boolean homeScored = recentState.home().runs() > currentState.home().runs();

                    embed.setTitle((homeScored ? recentState.home().clubName() : recentState.away().clubName()) + " scored!");
                    embed.addField("Score", recentState.away().clubName() + " " + recentState.away().runs() + " - " + recentState.home().runs() + " " + recentState.home().clubName(), true);
                }

                // Check if outs changed. Display if it did.
                if (hasOut) {
                    int oldOuts = recentState.outs() - currentState.outs();
                    if (oldOuts < 0) {
                        oldOuts = recentState.outs();
                    }

                    embed.addField("Outs", recentState.outs() + " (+" + oldOuts + ")", true);

                    if (recentState.outs() == 3 && !scoringPlay) {
                        embed.addField("Score", recentState.away().clubName() + " " + recentState.away().runs() + " - " + recentState.home().runs() + " " + recentState.home().clubName(), true);
                    }
                }

                if (scoringPlay) {
                    embed.setColor(0x427ee6);
                } else if (hasOut) {
                    embed.setColor(0xd23d33);
                } else if (recentState.currentPlayDescription().contains("walks") || recentState.currentPlayDescription().contains("hit by pitch")) {
                    embed.setColor(0x4fc94f);
                } else {
                    embed.setColor(0x979797);
                }

                // Send result
                sendPlay(embed.build(), gamePk, recentState, scoringPlay);
            }

            // Check for new advisories
            List<JSONObject> newAdvisories = recentState.gameAdvisories();
            if (newAdvisories.size() > postedAdvisories.size()) {
                int startIndex = postedAdvisories.size();
                List<MessageEmbed> queuedAdvisories = new ArrayList<>();
                for (int i = startIndex; i < newAdvisories.size(); i++) {
                    JSONObject advisory = newAdvisories.get(i);
                    JSONObject details = advisory.getJSONObject("details");

                    logger.debug("New advisory: {}", advisory);

                    String event = details.getString("event");
                    String description = details.getString("description");

                    if (description.replaceAll("\\.", "").equals(event)) {
                        // reset description if it's the same as the event
                        description = null;
                    }

                    EmbedBuilder detailEmbed = new EmbedBuilder()
                        .setTitle(event)
                        .setDescription(description);

                    // Check if score changed
                    if (details.optBoolean("isScoringPlay", false)) {
                        int homeScore = details.getInt("homeScore");
                        int awayScore = details.getInt("awayScore");
                        boolean homeScored = homeScore > awayScore;

                        detailEmbed.setAuthor((homeScored ? recentState.home().clubName() : recentState.away().clubName()) + " scored!");
                        detailEmbed.addField("Score", recentState.away().clubName() + " " + details.getInt("awayScore") + " - " + details.getInt("homeScore") + " " + recentState.home().clubName(), true);
                    }

                    queuedAdvisories.add(detailEmbed.build());
                }

                sendAdvisory(queuedAdvisories, gamePk);
            }

            // Check if the inning state changed
            if (!currentState.inningState().equals(recentState.inningState())) {
                // Ignore if the state is "Middle" or "End"
                if (!recentState.inningState().equals("Middle") && !recentState.inningState().equals("End")) {
                    String upToBat = """
                        Next up: %s
                        On deck: %s
                        In the hole: %s
                        """
                        .formatted(
                            currentState.offenseBatter(), currentState.onDeck(), currentState.inTheHole()
                        );

                    EmbedBuilder inningEmbed = new EmbedBuilder()
                        .setTitle("Inning State Updated")
                        .setDescription(
                            recentState.inningState() + " of the " + recentState.inningOrdinal()
                             + "\n\n" + upToBat
                        );

                    sendMessages(inningEmbed.build(), gamePk);
                }
            }

            // Update the current states
            postedAdvisories = newAdvisories;
            currentState = recentState;

            // Wait for 10 seconds before requesting the next game state
            try {
                //noinspection BusyWait
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                // Shutdown the thread
                removeThread(gamePk);
                return;
            }
        }

        // Build a scorecard embed
        EmbedBuilder scorecardEmbed = new EmbedBuilder();
        scorecardEmbed.setTitle("Scorecard");

        TableBuilder tableBuilder = new TableBuilder();
        List<String> headers = new ArrayList<>();
        headers.add("Team");

        JSONArray inningData = currentState.lineScore().getJSONArray("innings");
        int totalInnings = inningData.length();

        for (int i = 1; i <= totalInnings; i++) {
            headers.add(String.valueOf(i));
        }

        headers.add("R");
        headers.add("H");
        headers.add("E");
        headers.add("LOB");

        tableBuilder.addHeaders(headers.toArray(new String[0]));

        String[][] tableData = new String[totalInnings + 5][2];

        // Add team names
        tableData[0][0] = currentState.away().clubName();
        tableData[0][1] = currentState.home().clubName();

        for (int i = 0; i < totalInnings; i++) {
            JSONObject inning = inningData.getJSONObject(i);
            String awayScore = inning.getJSONObject("away").optString("runs", "-");
            String homeScore = inning.getJSONObject("home").optString("runs", "-");

            tableData[i+1] = new String[] {
                String.valueOf(awayScore),
                homeScore
            };
        }

        // Add runs, hits, errors, and leftOnBase to the last row
        JSONObject homeTotals = currentState.lineScore().getJSONObject("teams").getJSONObject("home");
        JSONObject awayTotals = currentState.lineScore().getJSONObject("teams").getJSONObject("away");

        int index = totalInnings + 1;
        for (String item : new String[]{"runs", "hits", "errors", "leftOnBase"}) {
            tableData[index] = new String[] {
                String.valueOf(awayTotals.getInt(item)),
                String.valueOf(homeTotals.getInt(item))
            };

            index++;
        }

        // Flip the rows and columns in the tableData matrix
        String[][] flippedTableData = new String[tableData[0].length][tableData.length];
        for (int i = 0; i < tableData.length; i++) {
            for (int j = 0; j < tableData[i].length; j++) {
                flippedTableData[j][i] = tableData[i][j];
            }
        }

        // Set values
        tableBuilder.setValues(flippedTableData);
        tableBuilder.setBorders(TableBuilder.Borders.HEADER_PLAIN);
        tableBuilder.codeblock(true);

        // Game is over!
        endGame(gamePk, currentState, tableBuilder.build());
    }

    /**
     * Sends a game advisory message to enabled channels.
     *
     * @param embeds The embeds to send.
     * @param gamePk The gamePk of the game.
     */
    public static void sendAdvisory(List<MessageEmbed> embeds, String gamePk) {
        for (ActiveGame game : getGames(gamePk)) {
            ChannelConfig config = ChannelConfig.getConfig(game.channelId());
            if (!config.gameAdvisories()) continue;

            GuildMessageChannel channel = canSafelySend(game);
            if (channel == null) continue;

            channel.sendMessageEmbeds(embeds).queue();
        }
    }

    public static void sendMessages(MessageEmbed message, String gamePk) {
        for (ActiveGame game : getGames(gamePk)) {
            GuildMessageChannel channel = canSafelySend(game);
            if (channel == null) continue;

            channel.sendMessageEmbeds(message).queue();
        }
    }

    /**
     * Sends a play message to enabled channels.
     *
     * @param message The message to send.
     * @param gamePk The gamePk of the game.
     * @param gameState The game state at the time of this play.
     * @param isScoringPlay Whether the play is a scoring play.
     */
    public static void sendPlay(MessageEmbed message, String gamePk, GameState gameState, boolean isScoringPlay) {
        for (ActiveGame game : getGames(gamePk)) {
            ChannelConfig config = ChannelConfig.getConfig(game.channelId());

            // If configured to only show scoring plays, ignore non-scoring plays
            if (config.onlyScoringPlays() && !isScoringPlay) continue;

            boolean inPlay = gameState.currentBallInPlay();
            int delay = inPlay ? config.inPlayDelay() : config.noPlayDelay();

            GuildMessageChannel channel = canSafelySend(game);
            if (channel == null) continue;

            Instant sent = Instant.now();
            channel.sendMessageEmbeds(message).queueAfter(delay, TimeUnit.SECONDS, playMsg -> {
                // Wait 30 seconds until after the original message was sent
                long waitTime = 30 - Duration.between(sent, Instant.now()).getSeconds();

                if (!gameState.potentialHomer()) return;

                playMsg.editMessageEmbeds(message)
                    .delay(waitTime, TimeUnit.SECONDS)
                    .flatMap(playMsg1 -> {
                        // Get the embed
                        MessageEmbed embed = playMsg.getEmbeds().get(0);
                        // add a new field
                        EmbedBuilder builder = new EmbedBuilder(embed);

                        // find the "Homer Info" field
                        if (gameState.potentialHomer()) {
                            // Check potential homers
                            String homerDescription = gameState.homerDescription();

                            // find the "Homer Info" field
                            for (MessageEmbed.Field field : embed.getFields()) {
                                if (Objects.equals(field.getName(), "Homer Info")) {
                                    // remove the field
                                    builder.getFields().remove(field);
                                    break;
                                }
                            }

                            if (homerDescription != null) {
                                builder.addField("Homer Info", homerDescription, false);
                            }
                        }

                        return playMsg1.editMessageEmbeds(builder.build());
                    })
                    .queue();
            });
        }
    }

    public static void endGame(String gamePk, GameState currentState, String scorecard) {
        for (ActiveGame game : getGames(gamePk)) {
            GuildChannel gChan = jda.getGuildChannelById(game.channelId());
            if (gChan == null) continue;

            GuildMessageChannel channel = (GuildMessageChannel)gChan;
            try {
                if (scorecard.contains("Unfortunately")) {
                    channel.sendMessage(scorecard).queue();
                    continue;
                }

                channel.sendMessage("""
                    # Game Over!
                    ## Summary
                    %s
                    ## Decisions
                    %s
                    ## Final Scorecard
                    %s
                    """.formatted(currentState.summary(), currentState.decisions(), scorecard))
                    .setComponents(ActionRow.of(Button.primary("gameinfo:send:%s".formatted(currentState.gamePk()), "View Game Info")))
                    .queue();
            } catch (InsufficientPermissionException ignored) {
                logger.debug("Insufficient permissions to send message to channel " + game.channelId());
            }

            // Check for scheduled game and end it, game is over
            List<ScheduledEvent> events = channel.getGuild().getScheduledEvents();
            for (ScheduledEvent event : events) {
                User creator = event.getCreator();
                // If no creator, skip
                if (creator == null) continue;
                // If not us, skip
                if (!creator.getId().equals(jda.getSelfUser().getId())) continue;

                // Get description
                String description = event.getDescription();
                // If no description, skip
                if (description == null) continue;
                // Game link is at very end
                String id = description.split("/")[description.split("/").length - 1];

                // If we can't do anything, skip
                if (!channel.getGuild().getSelfMember().hasPermission(Permission.MANAGE_EVENTS)) continue;

                if (id.equals(gamePk)) {
                    event.delete().queue();
                }
            }
        }

        // Remove the games from the active games list
        for (ActiveGame game : getGames(gamePk)) {
            stopGame(game);
        }

        // Remove the game thread
        removeThread(gamePk);
    }

    /**
     * Checks to see if the bot can safely send messages to the channel.
     * This checks to make sure the channel exists, and if we can talk.
     *
     * @param game The game to check
     * @return The channel if we can safely send messages, null otherwise
     */
    private static GuildMessageChannel canSafelySend(ActiveGame game) {
        GuildChannel gChan = jda.getGuildChannelById(game.channelId());
        // channel is null for some reason, so we're going to remove the game from the active games list
        if (gChan == null) {
            logger.debug("Stopping game %s due to nonexistent channel %s".formatted(game.gamePk(), game.channelId()));
            stopGame(game);
            return null;
        }

        GuildMessageChannel channel = (GuildMessageChannel)gChan;
        // If we can't talk in the channel, we're going to remove the game from the active games list
        if (!channel.canTalk()) {
            logger.debug("Stopping game %s because we can't speak in %s".formatted(game.gamePk(), game.channelId()));
            stopGame(game);
            return null;
        }
        // Check to see if we have permission to send embeds
        if (!channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_EMBED_LINKS)) {
            logger.debug("Stopping game %s because we can't send embeds to %s".formatted(game.gamePk(), game.channelId()));
            channel.sendMessage("Uh oh! I can't send embeds to this channel. This is required! Please give me the permission and start the game again.").queue();
            stopGame(game);
            return null;
        }

        return channel;
    }

    /**
     * Gets active games for the specified gamePk
     *
     * @param gamePk The gamePk to get active games for
     * @return A list of active games
     */
    public static List<ActiveGame> getGames(String gamePk) {
        List<ActiveGame> games = new ArrayList<>();

        for (ActiveGame game : gamesMap.values()) {
            if (!game.gamePk().equals(gamePk)) continue;

            games.add(game);
        }

        return games;
    }
}
