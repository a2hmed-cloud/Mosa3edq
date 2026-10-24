package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val dueDate: Long? = null,
    val isCompleted: Boolean = false,
    val category: String = "عام",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_logs")
data class AgentLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objective: String,
    val actionsCount: Int,
    val status: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY createdAt DESC")
    suspend fun getPendingTasks(): List<TaskEntity>
}

@Dao
interface AgentLogDao {
    @Query("SELECT * FROM agent_logs ORDER BY timestamp DESC LIMIT 50")
    fun getAllLogs(): Flow<List<AgentLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AgentLogEntity): Long

    @Query("DELETE FROM agent_logs")
    suspend fun clearLogs()
}

@Database(entities = [TaskEntity::class, AgentLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun agentLogDao(): AgentLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yamosaed_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
