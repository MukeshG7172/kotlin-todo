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
        return auth.currentUser?.uid
    }

    private fun getTodosCollection() = firestore
        .collection("todos")
        .whereEqualTo("userId", getCurrentUserId())

    fun loadTodos() {
        val userId = getCurrentUserId()

        if (userId == null) {
            _error.value = "User not authenticated"
            _isInitialized.value = true
            return
        }

        _isLoading.value = true
        _error.value = null

        getTodosCollection()
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                try {
                    val todoList = querySnapshot.documents.mapNotNull { document ->
                        try {
                            val data = document.data ?: return@mapNotNull null
                            val title = data["title"] as? String

                            if (title.isNullOrBlank()) return@mapNotNull null

                            TodoItem(
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
                                userId = data["userId"] as? String ?: userId
                            )
                        } catch (e: Exception) {
                            Log.e("TodoManager", "Error parsing document ${document.id}", e)
                            null
                        }
                    }

                    _todos.clear()
                    _todos.addAll(todoList)
                    _isLoading.value = false
                    _isInitialized.value = true
                    _error.value = null

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
                _error.value = "Failed to load todos: ${exception.message}"
            }
    }

    fun getFilteredAndSortedTodos(
        filterType: FilterType = FilterType.ALL,
        sortBy: SortBy = SortBy.CREATED_DATE
    ): List<TodoItem> {
        val filtered = when (filterType) {
            FilterType.ALL -> _todos.toList()
            FilterType.PENDING -> _todos.filter { !it.isCompleted }
            FilterType.COMPLETED -> _todos.filter { it.isCompleted }
            FilterType.HIGH_PRIORITY -> _todos.filter { it.priority == Priority.HIGH }
            FilterType.MEDIUM_PRIORITY -> _todos.filter { it.priority == Priority.MEDIUM }
            FilterType.LOW_PRIORITY -> _todos.filter { it.priority == Priority.LOW }
        }

        return when (sortBy) {
            SortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortBy.PRIORITY -> filtered.sortedByDescending { it.priority.value }
            SortBy.CREATED_DATE -> filtered.sortedByDescending { it.createdAt }
            SortBy.UPDATED_DATE -> filtered.sortedByDescending { it.updatedAt }
            SortBy.COMPLETION -> filtered.sortedBy { it.isCompleted }
        }
    }

    fun searchTodos(query: String): List<TodoItem> {
        if (query.trim().isEmpty()) return _todos.toList()

        val searchQuery = query.trim().lowercase()
        return _todos.filter { todo ->
            todo.title.lowercase().contains(searchQuery) ||
                    todo.description.lowercase().contains(searchQuery)
        }
    }

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
            Result.success(todoId)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error adding todo", e)
            Result.failure(Exception("Failed to add todo: ${e.message}"))
        }
    }

    suspend fun updateTodo(
        id: String,
        title: String,
        description: String,
        priority: Priority? = null
    ): Result<Unit> {
        return try {
            if (id.isBlank() || title.trim().isBlank()) {
                return Result.failure(Exception("Invalid input"))
            }

            val existingTodoIndex = _todos.indexOfFirst { it.id == id }
            if (existingTodoIndex == -1) {
                return Result.failure(Exception("Todo not found"))
            }

            val existingTodo = _todos[existingTodoIndex]
            val currentTime = System.currentTimeMillis()

            val updates = mutableMapOf<String, Any>("updatedAt" to currentTime)

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

            if (updates.size == 1) return Result.success(Unit) // Only updatedAt changed

            firestore.collection("todos")
                .document(id)
                .update(updates)
                .await()

            _todos[existingTodoIndex] = existingTodo.copy(
                title = title.trim(),
                description = description.trim(),
                priority = priority ?: existingTodo.priority,
                updatedAt = currentTime
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error updating todo: $id", e)
            Result.failure(Exception("Failed to update todo: ${e.message}"))
        }
    }

    suspend fun toggleComplete(id: String): Result<Unit> {
        return try {
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

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TodoManager", "Error toggling todo completion", e)
            Result.failure(Exception("Failed to toggle completion: ${e.message}"))
        }
    }

    suspend fun deleteTodo(id: String): Result<Unit> {
        return try {
            firestore.collection("todos")
                .document(id)
                .delete()
                .await()

            _todos.removeAll { it.id == id }
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

            val batch = firestore.batch()
            for (todo in completedTodos) {
                val docRef = firestore.collection("todos").document(todo.id)
                batch.delete(docRef)
            }

            batch.commit().await()
            _todos.removeAll { it.isCompleted }

            Result.success(completedTodos.size)
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

            val batch = firestore.batch()
            for (todo in allTodos) {
                val docRef = firestore.collection("todos").document(todo.id)
                batch.delete(docRef)
            }

            batch.commit().await()
            _todos.clear()

            Result.success(allTodos.size)
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

    fun isEmpty(): Boolean = _todos.isEmpty() && _isInitialized.value
}