package org.radi8.getAJob

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class KickConfig(
    val kickTime: Long,
    val kickMessage: String,
    val kickBroadcast: String,
    val kickIgnoreUsers: List<String>
)

data class Coords(
    val x: Double,
    val y: Double,
)

class GetAJob : JavaPlugin(), CommandExecutor, Listener {
    val playtime: MutableMap<UUID, Long> = ConcurrentHashMap()
    var coords: MutableMap<UUID, Coords> = ConcurrentHashMap()

    private var connection: Connection? = null

    private fun getDatabaseFile(): File {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        return File(dataFolder, "database.db")
    }

    private fun initializeDatabase() {
        val dbFile = getDatabaseFile()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"

        try {
            connection = DriverManager.getConnection(url)
            logger.info("successfully connected to sqlite!")

            connection?.createStatement().use { statement ->
                statement?.execute(
                    """
                        CREATE TABLE IF NOT EXISTS playtime (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid VARCHAR(36) NOT NULL,
                            length INTEGER NOT NULL,
                            time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """.trimIndent()
                )
                logger.info("database table 'playtime' init'd!")
            }
        } catch (e: SQLException) {
            logger.severe("failed to connect to or init sqlite: ${e.message}")
            server.pluginManager.disablePlugin(this)
        }
    }

    private fun closeDatabaseConnection() {
        try {
            connection?.takeIf { !it.isClosed }?.close()
            logger.info("db connection closed")
        } catch (e: SQLException) {
            logger.severe("failed to close db connection: ${e.message}")
        }
    }

    fun getKickConfig(): KickConfig {
        val kickTime = config.getLong("kick-time")
        val kickMessage = config.getString("kick-message") ?: ""
        val kickBroadcast = config.getString("kick-broadcast") ?: ""
        val kickIgnoreUsers = config.getStringList("kick-ignore-users")

        return KickConfig(
            kickTime = kickTime,
            kickMessage = kickMessage,
            kickBroadcast = kickBroadcast,
            kickIgnoreUsers = kickIgnoreUsers
        )
    }


    override fun onEnable() {
        initializeDatabase()
        if (connection == null || connection?.isClosed == true) {
            logger.severe("db connection failed, disabling plugin")
            server.pluginManager.disablePlugin(this)
        }
        Bukkit.getPluginManager().registerEvents(this, this)

        getCommand("get-time")?.setExecutor(GetTime(this))
        getCommand("leaderboard")?.setExecutor(Leaderboard(this))

        this.saveDefaultConfig()
        config.addDefault("kick-time", 1)
        config.addDefault(
            "kick-message",
            "Seriously? 1 minute already? Don't you have anything better to do, like... getting a job? (unless you already have one? sorry.)"
        )
        config.addDefault(
            "kick-broadcast",
            "{player} has spent 1 minute(s) on the server today. If they don't have a job, have them get one. Shame!"
        )
        config.addDefault("kick-ignore-users", listOf<String>())
        config.options().copyDefaults(true)

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            server.onlinePlayers.forEach { player ->
                val uuid = player.uniqueId
                val loc = player.location
                val x = loc.x
                val y = loc.y

                if (coords.contains(uuid)) {
                    if (abs(coords[uuid]?.x?.minus(x) ?: 0.0) > 5 || abs(coords[uuid]?.y?.minus(y) ?: 0.0) > 5) {
                        coords[uuid] = Coords(x, y)
                    } else {
                        logger.info("player $uuid is likely afk; not adding time.")
                        return@forEach
                        coords[uuid] = Coords(x, y)
                    }
                } else {
                    coords[uuid] = Coords(x, y)
                }

                playtime.compute(uuid) { _, currentTime ->
                    (currentTime ?: 0L) + 1200L
                }

                val playtimePastSessionsToday = getPlaytimePastSessionsToday(uuid)

                val playtimeToday = (playtime[uuid]?.plus(playtimePastSessionsToday * 1200L))?.div(1200L)
                if (playtimeToday != null) {
                    val kickConfig = getKickConfig()
                    if (!kickConfig.kickIgnoreUsers.contains(player.uniqueId.toString())) {
                        if (playtimeToday >= kickConfig.kickTime) {
                            Bukkit.getScheduler().runTask(this, Runnable {
                                if (player.isOnline) {
                                    player.kickPlayer(kickConfig.kickMessage)
                                }
                                Bukkit.broadcastMessage(kickConfig.kickBroadcast.replace("{player}", player.name))
                            })
                        }
                    }
                }

                logger.info("$uuid has played for $playtimeToday minute(s) today.")
            }
        }, 0L, 1200L)

        logger.info("${description.name} version ${description.version} enabled!")
    }

    private fun getPlaytimePastSessionsToday(uuid: UUID): Long {
        try {
            connection?.prepareStatement(
                "SELECT SUM(length) FROM playtime WHERE uuid = ? AND time >= date('now', 'start of day')"
            ).use { preparedStatement ->
                preparedStatement?.setString(1, uuid.toString())
                val resultSet = preparedStatement?.executeQuery()
                return if (resultSet?.next() == true) {
                    resultSet.getLong(1)
                } else {
                    0L
                }
            }
        } catch (e: SQLException) {
            logger.severe("failed to get playtime for ${uuid}: ${e.message}")
            return 0L
        }
    }

    private fun generateTodaySummarization(): String {
        val sessions = ConcurrentHashMap<String, Long>()

        try {
            connection?.prepareStatement(
                "SELECT uuid, SUM(length) as total_length FROM playtime WHERE time >= date('now', 'start of day') GROUP BY uuid"
            )?.use { preparedStatement ->
                val resultSet = preparedStatement.executeQuery()
                while (resultSet.next()) {
                    val uuid = resultSet.getString("uuid")
                    val totalLength = resultSet.getLong("total_length")
                    sessions[uuid] = totalLength
                }
            }
        } catch (e: SQLException) {
            logger.severe("failed to get today's past sessions summarization: ${e.message}")
        }

        val currentPlaytime = HashMap(playtime)
        currentPlaytime.forEach { (uuid, currentSessionTicks) ->
            val currentSessionMinutes = currentSessionTicks / 1200L
            if (currentSessionMinutes > 0) {
                sessions.compute(uuid.toString()) { _, existingMinutes ->
                    (existingMinutes ?: 0L) + currentSessionMinutes
                }
            }
        }

        if (sessions.isEmpty()) {
            return "No time played today."
        }

        val messageBuilder = StringBuilder()
        sessions.entries
            .forEach { (uuidString, totalMinutes) ->
                val uuid = UUID.fromString(uuidString)
                val username = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown ($uuidString)"
                val displayMinutes = totalMinutes

                if (displayMinutes > 0) {
                    messageBuilder.append("$username has played for $displayMinutes minute(s) today\n")
                }
            }

        if (messageBuilder.isEmpty()) {
            return "No significant time played today."
        }

        return messageBuilder.toString().trimEnd()
    }

    class GetTime(private val plugin: GetAJob) : CommandExecutor {
        override fun onCommand(
            sender: CommandSender,
            command: Command,
            label: String,
            args: Array<out String?>
        ): Boolean {
            val player = sender as? org.bukkit.entity.Player
            if (player != null) {
                val time = plugin.playtime[player.uniqueId] ?: 0L
                val minutes = time / 1200L
                sender.sendMessage("Your approximate playtime this session: $minutes minute(s).")
            } else {
                sender.sendMessage("This command can only be run by a player.")
            }

            return true
        }
    }

    class Leaderboard(private val plugin: GetAJob) : CommandExecutor {
        override fun onCommand(
            sender: CommandSender,
            command: Command,
            label: String,
            args: Array<out String?>
        ): Boolean {
            val player = sender as? org.bukkit.entity.Player
            if (player != null) {
                val message = plugin.generateTodaySummarization()
                sender.sendMessage(message)
            } else {
                sender.sendMessage("This command can only be run by a player.")
            }

            return true
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId

        logger.info("player ${player.name} joined!")

        playtime[uuid] = 0L

        val playtimePastSessionsTodayFuture = CompletableFuture.supplyAsync {
            getPlaytimePastSessionsToday(uuid)
        }

        Bukkit.getScheduler().runTask(this, Runnable {
            val playtimePastSessionsToday = playtimePastSessionsTodayFuture.get()
            val playtimeToday =
                (playtime[uuid]?.plus(playtimePastSessionsToday * 1200L))?.div(1200L) ?: 0L // Provide a default value

            val kickConfig = getKickConfig()
            if (!kickConfig.kickIgnoreUsers.contains(player.uniqueId.toString())) {
                if (playtimeToday >= kickConfig.kickTime) {
                    Bukkit.getScheduler().runTask(this, Runnable {
                        if (player.isOnline) {
                            player.kickPlayer(kickConfig.kickMessage)
                        }
                    })
                }
            }
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val time = (playtime.remove(uuid) ?: 0L) / 1200L // inserts session time in minutes

        if (time < 1L) {
            return
        }

        logger.info("saving playtime for $uuid: $time minute(s)")

        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            try {
                connection?.prepareStatement(
                    "INSERT INTO playtime (uuid, length) VALUES (?, ?)"
                ).use { preparedStatement ->
                    preparedStatement?.setString(1, uuid.toString())
                    preparedStatement?.setLong(2, time)
                    preparedStatement?.executeUpdate()

                    logger.info("saved playtime for $uuid: $time minute(s)")
                }
            } catch (e: SQLException) {
                logger.severe("failed to save playtime for $uuid: ${e.message}")
            }
        })
    }

    override fun onDisable() {
        Bukkit.getScheduler().cancelTasks(this)
        server.onlinePlayers.forEach { player ->
            val time = (playtime.remove(player.uniqueId) ?: 0L) / 1200L
            if (time < 1L) {
                return@forEach
            }

            logger.info("saving playtime for ${player.uniqueId}: $time minute(s)")

            try {
                connection?.prepareStatement(
                    "INSERT INTO playtime (uuid, length) VALUES (?, ?)"
                ).use { preparedStatement ->
                    preparedStatement?.setString(1, player.uniqueId.toString())
                    preparedStatement?.setLong(2, time)
                    preparedStatement?.executeUpdate()

                    logger.info("saved playtime for ${player.uniqueId}: $time minute(s)")
                }
            } catch (e: SQLException) {
                logger.severe("failed to save playtime for ${player.uniqueId}: ${e.message}")
            }
        }

        closeDatabaseConnection()
        logger.info("${description.name} disabled.")
    }
}
