package dev.mukesh.login

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import java.util.*

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var description: String = "",
    var isCompleted: Boolean = false,
    var priority: Priority = Priority.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)

enum class Priority(val displayName: String, val value: Int) {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3);

    companion object {
        fun fromValue(value: Int): Priority {
            return Priority.entries.find { it.value == value } ?: MEDIUM
        }
    }
}

enum class SortBy(val displayName: String) {
    TITLE("Title"),
    PRIORITY("Priority"),
    CREATED_DATE("Created Date"),
    UPDATED_DATE("Updated Date"),
    COMPLETION("Completion Status")
}


enum class FilterType(val displayName: String) {
    ALL("All"),
    PENDING("Pending"),
    COMPLETED("Completed"),
    HIGH_PRIORITY("High Priority"),
    MEDIUM_PRIORITY("Medium Priority"),
    LOW_PRIORITY("Low Priority")
}

class TodoManager {
    private val firestore = Firebase.firestore
    private val auth = Firebase.auth

    private val _todos = mutableStateListOf<TodoItem>()
    val todos: SnapshotStateList<TodoItem> get() = _todos

    private var _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private var _error = mutableStateOf<String?>(null)
    val error: State<String?> = _error

    private var _isInitialized = mutableStateOf(false)
    val isInitialized: State<Boolean> = _isInitialized

    val totalTodos: Int get() = _todos.size
    val completedTodos: Int get() = _todos.count { it.isCompleted }
    val pendingTodos: Int get() = _todos.count { !it.isCompleted }
    val highPriorityTodos: Int get() = _todos.count { it.priority == Priority.HIGH && !it.isCompleted }

    init {
        loadTodos()
    }

    private fun getCurrentUserId(): String? {
        return try {
            val userId = auth.currentUser?.uid
            Log.d("TodoManager", "Current user ID: $userId")
            userId
        } catch (e: Exception) {
            Log.e("TodoManager", "Error getting current user", e)
            null
        }
    }

    private fun getTodosCollection() = firestore
        .collection("todos")
        .whereEqualTo("userId", getCurrentUserId())

    fun loadTodos() {
        val userId = getCurrentUserId()
        Log.d("TodoManager", "Starting loadTodos() - User ID: $userId")

        if (userId == null) {
            Log.e("TodoManager", "User not authenticated - cannot load todos")
            _error.value = "User not authenticated"
            _isInitialized.value = true
            return
        }

        _isLoading.value = true
        _error.value = null

        Log.d("TodoManager", "Loading todos for user: $userId")

        getTodosCollection()
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                try {
                    Log.d("TodoManager", "Firestore query successful")
                    Log.d("TodoManager", "Query returned ${querySnapshot.documents.size} documents")

                    val todoList = mutableListOf<TodoItem>()

                    querySnapshot.documents.forEach { document ->
                        try {
                            Log.d("TodoManager", "Processing document: ${document.id}")

                            if (!document.exists()) {
                                Log.w("TodoManager", "Document ${document.id} does not exist")
                                return@forEach
                            }

                            val data = document.data
                            if (data == null) {
                                Log.w("TodoManager", "Document data is null for ${document.id}")
                                return@forEach
                            }

                            val title = data["title"] as? String
                            val docUserId = data["userId"] as? String

                            if (title.isNullOrBlank()) {
                                Log.w("TodoManager", "Document ${document.id} has empty title: '$title'")
                                return@forEach // Skip todos with empty titles
                            }

                            if (docUserId != userId) {
                                Log.w("TodoManager", "Document ${document.id} userId mismatch: expected '$userId', got '$docUserId'")
                                return@forEach // Skip todos that don't belong to current user
                            }

                            val todoItem = TodoItem(
                                id = document.id,
                                title = title,
                                description = data["description"] as? String ?: "",
                                isCompleted = data["isCompleted"] as? Boolean ?: false,
                                priority = Priority.fromValue(
                                    when (val priorityValue = data["priority"]) {
                                        is Long -> priorityValue.toInt()
                                        is Int -> priorityValue
                                        is String -> priorityValue.toIntOrNull() ?: Priority.MEDIUM.value
                                        else -> Priority.MEDIUM.value
                                    }
                                ),
                                createdAt = (data["createdAt"] as? Long) ?: System.currentTimeMillis(),
                                updatedAt = (data["updatedAt"] as? Long) ?: System.currentTimeMillis(),
                                userId = docUserId ?: userId
                            )

                            Log.d("TodoManager", "Successfully created TodoItem: ${todoItem.id} - '${todoItem.title}'")
                            todoList.add(todoItem)

                        } catch (e: Exception) {
                            Log.e("TodoManager", "Error parsing document ${document.id}", e)
                        }
                    }

                    Log.d("TodoManager", "Successfully parsed ${todoList.size} todos")

                    // Update state in a single operation
                    _todos.clear()
                    _todos.addAll(todoList)
                    _isLoading.value = false
                    _isInitialized.value = true
                    _error.value = null

                    Log.d("TodoManager", "Todos loaded successfully. Total count: ${_todos.size}")

                } catch (e: Exception) {
                    Log.e("TodoManager", "Error processing loaded todos", e)
                    _isLoading.value = false
                    _isInitialized.value = true
                    _error.value = "Error processing todos: ${e.message}"
                }
            }
            .addOnFailureListener { exception ->
                _isLoading.value = false
                _isInitialized.value = true
                Log.e("TodoManager", "Firestore query failed", exception)
                _error.value = "Failed to load todos: ${exception.message}"
            }
    }

    // Enhanced filtering methods for better UI integration
    fun getFilteredTodos(filterType: FilterType = FilterType.ALL): List<TodoItem> {
        return try {
            when (filterType) {
                FilterType.ALL -> _todos.toList()
                FilterType.PENDING -> _todos.filter { !it.isCompleted }
                FilterType.COMPLETED -> _todos.filter { it.isCompleted }
                FilterType.HIGH_PRIORITY -> _todos.filter { it.priority == Priority.HIGH }
                FilterType.MEDIUM_PRIORITY -> _todos.filter { it.priority == Priority.MEDIUM }
                FilterType.LOW_PRIORITY -> _todos.filter { it.priority == Priority.LOW }
            }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error filtering todos", e)
            emptyList()
        }
    }

    fun getSortedAndFilteredTodos(
        sortBy: SortBy = SortBy.CREATED_DATE,
        filterType: FilterType = FilterType.ALL
    ): List<TodoItem> {
        return try {
            val filtered = getFilteredTodos(filterType)
            when (sortBy) {
                SortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
                SortBy.PRIORITY -> filtered.sortedByDescending { it.priority.value }
                SortBy.CREATED_DATE -> filtered.sortedByDescending { it.createdAt }
                SortBy.UPDATED_DATE -> filtered.sortedByDescending { it.updatedAt }
                SortBy.COMPLETION -> filtered.sortedBy { it.isCompleted }
            }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error sorting and filtering todos", e)
            emptyList()
        }
    }

    // Add a method to check if todos are ready to be displayed
    fun hasTodos(): Boolean = _todos.isNotEmpty()

    fun isEmpty(): Boolean = _todos.isEmpty() && _isInitialized.value

    // Rest of your existing methods remain the same...
    suspend fun addTodo(
        title: String,
        description: String = "",
        priority: Priority = Priority.MEDIUM
    ): Result<String> {
        return try {
            val userId = getCurrentUserId() ?: return Result.failure(Exception("User not authenticated"))

            if (title.trim().isBlank()) {
                return Result.failure(Exception("Title cannot be empty"))
            }

            val todoId = UUID.randomUUID().toString()
            val currentTime = System.currentTimeMillis()

            val todoData = hashMapOf(
                "title" to title.trim(),
                "description" to description.trim(),
                "isCompleted" to false,
                "priority" to priority.value,
                "createdAt" to currentTime,
                "updatedAt" to currentTime,
                "userId" to userId
            )

            firestore.collection("todos")
                .document(todoId)
                .set(todoData)
                .await()

            val newTodo = TodoItem(
                id = todoId,
                title = title.trim(),
                description = description.trim(),
                isCompleted = false,
                priority = priority,
                createdAt = currentTime,
                updatedAt = currentTime,
                userId = userId
            )

            _todos.add(0, newTodo)

            Log.d("TodoManager", "Successfully added todo: $todoId")
            Result.success(todoId)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error adding todo", e)
            loadTodos()
            Result.failure(Exception("Failed to add todo: ${e.message}"))
        }
    }

    fun getTodoById(id: String): TodoItem? {
        return try {
            _todos.find { it.id == id }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error getting todo by ID", e)
            null
        }
    }

    fun getTodosByStatus(completed: Boolean): List<TodoItem> {
        return try {
            _todos.filter { it.isCompleted == completed }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error filtering todos by status", e)
            emptyList()
        }
    }

    fun getTodosByPriority(priority: Priority): List<TodoItem> {
        return try {
            _todos.filter { it.priority == priority }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error filtering todos by priority", e)
            emptyList()
        }
    }

    fun searchTodos(query: String): List<TodoItem> {
        return try {
            if (query.trim().isEmpty()) return _todos.toList()

            val searchQuery = query.trim().lowercase()
            _todos.filter { todo ->
                todo.title.lowercase().contains(searchQuery) ||
                        todo.description.lowercase().contains(searchQuery)
            }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error searching todos", e)
            emptyList()
        }
    }

    suspend fun updateTodo(
        id: String,
        title: String,
        description: String,
        priority: Priority? = null
    ): Result<Unit> {
        return try {
            if (id.isBlank()) {
                return Result.failure(Exception("Todo ID cannot be empty"))
            }

            if (title.trim().isBlank()) {
                return Result.failure(Exception("Title cannot be empty"))
            }

            val existingTodoIndex = _todos.indexOfFirst { it.id == id }
            if (existingTodoIndex == -1) {
                Log.e("TodoManager", "Todo not found in local state: $id")
                return Result.failure(Exception("Todo not found"))
            }

            val existingTodo = _todos[existingTodoIndex]
            val currentTime = System.currentTimeMillis()

            val updates = mutableMapOf<String, Any>()

            if (existingTodo.title != title.trim()) {
                updates["title"] = title.trim()
            }

            if (existingTodo.description != description.trim()) {
                updates["description"] = description.trim()
            }

            priority?.let { newPriority ->
                if (existingTodo.priority != newPriority) {
                    updates["priority"] = newPriority.value
                }
            }

            updates["updatedAt"] = currentTime

            if (updates.size == 1) {
                Log.d("TodoManager", "No changes detected for todo: $id")
                return Result.success(Unit)
            }

            Log.d("TodoManager", "Updating todo $id with changes: ${updates.keys}")

            firestore.collection("todos")
                .document(id)
                .update(updates)
                .await()

            val updatedTodo = existingTodo.copy(
                title = title.trim(),
                description = description.trim(),
                priority = priority ?: existingTodo.priority,
                updatedAt = currentTime
            )

            _todos[existingTodoIndex] = updatedTodo

            Log.d("TodoManager", "Successfully updated todo: $id")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e("TodoManager", "Error updating todo: $id", e)
            Result.failure(Exception("Failed to update todo: ${e.message}"))
        }
    }

    suspend fun toggleComplete(id: String): Result<Unit> {
        return try {
            if (id.isBlank()) {
                return Result.failure(Exception("Todo ID cannot be empty"))
            }

            val todoIndex = _todos.indexOfFirst { it.id == id }
            if (todoIndex == -1) {
                return Result.failure(Exception("Todo not found"))
            }

            val existingTodo = _todos[todoIndex]
            val newCompletedStatus = !existingTodo.isCompleted
            val currentTime = System.currentTimeMillis()

            val updates = hashMapOf<String, Any>(
                "isCompleted" to newCompletedStatus,
                "updatedAt" to currentTime
            )

            firestore.collection("todos")
                .document(id)
                .update(updates)
                .await()

            _todos[todoIndex] = existingTodo.copy(
                isCompleted = newCompletedStatus,
                updatedAt = currentTime
            )

            Log.d("TodoManager", "Successfully toggled completion for todo: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error toggling todo completion", e)
            Result.failure(Exception("Failed to toggle completion: ${e.message}"))
        }
    }

    suspend fun updatePriority(id: String, priority: Priority): Result<Unit> {
        return try {
            if (id.isBlank()) {
                return Result.failure(Exception("Todo ID cannot be empty"))
            }

            val todoIndex = _todos.indexOfFirst { it.id == id }
            if (todoIndex == -1) {
                return Result.failure(Exception("Todo not found"))
            }

            val existingTodo = _todos[todoIndex]
            if (existingTodo.priority == priority) {
                Log.d("TodoManager", "Priority unchanged for todo: $id")
                return Result.success(Unit)
            }

            val currentTime = System.currentTimeMillis()
            val updates = hashMapOf<String, Any>(
                "priority" to priority.value,
                "updatedAt" to currentTime
            )

            firestore.collection("todos")
                .document(id)
                .update(updates)
                .await()

            _todos[todoIndex] = existingTodo.copy(
                priority = priority,
                updatedAt = currentTime
            )

            Log.d("TodoManager", "Successfully updated priority for todo: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error updating todo priority", e)
            Result.failure(Exception("Failed to update priority: ${e.message}"))
        }
    }

    suspend fun deleteTodo(id: String): Result<Unit> {
        return try {
            if (id.isBlank()) {
                return Result.failure(Exception("Todo ID cannot be empty"))
            }

            firestore.collection("todos")
                .document(id)
                .delete()
                .await()

            _todos.removeAll { it.id == id }

            Log.d("TodoManager", "Successfully deleted todo: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error deleting todo", e)
            Result.failure(Exception("Failed to delete todo: ${e.message}"))
        }
    }

    suspend fun deleteCompletedTodos(): Result<Int> {
        return try {
            val completedTodos = _todos.filter { it.isCompleted }
            if (completedTodos.isEmpty()) {
                return Result.success(0)
            }

            var deletedCount = 0
            val batch = firestore.batch()

            for (todo in completedTodos) {
                val docRef = firestore.collection("todos").document(todo.id)
                batch.delete(docRef)
                deletedCount++
            }

            batch.commit().await()
            _todos.removeAll { it.isCompleted }

            Log.d("TodoManager", "Successfully deleted $deletedCount completed todos")
            Result.success(deletedCount)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error deleting completed todos", e)
            Result.failure(Exception("Failed to delete completed todos: ${e.message}"))
        }
    }

    suspend fun deleteAllTodos(): Result<Int> {
        return try {
            val allTodos = _todos.toList()
            if (allTodos.isEmpty()) {
                return Result.success(0)
            }

            var deletedCount = 0
            val batch = firestore.batch()

            for (todo in allTodos) {
                val docRef = firestore.collection("todos").document(todo.id)
                batch.delete(docRef)
                deletedCount++
            }

            batch.commit().await()
            _todos.clear()

            Log.d("TodoManager", "Successfully deleted all $deletedCount todos")
            Result.success(deletedCount)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error deleting all todos", e)
            Result.failure(Exception("Failed to delete all todos: ${e.message}"))
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun refresh() {
        _isInitialized.value = false
        loadTodos()
    }

    fun getSortedTodos(sortBy: SortBy = SortBy.CREATED_DATE): List<TodoItem> {
        return try {
            when (sortBy) {
                SortBy.TITLE -> _todos.sortedBy { it.title.lowercase() }
                SortBy.PRIORITY -> _todos.sortedByDescending { it.priority.value }
                SortBy.CREATED_DATE -> _todos.sortedByDescending { it.createdAt }
                SortBy.UPDATED_DATE -> _todos.sortedByDescending { it.updatedAt }
                SortBy.COMPLETION -> _todos.sortedBy { it.isCompleted }
            }
        } catch (e: Exception) {
            Log.e("TodoManager", "Error sorting todos", e)
            _todos.toList()
        }
    }

    suspend fun validateTodoExists(id: String): Boolean {
        return try {
            if (id.isBlank()) return false
            val doc = firestore.collection("todos").document(id).get().await()
            doc.exists()
        } catch (e: Exception) {
            Log.e("TodoManager", "Error validating todo existence", e)
            false
        }
    }

    suspend fun syncWithFirestore() {
        try {
            val userId = getCurrentUserId() ?: return

            val snapshot = getTodosCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()

            val firestoreTodos = snapshot.documents.mapNotNull { document ->
                try {
                    val data = document.data ?: return@mapNotNull null
                    TodoItem(
                        id = document.id,
                        title = data["title"] as? String ?: "",
                        description = data["description"] as? String ?: "",
                        isCompleted = data["isCompleted"] as? Boolean ?: false,
                        priority = Priority.fromValue(
                            when (val priorityValue = data["priority"]) {
                                is Long -> priorityValue.toInt()
                                is Int -> priorityValue
                                is String -> priorityValue.toIntOrNull() ?: Priority.MEDIUM.value
                                else -> Priority.MEDIUM.value
                            }
                        ),
                        createdAt = (data["createdAt"] as? Long) ?: System.currentTimeMillis(),
                        updatedAt = (data["updatedAt"] as? Long) ?: System.currentTimeMillis(),
                        userId = data["userId"] as? String ?: userId
                    )
                } catch (e: Exception) {
                    Log.e("TodoManager", "Error parsing document during sync", e)
                    null
                }
            }

            _todos.clear()
            _todos.addAll(firestoreTodos)

            Log.d("TodoManager", "Successfully synced ${firestoreTodos.size} todos")
        } catch (e: Exception) {
            Log.e("TodoManager", "Error syncing with Firestore", e)
        }
    }
}