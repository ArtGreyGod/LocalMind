package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
import kotlinx.coroutines.flow.Flow

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
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

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
    val citationsJson: String? = null,
    val telemetryJson: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "indexed_documents",
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
        IndexedDocumentEntity::class,
        DocumentChunkEntity::class,
        PromptTemplateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LocalMindDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun promptTemplateDao(): PromptTemplateDao

    companion object {
        fun create(context: Context): LocalMindDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                LocalMindDatabase::class.java,
                "localmind_studio.db"
            ).build()
        }

        fun createInMemory(context: Context): LocalMindDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                LocalMindDatabase::class.java
            ).allowMainThreadQueries().build()
        }
    }
}
