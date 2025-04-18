package io.runelab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.awt.Color
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull
import kotlin.math.ceil
import kotlin.math.max
import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.channel.TextChannel
import org.javacord.api.entity.message.MessageBuilder
import org.javacord.api.entity.message.MessageFlag
import org.javacord.api.entity.message.component.ActionRow
import org.javacord.api.entity.message.component.Button
import org.javacord.api.entity.message.component.HighLevelComponent
import org.javacord.api.entity.message.component.TextInputBuilder
import org.javacord.api.entity.message.component.TextInputStyle
import org.javacord.api.entity.message.embed.EmbedBuilder
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder
import org.javacord.api.entity.permission.PermissionType
import org.javacord.api.entity.server.Server
import org.javacord.api.entity.user.User
import org.javacord.api.event.interaction.MessageComponentCreateEvent
import org.javacord.api.event.interaction.ModalSubmitEvent
import org.javacord.api.event.interaction.SlashCommandCreateEvent
import org.javacord.api.event.message.MessageCreateEvent
import org.javacord.api.interaction.MessageComponentInteraction
import org.javacord.api.interaction.SlashCommand
import org.javacord.api.interaction.SlashCommandBuilder
import org.javacord.api.interaction.SlashCommandInteraction
import org.javacord.api.interaction.SlashCommandOption
import org.javacord.api.interaction.SlashCommandOptionType


class Bot(private val env: String, private val discordToken: String, driver: JdbcSqliteDriver) {

    private val database = Database(driver)
    private lateinit var leaderboardCommand: SlashCommand
    private lateinit var scoreCommand: SlashCommand
    private lateinit var rankCommand: SlashCommand
    private lateinit var deleteCommand: SlashCommand
    private lateinit var inactiveCommand: SlashCommand
    private lateinit var getUserCommand: SlashCommand
    private lateinit var setScoreCommand: SlashCommand
    private lateinit var joinMessageCommand: SlashCommand

    private val noMentions =
        AllowedMentionsBuilder().setMentionRoles(false).setMentionEveryoneAndHere(false).build()
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun run() {
        val api = DiscordApiBuilder().setToken(discordToken).setAllIntents().login().join()

        val server = api.getServerById(SERVER_ID).getOrNull() ?: return

        addAllUsersToDatabase(server)
        createSlashCommands(server)
        setupListeners(api)
        scheduleScoreReductionTask(server)
    }

    private fun addAllUsersToDatabase(server: Server) {
        server.members.forEach { member ->
            if (!member.isBot) {
                addUserToDatabase(member.id.toString())
            }
        }
    }

    private fun createSlashCommands(server: Server) {
        leaderboardCommand =
            SlashCommandBuilder()
                .setName("leaderboard")
                .setDescription("Check the leaderboard")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createForServer(server)
                .join()

        scoreCommand =
            SlashCommandBuilder()
                .setName("score")
                .setDescription("Check your current score")
                .createForServer(server)
                .join()

        rankCommand =
            SlashCommandBuilder()
                .setName("rank")
                .setDescription("Check your progress to the next rank")
                .createForServer(server)
                .join()

        inactiveCommand =
            SlashCommandBuilder()
                .setName("inactive-list")
                .setDescription("Check for inactive users")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createForServer(server)
                .join()

        deleteCommand =
            SlashCommandBuilder()
                .setName("delete-user")
                .setDescription("Deletes a user from the DB")
                .addOption(
                    SlashCommandOption.create(
                        SlashCommandOptionType.USER,
                        "user",
                        "The user that you want deleting",
                        true,
                    ),
                )
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createForServer(server)
                .join()

        getUserCommand =
            SlashCommandBuilder()
                .setName("get-user")
                .setDescription("Get a user from the DB")
                .addOption(
                    SlashCommandOption.create(
                        SlashCommandOptionType.USER,
                        "user",
                        "The user that you want to get",
                        true,
                    ),
                )
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createForServer(server)
                .join()

        setScoreCommand =
            SlashCommandBuilder()
                .setName("set-score")
                .setDescription("Set a user's score")
                .addOption(
                    SlashCommandOption.create(
                        SlashCommandOptionType.USER,
                        "user",
                        "The user that you want to set the score for",
                        true,
                    ),
                )
                .addOption(
                    SlashCommandOption.create(
                        SlashCommandOptionType.LONG,
                        "score",
                        "The score you want to set",
                        true,
                    ),
                )
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createForServer(server)
                .join()

        joinMessageCommand = SlashCommandBuilder()
            .setName("join-message")
            .setDescription("Sends the join message to the lobby channel")
            .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
            .createForServer(server)
            .join()
    }

    private fun setupListeners(api: DiscordApi) {
        api.addSlashCommandCreateListener { event ->
            if (env == "dev" && !event.slashCommandInteraction.user.isBotOwner) {
                return@addSlashCommandCreateListener
            }
            if (!event.slashCommandInteraction.user.isBot) {
                handleCommand(event)
            }
        }
        api.addMessageCreateListener { event ->
            if (env == "dev" && !event.messageAuthor.isBotOwner) {
                return@addMessageCreateListener
            }
            if (!event.messageAuthor.isBotUser) {
                handleMessage(event)
            }
        }
        api.addServerMemberJoinListener { event ->
            if (!event.user.isBot) {
                addUserToDatabase(event.user.id.toString())
            }
        }
        api.addServerMemberLeaveListener { event ->
            if (!event.user.isBot) {
                deleteUserFromDatabase(event.user.id.toString())
            }
        }
        api.addMessageComponentCreateListener { event ->
            handleComponent(event)
        }
        api.addModalSubmitListener { event ->
            handleModal(event)
        }
    }

    private fun handleModal(event: ModalSubmitEvent) {
        val interaction = event.modalInteraction
        val customId = interaction.customId

        when (customId) {
            "application" -> {
                val channel =
                    interaction.server.getOrNull()?.getTextChannelById(APPLICATION_CHANNEL_ID)
                        ?.getOrNull() ?: return
                val user = interaction.user

                val embed = EmbedBuilder()
                    .setAuthor(user)
                    .setColor(Color.yellow)
                    .setThumbnail(user.avatar)
                    .setDescription("Application to Join")

                embed.addField("User", "${user.name} <@${user.id}>")

                for (q in questions) {
                    val input = interaction.getTextInputValueByCustomId(q.key).getOrNull() ?: ""
                    embed.addField(q.value, input)
                }

                val builder = MessageBuilder()
                    .setEmbeds(embed)
                    .addComponents(
                        ActionRow.of(
                            Button.success("accept-application-${user.id}", "Accept"),
                            Button.danger("decline-application-${user.id}", "Decline"),
                        ),
                    )

                builder.send(channel)

                event.interaction.createImmediateResponder().setFlags(MessageFlag.EPHEMERAL).setAllowedMentions(noMentions)
                    .setContent("Thank you for your application. <@&1259974713774571582> will review it in due course.")
                    .respond()
            }
        }
    }

    private val questions = hashMapOf(
        "q1" to "Are you interested in the development/future of RSPS?",
        "q2" to "What was the last thing you worked on in RSPS big or small?",
        "q3" to "How long ago did you do this?",
        "q4" to "If one side of a square is 25 pixels wide, how wide are all four sides combined?",
        "q5" to "Write \"hello world\" in Java/Kotlin to console?",
    )

    private fun updateApplicationMessage(
        interaction: MessageComponentInteraction,
        accept: Boolean
    ) {
        val message = interaction.message
        val embed = message.embeds[0]

        val builder = embed.toBuilder()
        if (accept) {
            builder.setColor(Color.green)
        } else {
            builder.setColor(Color.RED)
        }

        val updater = message.createUpdater()
        updater.setEmbed(builder)
        updater.removeAllComponents()
        updater.applyChanges()
    }

    private fun handleComponent(event: MessageComponentCreateEvent) {
        val interaction = event.messageComponentInteraction
        val customId = interaction.customId
        val message = interaction.message

        if (customId.startsWith("accept-application-")) {
            updateApplicationMessage(interaction, true)

            val user = event.api.getUserById(customId.substring(19).toLong()).get()
            val role = event.api.getRoleById(MEMBER_ROLE_ID).getOrNull() ?: return

            user.addRole(role).join()

            val general = event.api.getTextChannelById(GENERAL_CHANNEL_ID).getOrNull()
            if (general != null) {
                MessageBuilder().setContent("Everyone please give a warm welcome to our newest user <@${user.id}>!")
                    .send(general)
            }

            interaction.createImmediateResponder()
                .setContent("${user.name} application **accepted** by ${interaction.user.name}")
                .respond()
            return
        }

        if (customId.startsWith("decline-application-")) {
            updateApplicationMessage(interaction, false)

            val user = event.api.getUserById(customId.substring(20).toLong()).get()
            user.sendMessage("Your application has unfortunately been declined.").join()

            interaction.createImmediateResponder()
                .setContent("${user.name} application **declined** by ${interaction.user.name}")
                .respond()
            return
        }

        when (customId) {
            "apply" -> {
                val list = mutableListOf<ActionRow>()
                var i = 1
                for (q in questions) {
                    list.add(
                        ActionRow.of(
                            TextInputBuilder(TextInputStyle.PARAGRAPH, q.key, "Question ${i++}")
                                .setRequired(true)
                                .setPlaceholder(q.value)
                                .build(),
                        ),
                    )
                }
                interaction.respondWithModal(
                    "application", "Application to Join",
                    list as List<HighLevelComponent>?,
                ).join()
            }
        }
    }

    private fun addUserToDatabase(memberId: String) {
        getUser(database, memberId) ?: database.userQueries.createUser(memberId)
    }

    private fun deleteUserFromDatabase(memberId: String) {
        getUser(database, memberId) ?: database.userQueries.deleteUser(memberId)
    }

    private fun scheduleScoreReductionTask(server: Server) {
        val executor = Executors.newScheduledThreadPool(1)
        val task = Runnable { reduceInactiveUserScores(server) }
        executor.scheduleAtFixedRate(task, 0, 5, TimeUnit.MINUTES)
    }

    private fun reduceInactiveUserScores(server: Server) {
        val inactiveUsers = database.userQueries.getInactiveUsersCheck().executeAsList()

        inactiveUsers.forEach { user ->
            val currentRank =
                Ranks.entries.lastOrNull { user.activity_score >= it.requirement } ?: return@forEach

            val scoreReduction = max(1, ceil(user.activity_score * 0.01).toInt())
            val newScore = max(0, user.activity_score - scoreReduction)

            logger.info { "Reducing score for ${user.discord_id} by $scoreReduction" }

            database.userQueries.updateInactiveCheckWithScore(newScore, user.id)

            val nextRank = Ranks.entries.lastOrNull { newScore >= it.requirement }
            if (nextRank != currentRank) {
                val member = server.getMemberById(user.discord_id).getOrNull() ?: return@forEach

                logger.info { "Removing role for ${user.discord_id} - ${currentRank.name}" }
                val currentRole =
                    server.getRoleById(currentRank.roleId).getOrNull() ?: return@forEach
                member.removeRole(currentRole).join()

                nextRank?.let {
                    logger.info { "Assigning role for ${user.discord_id} - ${it.name}" }
                    val newRole = server.getRoleById(it.roleId).getOrNull() ?: return@forEach
                    member.addRole(newRole).join()
                }
            }
        }
    }

    private fun handleCommand(event: SlashCommandCreateEvent) {
        val cmd = event.slashCommandInteraction

        when (cmd.commandId) {
            leaderboardCommand.id -> handleLeaderboardCommand(cmd)
            rankCommand.id -> handleRankCommand(cmd)
            scoreCommand.id -> handleScoreCommand(cmd)
            deleteCommand.id -> handleDeleteCommand(cmd)
            inactiveCommand.id -> handleInactiveCommand(cmd)
            getUserCommand.id -> handleGetUserCommand(cmd)
            setScoreCommand.id -> handleSetScoreCommand(cmd)
            joinMessageCommand.id -> handleJoinMessageCommand(cmd)
        }
    }

    private fun handleJoinMessageCommand(cmd: SlashCommandInteraction) {
        val lobbyChannel =
            cmd.server.getOrNull()?.getTextChannelById(LOBBY_CHANNEL_ID)?.getOrNull() ?: return

        MessageBuilder()
            .setContent("To join our Discord, you'll need to complete a brief application. This helps us ensure that everyone who joins shares a genuine interest in the hobby and contributes positively to our community.")
            .addComponents(
                ActionRow.of(
                    Button.secondary("apply", "Apply to Join"),
                ),
            )
            .send(lobbyChannel)

        cmd.createImmediateResponder().setContent("sent").respond()
    }

    private fun handleLeaderboardCommand(cmd: SlashCommandInteraction) {
        val users = database.userQueries.getTopUsers().executeAsList()
        var text = "**Leaderboard**\n"
        users.forEachIndexed { index, user ->
            text = text.plus("${index + 1}. <@${user.discord_id}>: ${user.activity_score}\n")
        }
        val allowedMentions =
            AllowedMentionsBuilder().setMentionRoles(false).setMentionEveryoneAndHere(false).build()
        cmd.createImmediateResponder()
            .setAllowedMentions(allowedMentions)
            .setContent(text)
            .respond()
    }

    private fun handleRankCommand(cmd: SlashCommandInteraction) {
        val user = getUser(database, cmd.user.id.toString()) ?: return
        val currentRank =
            Ranks.entries.lastOrNull { user.activity_score >= it.requirement } ?: return
        val nextRank = Ranks.entries.firstOrNull { it.requirement > user.activity_score } ?: run {  
            cmd.createImmediateResponder().setContent("if u made it this far log off").respond()
            return
        }
        val diff = nextRank.requirement - currentRank.requirement
        val remaining = nextRank.requirement - user.activity_score
        val progress = diff - remaining
        val percentage = (progress.toDouble() / diff.toDouble()) * 100

        cmd.createImmediateResponder()
            .setContent(
                "You are ${PERCENTAGE_FORMAT.format(percentage)}% of the way to the next rank! You need $remaining more points to reach ${nextRank.name}(req: ${nextRank.requirement}).",
            )
            .respond()
    }

    private fun handleScoreCommand(cmd: SlashCommandInteraction) {
        val score = database.userQueries.getScore(cmd.user.id.toString()).executeAsOneOrNull() ?: 0
        cmd.createImmediateResponder().setContent("Your score is: $score").respond()
    }

    private fun handleInactiveCommand(cmd: SlashCommandInteraction) {
        val inactive = database.userQueries.getInactiveUsers().executeAsList()
        val allowedMentions =
            AllowedMentionsBuilder().setMentionRoles(false).setMentionEveryoneAndHere(false).build()

        if (inactive.isNotEmpty()) {
            val now = LocalDateTime.now()
            val sb = StringBuilder("Inactive users:\n")

            for (user in inactive) {
                val lastMessageDate = LocalDateTime.parse(user.last_message_date!!, formatter)
                val daysBetween = Duration.between(lastMessageDate, now).toDays()

                sb.appendLine("<@${user.discord_id}> (days: $daysBetween)")
            }

            cmd.createImmediateResponder()
                .setAllowedMentions(allowedMentions)
                .setContent(sb.toString())
                .respond()
        } else {
            cmd.createImmediateResponder()
                .setContent("No inactive users found!")
                .respond()
        }
    }

    private fun handleDeleteCommand(cmd: SlashCommandInteraction) {
        database.userQueries.deleteUser(cmd.arguments[0].userValue.get().id.toString())
        cmd.createImmediateResponder().setContent("User deleted!").respond()
    }

    private fun handleGetUserCommand(cmd: SlashCommandInteraction) {
        val user = database.userQueries.selectUser(cmd.arguments[0].userValue.get().id.toString())
            .executeAsOneOrNull()
        val allowedMentions =
            AllowedMentionsBuilder().setMentionRoles(false).setMentionEveryoneAndHere(false).build()
        cmd.createImmediateResponder().setAllowedMentions(allowedMentions)
            .setContent("**User:** <@${user?.discord_id}> **First Seen:** ${user?.date} **Last Seen:** ${user?.last_message_date} **Score:** ${user?.activity_score}")
            .respond()
    }

    private fun handleSetScoreCommand(cmd: SlashCommandInteraction) {
        val user = cmd.arguments[0].userValue.getOrNull() ?: return
        val score = cmd.arguments[1].longValue.get()
        tryUpdateUserRole(
            getUser(database, user.id.toString())!!,
            server = cmd.server.get(),
            target = user,
            points = score,
        )
        database.userQueries.setScore(score, user.id.toString())
        cmd.createImmediateResponder().setAllowedMentions(noMentions)
            .setContent("<@${user.id}> score set to $score").respond()
    }

    private fun handleMessage(event: MessageCreateEvent) {
        // exclude the bot channel
        if (event.channel.id == BOT_CHANNEL_ID) {
            return
        }

        val now = LocalDateTime.parse(database.miscQueries.getTime().executeAsOne(), formatter)
        val discordId = event.messageAuthor.id.toString()
        val user = getUser(database, discordId) ?: return
        val lastMessageDate = LocalDateTime.parse(user.last_message_date!!, formatter)

        if (now.isBefore(lastMessageDate.plusSeconds(5))) {
            return
        }

        val split = event.messageContent.trim().split(" ")

        if (split.size >= 3 && split.count { it.length >= 3 } >= 3) {
            database.userQueries.incrementScore(discordId)

            event.messageAuthor.asUser().getOrNull()?.let { discordUser ->
                tryUpdateUserRole(user, event.server.get(), discordUser, event.channel)
            }
        }
    }

    private fun tryUpdateUserRole(
        user: Users,
        server: Server,
        target: User,
        channel: TextChannel? = null,
        points: Long = 1,
        announce: Boolean = true
    ) {
        val currentRank = Ranks.entries.lastOrNull { user.activity_score >= it.requirement }
        val userScore = if (points == 1.toLong()) user.activity_score + points else points
        val nextRank = Ranks.entries.last { userScore >= it.requirement }

        if (currentRank == null || currentRank != nextRank) {
            val role = server.getRoleById(nextRank.roleId).getOrNull() ?: return

            target.getRoles(server).forEach { r ->
                if (Ranks.entries.any { r.id == it.roleId }) {
                    target.removeRole(r).exceptionally {
                        return@exceptionally null
                    }.join()
                }
            }

            target.addRole(role).join()

            if (announce && channel != null) {
                channel.sendMessage(
                    "${target.mentionTag} congratulations on the promotion to ${role.name}!",
                )
            }
        }
    }

    private fun getUser(database: Database, discordId: String): Users? {
        return database.userQueries.selectUser(discordId).executeAsOneOrNull()
    }

    companion object {
        const val GENERAL_CHANNEL_ID = 1259965877185679424L
        const val MEMBER_ROLE_ID = 1261060308911526071L
        const val SERVER_ID = 1259965876598472815L
        const val BOT_CHANNEL_ID = 1261381446724096093L
        const val LOBBY_CHANNEL_ID = 1261060232050774086L
        const val APPLICATION_CHANNEL_ID = 1261063297327829103L
        const val PERCENTAGE_FORMAT = "%.2f"
    }
}
