package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val defaultModelId: String? = null,
    val isDefault: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "chat_threads",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("workspaceId"), Index("updatedAtMillis")]
)
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val workspaceId: String?,
    val title: String,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val lastModelId: String? = null,
    val lastModelName: String? = null,
    val trimBeforeTurnOrder: Int = 0,
    // Rolling model-generated summary of turns below the trim boundary (conversation memory).
    val memorySummary: String? = null,
    val summaryUntilTurnOrder: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("threadId"), Index("branchGroupId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val turnOrder: Int,
    val branchGroupId: String,
    val revisionIndex: Int = 0,
    val isActiveBranch: Boolean = true,
    val role: String,
    val text: String,
    val thinkingText: String = "",
    val imageUriString: String? = null,
    /** JSON array of all attached image URIs (multi-image turns); first mirrors imageUriString. */
    val imageUrisJson: String? = null,
    val citationsJson: String? = null,
    val telemetryJson: String? = null,
    val isInterrupted: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)

/** Mirror of [ChatMessageEntity.text] for full-text search; rowids match the content table. */
@Fts4(contentEntity = ChatMessageEntity::class)
@Entity(tableName = "chat_messages_fts")
data class ChatMessageFtsEntity(
    val text: String
)

@Serializable
@Entity(
    tableName = "indexed_documents",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("threadId"), Index("workspaceId")]
)
data class IndexedDocumentEntity(
    @PrimaryKey val id: String,
    val threadId: String?,
    val workspaceId: String?,
    val displayName: String,
    val mimeType: String,
    val chunkCount: Int,
    val charCount: Int,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = IndexedDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class DocumentChunkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val sectionLabel: String,
    val content: String,
    val normalizedTokens: String
)

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey val id: String,
    val trigger: String,
    val label: String,
    val promptBody: String,
    val isBuiltIn: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY isDefault DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT COUNT(*) FROM workspaces")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(workspace: WorkspaceEntity)

    @Update
    suspend fun update(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE id = :id AND isDefault = 0")
    suspend fun deleteCustom(id: String)
}

@Dao
interface ChatThreadDao {
    @Query("SELECT * FROM chat_threads ORDER BY isPinned DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<ChatThreadEntity>>

    @Query("SELECT * FROM chat_threads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChatThreadEntity?

    @Query("SELECT * FROM chat_threads WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAtMillis DESC LIMIT 1")
    suspend fun getMostRecentActive(): ChatThreadEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(thread: ChatThreadEntity)

    @Update
    suspend fun update(thread: ChatThreadEntity)

    @Query("UPDATE chat_threads SET updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, updatedAt: Long)

    @Query("UPDATE chat_threads SET title = :title, updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE chat_threads SET isArchived = :archived, updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("UPDATE chat_threads SET isPinned = :pinned, updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE chat_threads SET trimBeforeTurnOrder = :turnOrder, updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun setTrimBeforeTurnOrder(id: String, turnOrder: Int, updatedAt: Long)

    @Query(
        "UPDATE chat_threads SET memorySummary = :summary, summaryUntilTurnOrder = :untilTurn, updatedAtMillis = :updatedAt WHERE id = :id"
    )
    suspend fun setMemorySummary(id: String, summary: String?, untilTurn: Int, updatedAt: Long)

    @Query("DELETE FROM chat_threads WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY turnOrder ASC, revisionIndex ASC, timestampMillis ASC")
    fun observeForThread(threadId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY turnOrder ASC, revisionIndex ASC, timestampMillis ASC")
    suspend fun getForThread(threadId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query(
        "UPDATE chat_messages SET text = :text, thinkingText = :thinkingText, isInterrupted = :isInterrupted, timestampMillis = :timestampMillis WHERE id = :id"
    )
    suspend fun updateStreamingCheckpoint(
        id: String,
        text: String,
        thinkingText: String,
        isInterrupted: Boolean,
        timestampMillis: Long
    )

    @Query("UPDATE chat_messages SET isActiveBranch = 0 WHERE threadId = :threadId AND branchGroupId = :groupId")
    suspend fun deactivateBranchesInGroup(threadId: String, groupId: String)

    @Query("UPDATE chat_messages SET isActiveBranch = 1 WHERE id = :messageId")
    suspend fun activateBranch(messageId: String)

    @Transaction
    suspend fun selectBranch(threadId: String, groupId: String, targetMessageId: String) {
        deactivateBranchesInGroup(threadId, groupId)
        activateBranch(targetMessageId)
    }

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId AND turnOrder >= :fromTurnOrder")
    suspend fun deleteFromTurnOrder(threadId: String, fromTurnOrder: Int)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun clearThread(threadId: String)

    /** Atomically replaces the active branch of a group: deactivate, then upsert. */
    @Transaction
    suspend fun replaceActiveBranch(threadId: String, groupId: String, message: ChatMessageEntity) {
        deactivateBranchesInGroup(threadId, groupId)
        upsert(message)
    }

    @Query(
        "SELECT chat_messages.* FROM chat_messages " +
            "INNER JOIN chat_messages_fts ON chat_messages.rowid = chat_messages_fts.rowid " +
            "WHERE chat_messages_fts MATCH :query " +
            "ORDER BY chat_messages.timestampMillis DESC LIMIT :limit"
    )
    suspend fun searchMessages(query: String, limit: Int): List<ChatMessageEntity>
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM indexed_documents WHERE threadId = :threadId OR workspaceId = :workspaceId ORDER BY createdAtMillis DESC")
    fun observeForContext(threadId: String, workspaceId: String): Flow<List<IndexedDocumentEntity>>

    @Query("SELECT * FROM indexed_documents ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<IndexedDocumentEntity>>

    @Query("SELECT * FROM document_chunks WHERE documentId IN (:documentIds) ORDER BY chunkIndex ASC")
    suspend fun getChunksForDocuments(documentIds: List<String>): List<DocumentChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: IndexedDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Transaction
    suspend fun saveDocumentWithChunks(doc: IndexedDocumentEntity, chunks: List<DocumentChunkEntity>) {
        insertDocument(doc)
        insertChunks(chunks)
    }

    @Query("DELETE FROM indexed_documents WHERE id = :docId")
    suspend fun deleteDocument(docId: String)
}

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates ORDER BY isBuiltIn DESC, createdAtMillis ASC")
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT COUNT(*) FROM prompt_templates")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: PromptTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<PromptTemplateEntity>)

    @Query("DELETE FROM prompt_templates WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: String)
}

@Database(
    entities = [
        WorkspaceEntity::class,
        ChatThreadEntity::class,
        ChatMessageEntity::class,
        ChatMessageFtsEntity::class,
        IndexedDocumentEntity::class,
        DocumentChunkEntity::class,
        PromptTemplateEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class LocalMindDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun promptTemplateDao(): PromptTemplateDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN isInterrupted INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Multi-image attachment history
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imageUrisJson TEXT")

                // Conversation-memory columns on threads
                db.execSQL("ALTER TABLE chat_threads ADD COLUMN memorySummary TEXT")
                db.execSQL(
                    "ALTER TABLE chat_threads ADD COLUMN summaryUntilTurnOrder INTEGER NOT NULL DEFAULT 0"
                )

                // Full-text search mirror of chat_messages.text, kept in sync by content triggers
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `chat_messages_fts` USING FTS4(`text` TEXT NOT NULL, content=`chat_messages`)"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_messages_fts_BEFORE_UPDATE " +
                        "BEFORE UPDATE ON `chat_messages` BEGIN DELETE FROM `chat_messages_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_messages_fts_BEFORE_DELETE " +
                        "BEFORE DELETE ON `chat_messages` BEGIN DELETE FROM `chat_messages_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_messages_fts_AFTER_UPDATE " +
                        "AFTER UPDATE ON `chat_messages` BEGIN INSERT INTO `chat_messages_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_messages_fts_AFTER_INSERT " +
                        "AFTER INSERT ON `chat_messages` BEGIN INSERT INTO `chat_messages_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END"
                )
                db.execSQL("INSERT INTO chat_messages_fts(chat_messages_fts) VALUES('rebuild')")

                // Re-create indexed_documents with cascade FKs so documents/chunks die with
                // their thread or workspace instead of leaking forever.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `indexed_documents_new` (" +
                        "`id` TEXT NOT NULL, `threadId` TEXT, `workspaceId` TEXT, " +
                        "`displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                        "`chunkCount` INTEGER NOT NULL, `charCount` INTEGER NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`threadId`) REFERENCES `chat_threads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `indexed_documents_new` " +
                        "(`id`, `threadId`, `workspaceId`, `displayName`, `mimeType`, `chunkCount`, `charCount`, `createdAtMillis`) " +
                        "SELECT `id`, `threadId`, `workspaceId`, `displayName`, `mimeType`, `chunkCount`, `charCount`, `createdAtMillis` " +
                        "FROM `indexed_documents`"
                )
                db.execSQL("DROP TABLE `indexed_documents`")
                db.execSQL("ALTER TABLE `indexed_documents_new` RENAME TO `indexed_documents`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_indexed_documents_threadId` ON `indexed_documents` (`threadId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_indexed_documents_workspaceId` ON `indexed_documents` (`workspaceId`)"
                )
                // Drop pre-migration rows whose owners are already gone (they were unreachable leaks)
                db.execSQL(
                    "DELETE FROM indexed_documents WHERE threadId IS NOT NULL AND threadId NOT IN (SELECT id FROM chat_threads)"
                )
                db.execSQL(
                    "DELETE FROM indexed_documents WHERE workspaceId IS NOT NULL AND workspaceId NOT IN (SELECT id FROM workspaces)"
                )
            }
        }

        fun create(context: Context): LocalMindDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                LocalMindDatabase::class.java,
                "localmind_studio.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        fun createInMemory(context: Context): LocalMindDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                LocalMindDatabase::class.java
            ).allowMainThreadQueries().build()
        }
    }
}
