package com.eik0.defiwallet.managers

import com.eik0.defiwallet.DefiWallet
import com.eik0.defiwallet.wallet.UserData
import com.eik0.defiwallet.wallet.WalletManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.privy.api.models.components.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class DatabaseManager {
    private lateinit var dataSource: HikariDataSource

    private val host = DefiWallet.instance.config.getString("mysql.host")
    private val port = DefiWallet.instance.config.getInt("mysql.port")
    private val database = DefiWallet.instance.config.getString("mysql.database")
    private val username = DefiWallet.instance.config.getString("mysql.username")
    private val password = DefiWallet.instance.config.getString("mysql.password")

    fun load() {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://$host:$port/$database?useSSL=false&autoReconnect=true"
            driverClassName = "com.mysql.cj.jdbc.Driver"
            this.username = this@DatabaseManager.username
            this.password = this@DatabaseManager.password
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

        dataSource = HikariDataSource(config)
        createTables()
    }

    private fun createTables() {
        try {
            DefiWallet.instance.logger.info("Creating sql tables if needed")

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                    CREATE TABLE IF NOT EXISTS users (
                        uuid VARCHAR(36) PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        wallet_id TEXT NOT NULL,
                        wallet_address TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    , INDEX user_idx (user_id));
                """.trimIndent()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun saveUser(uuid: UUID, userId: String, walletId: String, walletAddress: String) {
        withContext(Dispatchers.IO) {
            try {
                DefiWallet.instance.logger.info("Saving user $userId for player $uuid")

                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                    INSERT INTO users (uuid, user_id, wallet_id, wallet_address)
                    VALUES (?, ?, ?, ?)
                """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, uuid.toString())
                        statement.setString(2, userId)
                        statement.setString(3, walletId)
                        statement.setString(4, walletAddress)

                        statement.executeUpdate()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun loadUser(uuid: UUID): UserData? {
        return withContext(Dispatchers.IO) {
            try {
                DefiWallet.instance.logger.info("Loading user data of player $uuid")

                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                    SELECT * FROM users WHERE uuid = ?
                """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, uuid.toString())
                        val resultSet = statement.executeQuery()
                        if (resultSet.next()) {
                            return@withContext UserData(
                                uuid = uuid,
                                userId = resultSet.getString("user_id"),
                                walletId = resultSet.getString("wallet_id"),
                                walletAddress = resultSet.getString("wallet_address"),
                            )
                        } else {
                            return@withContext null
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext null
        }
    }

    fun close() {
        if (!::dataSource.isInitialized || !dataSource.isClosed) {
            dataSource.close()
        }
    }
}